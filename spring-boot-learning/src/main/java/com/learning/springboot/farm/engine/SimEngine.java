package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 模拟核心引擎（Java 移植版）。
 *
 * <p>与 {@code prototype/sim-core.mjs} **逐行等价**。移植的目的是把"数值正确"变成"可部署"，
 * 不是重新设计——所以这里的每个公式、每个 clamp、每个判定顺序都必须与原型一致，
 * 由 {@code SimRegressionTest} 用 9 年真实气象逐项比对。
 *
 * <p>三条不可违背的分离（改这里之前先读）：
 * <ol>
 *   <li><b>发育 vs 损伤</b>：{@code f_dev_T} 只改"长得快慢"，不产生损伤；损伤走 {@code dmg} 独立累积器。</li>
 *   <li><b>日均 vs 百叶箱 T_max</b>：AAT 用 {@code tmean}，高温胁迫用 {@code tmax}。绝不互换。</li>
 *   <li><b>累积 vs 瞬时</b>：{@code f_dev_T} 是累积型（{@code heatForce} 跨日累加），不是当日瞬时。</li>
 * </ol>
 */
public final class SimEngine {

    /** 单日注入（用于场景 B/C 的受控实验：在指定 DVS 处改写若干天天气）。 */
    public record Injection(double atDvs, int nDays, double tmax, double tmin) {
    }

    /**
     * 农事操作的当日效应（玩家在 {@code date} 做了某事，引擎在回放那日时应用此效应）。
     *
     * <p>三件事，互不重叠：
     * <ul>
     *   <li>{@code tmaxOffset}：扣减当日 effective tmax（深水降温、揭帘保暖），影响高温胁迫；</li>
     *   <li>{@code fertMitigation}：抹除当日已累积结实率伤害的比例（0..1，如施肥、打药）；</li>
     *   <li>{@code floretMitigation}：抹除当日已累积粒数伤害的比例（0..1，如灌水护花）。</li>
     * </ul>
     *
     * <p>所有 action 在引擎层都归约成这三件事。Service 层负责把
     * {@code FarmAction.Type} 翻译成 {@code ActionEffect}。
     */
    public record ActionEffect(
            double tmaxOffset,
            double fertMitigation,
            double floretMitigation
    ) {
        public static final ActionEffect NONE = new ActionEffect(0, 0, 0);

        /**
         * 农事类型 → 当日 effect 映射。
         *
         * <p>数字取自 D10 / S10 的物候响应表，是"有依据的粗估"——精细到品类（双季稻 vs 粳稻）
         * 后续由 {@code CropConfig} 扩展，本期不做。原则是**保守**：单次操作只能抹除当日伤害
         * 的 20–35%，不允许"一次施肥治百病"。
         */
        public static ActionEffect of(String type) {
            if (type == null) return NONE;
            return switch (type) {
                // 灌水：深水层降穗部温度约 0.6℃（与现有 mitigationTmaxOffset 同量级），并护花
                case "IRRIGATE"  -> new ActionEffect(-0.6, 0.10, 0.25);
                // 排水：晒田/播前整地，地温上升 0.5℃；不抹除任何伤害（有时反而控苗）
                case "DRAIN"      -> new ActionEffect(+0.5, 0.00, 0.00);
                // 施肥：补结实率，不降温（也不升温）
                case "FERTILIZE"  -> new ActionEffect( 0.0, 0.25, 0.05);
                // 打药：清病虫压力，走结实率通道（虫害败花 = 结实率下降）
                case "PESTICIDE"  -> new ActionEffect( 0.0, 0.35, 0.10);
                // 揭帘（冬季大棚保温）：保温，穗部降温 2℃（棚内偏暖对冬作是减胁迫）
                case "UNCOVER"    -> new ActionEffect(-2.0, 0.05, 0.05);
                // ==== S10 工序链：归约到同样的三种作用 ====
                case "PUDDLE"      -> new ActionEffect(-0.6, 0.10, 0.25);   // 泡田降温 + 护花
                case "BASE_FERT"   -> new ActionEffect( 0.0, 0.30, 0.10);   // 基肥：补结实率
                case "TILLER_FERT" -> new ActionEffect( 0.0, 0.20, 0.08);
                case "PANICLE_FERT"-> new ActionEffect( 0.0, 0.25, 0.12);
                case "DRY_FIELD"   -> new ActionEffect(+0.5, 0.00, 0.00);   // 晒田：地温升
                case "DRAIN_FINAL" -> new ActionEffect( 0.0, 0.00, 0.00);
                case "TRANSPLANT", "DRY_GRAIN" -> NONE;
                default           -> NONE;
            };
        }
    }

    /** 各通道损伤累积量（0..1，已含 sens 权重，未 clamp）。 */
    public record Damage(double panicle, double floret, double fertility, double weight) {
    }

    /** 模拟结果。 */
    public record SimResult(
            double aatReq,
            int days,
            double dvs,
            String sowDate,
            String headingDate,
            String matureDate,
            double aatCum,
            double heatForce,
            int daysOverOpt,
            Damage dmg,
            double panicles,
            double florets,
            double fertility,
            double grains,
            double weight,
            double eyRaw,
            double ey,
            java.util.List<Double> dvsByDay,
            boolean frostStop,
            boolean winterKilled,
            int vernalizationDays) {
    }

    /**
     * 灌浆完成度（0–1）。按 DVS 分段，而不是按积温线性：DVS 0.92 已黄熟，减产只有几个点；
     * DVS 0.75 还在乳熟，千粒重只有一半。这是 S23 §6.2「非线性」的落地。
     */
    public static double fillFactor(double dvs) {
        if (dvs >= 1.0) {
            return 1.0;
        }
        if (dvs >= 0.92) {
            return 0.88 + 0.12 * (dvs - 0.92) / 0.08;
        }
        if (dvs >= 0.72) {
            return 0.35 + 0.53 * (dvs - 0.72) / 0.20;
        }
        if (dvs >= 0.63) {
            return 0.22;
        }
        if (dvs >= 0.41) {
            return 0.10;
        }
        return 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** S7 §4.1 单日活动积温（**日均口径**）。AAT = Σ min(T_mean, t_opt_upper)，仅统计 T_mean ≥ aat_ref。 */
    public static double aatDay(double tmean, CropConfig c) {
        return tmean >= c.aatRef() ? Math.min(tmean, c.tOptUpper()) : 0.0;
    }

    /** S7 §4.3.1 高温发育加速（**累积型**）。 */
    public static double fDevT(double heatForce, double aatReq, CropConfig c) {
        return clamp(1 + c.kForce() * (heatForce / aatReq), 1.0, c.fDevTCap());
    }

    /** S7 §4.6.3 高温胁迫强度（**百叶箱 T_max 口径**，用于产量积分）。 */
    public static double heatStress(double tmax, CropConfig.HeatProfile p) {
        if (p == null) {
            return 0.0;
        }
        return clamp((tmax - p.tStart()) / (p.tSevere() - p.tStart()), 0.0, 1.0);
    }

    /** 展示层热害强度（33℃，**只驱动徽标，不得进产量积分**）。 */
    public static double heatDisplay(double tmax, CropConfig.HeatProfile p) {
        if (p == null) {
            return 0.0;
        }
        return clamp((tmax - p.tDisplay()) / (p.tSevere() - p.tDisplay()), 0.0, 1.0);
    }

    public static CropConfig.HeatProfile profileAt(double dvs, CropConfig c) {
        for (CropConfig.HeatProfile p : c.heatProfiles()) {
            if (dvs >= p.lo() && dvs < p.hi()) {
                return p;
            }
        }
        return null;
    }

    public static String phenologyAt(double dvs, CropConfig c) {
        for (CropConfig.PhenoStage s : c.phenology()) {
            if (dvs >= s.lo() && dvs < s.hi()) {
                return s.name();
            }
        }
        return "过熟";
    }

    public static SimResult simulate(List<DayWeather> days, int startIdx, CropConfig c) {
        return simulate(days, startIdx, c, null, 0.0, 260);
    }

    /**
     * @param inject                  受控注入（可为 null）
     * @param mitigationTmaxOffset    缓解措施造成的穗部降温（如深水层 −2.2℃）。**只作用于胁迫，不改发育**
     */
    public static SimResult simulate(List<DayWeather> days,
                                     int startIdx,
                                     CropConfig c,
                                     Injection inject,
                                     double mitigationTmaxOffset,
                                     int maxDays) {
        return simulate(days, startIdx, c, inject, mitigationTmaxOffset, java.util.Collections.emptyMap(), maxDays);
    }

    /**
     * 带农事操作的重载。{@code actionsByDate} 是一张「日 → 玩家当日所做操作的 effect 列表」表，
     * 由 Service 层从 {@code t_farm_action} 加载后翻译而成。
     *
     * <p>当日应用规则：
     * <ol>
     *   <li>多个 effect 的 tmaxOffset 累加（深水层叠加）</li>
     *   <li>mitigation 因子相加后 clamp 到 [0, 1]（不允许负伤害 / 大于 100% 抹除）</li>
     *   <li>damage 在累加后立即乘 {@code (1 - mitigation)}——而不是"先累到结束再回抹"，那会污染后续日的衰减逻辑</li>
     * </ol>
     */
    public static SimResult simulate(List<DayWeather> days,
                                     int startIdx,
                                     CropConfig c,
                                     Injection inject,
                                     double mitigationTmaxOffset,
                                     java.util.Map<String, java.util.List<ActionEffect>> actionsByDate,
                                     int maxDays) {
        return simulate(days, startIdx, c, inject, mitigationTmaxOffset, actionsByDate, maxDays, false);
    }

    /**
     * @param finalSeason 本季是否已经"盖棺"：true 表示调用方只回放了一季（如适区评级），
     *                    没成熟就按灌浆完成度折算；false 表示只是回放到"今天"，
     *                    作物还在长，预估产量不能打折。
     */
    public static SimResult simulate(List<DayWeather> days,
                                     int startIdx,
                                     CropConfig c,
                                     Injection inject,
                                     double mitigationTmaxOffset,
                                     java.util.Map<String, java.util.List<ActionEffect>> actionsByDate,
                                     int maxDays,
                                     boolean finalSeason) {
        final double req = c.aatReq();
        double dvs = 0, aatCum = 0, heatForce = 0;
        double dmgPan = 0, dmgFloret = 0, dmgFert = 0, dmgWt = 0;
        int daysOverOpt = 0;

        int injectLeft = 0;
        Integer injectStartDay = null;

        Integer headingK = null;
        String headingDate = null;
        String matureDate = null;

        java.util.List<Double> dvsTrace = new java.util.ArrayList<>();
        boolean frostStop = false;
        boolean winterKilled = false;
        int vernDays = 0;
        int k = 0;
        for (; k < maxDays; k++) {
            int i = startIdx + k;
            if (i >= days.size()) {
                break;
            }
            DayWeather raw = days.get(i);
            DayWeather day = raw;

            // 霜冻终止（S23 §6.2 M1 / M2）。
            // · 不耐寒作物：T_min ≤ 0℃ 即本季结束（水稻/玉米/大豆）；
            // · 越冬作物（冬小麦）：撑过冬天，只有低于致死温度才死。
            // 不截断的话，越过霜期继续累加会把第二年春天的积温算进来，寒地晚播会“神奇地”在冬天成熟。
            if (dvs < 1.0) {
                if (!c.winterHardy() && day.tmin() <= 0.0) {
                    frostStop = true;
                    break;
                }
                if (c.winterHardy() && day.tmin() <= c.winterKillTmin()) {
                    frostStop = true;
                    winterKilled = true;
                    break;
                }
            }

            if (injectLeft > 0) {
                day = new DayWeather(raw.date(), inject.tmax(), inject.tmin(), raw.precip());
                injectLeft--;
            } else if (inject != null && injectStartDay == null && dvs >= inject.atDvs()) {
                injectStartDay = k;
                injectLeft = inject.nDays() - 1;
                day = new DayWeather(raw.date(), inject.tmax(), inject.tmin(), raw.precip());
            }

            // 汇总当日的所有 action effect
            ActionEffect ax = ActionEffect.NONE;
            if (actionsByDate != null) {
                java.util.List<ActionEffect> list = actionsByDate.get(raw.date());
                if (list != null) {
                    for (ActionEffect e : list) {
                        ax = new ActionEffect(
                                ax.tmaxOffset + e.tmaxOffset,
                                Math.min(1.0, ax.fertMitigation + e.fertMitigation),
                                Math.min(1.0, ax.floretMitigation + e.floretMitigation)
                        );
                    }
                }
            }

            double tMean = day.tmean();

            // ① AAT（日均口径）
            // 越冬作物冬季休眠：日均 < 3℃ 的日子只用于春化，不推进发育。
            // 否则基点温度 0℃ 会把整个冬天的"免费热量"都算成发育，小麦会在 2 月抽穗。
            double aat = (c.winterHardy() && tMean < 3.0) ? 0.0 : aatDay(tMean, c);

            // ② 发育速率（累积型：先累积高温强迫量，再算当日 f_dev_T）
            if (tMean > c.tOptUpper()) {
                heatForce += tMean - c.tOptUpper();
                daysOverOpt++;
            }
            double fdev = fDevT(heatForce, req, c);
            aatCum += aat;
            double prevDvs = dvs;
            dvs = clamp(dvs + (aat * fdev) / req, 0.0, 1.3);
            // 春化（S7 §4.3 / S23 §5.2 F2a）：冬性品种必须累计 0–7℃ 天数才能转入生殖生长。
            // 不足时 DVS 卡在 0.42（"只长草不抽穗"），完成度 0.6–1.0 时抽穗推迟、穗小。
            if (c.vernalizationDays() > 0) {
                if (tMean >= 0.0 && tMean <= 7.0) {
                    vernDays++;
                }
                double vc = Math.min(1.0, vernDays / (double) c.vernalizationDays());
                if (vc < 1.0 && dvs > 0.42) {
                    double fv = vc < 0.60 ? 0.0 : (0.5 + 0.5 * vc);
                    dvs = vc < 0.60 ? Math.min(dvs, 0.42) : prevDvs + (dvs - prevDvs) * fv;
                }
            }
            dvsTrace.add(dvs);

            // ③ 高温损伤（T_max 口径；与 ① 严格分离，独立累积器）
            CropConfig.HeatProfile prof = profileAt(dvs, c);
            double effectiveTmax = day.tmax() + mitigationTmaxOffset + ax.tmaxOffset();
            double sh = heatStress(effectiveTmax, prof);
            if (prof != null && sh > 0) {
                double kCh = switch (prof.target()) {
                    case "PANICLE" -> c.kHPan();
                    case "FLORET" -> c.kHFloret();
                    case "FERTILITY" -> c.kHFert();
                    default -> c.kHWt();
                };
                double inc = kCh * sh * prof.sens();
                // 当日 action 抹除（按目标通道选 fert 或 floret；FERTILITY → fert，FLORET → floret）
                if ("FERTILITY".equals(prof.target())) {
                    inc *= (1.0 - ax.fertMitigation());
                } else if ("FLORET".equals(prof.target())) {
                    inc *= (1.0 - ax.floretMitigation());
                }
                switch (prof.target()) {
                    case "PANICLE" -> dmgPan += inc;
                    case "FLORET" -> dmgFloret += inc;
                    case "FERTILITY" -> dmgFert += inc;
                    default -> dmgWt += inc;
                }
            }

            if (headingK == null && dvs >= 0.63) {
                headingK = k + 1;
                headingDate = day.date();
            }
            if (dvs >= 1.0) {
                matureDate = day.date();
                k++;
                break;
            }
        }

        int growthDays = matureDate != null ? k : k;

        // —— 产量三要素（S7 §4.7）——
        // 穗数 ← 分蘖期 | 粒数 ← 颖花数（孕穗期）× 结实率（抽穗期）| 粒重 ← 灌浆期
        // S23 §6.2 M1「霜前没熟」：没走完一季时，穗上籽粒只灌到当前 DVS 对应程度。
        // 成熟了就不打折——高温发育加速会让 aatCum < req 也能成熟，不能用积温比代替。
        boolean seasonOver = frostStop || finalSeason;
        double maturityRatio = matureDate != null ? 1.0 : clamp(aatCum / req, 0.0, 1.3);
        double fMaturity = (matureDate != null || !seasonOver) ? 1.0 : fillFactor(dvs);

        double panicles = c.paniclesPot() * (1 - clamp(dmgPan, 0, c.capPan()));
        double florets = c.floretsPot() * (1 - clamp(dmgFloret, 0, c.capFloret()));
        double fertility = c.fertilityBase() * (1 - clamp(dmgFert, 0, c.capFert()));
        double grains = florets * fertility;
        double weight = c.weightPot() * (1 - clamp(dmgWt, 0, c.capWt())) * fMaturity;
        // 积温不到需求 55%：只有秕谷，无经济产量（可转青贮）
        if (seasonOver && matureDate == null && maturityRatio < 0.55) {
            grains = 0;
        } else if (seasonOver && matureDate == null) {
            // 🔴 S12 修复：积温 0.55–1.0 区间原来**完全没有惩罚**，
            // 「只完成 56% 积温」和「积温刚好够」拿到几乎一样的产量。
            // 现实里没熟的稻子灌浆不足、秕粒多，产量必须连续下降，不能只有"归零/不归零"两档。
            // 这里对粒重做连续惩罚：完成度 55% → 线性惩罚到 0.45，100% → 0。
            // 惩罚作用在粒重（灌浆）通道上，这与 fillFactor 的分工一致：
            // fillFactor 管"按 DVS 走到哪一步"，这里管"积温够不够"（发育不足 = 籽粒灌不饱）。
            double fillShort = (1.0 - maturityRatio) / 0.45;   // 0.55 → 1.0, 1.0 → 0.0
            double penalty = clamp(0.55 * fillShort, 0, 0.55);
            weight *= (1 - penalty);
            grains *= (1 - penalty * 0.5);
        }
        // 除以 (1000 × weightPer)：weightPer 是 weight 的基准个数，
        // 禾谷类填千粒（1000）→ /1e6；园艺作物填单果（1）→ /1e3。
        // 写成 /1e6 的固定值会让蔬菜产量差 1000 倍，评级直接全 L5。
        double eyRaw = (panicles * grains * weight) / (1e3 * Math.max(1, c.weightPer()));
        double ey = Math.min(eyRaw, c.yieldCap());

        return new SimResult(req, growthDays, dvs,
                days.get(startIdx).date(), headingDate, matureDate,
                aatCum, heatForce, daysOverOpt,
                new Damage(dmgPan, dmgFloret, dmgFert, dmgWt),
                panicles, florets, fertility, grains, weight, eyRaw, ey, dvsTrace,
                frostStop, winterKilled, vernDays);
    }
}
