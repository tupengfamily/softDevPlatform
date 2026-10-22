package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 杂草发生与危害模型（2026-10-22 需求 O 补齐）。
 *
 * <p><b>为什么单独做一个引擎</b>：作物、病虫害、土壤三条线都已经有了，
 * 但「除草」这条线是空的 —— 没有杂草模型，就没有除草这个动作的收益，
 * 除草就只是一个没有后果的按钮（点了跟没点一样），那是假操作。
 * 所以这里补一条<b>最小但自洽</b>的杂草线：
 *
 * <ol>
 *   <li><b>发生</b>：杂草不像病害需要精确的病害三角，它靠的是「空窗期」——
 *       整地后到苗封行之间的裸地天数。裸地越久，杂草萌发基数越高。
 *       这里用「裸地累积度」作发生驱动力，而不是随机掷骰子碰运气。</li>
 *   <li><b>生长</b>：杂草按 Logistic 逼近饱和压力，与 {@link PestEngine} 同一种形状，
 *       但快得多（杂草是 r 型策略者）。</li>
 *   <li><b>危害</b>：杂草与作物<b>争水争光争肥</b>，所以它主要走
 *       <b>水分</b>通道（旱地作物）与 <b>产量系数</b>通道，而不是直接砍产量四要素 ——
 *       这与「杂草是资源的竞争者」这个真实机制一致。</li>
 *   <li><b>防治</b>：人工除草（WEED 农事）与除草剂（HERBICIDE 类农资）。
 *       两者都降低杂草压力；除草剂有 2–3 天见效延迟（药效不是即时的），
 *       人工除草是即时的但只能做一次。</li>
 * </ol>
 *
 * <p><b>口径纪律</b>：
 * <ul>
 *   <li>本引擎只算「杂草压力」与「因杂草造成的损失系数」，
 *       <b>不直接改产量四要素</b>——产量折算仍在 Service 层统一做，
 *       避免"土壤扣一次、S7 又扣一次"那类重复计账。</li>
 *   <li>与 {@link PestEngine} 一样是纯计算：不读写数据库、不决定 UI 文案。</li>
 * </ul>
 */
public final class WeedEngine {

    private WeedEngine() {
    }

    /** 杂草压力等级。 */
    public enum Level {
        /** 无明显杂草 */
        NONE,
        /** 少量杂草，可以忽略 */
        LIGHT,
        /** 中等杂草，需要处理 */
        MEDIUM,
        /** 杂草成灾，与作物争水争肥 */
        SEVERE
    }

    /**
     * 模拟结果。
     *
     * @param pressure      杂草压力 0..1（1 = 完全被杂草占满）
     * @param level         压力分档
     * @param waterLoss     因争水造成的产量损失系数 0..1（旱地更明显）
     * @param onsetDate     首次达到「需要处理」水平的日期
     * @param manualWeeds   人工除草次数
     * @param herbicideDays 除草剂覆盖天数（保护期）
     */
    public record Result(
            double pressure,
            Level level,
            double waterLoss,
            String onsetDate,
            int manualWeeds,
            int herbicideDays) {
        public static final Result EMPTY = new Result(0, Level.NONE, 0, null, 0, 0);
    }

    /**
     * 一次除草行为（人工或药剂）。
     *
     * @param date   除草日期
     * @param manual true = 人工除草（立即见效）；false = 化学除草（1–3 天见效）
     */
    public record WeedControl(LocalDate date, boolean manual) {
    }

    /** 杂草覆盖度的平衡上限（相对作物覆盖率），1 = 杂草完全盖住作物。 */
    private static final double CARRY_MAX = 1.0;
    /** 人工除草一次能压掉多少压力。 */
    private static final double MANUAL_KILL = 0.92;
    /** 化学除草一次能压掉多少压力（比人工低，且有延迟）。 */
    private static final double CHEM_KILL = 0.75;
    /** 化学除草的见效延迟（天）。 */
    private static final int CHEM_DELAY = 2;
    /** 化学除草的保护期（天）：这期间压力不再回升。 */
    private static final int CHEM_PROTECT = 12;
    /** 杂草每日自然回升速率（裸地越久回升越快，见下）。 */
    private static final double REGROW_BASE = 0.012;

    /**
     * 模拟一季的杂草压力。
     *
     * @param days     天气序列
     * @param startIdx 播种日下标
     * @param nDays    已生长天数
     * @param dvsTrace 逐日 DVS
     * @param controls 除草行为列表
     * @param paddy    true = 水田（有水层，杂草被抑制）；false = 旱地（杂草更凶）
     * @param seed     随机种子
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  List<WeedControl> controls,
                                  boolean paddy,
                                  long seed) {
        if (days == null || nDays <= 0 || dvsTrace == null) {
            return Result.EMPTY;
        }
        Random rnd = new Random(seed);

        double pressure = 0;
        String onsetDate = null;
        int manualCount = 0;
        int chemDays = 0;

        // 按日期归集除草行为：人工即时，药剂延迟生效
        java.util.Map<LocalDate, Integer> manualByDate = new java.util.HashMap<>();
        java.util.Map<LocalDate, Integer> chemByDate = new java.util.HashMap<>();
        if (controls != null) {
            for (WeedControl c : controls) {
                if (c == null || c.date() == null) {
                    continue;
                }
                if (c.manual()) {
                    manualByDate.merge(c.date(), 1, Integer::sum);
                } else {
                    chemByDate.merge(c.date(), 1, Integer::sum);
                }
            }
        }

        // 🔴 化学除草的延迟队列：喷药当天不生效，
        // CHEM_DELAY 天后真正压制压力，并开始 CHEM_PROTECT 天的保护期。
        // 旧实现把药剂从「喷药日」挪到「生效日」就结束了，
        // **从来没有真正减过 pressure** —— 化学除草成了空操作。
        // 现在用 pending 队列显式记账：到期日在第几天，就在那一天削压力。
        java.util.Map<LocalDate, Integer> pendingChem = new java.util.HashMap<>();
        // 药剂保护期：某天施药 → 从生效日起 protectDays 天内压力不回升
        java.util.Map<LocalDate, LocalDate> protectUntil = new java.util.HashMap<>();

        for (int k = 0; k < nDays; k++) {
            int i = startIdx + k;
            if (i >= days.size()) {
                break;
            }
            DayWeather w = days.get(i);
            LocalDate date = LocalDate.parse(w.date());
            double dvs = k < dvsTrace.size() ? dvsTrace.get(k) : 0;

            // ① 人工除草：即时生效
            int manual = manualByDate.getOrDefault(date, 0);
            if (manual > 0) {
                manualCount += manual;
                pressure *= Math.pow(1 - MANUAL_KILL, manual);
            }

            // ② 当天喷的药入延迟队列（喷药当天不生效，延迟后才起效）
            int sprayed = chemByDate.getOrDefault(date, 0);
            if (sprayed > 0) {
                pendingChem.merge(date.plusDays(CHEM_DELAY), sprayed, Integer::sum);
            }

            // ③ 延迟到期的药剂：此刻真正压制压力 🔴
            int effective = pendingChem.getOrDefault(date, 0);
            if (effective > 0) {
                pressure *= Math.pow(1 - CHEM_KILL, effective);
                // 保护期：从今天起 CHEM_PROTECT 天内压力不回升
                protectUntil.put(date, date.plusDays(CHEM_PROTECT));
                chemDays += CHEM_PROTECT;
            }

            // ④ 保护期判定：当天是否落在某次药剂的保护窗口内
            boolean protectedNow = false;
            for (var e : protectUntil.entrySet()) {
                if (!date.isBefore(e.getKey()) && !date.isAfter(e.getValue())) {
                    protectedNow = true;
                    break;
                }
            }

            // ⑤ 杂草萌发与回升：驱动力是「裸地累积度」
            //    苗封行之前（DVS < 0.36）是杂草的黄金期；
            //    之后作物冠层郁闭，杂草自然受抑（这就是「封行后不用除草」的机制）。
            double bareSoil;
            if (dvs < 0.36) {
                // 出苗前完全裸地，出苗后仍有一段裸地
                bareSoil = 1.0 - (dvs / 0.36) * 0.55;
            } else if (dvs < 0.55) {
                // 封行期：郁闭度上升，裸地快速下降
                bareSoil = 0.45 * (1 - (dvs - 0.36) / 0.19);
            } else {
                bareSoil = 0;
            }
            // 降雨助长杂草萌发（雨后杂草出苗最快，这是真实经验）
            double rainBoost = 1.0 + Math.min(1.2, w.precip() / 25.0) * 0.5;
            // 水田有水层，杂草被抑制（这是稻田少打除草剂的原因）
            double paddyFactor = paddy ? 0.35 : 1.0;
            // 杂草生长快于作物：r 型策略者
            double regrow = REGROW_BASE * bareSoil * rainBoost * paddyFactor
                    * (1.0 + 0.8 * rnd.nextDouble());

            if (!protectedNow) {
                // 🔴 Logistic 饱和：增量式增长 —— 压力越高越接近上限，
                // 越接近上限增速越慢（旧实现是「先加再除」的错误形状，
                // 会让压力在早期就冲得很高）。
                pressure += regrow * (1.0 - pressure / CARRY_MAX);
            }
            if (pressure < 0) {
                pressure = 0;
            }
            if (pressure > CARRY_MAX) {
                pressure = CARRY_MAX;
            }

            if (onsetDate == null && levelOf(pressure) != Level.NONE) {
                onsetDate = date.toString();
            }
        }

        Level level = levelOf(pressure);
        // 争水损失：旱地作物（蔬菜 / 花卉 / 大豆）对水分敏感，杂草的争水危害更直接
        double waterLoss = paddy ? pressure * 0.10 : pressure * 0.28;
        return new Result(round(pressure), level, round(waterLoss), onsetDate, manualCount, chemDays);
    }

    /** 压力分档。 */
    public static Level levelOf(double pressure) {
        if (pressure < 0.08) {
            return Level.NONE;
        }
        if (pressure < 0.25) {
            return Level.LIGHT;
        }
        if (pressure < 0.55) {
            return Level.MEDIUM;
        }
        return Level.SEVERE;
    }

    /** 压力分档的中文名（UI 直接渲染，不在前端复算口径）。 */
    public static String levelCn(Level level) {
        return switch (level) {
            case NONE -> "无杂草";
            case LIGHT -> "少量杂草";
            case MEDIUM -> "杂草较多";
            case SEVERE -> "杂草成灾";
        };
    }

    /** 该压力下的处置建议。 */
    public static String advice(Level level) {
        return switch (level) {
            case NONE -> "田间干净，暂时不用管";
            case LIGHT -> "零星几株，可以先不管";
            case MEDIUM -> "该除草了：杂草开始跟作物争水争肥";
            case SEVERE -> "必须马上除草：杂草已盖住作物，再拖会明显减产";
        };
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
