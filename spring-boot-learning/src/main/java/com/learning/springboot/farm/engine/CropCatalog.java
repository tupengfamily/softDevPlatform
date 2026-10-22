package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作物库（S1 精简版）：水稻 / 冬小麦 / 玉米 / 大豆。
 *
 * <p><b>两层模型</b>：作物层（这里）决定物候、需肥、产量构成与形态；
 * 品种层（v1.1）才细分熟期与抗性。当前每作物只有 1 个代表品种，够跑通"选作物 → 选播期 → 一季"。
 *
 * <p>播期不是一个固定日期，而是<b>每个区一个代表播期</b>：同样是玉米，江南是春播（4 月初）、
 * 黄淮海是夏播（6 月中）、东北是 5 月初。播种窗口之外仍然可以种——S23 只给概率，不给许可。
 */
public final class CropCatalog {

    private CropCatalog() {
    }

    /**
     * @param category 粮食·禾谷 / 粮食·豆科 …（UI 分类）
     * @param sowByZone 各农业区的代表播期（MM-DD）
     */
    public record Crop(
            String id,
            String name,
            String shortName,
            String category,
            String desc,
            CropConfig config,
            Map<String, MonthDay> sowByZone) {
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
                        "huanghuaihai", MonthDay.of(5, 20), "dongbei", MonthDay.of(5, 5))));

        put(new Crop("wheat_winter", "冬小麦（半冬性）", "冬小麦", "粮食·禾谷",
                "唯一需要越冬的作物：秋播、越冬、返青、抽穗。必须经过 0–7℃ 春化才会抽穗——华南种它只长草。",
                WheatConfig.winterWheat(),
                Map.of("jiangnan", MonthDay.of(10, 25), "huanan", MonthDay.of(11, 5),
                        "huanghuaihai", MonthDay.of(10, 8), "dongbei", MonthDay.of(9, 20))));

        put(new Crop("corn_spring", "玉米（春/夏播）", "玉米", "粮食·禾谷",
                "需氮最高的作物，大喇叭口期是需肥临界期；单株大穗，穗数只有几千/亩。",
                CornConfig.springCorn(),
                Map.of("jiangnan", MonthDay.of(4, 5), "huanan", MonthDay.of(3, 5),
                        "huanghuaihai", MonthDay.of(6, 12), "dongbei", MonthDay.of(5, 1))));

        put(new Crop("soybean", "大豆（夏播）", "大豆", "粮食·豆科",
                "60–70% 的氮靠根瘤固氮。多施尿素会抑制根瘤（氮阻遏），花钱反而减产——本作最反直觉的一课。",
                SoybeanConfig.summerSoybean(),
                Map.of("jiangnan", MonthDay.of(6, 15), "huanan", MonthDay.of(3, 20),
                        "huanghuaihai", MonthDay.of(6, 20), "dongbei", MonthDay.of(5, 10))));
        // ---------------- 蔬菜 ----------------
        put(new Crop("cabbage", "大白菜（秋播）", "大白菜", "蔬菜·叶菜",
                "生育期最短、需氮最高的一类：莲座期决定叶面积，结球期决定产量。霜冻前必须收完。",
                HortConfigs.cabbage(),
                Map.of("jiangnan", MonthDay.of(8, 20), "huanan", MonthDay.of(9, 10),
                        "huanghuaihai", MonthDay.of(8, 10), "dongbei", MonthDay.of(7, 20))));

        put(new Crop("tomato", "番茄（春/秋茬）", "番茄", "蔬菜·果菜",
                "连续坐果作物：商品率是命门，畸形果、裂果、日灼都直接扣钱。露地栽培怕高温高湿。",
                HortConfigs.tomato(),
                Map.of("jiangnan", MonthDay.of(3, 15), "huanan", MonthDay.of(2, 20),
                        "huanghuaihai", MonthDay.of(4, 5), "dongbei", MonthDay.of(4, 20))));

        put(new Crop("cucumber", "黄瓜（春茬）", "黄瓜", "蔬菜·果菜",
                "需水需氮都高，结瓜期一天一个样。霜霉病在叶面结露时扩散最快。",
                HortConfigs.cucumber(),
                Map.of("jiangnan", MonthDay.of(3, 20), "huanan", MonthDay.of(3, 1),
                        "huanghuaihai", MonthDay.of(4, 10), "dongbei", MonthDay.of(4, 25))));

        put(new Crop("chili", "辣椒（春茬鲜椒）", "辣椒", "蔬菜·果菜",
                "单株果多果小，脐腐病由缺钙诱发——不是病害，是水肥问题。日灼会直接毁掉商品性。",
                HortConfigs.chili(),
                Map.of("jiangnan", MonthDay.of(3, 25), "huanan", MonthDay.of(3, 5),
                        "huanghuaihai", MonthDay.of(4, 15), "dongbei", MonthDay.of(4, 28))));

        // ---------------- 水果 ----------------
        put(new Crop("strawberry", "草莓（秋植春收）", "草莓", "水果·浆果",
                "秋植越冬、春季采收。必须经历低温才能成花，北方越冬要防冻害；果期怕高温与灰霉。",
                HortConfigs.strawberry(),
                Map.of("jiangnan", MonthDay.of(9, 20), "huanan", MonthDay.of(10, 10),
                        "huanghuaihai", MonthDay.of(9, 5), "dongbei", MonthDay.of(8, 20))));

        put(new Crop("watermelon", "西瓜（春茬）", "西瓜", "水果·瓜类",
                "单株一两个大果，坐果期高温会大量化瓜；成熟期雨水多则裂瓜、甜度下降。",
                HortConfigs.watermelon(),
                Map.of("jiangnan", MonthDay.of(4, 1), "huanan", MonthDay.of(3, 10),
                        "huanghuaihai", MonthDay.of(4, 20), "dongbei", MonthDay.of(5, 5))));

        put(new Crop("melon", "甜瓜（春茬厚皮）", "甜瓜", "水果·瓜类",
                "比西瓜耐密植，单株多果。厚皮甜瓜对昼夜温差敏感，温差大才甜。",
                HortConfigs.melon(),
                Map.of("jiangnan", MonthDay.of(4, 5), "huanan", MonthDay.of(3, 15),
                        "huanghuaihai", MonthDay.of(4, 25), "dongbei", MonthDay.of(5, 8))));

        // ---------------- 花卉 ----------------
        put(new Crop("chrysanthemum", "菊花（切花）", "菊花", "花卉·切花",
                "短日植物，秋播型在短日季自然开花。现蕾期高温会导致花芽败育，切花品质一票否决。",
                HortConfigs.chrysanthemum(),
                Map.of("jiangnan", MonthDay.of(6, 20), "huanan", MonthDay.of(7, 10),
                        "huanghuaihai", MonthDay.of(6, 10), "dongbei", MonthDay.of(6, 1))));

        put(new Crop("sunflower", "向日葵（籽用）", "向日葵", "花卉·油料",
                "耐热耐旱，花期高温会显著降低结实率；与大豆相反，它不固氮，需氮不低。",
                HortConfigs.sunflower(),
                Map.of("jiangnan", MonthDay.of(4, 10), "huanan", MonthDay.of(3, 20),
                        "huanghuaihai", MonthDay.of(5, 1), "dongbei", MonthDay.of(5, 10))));

        put(new Crop("tulip", "郁金香（秋植球根）", "郁金香", "花卉·球根",
                "秋天种、春天开，必须经过足够低温才抽花——与冬小麦共用春化机制。球根耐寒，东北也能露地越冬。",
                HortConfigs.tulip(),
                Map.of("jiangnan", MonthDay.of(11, 10), "huanan", MonthDay.of(11, 25),
                        "huanghuaihai", MonthDay.of(10, 25), "dongbei", MonthDay.of(9, 25))));
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

    /** 该作物在该区的代表播期；若今年已过，顺延到明年。 */
    public static LocalDate defaultSow(String cropId, String zoneId, LocalDate today) {
        Crop c = get(cropId);
        MonthDay md = c.sowByZone().getOrDefault(zoneId, c.sowByZone().get("jiangnan"));
        LocalDate candidate = md.atYear(today.getYear());
        if (candidate.isBefore(today.minusDays(20))) {
            candidate = md.atYear(today.getYear() + 1);
        }
        return candidate;
    }

    /** 参照产量（S23 的 Y_ref）。 */
    public static double yieldRef(String cropId) {
        return get(cropId).config().yieldRef();
    }
}
