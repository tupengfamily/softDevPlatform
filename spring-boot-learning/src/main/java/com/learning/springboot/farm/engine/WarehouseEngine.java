package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库引擎（S24）：收获物的<b>仓储期</b>、<b>变质</b>与<b>市场价格</b>。
 *
 * <h2>为什么要有仓库</h2>
 * 收割时直接把产量折成钱，模拟就退化成「种 → 收钱」的单步操作，
 * 玩家感受不到「丰收也可能变成负担」。仓库把这个选择显性化：
 * <ul>
 *   <li><b>什么时候卖</b>——早卖得高价（抢鲜），晚卖打折（品质衰减），过期白扔；</li>
 *   <li><b>卖多少</b>——囤货本身会压价（供给压力），全砸在一个品种上最亏。</li>
 * </ul>
 *
 * <h2>价格公式</h2>
 * <pre>
 *   单价 = 基准价(作物) × 地区系数(区) × 品质系数(批次) × 供给系数(用户囤货量)
 * </pre>
 * 四个系数各自对应一条现实：
 * <ul>
 *   <li><b>基准价</b>：2025 年产地批发价量级（元/kg），粮食按最低收购价，园艺按产地中位价；</li>
 *   <li><b>地区系数</b>：主产区便宜、销区贵（rice 在江南 0.92、华南 1.08）；</li>
 *   <li><b>品质系数</b>：随仓储天数线性衰减（新鲜 1.0 → 临界 0.72 → 过期 0）；</li>
 *   <li><b>供给系数</b>：用户自己的库存越多，价格越低（局部均衡，见 {@link #supplyFactor}）。</li>
 * </ul>
 *
 * <h2>品质分级</h2>
 * <ul>
 *   <li>{@code FRESH}    新鲜，品质系数 1.0，可卖；</li>
 *   <li>{@code DECAYING} 品质下降，价格打折，<b>仍可卖</b>；</li>
 *   <li>{@code ROTTEN}   已变质，<b>不可出售</b>（只能丢弃，白扔投入成本）。</li>
 * </ul>
 * 分级边界由「仓储期」和「临期阈值」共同决定，两者都按作物配置。
 */
public final class WarehouseEngine {

    private WarehouseEngine() {
    }

    // ==================== 品质状态 ====================

    /** 仓库批次的品质状态。 */
    public enum Grade {
        /** 新鲜：品质系数 1.0。 */
        FRESH("新鲜", 1.00),
        /** 品质下降：仍可卖，但打折。 */
        DECAYING("品质下降", 0.72),
        /** 已变质：不可出售。 */
        ROTTEN("已变质", 0.00);

        private final String label;
        private final double factor;

        Grade(String label, double factor) {
            this.label = label;
            this.factor = factor;
        }

        /**
         * 中文标签。
         *
         * <p>⚠️ 不能叫 {@code name()} —— {@link Enum#name()} 是 final 方法，
         * 覆盖会编译失败（Java 报「被覆盖的方法为 final」）。
         */
        public String label() {
            return label;
        }

        /** 品质对售价的折算系数。 */
        public double factor() {
            return factor;
        }

        /** 是否还能卖出去。 */
        public boolean sellable() {
            return this != ROTTEN;
        }
    }

    // ==================== 作物仓储档案 ====================

    /**
     * 某作物的仓储档案。
     *
     * @param id           作物 id
     * @param name         作物名
     * @param basePrice    基准价（元/kg）
     * @param storageDays  仓储期（天）：超过就变质不可售
     * @param warnDays     临期提醒提前量（天）：距变质 ≤ 该值时提醒玩家
     * @param perishable   是否易腐（蔬菜/水果/花卉为 true）：易腐作物品质衰减更快
     */
    public record Storage(
            String id,
            String name,
            double basePrice,
            int storageDays,
            int warnDays,
            boolean perishable) {
    }

    /**
     * 22 种作物的仓储档案。
     *
     * <p><b>仓储期取值的现实依据</b>（常温静置、不冷链）：
     * <ul>
     *   <li>粮食：仓储 months 级，散装稻谷能存半年以上 → 180~270 天；</li>
     *   <li>豆类：耐储 → 240 天；</li>
     *   <li>蔬菜：叶菜几天、果菜一两周 → 7~21 天；</li>
     *   <li>水果：草莓 2 天、西瓜甜瓜 20~30 天；</li>
     *   <li>花卉：鲜切花几天就谢 → 3~7 天；</li>
     *   <li>块茎：马铃薯需阴凉窖藏、避光防发芽 → 60 天（常温下会发芽变青）；</li>
     *   <li>仁果：梨耐储 → 45 天；葡萄不耐压 → 15 天。</li>
     * </ul>
     *
     * <p>基准价与 {@code FarmPlayService.priceOf()} 同量级，但**这里另存一份**——
     * 那份是「收割即结算」的旧口径，这份是「仓库变卖」的口径（带地区与供给系数）。
     * 两份口径本来就该不同：同一批稻谷，在江南卖和在华南卖不是一个价。
     */
    private static final Map<String, Storage> STORAGES = new LinkedHashMap<>();

    static {
        // 粮食：耐储，仓储期以「月」计
        put(new Storage("rice_mid", "水稻", 2.58, 270, 30, false));
        put(new Storage("wheat_winter", "冬小麦", 2.40, 240, 30, false));
        put(new Storage("corn_spring", "玉米", 2.30, 270, 30, false));
        put(new Storage("soybean", "大豆", 5.00, 300, 45, false));
        // 蔬菜：易腐，仓储期以「周」计
        put(new Storage("cabbage", "大白菜", 1.20, 45, 10, true));
        put(new Storage("tomato", "番茄", 4.50, 14, 4, true));
        put(new Storage("cucumber", "黄瓜", 3.20, 12, 4, true));
        put(new Storage("chili", "辣椒", 6.00, 21, 6, true));
        // 水果：草莓极易腐，西瓜甜瓜耐储
        put(new Storage("strawberry", "草莓", 20.00, 3, 1, true));
        put(new Storage("watermelon", "西瓜", 2.00, 30, 8, true));
        put(new Storage("melon", "甜瓜", 5.00, 21, 6, true));
        // 花卉：鲜切花最娇气
        put(new Storage("chrysanthemum", "菊花", 15.00, 7, 2, true));
        put(new Storage("sunflower", "向日葵", 8.00, 10, 3, true));
        put(new Storage("tulip", "郁金香", 25.00, 5, 2, true));
        // ---------------- 2026-10-24 新增 8 种 ----------------
        // 粮食/块茎：耐储；蔬菜：易腐；水果：葡萄不耐压、梨耐储；花卉：鲜切花最娇气。
        put(new Storage("millet", "谷子", 3.20, 240, 30, false));
        put(new Storage("potato", "马铃薯", 1.60, 60, 12, false));
        put(new Storage("eggplant", "茄子", 3.60, 12, 4, true));
        put(new Storage("yardlong_bean", "豆角", 6.00, 8, 3, true));
        put(new Storage("radish", "萝卜", 1.50, 30, 8, true));
        put(new Storage("grape", "葡萄", 12.00, 15, 5, true));
        put(new Storage("pear", "梨", 5.00, 45, 12, true));
        put(new Storage("rose", "月季", 18.00, 5, 2, true));
    }

    private static void put(Storage s) {
        STORAGES.put(s.id(), s);
    }

    /** 取作物的仓储档案；未知作物按「粮食·中等仓储」兜底。 */
    public static Storage storage(String cropId) {
        String id = cropId == null ? "rice_mid" : cropId;
        Storage s = STORAGES.get(id);
        return s != null ? s : new Storage(id, id, 2.00, 90, 20, false);
    }

    /** 全部仓储档案（供 UI 展示「每种作物能存多久」）。 */
    public static List<Storage> all() {
        return List.copyOf(STORAGES.values());
    }

    // ==================== 地区价格系数 ====================

    /**
     * 各农业区的价格系数（1.0 = 全国均价）。
     *
     * <p>逻辑：**主产区压价、销区抬价**。
     * <ul>
     *   <li>江南是水稻主产区 → 稻米 0.92（本地米价本来就比销区低）；</li>
     *   <li>华南是蔬菜与热带水果主产区 + 又是人口密集销区 → 菜价高、花卉溢价高；</li>
     *   <li>黄淮海是玉米与小麦主产区 → 玉米 0.90、小麦 0.95；</li>
     *   <li>东北是优质稻米产区，但物流远 → 稻米 0.96。</li>
     * </ul>
     */
    private static final Map<String, Map<String, Double>> ZONE_PRICE = new LinkedHashMap<>();

    static {
        Map<String, Double> jiangnan = new LinkedHashMap<>();
        jiangnan.put("rice_mid", 0.92);
        jiangnan.put("wheat_winter", 1.02);
        jiangnan.put("corn_spring", 1.00);
        jiangnan.put("soybean", 1.00);
        jiangnan.put("cabbage", 0.95);
        jiangnan.put("tomato", 1.00);
        jiangnan.put("cucumber", 0.98);
        jiangnan.put("chili", 1.02);
        jiangnan.put("strawberry", 1.05);
        jiangnan.put("watermelon", 1.00);
        jiangnan.put("melon", 1.00);
        jiangnan.put("chrysanthemum", 0.95);
        jiangnan.put("sunflower", 1.00);
        jiangnan.put("tulip", 0.90);
        // 2026-10-24 新增：江南是蔬菜与花卉主产区，同时也是人口密集销区 → 菜价偏高
        jiangnan.put("millet", 1.00);
        jiangnan.put("potato", 1.00);
        jiangnan.put("eggplant", 1.02);
        jiangnan.put("yardlong_bean", 1.05);
        jiangnan.put("radish", 1.00);
        jiangnan.put("grape", 1.08);
        jiangnan.put("pear", 1.05);
        jiangnan.put("rose", 1.15);
        ZONE_PRICE.put("jiangnan", jiangnan);

        Map<String, Double> huanan = new LinkedHashMap<>();
        huanan.put("rice_mid", 1.08);
        huanan.put("wheat_winter", 1.15);
        huanan.put("corn_spring", 1.12);
        huanan.put("soybean", 1.10);
        huanan.put("cabbage", 1.05);
        huanan.put("tomato", 1.08);
        huanan.put("cucumber", 1.06);
        huanan.put("chili", 1.05);
        huanan.put("strawberry", 1.12);
        huanan.put("watermelon", 1.05);
        huanan.put("melon", 1.05);
        huanan.put("chrysanthemum", 1.20);
        huanan.put("sunflower", 1.15);
        huanan.put("tulip", 1.10);
        // 华南是热带水果与冬季蔬菜主产区，但本地也产 → 蔬果价差不大
        huanan.put("millet", 1.05);
        huanan.put("potato", 1.10);
        huanan.put("eggplant", 1.05);
        huanan.put("yardlong_bean", 1.00);
        huanan.put("radish", 1.02);
        huanan.put("grape", 1.02);
        huanan.put("pear", 1.05);
        huanan.put("rose", 1.20);
        ZONE_PRICE.put("huanan", huanan);

        Map<String, Double> huanghuaihai = new LinkedHashMap<>();
        huanghuaihai.put("rice_mid", 1.02);
        huanghuaihai.put("wheat_winter", 0.95);
        huanghuaihai.put("corn_spring", 0.90);
        huanghuaihai.put("soybean", 0.94);
        huanghuaihai.put("cabbage", 1.00);
        huanghuaihai.put("tomato", 0.96);
        huanghuaihai.put("cucumber", 0.95);
        huanghuaihai.put("chili", 0.95);
        huanghuaihai.put("strawberry", 1.10);
        huanghuaihai.put("watermelon", 1.00);
        huanghuaihai.put("melon", 0.98);
        huanghuaihai.put("chrysanthemum", 1.05);
        huanghuaihai.put("sunflower", 0.95);
        huanghuaihai.put("tulip", 1.05);
        // 黄淮海是马铃薯与梨的主产区 → 本地价低；物流到南方后溢价
        huanghuaihai.put("millet", 0.95);
        huanghuaihai.put("potato", 0.90);
        huanghuaihai.put("eggplant", 0.95);
        huanghuaihai.put("yardlong_bean", 0.95);
        huanghuaihai.put("radish", 0.98);
        huanghuaihai.put("grape", 1.00);
        huanghuaihai.put("pear", 0.92);
        huanghuaihai.put("rose", 1.05);
        ZONE_PRICE.put("huanghuaihai", huanghuaihai);

        Map<String, Double> dongbei = new LinkedHashMap<>();
        dongbei.put("rice_mid", 0.96);
        dongbei.put("wheat_winter", 1.00);
        dongbei.put("corn_spring", 1.05);
        dongbei.put("soybean", 1.08);
        dongbei.put("cabbage", 1.12);
        dongbei.put("tomato", 1.20);
        dongbei.put("cucumber", 1.20);
        dongbei.put("chili", 1.18);
        dongbei.put("strawberry", 1.30);
        dongbei.put("watermelon", 1.15);
        dongbei.put("melon", 1.15);
        dongbei.put("chrysanthemum", 1.30);
        dongbei.put("sunflower", 1.25);
        dongbei.put("tulip", 1.30);
        // 东北：设施蔬菜价高（本地暖季产量不足），马铃薯是主粮价格低
        dongbei.put("millet", 1.00);
        dongbei.put("potato", 0.95);
        dongbei.put("eggplant", 1.15);
        dongbei.put("yardlong_bean", 1.15);
        dongbei.put("radish", 1.08);
        dongbei.put("grape", 1.10);
        dongbei.put("pear", 1.00);
        dongbei.put("rose", 1.30);
        ZONE_PRICE.put("dongbei", dongbei);
    }

    /** 地区价格系数；未知区返回 1.0。 */
    public static double zonePriceFactor(String zoneId, String cropId) {
        Map<String, Double> m = ZONE_PRICE.get(zoneId);
        if (m == null) {
            return 1.0;
        }
        Double v = m.get(cropId);
        return v == null ? 1.0 : v;
    }

    // ==================== 供给压力（局部均衡） ====================

    /**
     * 供给压力的死区（kg）：库存不超过这个量<b>不压价</b>。
     *
     * <p>现实依据：收购商不会因为你多囤 100 公斤就压价——那是正常农户的量。
     * 只有当货量大到能冲击当地市场（几百公斤以上）时才有议价压力。
     */
    public static final double DEAD_KG = 150.0;

    /**
     * 供给压力的参照量（kg）：超过死区后，每多这么多货算作「一份额外供给」。
     *
     * <p>取 2000 kg 是为了让曲线有足够的区分度：2000 kg 压到 0.75，
     * 5000 kg 压到 0.56，10000 kg 才触到 0.45 下界——
     * 如果参照量太小（比如 500），1500 kg 就直接触底，
     * 「囤 2000 kg」和「囤 20000 kg」价格完全一样，玩家的囤货决策就没有梯度了。
     */
    public static final double REF_KG = 2000.0;

    /**
     * 供给压力：用户囤货越多，同一作物的价格越低。
     *
     * <p><b>模型</b>：设用户该作物的总库存为 {@code kg}。现实里小批量卖货
     * 是<b>不压价</b>的——收购商不会因为你多囤 100 公斤就压你的价，
     * 只有当你的货量<b>大到能冲击当地市场</b>时才会压价。所以先过一个死区：
     * <pre>
     *   有效供给 s = max(0, kg - DEAD_KG) / REF_KG        // DEAD_KG = 150 kg 死区
     *   供给系数   = 1 / (1 + 0.45 × s)
     * </pre>
     * 效果：
     * <ul>
     *   <li>0~150 kg：<b>不压价</b>（系数 1.0）——正常农户的量；</li>
     *   <li>500 kg：0.93（小幅压价）；</li>
     *   <li>2000 kg：0.71（压掉近三成）；</li>
     *   <li>5000 kg：0.48（腰斩）；</li>
     *   <li>10000 kg 以上：触到 0.45 下界。</li>
     * </ul>
     *
     * <p>为什么用倒数而不是线性扣减：线性扣减会在囤够量后出现负价，
     * 倒数形式天然落在 (0, 1]，且「囤得越多、边际收益递减」符合现实
     * （多出来的粮只能去更远的地方卖，运费吃掉差价）。
     *
     * <p><b>下界 0.45</b>：再囤也不会把价格压到 45% 以下——真实市场有外部收购价托底，
     * 不会无限跌。
     *
     * @param kg 该作物在用户仓库中的总库存（kg，只统计未变质的）
     */
    public static double supplyFactor(double kg) {
        if (kg <= DEAD_KG) {
            return 1.0;
        }
        double s = (kg - DEAD_KG) / REF_KG;
        double f = 1.0 / (1.0 + 0.45 * s);
        return Math.max(0.45, f);
    }

    /**
     * 供给压力的文字说明（给 UI 显示「为什么便宜了」）。
     *
     * @param kg 该作物总库存
     * @return 说明文案；无压力时返回 null
     */
    public static String supplyNote(double kg) {
        double f = supplyFactor(kg);
        if (f >= 0.995) {
            return null;
        }
        double drop = Math.round((1 - f) * 100);
        if (kg >= 1500) {
            return "你仓库里这个品种压了 " + Math.round(kg) + " kg，收购商都在压价（-" + drop
                    + "%），再囤只会更贱";
        }
        return "库存 " + Math.round(kg) + " kg，供给偏多，价格被压了 " + drop + "%";
    }

    // ==================== 品质评估 ====================

    /**
     * 评估一个批次当前的品质状态。
     *
     * <p>分级规则：
     * <ul>
     *   <li>仓储天数 ≥ 仓储期 → {@link Grade#ROTTEN}（不可售）；</li>
     *   <li>仓储天数 ≥ 临界点（仓储期的 60%，易腐作物 50%）→ {@link Grade#DECAYING}（打折但可卖）；</li>
     *   <li>否则 → {@link Grade#FRESH}。</li>
     * </ul>
     *
     * @param cropId     作物 id
     * @param storedDate 入库日期（世界日期）
     * @param today      当前世界日期
     */
    public static Grade grade(String cropId, LocalDate storedDate, LocalDate today) {
        Storage s = storage(cropId);
        int aged = agedDays(storedDate, today);
        return gradeByAge(s, aged);
    }

    /** 按仓储天数定级（不依赖日期，便于单测）。 */
    public static Grade gradeByAge(Storage s, int agedDays) {
        if (agedDays >= s.storageDays()) {
            return Grade.ROTTEN;
        }
        int threshold = s.perishable()
                ? (int) Math.round(s.storageDays() * 0.5)
                : (int) Math.round(s.storageDays() * 0.6);
        return agedDays >= threshold ? Grade.DECAYING : Grade.FRESH;
    }

    /**
     * 已仓储天数（世界日期口径）。
     *
     * <p>⚠️ 演示模式下每块地有自己的 {@code demoOffsetDays}，仓库是用户级的，
     * 所以这里由调用方传入「当前世界日期」，不能直接用 {@code LocalDate.now()}。
     */
    public static int agedDays(LocalDate storedDate, LocalDate today) {
        if (storedDate == null || today == null) {
            return 0;
        }
        long d = ChronoUnit.DAYS.between(storedDate, today);
        return (int) Math.max(0, d);
    }

    /** 距离变质还有几天（≤0 表示已变质）。 */
    public static int daysToSpoil(Storage s, LocalDate storedDate, LocalDate today) {
        return s.storageDays() - agedDays(storedDate, today);
    }

    /** 是否处于临期（距变质 ≤ 提醒提前量，且尚未变质）。 */
    public static boolean isExpiring(Storage s, LocalDate storedDate, LocalDate today) {
        int left = daysToSpoil(s, storedDate, today);
        return left > 0 && left <= s.warnDays();
    }

    // ==================== 估值 ====================

    /**
     * 一批货的估值结果。
     *
     * @param unitPrice  单价（元/kg，已含全部系数）
     * @param quantity   数量（kg）
     * @param amount     总价值（元）
     * @param grade      品质状态
     * @param agedDays   已仓储天数
     * @param daysLeft   距变质天数
     */
    public record Valuation(
            double unitPrice,
            double quantity,
            double amount,
            Grade grade,
            int agedDays,
            int daysLeft,
            boolean expiring,
            String supplyNote) {
    }

    /**
     * 给一个批次估值。
     *
     * @param cropId     作物 id
     * @param zoneId     所在农业区（决定地区系数）
     * @param quantity   数量（kg）
     * @param storedDate 入库日期
     * @param today      当前世界日期
     * @param totalKg    该作物在用户仓库的<b>总</b>库存（kg）——供给压力用
     */
    public static Valuation value(String cropId, String zoneId, double quantity,
                                   LocalDate storedDate, LocalDate today, double totalKg) {
        return value(cropId, zoneId, quantity, storedDate, today, totalKg, 1.0);
    }

    /**
     * 给一个批次估值（带额外的品质折价）。
     *
     * <p>🔴 2026-10-26 修复轻级问题：原来写的是 {@code extraFactor <= 0 ? 1.0 : extraFactor}，
     * 把 <b>0 当成了「没传」</b>。但 0 在业务上是<b>有意义的</b>——
     * 它表示「这批货一文不值」（全额折价 / 直接报废），静默当成 1.0 会让玩家
     * 白白按原价卖出本该归零的货。现在只有<b>负数</b>（脏数据）才退回 1.0。
     *
     * @param extraFactor 额外折价系数（农残超标 0.85、正常 1.0、烘干机加成可 &gt; 1）——
     *                    入库时就已注定的扣价必须跟着货走。
     *                    <b>0 = 该批货价值归零</b>；负数视为脏数据，按 1.0 处理。
     */
    public static Valuation value(String cropId, String zoneId, double quantity,
                                   LocalDate storedDate, LocalDate today, double totalKg,
                                   double extraFactor) {
        Storage s = storage(cropId);
        int aged = agedDays(storedDate, today);
        Grade g = gradeByAge(s, aged);
        double factor = extraFactor < 0 ? 1.0 : extraFactor;
        double unit = round(s.basePrice()
                * zonePriceFactor(zoneId, cropId)
                * g.factor()
                * factor
                * supplyFactor(totalKg), 3);
        double amount = round(unit * quantity, 1);
        int left = daysToSpoil(s, storedDate, today);
        return new Valuation(unit, round(quantity, 1), amount, g, aged, left,
                isExpiring(s, storedDate, today), supplyNote(totalKg));
    }

    /**
     * 「如果现在全卖」的总价（用于 UI 显示「全部卖出能拿多少」）。
     *
     * @param batches 批次列表（每个含 cropId / zoneId / quantity / storedDate）
     */
    public static double totalValue(List<BatchRef> batches, LocalDate today) {
        double sum = 0;
        for (BatchRef b : batches) {
            sum += value(b.cropId(), b.zoneId(), b.quantity(), b.storedDate(), today, b.totalKg()).amount();
        }
        return round(sum, 1);
    }

    /** 估值所需的批次信息（避免引擎依赖实体类）。 */
    public record BatchRef(String cropId, String zoneId, double quantity,
                           LocalDate storedDate, double totalKg) {
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
