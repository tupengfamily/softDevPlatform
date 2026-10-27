package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.CropConfig;
import com.learning.springboot.farm.engine.DayWeather;
import com.learning.springboot.farm.engine.SimEngine;
import com.learning.springboot.farm.engine.SuitabilityEngine;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区县 → 农业气候区 → 主栽作物 / 当前物候。
 *
 * <p><b>数据口径必须说清</b>：中国县级播种面积统计（统计年鉴）没有可靠的公开机读源，
 * 所以这里的「主要农作物」不是实测统计，而是两层叠加：
 * <ol>
 *   <li><b>主栽作物先验</b>（{@link #MAIN_CROPS}）：按省级单位的农业地理常识给出候选与优先级，
 *       例如东北是玉米/大豆/水稻，黄淮海是冬小麦/夏玉米，华南是双季稻；</li>
 *   <li><b>本地气候可行性</b>：用 {@link SuitabilityEngine} 对该区县所属农业区做十年回放，
 *       给每个候选作物的适种等级。等级低说明「这地方能种但风险高」，会被排到后面。</li>
 * </ol>
 * UI 上必须标注是「模型推算」而非统计年鉴——这和 S23 适区评级是同一套口径。
 *
 * <p><b>性能</b>：等级只跟 (气候区, 作物, 年份) 有关，全国 2400 个区县实际只有
 * 4 区 × 4 作物 = 16 种组合，算一次缓存即可；一个省上百个区县的概览接口因此是毫秒级。
 */
@Service
public class DistrictCropService {

    /** 省级 adcode 前两位 → 主栽作物候选（顺序即优先级）。 */
    private static final Map<Integer, List<String>> MAIN_CROPS = new LinkedHashMap<>();

    static {
        List<String> northeast = List.of("corn_spring", "soybean", "rice_mid");
        List<String> huanghuai = List.of("wheat_winter", "corn_spring", "soybean");
        List<String> middle = List.of("rice_mid", "wheat_winter", "soybean");
        List<String> southwest = List.of("rice_mid", "corn_spring", "wheat_winter");
        List<String> south = List.of("rice_mid", "corn_spring", "soybean");

        for (int p : new int[]{21, 22, 23}) {
            MAIN_CROPS.put(p, northeast);
        }
        MAIN_CROPS.put(15, List.of("corn_spring", "wheat_winter", "soybean")); // 内蒙古
        for (int p : new int[]{11, 12, 14, 41, 61, 62, 63, 64}) {
            MAIN_CROPS.put(p, huanghuai);
        }

        // ---- 经济作物带：这些省份的主栽不是粮食，而是蔬菜/瓜果/花卉 ----
        // 依据是农业地理常识（设施蔬菜带、冬春瓜菜基地、鲜切花产区），
        // 不是"全省只有这一种作物"，而是"用地图着色表达这个县最典型的作物"。
        MAIN_CROPS.put(13, List.of("tomato", "cucumber", "corn_spring"));      // 河北：环京设施蔬菜
        MAIN_CROPS.put(37, List.of("tomato", "cucumber", "wheat_winter"));     // 山东：寿光设施蔬菜
        MAIN_CROPS.put(21, List.of("cucumber", "tomato", "corn_spring"));      // 辽宁：日光温室
        MAIN_CROPS.put(65, List.of("watermelon", "melon", "wheat_winter"));    // 新疆：瓜果
        MAIN_CROPS.put(46, List.of("watermelon", "melon", "rice_mid"));        // 海南：冬春瓜菜
        for (int p : new int[]{31, 32, 33, 34, 36, 42, 43, 50}) {
            MAIN_CROPS.put(p, middle);
        }
        MAIN_CROPS.put(51, southwest);
        MAIN_CROPS.put(52, southwest);
        // 云南：鲜切花主产区（斗南），主栽用花卉表达
        MAIN_CROPS.put(53, List.of("chrysanthemum", "tulip", "rice_mid"));
        // 注：海南（46）不在这里——它走下面的冬春瓜菜先验，放进这个循环会被覆盖
        for (int p : new int[]{35, 44, 45, 71, 81, 82}) {
            MAIN_CROPS.put(p, south);
        }
    }

    private final DistrictIndex districts;

    /** key = zone|crop|year */
    private final Map<String, SuitabilityEngine.Report> suitCache = new ConcurrentHashMap<>();

    public DistrictCropService(DistrictIndex districts) {
        this.districts = districts;
    }

    // ==================================================================
    //  区县 → 农业区
    // ==================================================================

    /**
     * 按省级归属 + 纬度做农业区划分段。
     *
     * <p>只有 4 个气候档案，所以这里做的是「归到最接近的一档」，不是精确气候分区。
     * 几个必须按纬度破例的地方：
     * <ul>
     *   <li>江苏/安徽北部（徐州、淮北，纬度 &gt; 32）属黄淮海，不是江南；</li>
     *   <li>河南信阳（纬度 &lt; 32.4）属江南稻区；</li>
     *   <li>陕南汉中/安康（纬度 &lt; 33.5）属江南；</li>
     *   <li>内蒙古东四盟（经度 &gt; 119）归东北；</li>
     *   <li>云南南部（纬度 &lt; 24）归华南。</li>
     * </ul>
     */
    public String zoneOf(DistrictIndex.District d) {
        int p = d.adcode() / 10000;
        double lat = d.lat();
        switch (p) {
            case 21, 22, 23:
                return "dongbei";
            case 15:
                return d.lng() > 119 ? "dongbei" : "huanghuaihai";
            case 41:
                return lat < 32.4 ? "jiangnan" : "huanghuaihai";
            case 61:
                return lat < 33.5 ? "jiangnan" : "huanghuaihai";
            case 32, 34:
                return lat < 32.0 ? "jiangnan" : "huanghuaihai";
            case 11, 12, 13, 14, 37, 62, 63, 64, 65:
                return "huanghuaihai";
            case 35, 44, 45, 46, 71, 81, 82:
                return "huanan";
            case 53:
                return lat < 24.0 ? "huanan" : "jiangnan";
            default:
                return "jiangnan";
        }
    }

    /**
     * 主栽作物候选（顺序即优先级）。
     *
     * <p>省级先验之外，两处必须按纬度破例，否则会出现明显违背常识的结论：
     * <ul>
     *   <li>东北北纬 45.5° 以北（黑河、大兴安岭、齐齐哈尔北部）大豆比重高于玉米；</li>
     *   <li>河南信阳（北纬 32.4° 以南）是稻区，不是麦区。</li>
     * </ul>
     */
    public List<String> mainCropIds(DistrictIndex.District d) {
        int p = d.adcode() / 10000;
        // 辽宁是东北唯一的日光温室蔬菜大省，不能被"东北=玉米/大豆"一刀切掉
        if (p == 21) {
            return List.of("cucumber", "tomato", "corn_spring");
        }
        if (p == 22 || p == 23) {
            return d.lat() >= 45.5
                    ? List.of("soybean", "corn_spring", "rice_mid")
                    : List.of("corn_spring", "soybean", "rice_mid");
        }
        if (p == 41 && d.lat() < 32.4) {
            return List.of("rice_mid", "wheat_winter", "soybean");
        }
        return MAIN_CROPS.getOrDefault(p, List.of("rice_mid", "wheat_winter", "soybean"));
    }

    // ==================================================================
    //  评级（缓存）
    // ==================================================================

    private SuitabilityEngine.Report report(String zoneId, String cropId, LocalDate today) {
        int year = today.getYear();
        String key = zoneId + "|" + cropId + "|" + year;
        return suitCache.computeIfAbsent(key, k -> {
            CropConfig crop = CropCatalog.get(cropId).config();
            LocalDate sow = CropCatalog.defaultSow(cropId, zoneId, today);
            return SuitabilityEngine.evaluate(zoneId, sow, crop);
        });
    }

    private static String levelNameOf(int level) {
        return switch (level) {
            case 0 -> "最适宜";
            case 1 -> "适宜";
            case 2 -> "基本适宜";
            case 3 -> "风险较高";
            case 4 -> "不推荐";
            default -> "不宜";
        };
    }

    /**
     * 区县概览用的作物条目：只要「种什么、多合适、什么时候种」，不算物候。
     */
    private Map<String, Object> cropBrief(String zoneId, String cropId, LocalDate today, boolean withStage) {
        CropCatalog.Crop c = CropCatalog.get(cropId);
        SuitabilityEngine.Report r = report(zoneId, cropId, today);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cropId", cropId);
        m.put("name", c.name());
        m.put("shortName", c.shortName());
        m.put("visual", c.config().visual());
        m.put("level", r.level());
        m.put("levelName", levelNameOf(r.level()));
        m.put("pPass", round(r.pPass(), 2));
        m.put("yieldMedian", Math.round(r.yieldMedian()));
        m.put("sowDate", r.sowDate());
        if (withStage) {
            Map<String, Object> st = currentStage(zoneId, cropId, today);
            m.putAll(st);
        }
        return m;
    }

    /**
     * 「当前这一季」的状态。
     *
     * <p>播期是按区县推出来的代表日期，所以这里要做一次「跨年判定」：
     * 若今年播期还没到，先看<b>去年</b>播下的那茬是否还在长（冬小麦越过冬天就是这个情况），
     * 否则才算「待播」。超过 430 天一律视为已收（引擎回放上限也是 430 天）。
     */
    private Map<String, Object> currentStage(String zoneId, String cropId, LocalDate today) {
        CropCatalog.Crop cc = CropCatalog.get(cropId);
        CropConfig crop = cc.config();
        LocalDate sow = CropCatalog.defaultSow(cropId, zoneId, today);
        if (sow.isAfter(today)) {
            LocalDate lastYear = sow.minusYears(1);
            if (ChronoUnit.DAYS.between(lastYear, today) <= 300) {
                sow = lastYear;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        long span = ChronoUnit.DAYS.between(sow, today);
        if (span < 0) {
            m.put("seasonState", "TO_SOW");
            m.put("stage", "待播种");
            m.put("dvs", 0.0);
            m.put("daysToSow", -span);
            m.put("sowDate", sow.toString());
            return m;
        }
        if (span > 430) {
            m.put("seasonState", "IDLE");
            m.put("stage", "已收获");
            m.put("dvs", 0.0);
            m.put("sowDate", sow.toString());
            return m;
        }
        List<DayWeather> days = new ArrayList<>();
        for (int i = 0; i <= span; i++) {
            days.add(ClimateModel.day(zoneId, sow.plusDays(i)));
        }
        SimEngine.SimResult r = SimEngine.simulate(days, 0, crop, null, 0.0, (int) span + 1);
        // 🔴 2026-10-27：成熟判定按作物取（OpChain.harvestDvsOf 是唯一真源）。
        //
        // 原来这里写死 0.92 —— 那是水稻口径。于是番茄（阈值 0.82）在区县概览里
        // 一直显示「生长中」，可它在地块页明明已经能收了；玉米（0.95）则相反，
        // 还没熟就显示「已成熟」。这是同一语义的第三份实现。
        //
        // 注意 `daysToHarvest` 也必须跟着换 —— 它是用 0.92 反推的，
        // 不同作物的剩余天数本来就不同。
        double harvestDvs = OpChain.harvestDvsOf(cropId);
        m.put("seasonState", r.dvs() >= harvestDvs ? "RIPE" : "GROWING");
        m.put("stage", SimEngine.phenologyAt(r.dvs(), crop));
        m.put("dvs", round(r.dvs(), 3));
        m.put("harvestDvs", harvestDvs);
        m.put("sowDate", sow.toString());
        m.put("daysToHarvest", Math.max(0, (int) Math.round((harvestDvs - r.dvs()) * 120)));
        return m;
    }

    // ==================================================================
    //  对外输出
    // ==================================================================

    /** 一个区县的地图着色与悬浮信息。 */
    public Map<String, Object> brief(DistrictIndex.District d, LocalDate today) {
        String zoneId = zoneOf(d);
        List<String> ids = mainCropIds(d);
        List<Map<String, Object>> crops = new ArrayList<>();
        for (String id : ids) {
            crops.add(cropBrief(zoneId, id, today, false));
        }
        // 排序策略：先验顺序优先，只有先验首位已经"风险较高（3）/不推荐（4）"时才让位。
        // 若一律按等级排，吉林、辽宁的玉米会输给大豆（模型给玉米 L1、大豆 L0），
        // 与实际主栽完全相反——先验承载的是"当地种什么"，等级只回答"种得好不好"。
        if (!crops.isEmpty() && (int) crops.get(0).get("level") >= 3) {
            int best = 0;
            for (int i = 1; i < crops.size(); i++) {
                if ((int) crops.get(i).get("level") < (int) crops.get(best).get("level")) {
                    best = i;
                }
            }
            if (best > 0) {
                crops.add(0, crops.remove(best));
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adcode", d.adcode());
        m.put("name", d.name());
        m.put("province", d.province());
        m.put("city", d.city());
        m.put("lng", d.lng());
        m.put("lat", d.lat());
        m.put("zoneId", zoneId);
        m.put("zoneName", ClimateModel.zone(zoneId).name());
        m.put("crops", crops);
        Map<String, Object> first = crops.isEmpty() ? Map.of() : crops.get(0);
        m.put("mainCrop", first.get("shortName"));
        m.put("mainCropId", first.get("cropId"));
        m.put("visual", first.get("visual"));
        m.put("level", first.get("level"));
        m.put("levelName", first.get("levelName"));
        return m;
    }

    /** 整省概览（地图下钻用）。 */
    public List<Map<String, Object>> provinceOverview(int provinceAdcode, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DistrictIndex.District d : districts.ofProvince(provinceAdcode)) {
            out.add(brief(d, today));
        }
        return out;
    }

    /** 单区县详情：四个作物的评级 + 主导作物的当前物候。 */
    public Map<String, Object> detail(int adcode, LocalDate today) {
        DistrictIndex.District d = districts.get(adcode).orElse(null);
        if (d == null) {
            return null;
        }
        String zoneId = zoneOf(d);
        ClimateModel.Zone z = ClimateModel.zone(zoneId);

        List<Map<String, Object>> all = new ArrayList<>();
        for (CropCatalog.Crop c : CropCatalog.list()) {
            all.add(cropBrief(zoneId, c.id(), today, true));
        }
        List<Map<String, Object>> main = new ArrayList<>();
        for (String id : mainCropIds(d)) {
            main.add(cropBrief(zoneId, id, today, true));
        }


        Map<String, Object> m = brief(d, today);
        m.put("fullName", d.fullName());
        m.put("crops", all);
        m.put("mainCrops", main);
        m.put("climate", Map.of(
                "zoneId", zoneId,
                "zoneName", z.name(),
                "site", z.site(),
                "region", z.region(),
                "lat", z.lat(),
                "notice", "气候档案为模型推算，非气象业务数据"));
        return m;
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
