package com.learning.springboot.farm.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农资货架（S12）。
 *
 * <p><b>只定义"货"和"三道闸门"的参数，不定义产量公式</b>：化肥走 S6 的养分/地力通道，
 * 农药走 S9 的防治通道（{@link PestEngine}）。S12 里出现 {@code yield = f(化肥)} 就是错的。
 *
 * <p>三道闸门（缺一不可，充值无法跳过）：
 * <ol>
 *   <li><b>对的物</b>：药剂的 {@code targets} 是否匹配病原；化肥的元素是否匹配缺素。</li>
 *   <li><b>对的时机</b>：是否落在该工序的物候窗口（超窗药效/肥效 ×0.5）。</li>
 *   <li><b>对的量</b>：剂量是否在报酬递减区间；过量触发药害/徒长。</li>
 * </ol>
 *
 * <p>价格取真实量级（尿素 ~2.6 元/kg、复合肥 ~3.2、农药 5–15 元/亩），
 * 标注「真实参考值，待平衡」。
 */
public final class InputCatalog {

    private InputCatalog() {
    }

    public enum Kind {
        /** 化肥 / 微肥 */
        FERT,
        /** 农药 */
        PEST,
        /**
         * 除草剂（2026-10-22 需求 O）。
         *
         * <p>单独一类而不是塞进 PEST：除草剂的目标不是病虫而是<b>杂草</b>，
         * 走的是 {@link WeedEngine} 这条完全不同的通道（争水争光，不是防效），
         * 而且它<b>没有安全间隔期意义上的 PHI 农残问题</b>（草不进人),
         * 但有<b>作死期</b>——苗后除草剂会伤苗，用错时期比用错药更致命。
         */
        HERB
    }

    /**
     * @param nContent    化肥的折纯养分含量（N / P₂O₅ / K₂O），农药为 0
     * @param targets     农药可防治的病虫害 id（与 {@code PestEngine} 的 id 对齐）
     * @param efficacy    对症时的基础防效（生物农药 0.8）
     * @param phiDays     安全间隔期（天）：距采收不足此天数施用 → 农残超标
     * @param protectDays 持效期（天）
     */
    public record Item(
            String id,
            String name,
            Kind kind,
            String category,
            String unit,
            double doseMin,
            double doseOpt,
            double doseMax,
            double pricePerUnit,
            double nContent,
            String element,
            List<String> targets,
            String modeGroup,
            double efficacy,
            int phiDays,
            int protectDays,
            String note) {
    }

    /** 水稻全季需氮量（折纯，kg/亩）：数据卡「总 N 9–13」取中位。 */
    public static final double RICE_N_NEED = 11.0;

    /** 作物需氮量（kg/亩，折纯）。 */
    public static double nNeedOf(String cropId) {
        return CropCatalog.get(cropId).config().nNeed();
    }

    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    static {
        // ---------------- 化肥 ----------------
        ITEMS.put("UREA", new Item(
                "UREA", "尿素（N 46%）", Kind.FERT, "FERT_N", "kg",
                0, 22, 34, 2.6, 0.46, "N",
                List.of(), "速效氮", 1.0, 0, 3,
                "速效氮肥，追肥主力；铵态氮长期过量会降 pH"));
        ITEMS.put("COMPOUND", new Item(
                "COMPOUND", "复合肥（15-15-15）", Kind.FERT, "FERT_NPK", "kg",
                0, 30, 45, 3.2, 0.15, "NPK",
                List.of(), "平衡肥", 1.0, 0, 3,
                "基肥主力，磷钾一次性带入；氮含量只有 15%，别当尿素用"));
        ITEMS.put("KCL", new Item(
                "KCL", "氯化钾（K₂O 60%）", Kind.FERT, "FERT_K", "kg",
                0, 10, 16, 4.0, 0.60, "K",
                List.of(), "钾肥", 1.0, 0, 3,
                "钾肥：壮秆、抗倒、促灌浆；忌氯作物改用硫酸钾"));
        ITEMS.put("BORAX", new Item(
                "BORAX", "硼砂（B 11%）", Kind.FERT, "FERT_MICRO", "kg",
                0, 0.75, 1.2, 6.0, 0.11, "B",
                List.of(), "微肥", 1.0, 0, 3,
                "微量元素；油菜缺硼会「花而不实」，水稻需求低但缺了也会空壳"));

        // ---------------- 农药 ----------------
        ITEMS.put("TRICYCLAZOLE", new Item(
                "TRICYCLAZOLE", "三环唑（75% WP）", Kind.PEST, "PEST_FUNGICIDE", "g",
                25, 50, 80, 0.16, 0, "",
                List.of("blast"), "三唑类", 1.0, 21, 10,
                "稻瘟病专用；破口前 3–5 天是穗颈瘟的唯一有效窗口"));
        ITEMS.put("JINGGANGMEISU", new Item(
                "JINGGANGMEISU", "井冈霉素", Kind.PEST, "PEST_BIO", "g",
                20, 40, 70, 0.13, 0, "",
                List.of("sheath_blight"), "生物类", 1.0, 14, 10,
                "纹枯病；生物农药，抗性风险低，但持效期短"));
        ITEMS.put("COPPER", new Item(
                "COPPER", "噻菌铜", Kind.PEST, "PEST_FUNGICIDE", "g",
                30, 60, 100, 0.20, 0, "",
                List.of("bacterial_blight", "soft_rot"), "铜制剂", 1.0, 14, 7,
                "细菌性病害；台风暴雨后预防性用药，发病后再打收效有限"));
        ITEMS.put("IMIDACLOPRID", new Item(
                "IMIDACLOPRID", "吡虫啉（10% WP）", Kind.PEST, "PEST_INSECTICIDE", "g",
                10, 20, 35, 0.30, 0, "",
                List.of("planthopper", "wheat_aphid", "soybean_aphid",
                        "melon_aphid", "flower_aphid"), "新烟碱类", 1.0, 14, 10,
                "稻飞虱；对蜜蜂高毒，花期禁用"));
        ITEMS.put("CHLORANTRANILIPROLE", new Item(
                "CHLORANTRANILIPROLE", "氯虫苯甲酰胺", Kind.PEST, "PEST_INSECTICIDE", "ml",
                15, 30, 50, 0.50, 0, "",
                List.of("stem_borer", "leaf_roller", "corn_borer", "fall_armyworm",
                        "cabbage_worm"), "双酰胺类", 1.0, 21, 14,
                "螟虫、卷叶螟；卵孵盛期到低龄幼虫期有效，3 龄后打不到"));
        ITEMS.put("CYPROCONAZOLE", new Item(
                "CYPROCONAZOLE", "氰烯菌酯（25% SC）", Kind.PEST, "PEST_FUNGICIDE", "ml",
                40, 80, 130, 0.42, 0, "",
                List.of("wheat_scab", "leafy_downy", "cuc_downy", "flower_gray"), "氰基丙烯酸酯类", 1.0, 21, 14,
                "小麦赤霉病：扬花初期「见花打药」是唯一有效窗口，错过基本无解"));
        ITEMS.put("TRIADIMEFON", new Item(
                "TRIADIMEFON", "三唑酮（20% EC）", Kind.PEST, "PEST_FUNGICIDE", "ml",
                30, 60, 100, 0.26, 0, "",
                List.of("stripe_rust", "powdery_mildew", "cuc_powdery", "flower_powdery"), "三唑类", 1.0, 21, 12,
                "条锈病/白粉病：发病初期用药，拔节期是最后窗口"));
        ITEMS.put("EMAMECTIN", new Item(
                "EMAMECTIN", "甲维盐（5% WG）", Kind.PEST, "PEST_INSECTICIDE", "g",
                8, 15, 25, 0.90, 0, "",
                List.of("fall_armyworm", "corn_borer"), "生物衍生类", 1.0, 14, 10,
                "草地贪夜蛾/玉米螟：低龄幼虫期（3 龄前）才打得住"));
        ITEMS.put("RHIZOBIUM", new Item(
                "RHIZOBIUM", "根瘤菌剂", Kind.FERT, "FERT_BIO", "g",
                100, 250, 400, 0.06, 0, "BIO-N",
                List.of(), "生物固氮", 1.0, 0, 5,
                "大豆专用：接种后固氮 +15~25%。对水稻/玉米无效，别买错"));
        ITEMS.put("MANCOZEB", new Item(
                "MANCOZEB", "代森锰锌（80% WP）", Kind.PEST, "PEST_FUNGICIDE", "g",
                100, 180, 260, 0.22, 0, "",
                List.of("early_blight", "gray_mold", "leafy_downy", "cuc_downy", "flower_gray"),
                "二硫代氨基甲酸酯", 1.0, 15, 12,
                "广谱保护性杀菌剂：必须在发病前或初期用，病斑连片后再打只能控住不扩散"));
        ITEMS.put("ABAMECTIN", new Item(
                "ABAMECTIN", "阿维菌素（1.8% EC）", Kind.PEST, "PEST_INSECTICIDE", "ml",
                20, 40, 70, 0.36, 0, "",
                List.of("spider_mite", "melon_aphid"), "阿维菌素类", 1.0, 14, 10,
                "红蜘蛛/瓜蚜：叶背着药才算数，喷不透等于没打；抗性上升快，别连用两遍"));
        ITEMS.put("BT", new Item(
                "BT", "苏云金杆菌（Bt）", Kind.PEST, "PEST_BIO", "g",
                30, 60, 100, 0.17, 0, "",
                List.of("leaf_roller", "fall_armyworm", "cabbage_worm"), "生物类", 0.8, 1, 6,
                "生物农药：安全间隔期只有 1 天，是采收前应急的唯一合法选择"));

        // ---------------- 除草剂（2026-10-22 需求 O）----------------
        // ⚠️ 除草剂最贵的错不是"打错草"，而是"打早/打晚"：
        //   苗前施用会伤苗（禾苗还没出，药剂直接接触种子/芽鞘）；
        //   杂草木质化后再打，杂草已经抢完了水肥，药钱白花。
        //   所以窗口给得比其他农药窄：只在苗后到封行前这一段有效。
        ITEMS.put("QUIZALOFOP", new Item(
                "QUIZALOFOP", "精喹禾灵（10% EC）", Kind.HERB, "PEST_HERBICIDE", "ml",
                25, 50, 75, 0.28, 0, "",
                List.of("weed"), "禾本科专效", 0.95, 15, 15,
                "只打禾本科杂草（稗草、千金子），对阔叶草无效；苗后 2–5 叶期打，打早了伤苗"));
        ITEMS.put("BENSULFURON", new Item(
                "BENSULFURON", "苄嘧磺隆（10% WP）", Kind.HERB, "PEST_HERBICIDE", "g",
                30, 60, 90, 0.32, 0, "",
                List.of("weed"), "阔叶广谱", 0.90, 20, 18,
                "广谱阔叶除草剂；见效慢（3–5 天），但对禾苗相对安全，封行前打最划算"));
    }

    public static Map<String, Item> all() {
        return ITEMS;
    }

    public static Item get(String id) {
        return id == null ? null : ITEMS.get(id);
    }

    public static List<Item> fertilizers() {
        return ITEMS.values().stream().filter(i -> i.kind() == Kind.FERT).toList();
    }

    public static List<Item> pesticides() {
        return ITEMS.values().stream().filter(i -> i.kind() == Kind.PEST).toList();
    }

    /** 除草剂货架（2026-10-22 需求 O）。 */
    public static List<Item> herbicides() {
        return ITEMS.values().stream().filter(i -> i.kind() == Kind.HERB).toList();
    }

    /**
     * 剂量系数（闸门三）。
     *
     * <p>不足按线性打折，过量不奖励（报酬递减已封顶），超过 1.5 倍触发药害/徒长，
     * 用负系数表示——这是"无脑加倍"变成负收益的地方。
     */
    public static double doseFactor(Item it, double dose) {
        if (it == null || it.doseOpt() <= 0) {
            return 0;
        }
        double r = dose / it.doseOpt();
        if (r <= 0) {
            return 0;
        }
        if (r < 0.85) {
            return Math.max(0.25, 0.55 + 0.45 * (r / 0.85));
        }
        if (r <= 1.25) {
            return 1.0;
        }
        if (r <= 1.5) {
            return 1.0 - 0.10 * (r - 1.25) / 0.25;
        }
        return -Math.min(0.35, 0.16 * (r - 1.5));
    }

    /** 是否为过量（用于提示药害/徒长）。 */
    public static boolean overdose(Item it, double dose) {
        return it != null && dose > it.doseOpt() * 1.5;
    }

    /**
     * 药剂/肥料的物候窗口（DVS 区间）。超窗仍可施，但效力 ×0.5——
     * 这是"对的时机"这道闸门的落地，也是真实农艺里最贵的一课。
     */
    public static double[] windowOf(String id) {
        return switch (id == null ? "" : id) {
            case "TRICYCLAZOLE" -> new double[]{0.55, 0.80};   // 破口前 3–5 天到齐穗
            case "JINGGANGMEISU" -> new double[]{0.16, 0.60};  // 分蘖末—拔节
            case "COPPER" -> new double[]{0.20, 0.95};         // 暴雨后预防
            case "IMIDACLOPRID" -> new double[]{0.30, 0.95};
            case "CHLORANTRANILIPROLE" -> new double[]{0.20, 0.95};
            case "BT" -> new double[]{0.30, 0.95};
            case "CYPROCONAZOLE" -> new double[]{0.60, 0.75};   // 见花打药
            case "TRIADIMEFON" -> new double[]{0.20, 0.70};
            case "EMAMECTIN" -> new double[]{0.10, 0.60};       // 低龄幼虫期
            case "RHIZOBIUM" -> new double[]{0.00, 0.15};       // 播期接种
            case "UREA" -> new double[]{0.16, 0.62};           // 分蘖—孕穗追肥
            case "KCL" -> new double[]{0.30, 0.70};
            case "COMPOUND" -> new double[]{0.00, 0.12};       // 基肥
            case "BORAX" -> new double[]{0.00, 0.85};
            // 除草剂：窗口比其他农药窄得多——只在苗后到封行前这一段。
            // 早于苗后 = 伤苗（药剂接触芽鞘），晚于封行 = 杂草已抢完水肥，药钱白花。
            case "QUIZALOFOP" -> new double[]{0.08, 0.30};
            case "BENSULFURON" -> new double[]{0.10, 0.36};
            default -> new double[]{0.00, 1.00};
        };
    }

    public static boolean inWindow(String id, double dvs) {
        double[] w = windowOf(id);
        return dvs >= w[0] && dvs <= w[1];
    }

    /** 折纯养分：化肥 = 用量 × 含量；农药为 0。 */
    public static double nutrient(Item it, double dose) {
        return it == null ? 0 : dose * it.nContent();
    }

    /**
     * 氮素报酬递减（Mitscherlich 形状）：施到需求量时约 82% 的地力提升，
     * 再往上边际收益迅速消失。返回 0–1 的"满足度"。
     */
    public static double nSatisfaction(double totalN) {
        return nSatisfaction(totalN, RICE_N_NEED);
    }

    /** 指定需氮量下的满足度（Mitscherlich）。 */
    public static double nSatisfaction(double totalN, double need) {
        if (totalN <= 0 || need <= 0) {
            return 0;
        }
        return 1 - Math.exp(-1.8 * totalN / need);
    }
}
