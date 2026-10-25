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
        HERB,
        /**
         * 种子（2026-10-23 需求 R · 商店）。
         *
         * <p>单独一类而不是塞进 FERT：种子不是「撒下去就消失」的消耗品，
         * 它是<b>播种的前置条件</b>——没有对应作物的种子就不能开田，
         * 而且它的计量单位是「亩/千粒重」而不是 kg/g/ml。
         */
        SEED,
        /**
         * 农具（2026-10-23 需求 R · 商店）。
         *
         * <p>农具是<b>耐用品</b>：买一次长期持有，不按次消耗。
         * 它的作用不是「提高某一项数值」，而是降低作业成本 / 放宽作业窗口
         * （比如旋耕机让翻耕工序的农时窗口变宽）。
         */
        TOOL,
        /**
         * 水资源（2026-10-23 需求 R · 商店）。
         *
         * <p>水是<b>按次消耗</b>的：每灌一次地扣一次额度，
         * 没额度就灌不了水 → 干旱损失无法规避。
         * 这是「农业生产效率」里最容易被忽略但影响最大的一环。
         */
        WATER
    }

    /**
     * @param nContent    化肥的折纯养分含量（N / P₂O₅ / K₂O），农药为 0
     * @param targets     农药可防治的病虫害 id（与 {@code PestEngine} 的 id 对齐）；
     *                   SEED 复用为「对应作物 id」，TOOL 复用为「作用工序 id」，WATER 复用为「适用气候区」
     * @param efficacy    对症时的基础防效（生物农药 0.8）；SEED = 单价基准，TOOL = 效率加成系数，WATER = 单次灌溉补水系数
     * @param phiDays     安全间隔期（天）：距采收不足此天数施用 → 农残超标
     * @param protectDays 持效期（天）；SEED = 每包可播亩数，TOOL = 可用作业次数，WATER = 每份可灌次数
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

        /**
         * 是否是<b>耐用品</b>（农具）：买一次长期持有，不按次消耗。
         *
         * <p>耐用品与消耗品在库存语义上完全不同：
         * 消耗品「数量」会随使用减少（种子播掉、水灌掉、药打掉），
         * 耐用品的「数量」是<b>拥有/未拥有</b>（0 或 1），重复购买是浪费。
         */
        public boolean durable() {
            return kind == Kind.TOOL;
        }

        /** 种子对应的作物 id（非种子返回 null）。 */
        public String cropId() {
            return kind == Kind.SEED && targets != null && !targets.isEmpty()
                    ? targets.get(0) : null;
        }
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
                List.of("bacterial_blight", "soft_rot", "potato_ringrot"), "铜制剂", 1.0, 14, 7,
                "细菌性病害；台风暴雨后预防性用药，发病后再打收效有限"));
        ITEMS.put("IMIDACLOPRID", new Item(
                "IMIDACLOPRID", "吡虫啉（10% WP）", Kind.PEST, "PEST_INSECTICIDE", "g",
                10, 20, 35, 0.30, 0, "",
                List.of("planthopper", "wheat_aphid", "soybean_aphid",
                        "melon_aphid", "flower_aphid", "potato_aphid"), "新烟碱类", 1.0, 14, 10,
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
                List.of("wheat_scab", "leafy_downy", "cuc_downy", "flower_gray",
                        "millet_blast", "pear_scab"), "氰基丙烯酸酯类", 1.0, 21, 14,
                "小麦赤霉病：扬花初期「见花打药」是唯一有效窗口，错过基本无解"));
        ITEMS.put("TRIADIMEFON", new Item(
                "TRIADIMEFON", "三唑酮（20% EC）", Kind.PEST, "PEST_FUNGICIDE", "ml",
                30, 60, 100, 0.26, 0, "",
                List.of("stripe_rust", "powdery_mildew", "cuc_powdery", "flower_powdery",
                        "millet_powdery", "pear_rust"), "三唑类", 1.0, 21, 12,
                "条锈病/白粉病：发病初期用药，拔节期是最后窗口"));
        ITEMS.put("EMAMECTIN", new Item(
                "EMAMECTIN", "甲维盐（5% WG）", Kind.PEST, "PEST_INSECTICIDE", "g",
                8, 15, 25, 0.90, 0, "",
                List.of("fall_armyworm", "corn_borer", "millet_moth", "pear_scale"),
                "生物衍生类", 1.0, 14, 10,
                "草地贪夜蛾/玉米螟：低龄幼虫期（3 龄前）才打得住"));
        ITEMS.put("RHIZOBIUM", new Item(
                "RHIZOBIUM", "根瘤菌剂", Kind.FERT, "FERT_BIO", "g",
                100, 250, 400, 0.06, 0, "BIO-N",
                List.of(), "生物固氮", 1.0, 0, 5,
                "大豆专用：接种后固氮 +15~25%。对水稻/玉米无效，别买错"));
        ITEMS.put("MANCOZEB", new Item(
                "MANCOZEB", "代森锰锌（80% WP）", Kind.PEST, "PEST_FUNGICIDE", "g",
                100, 180, 260, 0.22, 0, "",
                List.of("early_blight", "gray_mold", "leafy_downy", "cuc_downy", "flower_gray",
                        "potato_late_blight", "potato_early_blight"),
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

        // ---------------- 种子（2026-10-23 需求 R · 商店）----------------
        // ⚠️ 种子是**播种的前置条件**：没有对应作物的种子就开不了田。
        // 价格取「每公斤种子可播多少亩」反推（元/包，每包 1kg，protectDays = 可播亩数）。
        // 大田作物种子便宜（几元/kg），蔬菜种子贵（几十元/kg，因为是精制粒）。
        ITEMS.put("SEED_rice_mid", new Item(
                "SEED_rice_mid", "中稻种子（1kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 28.0, 0, "",
                List.of("rice_mid"), "常规种", 1.0, 0, 5,
                "可播约 5 亩；育秧后移栽，注意秧田与本田的用种量差别"));
        ITEMS.put("SEED_wheat_winter", new Item(
                "SEED_wheat_winter", "冬小麦种子（1kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 18.0, 0, "",
                List.of("wheat_winter"), "常规种", 1.0, 0, 6,
                "可播约 6 亩；半冬性品种需春化，南方低纬种它只长草"));
        ITEMS.put("SEED_corn_spring", new Item(
                "SEED_corn_spring", "春玉米种子（1kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 35.0, 0, "",
                List.of("corn_spring"), "杂交种", 1.0, 0, 4,
                "可播约 4 亩；杂交种第二代会性状分离，别留种"));
        ITEMS.put("SEED_soybean", new Item(
                "SEED_soybean", "大豆种子（1kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 22.0, 0, "",
                List.of("soybean"), "常规种", 1.0, 0, 5,
                "可播约 5 亩；建议同时接种根瘤菌，否则固氮能力不足"));
        ITEMS.put("SEED_cabbage", new Item(
                "SEED_cabbage", "大白菜种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 26.0, 0, "",
                List.of("cabbage"), "常规种", 1.0, 0, 3,
                "可播约 3 亩；叶菜不耐存，种子也要买得勤"));
        ITEMS.put("SEED_tomato", new Item(
                "SEED_tomato", "番茄种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 42.0, 0, "",
                List.of("tomato"), "蔬菜种", 1.0, 0, 3,
                "可播约 3 亩（精制粒）；连作易萎蔫，注意轮作"));
        ITEMS.put("SEED_cucumber", new Item(
                "SEED_cucumber", "黄瓜种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 48.0, 0, "",
                List.of("cucumber"), "蔬菜种", 1.0, 0, 2,
                "可播约 2 亩；黄瓜种性易退化，建议每两年换一次种"));
        ITEMS.put("SEED_chili", new Item(
                "SEED_chili", "辣椒种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 52.0, 0, "",
                List.of("chili"), "蔬菜种", 1.0, 0, 2,
                "可播约 2 亩；喜温，北方过早播种会僵苗"));
        ITEMS.put("SEED_strawberry", new Item(
                "SEED_strawberry", "草莓种子（50g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 96.0, 0, "",
                List.of("strawberry"), "精制种", 1.0, 0, 1,
                "可播约 1 亩；草莓生产多用匍匐茎苗，种子育的苗商品性偏低"));
        ITEMS.put("SEED_watermelon", new Item(
                "SEED_watermelon", "西瓜种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 60.0, 0, "",
                List.of("watermelon"), "瓜菜种", 1.0, 0, 2,
                "可播约 2 亩；坐果期高温会化瓜，育苗别抢农时"));
        ITEMS.put("SEED_melon", new Item(
                "SEED_melon", "甜瓜种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 58.0, 0, "",
                List.of("melon"), "瓜菜种", 1.0, 0, 2,
                "可播约 2 亩；厚皮甜瓜靠昼夜温差积累糖分"));
        ITEMS.put("SEED_chrysanthemum", new Item(
                "SEED_chrysanthemum", "菊花种子（20g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 110.0, 0, "",
                List.of("chrysanthemum"), "花卉种", 1.0, 0, 1,
                "可播约 1 亩；短日植物，花期靠日照长度触发"));
        ITEMS.put("SEED_sunflower", new Item(
                "SEED_sunflower", "向日葵种子（1kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 40.0, 0, "",
                List.of("sunflower"), "油料种", 1.0, 0, 5,
                "可播约 5 亩；耐热耐旱，但不固氮，地力差时要补氮"));
        ITEMS.put("SEED_tulip", new Item(
                "SEED_tulip", "郁金香种子（10g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 140.0, 0, "",
                List.of("tulip"), "球根种", 1.0, 0, 1,
                "可播约 1 亩；生产用球根繁殖，种子苗要多年才能开花"));
        // ---------------- 种子·新增 8 种（2026-10-24）----------------
        // ⚠️ targets 里的作物 id 必须与 CropCatalog 的 id 完全一致，
        //    否则商店里买了种子也开不了田（InputCatalog.seedOf 按这个反查）。
        ITEMS.put("SEED_millet", new Item(
                "SEED_millet", "谷子种子（1kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 24.0, 0, "",
                List.of("millet"), "常规种", 1.0, 0, 5,
                "可播约 5 亩；谷子粒小，千粒重约 8g，一包比水稻种便宜得多"));
        ITEMS.put("SEED_potato", new Item(
                "SEED_potato", "马铃薯种薯（5kg/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 45.0, 0, "",
                List.of("potato"), "种薯", 1.0, 0, 3,
                "可播约 3 亩；要买脱毒种薯，带病种薯等于把晚疫病一起买回来"));
        ITEMS.put("SEED_eggplant", new Item(
                "SEED_eggplant", "茄子种子（100g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 48.0, 0, "",
                List.of("eggplant"), "蔬菜种", 1.0, 0, 3,
                "可播约 3 亩；茄子喜温，北方过早播种会僵苗"));
        ITEMS.put("SEED_yardlong_bean", new Item(
                "SEED_yardlong_bean", "豆角种子（200g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 38.0, 0, "",
                List.of("yardlong_bean"), "蔬菜种", 1.0, 0, 3,
                "可播约 3 亩；豆角不耐连作，与黄瓜同棚要错开年份"));
        ITEMS.put("SEED_radish", new Item(
                "SEED_radish", "萝卜种子（150g/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 18.0, 0, "",
                List.of("radish"), "常规种", 1.0, 0, 4,
                "可播约 4 亩；根菜最怕积水，务必选排水好的地"));
        ITEMS.put("SEED_grape", new Item(
                "SEED_grape", "葡萄苗木（5株/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 220.0, 0, "",
                List.of("grape"), "果树苗", 1.0, 0, 2,
                "可播约 2 亩；葡萄苗定植后要几年才丰产，本作按一年生简化建模"));
        ITEMS.put("SEED_pear", new Item(
                "SEED_pear", "梨苗木（5株/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 260.0, 0, "",
                List.of("pear"), "果树苗", 1.0, 0, 2,
                "可播约 2 亩；梨树苗定植要 3–5 年才结果，本作按一年生简化建模"));
        ITEMS.put("SEED_rose", new Item(
                "SEED_rose", "月季苗木（20株/包）", Kind.SEED, "SEED_CROP", "包",
                1, 1, 1, 320.0, 0, "",
                List.of("rose"), "花卉苗", 1.0, 0, 1,
                "可播约 1 亩；月季是长日照植物，花期与菊花正好相反"));

        // ---------------- 农具（2026-10-23 需求 R · 商店）----------------
        // ⚠️ 农具是**耐用品**：买一次长期持有（quantity 0/1），重复购买是浪费。
        // 它的作用不是提高某个数值，而是**降低作业成本 / 放宽作业窗口 / 提高作业效率**。
        // efficacy 字段复用为「效率加成系数」，protectDays 复用为「可作业次数（无限次记 -1）」。
        ITEMS.put("TOOL_TILLER", new Item(
                "TOOL_TILLER", "旋耕机", Kind.TOOL, "TOOL_MACHINE", "台",
                1, 1, 1, 12000.0, 0, "",
                List.of("TILL"), "动力机械", 0.12, 0, -1,
                "整地效率 +12%，并放宽翻耕农时窗口（±10 天）；农机是大额长期投资"));
        ITEMS.put("TOOL_SPRAYER", new Item(
                "TOOL_SPRAYER", "背负式喷雾器", Kind.TOOL, "TOOL_MACHINE", "台",
                1, 1, 1, 680.0, 0, "",
                List.of("SPRAY"), "植保机械", 0.08, 0, -1,
                "施药作业效率 +8%，雾化更均匀，药液附着率更高"));
        ITEMS.put("TOOL_IRRIGATION", new Item(
                "TOOL_IRRIGATION", "滴灌系统", Kind.TOOL, "TOOL_MACHINE", "套",
                1, 1, 1, 5600.0, 0, "",
                List.of("IRRIGATE"), "水肥设备", 0.18, 0, -1,
                "灌溉节水 +18%，干旱损失减半；一次性投入，但长期最划算"));
        ITEMS.put("TOOL_HARVESTER", new Item(
                "TOOL_HARVESTER", "小型收割机", Kind.TOOL, "TOOL_MACHINE", "台",
                1, 1, 1, 38000.0, 0, "",
                List.of("HARVEST"), "收获机械", 0.10, 0, -1,
                "收割效率 +10%，减少收获期损失（错过窗口的损失也变小）"));
        ITEMS.put("TOOL_DRONE", new Item(
                "TOOL_DRONE", "植保无人机", Kind.TOOL, "TOOL_MACHINE", "台",
                1, 1, 1, 46000.0, 0, "",
                List.of("SPRAY"), "智慧农业", 0.22, 0, -1,
                "施药效率 +22%（作业快、覆盖匀），但对蜜蜂等传粉昆虫有风险，花期慎用"));
        ITEMS.put("TOOL_SEEDER", new Item(
                "TOOL_SEEDER", "精量播种机", Kind.TOOL, "TOOL_MACHINE", "台",
                1, 1, 1, 9800.0, 0, "",
                List.of("SOW"), "播种机械", 0.10, 0, -1,
                "播种质量 +10%，出苗率提升、群体密度更均匀（省种）"));
        ITEMS.put("TOOL_GRAIN_DRYER", new Item(
                "TOOL_GRAIN_DRYER", "粮食烘干机", Kind.TOOL, "TOOL_MACHINE", "台",
                1, 1, 1, 24000.0, 0, "",
                List.of("HARVEST"), "产后处理", 0.12, 0, -1,
                "入库品质 +12%：烘干降低含水率，仓储期更难变质"));
        ITEMS.put("TOOL_GREENHOUSE", new Item(
                "TOOL_GREENHOUSE", "连栋温室", Kind.TOOL, "TOOL_MACHINE", "栋",
                1, 1, 1, 68000.0, 0, "",
                List.of("PROTECT"), "设施农业", 0.15, 0, -1,
                "设施产量 +15%，并削弱极端天气（暴雨/高温/霜冻）造成的损失"));

        // ---------------- 水资源（2026-10-23 需求 R · 商店）----------------
        // ⚠️ 水是**按次消耗**：每灌一次扣一次额度。
        //
        // 🔴 **库存单位统一成「次」**，不是「份」——这是本轮踩过的坑：
        // 如果库存存「份」，那消费时就得做「份 → 剩余次数」的换算，
        // 而这需要额外存一个「已用次数」字段（否则 20 次的份灌了 19 次后
        // 库存还是 1 份，下次扣减会算错）。直接存「次」就没有这个问题：
        // 买 1 份 = 20 次，库存加 20；灌一次扣 1。
        // protectDays 复用为「每份可灌次数」，购买时用它换算入库量。
        //
        // 三种水对应不同来源与成本：自来水便宜、山泉水中等、灌溉水权最贵但最可靠。
        ITEMS.put("WATER_TAP", new Item(
                "WATER_TAP", "自来水灌溉额度", Kind.WATER, "WATER_SOURCE", "次",
                1, 1, 1, 6.0, 0, "",
                List.of("jiangnan", "huanan", "huanghuaihai", "dongbei"), "市政供水", 1.0, 0, 20,
                "1 份含 20 次灌溉；成本最低，但枯水期限水（缺水时效率打折）"));
        ITEMS.put("WATER_SPRING", new Item(
                "WATER_SPRING", "山泉灌溉额度", Kind.WATER, "WATER_SOURCE", "次",
                1, 1, 1, 10.0, 0, "",
                List.of("jiangnan", "huanan", "huanghuaihai", "dongbei"), "山泉水", 1.25, 0, 24,
                "1 份含 24 次灌溉；水质好、温度稳定，对喜温作物（如蔬菜）更友好"));
        ITEMS.put("WATER_CANAL", new Item(
                "WATER_CANAL", "灌区水权", Kind.WATER, "WATER_SOURCE", "次",
                1, 1, 1, 14.0, 0, "",
                List.of("huanghuaihai", "jiangnan", "dongbei"), "灌区配水", 1.40, 0, 30,
                "1 份含 30 次灌溉；最贵但最可靠，干旱年也不断供（大田作物首选）"));
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

    /** 种子货架（2026-10-23 需求 R · 商店）。 */
    public static List<Item> seeds() {
        return ITEMS.values().stream().filter(i -> i.kind() == Kind.SEED).toList();
    }

    /** 农具货架（2026-10-23 需求 R · 商店）。耐用品，买一次长期持有。 */
    public static List<Item> tools() {
        return ITEMS.values().stream().filter(i -> i.kind() == Kind.TOOL).toList();
    }

    /** 水资源货架（2026-10-23 需求 R · 商店）。 */
    public static List<Item> waters() {
        return ITEMS.values().stream().filter(i -> i.kind() == Kind.WATER).toList();
    }

    /** 取某作物对应的种子（没有返回 null）。 */
    public static Item seedOf(String cropId) {
        if (cropId == null || cropId.isBlank()) {
            return null;
        }
        for (Item i : ITEMS.values()) {
            if (i.kind() == Kind.SEED && cropId.equals(i.cropId())) {
                return i;
            }
        }
        return null;
    }

    /** 某用户在用的农具集合（已拥有的 tool id → 定义）。 */
    public static List<Item> toolsOwned(java.util.Set<String> ownedIds) {
        if (ownedIds == null || ownedIds.isEmpty()) {
            return List.of();
        }
        return ITEMS.values().stream()
                .filter(i -> i.kind() == Kind.TOOL && ownedIds.contains(i.id()))
                .toList();
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
