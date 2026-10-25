package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作物库（S1 精简版）：22 种。
 *
 * <p>粮食 5（水稻/冬小麦/玉米/大豆/谷子）+ 蔬菜 7（大白菜/番茄/黄瓜/辣椒/茄子/豆角/萝卜）
 * + 水果 5（草莓/西瓜/甜瓜/葡萄/梨）+ 花卉 4（菊花/向日葵/郁金香/月季）+ 块茎 1（马铃薯）。
 *
 * <p><b>两层模型</b>：作物层（这里）决定物候、需肥、产量构成与形态；
 * 品种层（v1.1）才细分熟期与抗性。当前每作物只有 1 个代表品种，够跑通"选作物 → 选播期 → 一季"。
 *
 * <p>播期不是一个固定日期，而是<b>每个区一个代表播期</b>：同样是玉米，江南是春播（4 月初）、
 * 黄淮海是夏播（6 月中）、东北是 5 月初。播种窗口之外仍然可以种——S23 只给概率，不给许可。
 *
 * <p><b>🔴 visual 语义（2026-10-24 改造）</b>：{@code CropConfig.visual} 现在是
 * <b>作物自己的 3D 形态标识</b>（园艺作物 = 作物 id），不再共用 {@code leafy/vine/bush/flower}
 * 这类通用族——那样会让「草莓长得像番茄、西瓜长得像黄瓜」。
 * 但<b>4 个粮食作物的 visual 保持原值</b>（{@code rice/wheat/corn/soybean}）：
 * 它们是前端已有贴图，也是存量 {@code t_farm_season.visual} 里最常见的值，不能动。
 * 需要「按形态族取参数」的地方请走 {@link CropVisual}，它同时认新 id 与存量旧值。
 */
public final class CropCatalog {

    private CropCatalog() {
    }

    /**
     * @param category 粮食·禾谷 / 粮食·豆科 …（UI 分类）
     * @param sowByZone 各农业区的代表播期（MM-DD）
     * @param earlyDays 推荐播期<b>可提前</b>的天数（正数，如 35 = 可提前 35 天）
     * @param lateDays  推荐播期<b>可推迟</b>的天数（正数，如 40 = 可推迟 40 天）
     */
    public record Crop(
            String id,
            String name,
            String shortName,
            String category,
            String desc,
            CropConfig config,
            Map<String, MonthDay> sowByZone,
            int earlyDays,
            int lateDays) {

        /**
         * 该作物在该区的播期窗口（相对推荐播期的偏移范围）。
         *
         * <p><b>为什么不直接存起止日期</b>：播期窗口随气候区变化（江南水稻 4/15–6/29，
         * 东北中稻 4/1–6/14），而「推荐播期」本来就是分区数据。用「推荐 ± 天数」
         * 表达，窗口自动跟随 {@link #sowByZone()} 一起变，改一处即可。
         *
         * <p>窗口是<b>适期</b>不是<b>许可</b>（S23 §2.1）：出了窗口照样能种，
         * 只是要提前知道这一季大概率会发生什么。
         */
        public record SowWindow(LocalDate recommended, LocalDate from, LocalDate to,
                                int earlyDays, int lateDays, int offsetDays) {
            /** 是否在适期窗口内。 */
            public boolean inWindow() {
                return offsetDays >= -earlyDays && offsetDays <= lateDays;
            }

            /** 偏早还是偏晚。负 = 偏早，正 = 偏晚，0 = 正中推荐期。 */
            public String direction() {
                if (offsetDays < 0) {
                    return "EARLY";
                }
                if (offsetDays > 0) {
                    return "LATE";
                }
                return "ON";
            }
        }

        /**
         * 该作物在该区、给定日期的播期窗口。
         *
         * <p><b>锚点用「距实际播期最近的该月日」</b>，而不是 {@link #defaultSow}
         * 的未来化结果。原因：风险评估要回答的是「你选的这个日子，和这一季
         * 公认最好的日子差多少」——必须<b>同期比较</b>。若拿明年 5/5 去比今年 6/15，
         * 会得出「晚了 41 天」这种跨年误导性结论。
         *
         * @param actual 实际播期
         */
        public SowWindow sowWindow(String zoneId, LocalDate actual, LocalDate today) {
            MonthDay md = sowByZone.getOrDefault(zoneId, sowByZone.get("jiangnan"));
            LocalDate anchor = nearestOccurrence(md, actual);
            int offset = (int) java.time.temporal.ChronoUnit.DAYS.between(anchor, actual);
            LocalDate from = anchor.minusDays(Math.max(0, earlyDays));
            LocalDate to = anchor.plusDays(Math.max(0, lateDays));
            return new SowWindow(anchor, from, to, earlyDays, lateDays, offset);
        }

        /**
         * 距 {@code actual} 最近的一次该月日（同年的优先）。
         *
         * <p>比如推荐月日是 5/5：实际播期 2026-06-15 → 锚点 2026-05-05（晚 41 天）；
         * 实际播期 2026-04-20 → 锚点 2026-05-05（早 15 天）。
         */
        private static LocalDate nearestOccurrence(MonthDay md, LocalDate actual) {
            LocalDate thisYear = md.atYear(actual.getYear());
            LocalDate nextYear = md.atYear(actual.getYear() + 1);
            long dThis = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(thisYear, actual));
            long dNext = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(nextYear, actual));
            return dThis <= dNext ? thisYear : nextYear;
        }

        /**
         * <b>距今最近</b>的播期窗口 —— 玩家真正能选的那段日子。
         *
         * <p>🔴 <b>2026-10-23 修正</b>（用户反馈：「推荐窗口期应该是距今最近的适合日期」）。
         *
         * <p><b>老实现的问题</b>：锚点取「该月日的下一次发生」。于是只要本期的
         * 推荐月日刚过、但窗口<b>尾巴还没走完</b>，它就<b>直接跳到明年</b> ——
         * 明明眼前还有几天可以种，它却把玩家推到一年之后。
         *
         * <p>具体例子：大白菜江南（推荐 8/20，可提前 30 天 / 可推迟 35 天
         * → 窗口 7/21 ~ 9/24）。世界日期 2028-09-20 时，本期窗口还剩
         * 09-20 ~ 09-24 可选，老实现却给出 2029-07-21 ~ 2029-09-24 ——
         * 玩家明明今天就能种，却被告知要等一年。
         *
         * <p><b>新语义</b>（三步）：
         * <ol>
         *   <li><b>锚点</b> = 第一个「<b>窗口末端还没过</b>」的该月日 ——
         *       本期窗口的尾巴还在收尾就用本期，否则用下一季；</li>
         *   <li><b>窗口起点</b> = {@code max(锚点 - earlyDays, 今天)} ——
         *       已经在过去的部分对玩家没有意义（日期选择器禁掉了今天之前），钳到今天；</li>
         *   <li><b>推荐日</b> = {@code max(锚点, 今天)} ——
         *       锚点已经过去时，「距今最近的适合日期」就是今天（该赶紧种了）。</li>
         * </ol>
         *
         * <p>⚠️ 与 {@link #sowWindow} 的区别：{@code sowWindow} 的锚点是
         * 「距实际播期最近的那次该月日」，服务于<b>同期比较</b>（算偏移天数），
         * 允许落在过去；本方法服务于<b>「我现在能选什么」</b>，起点永远是今天以后。
         * 两者不可混用。
         */
        public SowWindow nearestWindow(String zoneId, LocalDate today) {
            MonthDay md = sowByZone.getOrDefault(zoneId, sowByZone.get("jiangnan"));
            int early = Math.max(0, earlyDays);
            int late = Math.max(0, lateDays);
            LocalDate anchor = md.atYear(today.getYear());
            // 🔴 关键判定：本期窗口的<b>尾巴</b>（锚点 + lateDays）还没过 → 仍可用本期。
            // 老实现只比较「锚点是否早于今天」，于是本期尾巴还剩几天时也会跳到明年。
            if (anchor.plusDays(late).isBefore(today)) {
                anchor = md.atYear(today.getYear() + 1);
            }
            LocalDate from = anchor.minusDays(early);
            if (from.isBefore(today)) {
                from = today; // 过去的部分玩家选不了，钳到今天
            }
            LocalDate to = anchor.plusDays(late);
            // 锚点已过 → 「距今最近的适合日期」就是今天。
            LocalDate recommended = anchor.isBefore(today) ? today : anchor;
            return new SowWindow(recommended, from, to, early, late, 0);
        }
    }

    private static final Map<String, Crop> CROPS = new LinkedHashMap<>();

    private static void put(Crop c) {
        CROPS.put(c.id(), c);
    }

    static {
        put(new Crop("rice_mid", "水稻（中稻/一季稻）", "水稻", "粮食·禾谷",
                "水层管理是全部作物里最深的单人机制：浅水勤灌、够苗晒田、孕穗保水、收前断水。",
                RiceConfig.indicaMidSeason(),
                Map.of("jiangnan", MonthDay.of(5, 20), "huanan", MonthDay.of(3, 10),
                        "huanghuaihai", MonthDay.of(5, 20), "dongbei", MonthDay.of(5, 5)), 35, 40));

        put(new Crop("wheat_winter", "冬小麦（半冬性）", "冬小麦", "粮食·禾谷",
                "唯一需要越冬的作物：秋播、越冬、返青、抽穗。必须经过 0–7℃ 春化才会抽穗——华南种它只长草。",
                WheatConfig.winterWheat(),
                Map.of("jiangnan", MonthDay.of(10, 25), "huanan", MonthDay.of(11, 5),
                        "huanghuaihai", MonthDay.of(10, 8), "dongbei", MonthDay.of(9, 20)), 35, 25));

        put(new Crop("corn_spring", "玉米（春/夏播）", "玉米", "粮食·禾谷",
                "需氮最高的作物，大喇叭口期是需肥临界期；单株大穗，穗数只有几千/亩。",
                CornConfig.springCorn(),
                Map.of("jiangnan", MonthDay.of(4, 5), "huanan", MonthDay.of(3, 5),
                        "huanghuaihai", MonthDay.of(6, 12), "dongbei", MonthDay.of(5, 1)), 45, 45));

        put(new Crop("soybean", "大豆（夏播）", "大豆", "粮食·豆科",
                "60–70% 的氮靠根瘤固氮。多施尿素会抑制根瘤（氮阻遏），花钱反而减产——本作最反直觉的一课。",
                SoybeanConfig.summerSoybean(),
                Map.of("jiangnan", MonthDay.of(6, 15), "huanan", MonthDay.of(3, 20),
                        "huanghuaihai", MonthDay.of(6, 20), "dongbei", MonthDay.of(5, 10)), 25, 25));

        // ---------------- 粮食·新增（2026-10-24）----------------
        put(new Crop("millet", "谷子（春播）", "谷子", "粮食·禾谷",
                "最耐旱耐瘠的禾谷：苗期必须蹲苗，地力越差越显它的本事。但它也最怕渍水。",
                HortConfigs.millet(),
                Map.of("jiangnan", MonthDay.of(4, 20), "huanan", MonthDay.of(3, 25),
                        "huanghuaihai", MonthDay.of(5, 5), "dongbei", MonthDay.of(5, 10)), 25, 30));

        put(new Crop("potato", "马铃薯（春播）", "马铃薯", "粮食·块茎",
                "收的是地下的块茎，但怕的是地上的霜：花期遇一次霜，一季白种。",
                HortConfigs.potato(),
                Map.of("jiangnan", MonthDay.of(2, 20), "huanan", MonthDay.of(1, 25),
                        "huanghuaihai", MonthDay.of(3, 5), "dongbei", MonthDay.of(4, 15)), 30, 35));

        // ---------------- 蔬菜 ----------------
        put(new Crop("cabbage", "大白菜（秋播）", "大白菜", "蔬菜·叶菜",
                "生育期最短、需氮最高的一类：莲座期决定叶面积，结球期决定产量。霜冻前必须收完。",
                HortConfigs.cabbage(),
                Map.of("jiangnan", MonthDay.of(8, 20), "huanan", MonthDay.of(9, 10),
                        "huanghuaihai", MonthDay.of(8, 10), "dongbei", MonthDay.of(7, 20)), 30, 35));

        put(new Crop("tomato", "番茄（春/秋茬）", "番茄", "蔬菜·果菜",
                "连续坐果作物：商品率是命门，畸形果、裂果、日灼都直接扣钱。露地栽培怕高温高湿。",
                HortConfigs.tomato(),
                Map.of("jiangnan", MonthDay.of(3, 15), "huanan", MonthDay.of(2, 20),
                        "huanghuaihai", MonthDay.of(4, 5), "dongbei", MonthDay.of(4, 20)), 30, 40));

        put(new Crop("cucumber", "黄瓜（春茬）", "黄瓜", "蔬菜·果菜",
                "需水需氮都高，结瓜期一天一个样。霜霉病在叶面结露时扩散最快。",
                HortConfigs.cucumber(),
                Map.of("jiangnan", MonthDay.of(3, 20), "huanan", MonthDay.of(3, 1),
                        "huanghuaihai", MonthDay.of(4, 10), "dongbei", MonthDay.of(4, 25)), 25, 45));

        put(new Crop("chili", "辣椒（春茬鲜椒）", "辣椒", "蔬菜·果菜",
                "单株果多果小，脐腐病由缺钙诱发——不是病害，是水肥问题。日灼会直接毁掉商品性。",
                HortConfigs.chili(),
                Map.of("jiangnan", MonthDay.of(3, 25), "huanan", MonthDay.of(3, 5),
                        "huanghuaihai", MonthDay.of(4, 15), "dongbei", MonthDay.of(4, 28)), 30, 40));

        // ---------------- 蔬菜·新增（2026-10-24）----------------
        put(new Crop("eggplant", "茄子（春茬）", "茄子", "蔬菜·果菜",
                "比番茄更耐热更耐涝，单果更重但单株果数少。褐纹病与蓟马同时盯上果面，商品率是命门。",
                HortConfigs.eggplant(),
                Map.of("jiangnan", MonthDay.of(4, 1), "huanan", MonthDay.of(3, 10),
                        "huanghuaihai", MonthDay.of(4, 20), "dongbei", MonthDay.of(5, 5)), 30, 40));

        put(new Crop("yardlong_bean", "豆角（春茬）", "豆角", "蔬菜·荚菜",
                "藤本要搭架，最怕伏天雨后落叶。锈病加豆荚螟，一季打不完的药。",
                HortConfigs.yardlongBean(),
                Map.of("jiangnan", MonthDay.of(4, 5), "huanan", MonthDay.of(3, 15),
                        "huanghuaihai", MonthDay.of(4, 25), "dongbei", MonthDay.of(5, 10)), 25, 40));

        put(new Crop("radish", "萝卜（秋播）", "萝卜", "蔬菜·根菜",
                "秋播短季作物，比白菜更耐寒。但根菜怕水：土壤过湿会烂根、糠心。",
                HortConfigs.radish(),
                Map.of("jiangnan", MonthDay.of(9, 5), "huanan", MonthDay.of(9, 25),
                        "huanghuaihai", MonthDay.of(8, 25), "dongbei", MonthDay.of(7, 25)), 30, 35));

        // ---------------- 水果 ----------------
        put(new Crop("strawberry", "草莓（秋植春收）", "草莓", "水果·浆果",
                "秋植越冬、春季采收。必须经历低温才能成花，北方越冬要防冻害；果期怕高温与灰霉。",
                HortConfigs.strawberry(),
                Map.of("jiangnan", MonthDay.of(9, 20), "huanan", MonthDay.of(10, 10),
                        "huanghuaihai", MonthDay.of(9, 5), "dongbei", MonthDay.of(8, 20)), 30, 30));

        put(new Crop("watermelon", "西瓜（春茬）", "西瓜", "水果·瓜类",
                "单株一两个大果，坐果期高温会大量化瓜；成熟期雨水多则裂瓜、甜度下降。",
                HortConfigs.watermelon(),
                Map.of("jiangnan", MonthDay.of(4, 1), "huanan", MonthDay.of(3, 10),
                        "huanghuaihai", MonthDay.of(4, 20), "dongbei", MonthDay.of(5, 5)), 25, 40));

        put(new Crop("melon", "甜瓜（春茬厚皮）", "甜瓜", "水果·瓜类",
                "比西瓜耐密植，单株多果。厚皮甜瓜对昼夜温差敏感，温差大才甜。",
                HortConfigs.melon(),
                Map.of("jiangnan", MonthDay.of(4, 5), "huanan", MonthDay.of(3, 15),
                        "huanghuaihai", MonthDay.of(4, 25), "dongbei", MonthDay.of(5, 8)), 25, 40));

        // ---------------- 水果·新增（2026-10-24）----------------
        put(new Crop("grape", "葡萄（休眠→春梢）", "葡萄", "水果·藤果",
                "冬季休眠要够冷才能萌芽。坐果期一场雨就会裂果，成熟期还怕鸟。",
                HortConfigs.grape(),
                Map.of("jiangnan", MonthDay.of(11, 1), "huanan", MonthDay.of(11, 15),
                        "huanghuaihai", MonthDay.of(10, 20), "dongbei", MonthDay.of(10, 10)), 25, 35));

        put(new Crop("pear", "梨（秋播）", "梨", "水果·仁果",
                "开花期怕霜，怕到「一场春雨就掉花」。疏果是产量控制的关键，留果太多反而压品质。",
                HortConfigs.pear(),
                Map.of("jiangnan", MonthDay.of(11, 5), "huanan", MonthDay.of(11, 20),
                        "huanghuaihai", MonthDay.of(10, 25), "dongbei", MonthDay.of(10, 15)), 25, 35));

        // ---------------- 花卉 ----------------
        put(new Crop("chrysanthemum", "菊花（切花）", "菊花", "花卉·切花",
                "短日植物，秋播型在短日季自然开花。现蕾期高温会导致花芽败育，切花品质一票否决。",
                HortConfigs.chrysanthemum(),
                Map.of("jiangnan", MonthDay.of(6, 20), "huanan", MonthDay.of(7, 10),
                        "huanghuaihai", MonthDay.of(6, 10), "dongbei", MonthDay.of(6, 1)), 25, 30));

        put(new Crop("sunflower", "向日葵（籽用）", "向日葵", "花卉·油料",
                "耐热耐旱，花期高温会显著降低结实率；与大豆相反，它不固氮，需氮不低。",
                HortConfigs.sunflower(),
                Map.of("jiangnan", MonthDay.of(4, 10), "huanan", MonthDay.of(3, 20),
                        "huanghuaihai", MonthDay.of(5, 1), "dongbei", MonthDay.of(5, 10)), 25, 35));

        put(new Crop("tulip", "郁金香（秋植球根）", "郁金香", "花卉·球根",
                "秋天种、春天开，必须经过足够低温才抽花——与冬小麦共用春化机制。球根耐寒，东北也能露地越冬。",
                HortConfigs.tulip(),
                Map.of("jiangnan", MonthDay.of(11, 10), "huanan", MonthDay.of(11, 25),
                        "huanghuaihai", MonthDay.of(10, 25), "dongbei", MonthDay.of(9, 25)), 20, 40));

        // ---------------- 花卉·新增（2026-10-24）----------------
        put(new Crop("rose", "月季（切花）", "月季", "花卉·观赏",
                "长日照植物，与菊花的花期触发机制正好相反。切花要的是花枝长度与整齐度，不是花数。",
                HortConfigs.rose(),
                Map.of("jiangnan", MonthDay.of(4, 15), "huanan", MonthDay.of(3, 25),
                        "huanghuaihai", MonthDay.of(4, 25), "dongbei", MonthDay.of(5, 5)), 30, 40));
    }


    public static Map<String, Crop> all() {
        return CROPS;
    }

    public static Crop get(String id) {
        Crop c = id == null ? null : CROPS.get(id);
        return c != null ? c : CROPS.get("rice_mid");
    }

    public static List<Crop> list() {
        return List.copyOf(CROPS.values());
    }

    /**
     * 该作物在该区的代表播期；若今年已过，顺延到明年。
     *
     * <p>🔴 <b>2026-10-23 修正</b>：这里的 {@code today} <b>必须是用户级世界日期</b>，
     * 不能是 {@code LocalDate.now()}。演示模式下世界日期可能已是 2027-11-21，
     * 用现实日期（2026-10-23）会算出「2027-09-20」这种<b>已经过去</b>的推荐播期——
     * 玩家点「回到推荐播期」会把日期设到过去。
     *
     * <p>顺延规则：若今年该月日比 {@code today} 早超过 20 天（还在窗口内的余量不够），
     * 顺延到明年同一月日。
     */
    public static LocalDate defaultSow(String cropId, String zoneId, LocalDate today) {
        Crop c = get(cropId);
        MonthDay md = c.sowByZone().getOrDefault(zoneId, c.sowByZone().get("jiangnan"));
        LocalDate candidate = md.atYear(today.getYear());
        if (candidate.isBefore(today.minusDays(20))) {
            candidate = md.atYear(today.getYear() + 1);
        }
        return candidate;
    }

    /**
     * <b>下一季</b>的推荐播期 —— 该月日的<b>下一次发生</b>，一定 ≥ 今天。
     *
     * <p>🔴 <b>2026-10-23 新增</b>。
     *
     * <p>为什么需要它：{@link #defaultSow} 算的是「<b>这一季</b>的代表播期」，
     * 允许落在今天之前（比如世界日期 2027-11-21 时，冬小麦在江南的推荐播期 10/25
     * 就已经过去了——那一茬是<b>上一季</b>播的，现在正长着）。
     *
     * <p>语义：找 today 之后<b>最近</b>的一次该月日。
     * <pre>
     *   today = 2027-11-21, md = 10/25
     *     → 今年 10/25 已过 → 2028-10-25
     *   today = 2027-09-01, md = 10/25
     *     → 今年 10/25 未到 → 2027-10-25
     *   today = 2027-10-25, md = 10/25
     *     → 今天就是推荐播期 → 2027-10-25（就是今天，允许）
     * </pre>
     *
     * <p>🔴 <b>2026-10-23 再修正</b>：本方法<b>不再是</b>推给玩家的「推荐播期」来源，
     * 那个位置已改用 {@link Crop.Crop#nearestWindow}。原因：本方法的锚点是
     * 「该月日的下一次发生」，<b>只要该月日刚过就跳到明年</b> ——
     * 大白菜江南（推荐 8/20、窗口 7/21~9/24），今天 2028-09-20 明明还能种到 9/24，
     * 本方法却给 2029-08-20（要等一年）。
     *
     * <p>本方法保留为「该月日的下一次发生」这一纯语义的工具，
     * 并作为 {@code nearestWindow} 行为的对照组。
     */
    public static LocalDate nextSow(String cropId, String zoneId, LocalDate today) {
        Crop c = get(cropId);
        MonthDay md = c.sowByZone().getOrDefault(zoneId, c.sowByZone().get("jiangnan"));
        LocalDate candidate = md.atYear(today.getYear());
        if (candidate.isBefore(today)) {
            candidate = md.atYear(today.getYear() + 1);
        }
        return candidate;
    }

    /** 参照产量（S23 的 Y_ref）。 */
    public static double yieldRef(String cropId) {
        return get(cropId).config().yieldRef();
    }
}
