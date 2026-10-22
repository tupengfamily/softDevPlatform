package com.learning.springboot.farm;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作物分布：把**省级**统计年鉴数据派发到区县，供地图着色与筛选。
 *
 * <p><b>为什么要做「派发」</b>：国家统计局《中国统计年鉴》只发布到**省级**，
 * 县级播种面积/产量没有可靠的公开机读源。如果不给区县赋值，
 * 地图上就只有省界着色，粒度太粗；但如果把省值均分给各县，
 * 「新疆棉花 2447.9 千公顷」会平摊到乌鲁木齐的市中心，明显荒谬。
 *
 * <p><b>派发算法</b>：以**区县面积占全省面积的比例**为权重，
 * 再按该作物是否属于该省主栽作物做修正：
 * <ol>
 *   <li>权重 = 该区县面积 / 该省区县总面积；</li>
 *   <li>若该作物在该省有值且 &gt; 0，则按权重分配到下辖区县；</li>
 *   <li>分配后按面积做<b>总量守恒</b>回填（避免浮点累积误差）；</li>
 *   <li>该省没有此作物数据时，下辖区县该作物值为 0（表示不种/无统计）。</li>
 * </ol>
 *
 * <p><b>口径声明（UI 必须展示）</b>：区县值是<b>按省值与区县面积推算的分布示意</b>，
 * 不是统计年鉴的区县实测值；只有省级数值是权威统计。
 */
@Service
public class CropDistributionService {

    private final CropDistributionIndex index;
    private final DistrictIndex districts;
    private final Map<Integer, Double> areaByAdcode;

    public CropDistributionService(CropDistributionIndex index, DistrictIndex districts) {
        this.index = index;
        this.districts = districts;
        this.areaByAdcode = loadAreas();
    }

    /** 区县面积（km²）。资源缺失时全部为 0，派发退化为均分。 */
    private Map<Integer, Double> loadAreas() {
        Map<Integer, Double> m = new LinkedHashMap<>();
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(
                "farm/district-areas.json").getInputStream()) {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            root.path("districts").fields().forEachRemaining(e -> {
                m.put(Integer.parseInt(e.getKey()), e.getValue().path("areaKm2").asDouble());
            });
        } catch (Exception ex) {
            // 面积缺失不致命：退化为均分，并在 notice 里说明
            System.err.println("[crop-dist] 区县面积载入失败，退化为均分: " + ex.getMessage());
        }
        return m;
    }

    // ==================================================================
    //  对外输出
    // ==================================================================

    /**
     * 某省（或某市）下辖区县的作物分布。
     *
     * @param adcode 省级（370000）或地级市（370100）编码
     * @param cropIds 需要的作物；为空表示全部
     */
    public Map<String, Object> distribution(int adcode, List<String> cropIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!index.isLoaded()) {
            out.put("districts", Collections.emptyList());
            out.put("notice", "作物分布数据未载入");
            return out;
        }

        List<DistrictIndex.District> list = isCityAdcode(adcode)
                ? districts.ofCity(adcode)
                : districts.ofProvince(adcode);
        if (list.isEmpty()) {
            out.put("districts", Collections.emptyList());
            out.put("notice", "未找到该行政单位下的区县");
            return out;
        }

        // 省级来源：区县列表可能跨省？——不会，ofCity/ofProvince 都在一省内
        // 省名要归一化：districts.json 写「山东省」，统计年鉴整理用「山东」
        String provName = CropDistributionIndex.normalizeProvince(list.get(0).province());
        Map<String, CropDistributionIndex.CropStat> provStats = index.ofProvince(provName);
        if (provStats.isEmpty()) {
            out.put("districts", Collections.emptyList());
            out.put("notice", "该省无统计数据：" + provName);
            return out;
        }

        // 目标作物
        List<String> targets = new ArrayList<>();
        if (cropIds == null || cropIds.isEmpty()) {
            targets.addAll(index.cropMetas().stream().map(CropDistributionIndex.CropMeta::cropId).toList());
        } else {
            for (String c : cropIds) {
                if (provStats.containsKey(c) && !targets.contains(c)) {
                    targets.add(c);
                }
            }
        }

        // 面积权重
        double totalArea = 0;
        Map<Integer, Double> w = new LinkedHashMap<>();
        for (DistrictIndex.District d : list) {
            double a = areaByAdcode.getOrDefault(d.adcode(), 0.0);
            w.put(d.adcode(), a);
            totalArea += a;
        }
        boolean useArea = totalArea > 0;

        // ------------------------------------------------------------------
        //  市级查询的口径修正
        // ------------------------------------------------------------------
        // 年鉴只到省级，市级查询拿到的仍��是**省值**。如果把省值原样派发给
        // 该市的几个区县，会出现「济南市的章丘区粮食面积 400 千公顷」这种
        // 比全省还离谱的数——数值被放大了 13 倍。
        //
        // 正确做法：按「该市面积 / 全省面积」把省值先缩到市级量级，再在市内
        // 按区县面积派发。这样济南市的合计 ≈ 山东全省 × (济南面积占比)。
        double scopeFactor = 1.0;
        if (isCityAdcode(adcode)) {
            double cityArea = 0;
            for (DistrictIndex.District d : list) {
                cityArea += areaByAdcode.getOrDefault(d.adcode(), 0.0);
            }
            // 该省全部区县的面积合计（用于算市占省比）
            double provArea = 0;
            for (DistrictIndex.District d : districts.ofProvince(provinceAdcodeOf(list))) {
                provArea += areaByAdcode.getOrDefault(d.adcode(), 0.0);
            }
            if (provArea > 0 && cityArea > 0) {
                scopeFactor = Math.min(1.0, cityArea / provArea);
            }
        }

        // 逐作物分配
        Map<Integer, Map<String, Object>> perDistrict = new LinkedHashMap<>();
        for (DistrictIndex.District d : list) {
            perDistrict.put(d.adcode(), new LinkedHashMap<>());
        }
        for (String cid : targets) {
            CropDistributionIndex.CropStat st = provStats.get(cid);
            if (st == null) {
                continue;
            }
            double areaTotal = st.area() == null ? 0 : st.area();
            double outTotal = st.output() == null ? 0 : st.output();
            // yieldPerHa 是 Double（可空），不能直接拆箱——缺失的作物会 NPE
            Double yieldBox = st.yieldPerHa();
            double yieldHa = yieldBox == null ? 0 : yieldBox;
            boolean hasYield = yieldHa > 0;
            // 该省在此作物上的「强度」：有面积但没产量说明产量数据缺失
            boolean hasArea = areaTotal > 0;
            boolean hasOutput = outTotal > 0;

            // 先按面积权重分配，记录已分配量，最后回填守恒
            double allocArea = 0;
            double allocOut = 0;
            for (DistrictIndex.District d : list) {
                double share = useArea ? (w.get(d.adcode()) / totalArea) : (1.0 / list.size());
                Map<String, Object> rec = perDistrict.get(d.adcode());
                if (hasArea) {
                    rec.put(cid + ".area", round(areaTotal * scopeFactor * share, 2));
                    allocArea += areaTotal * scopeFactor * share;
                }
                if (hasOutput) {
                    rec.put(cid + ".output", round(outTotal * scopeFactor * share, 2));
                    allocOut += outTotal * scopeFactor * share;
                }
                if (hasYield) {
                    rec.put(cid + ".yield", round(yieldHa, 1));
                }
            }
            // 总量守恒：把舍入误差补到面积最大的区县
            if (hasArea) {
                fixRounding(perDistrict, list, w, useArea, cid, "area", areaTotal * scopeFactor);
            }
            if (hasOutput) {
                fixRounding(perDistrict, list, w, useArea, cid, "output", outTotal * scopeFactor);
            }
        }

        // 组装响应
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DistrictIndex.District d : list) {
            Map<String, Object> rec = perDistrict.get(d.adcode());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adcode", d.adcode());
            m.put("name", d.name());
            m.put("province", d.province());
            m.put("city", d.city());
            // 市级聚合要用：前端在「省级视图」里画的是地级市面，
            // 需要把该市下各区县的值加起来才能给市着色
            m.put("cityAdcode", d.cityAdcode());
            m.put("areaKm2", round(areaByAdcode.getOrDefault(d.adcode(), 0.0), 1));
            Map<String, Object> crops = new LinkedHashMap<>();
            for (String cid : targets) {
                if (rec.containsKey(cid + ".area") || rec.containsKey(cid + ".output")) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("area", rec.get(cid + ".area"));
                    c.put("output", rec.get(cid + ".output"));
                    c.put("yield", rec.get(cid + ".yield"));
                    crops.put(cid, c);
                }
            }
            m.put("crops", crops);
            rows.add(m);
        }

        out.put("adcode", adcode);
        out.put("level", isCityAdcode(adcode) ? "city" : "province");
        out.put("province", provName);
        out.put("districts", rows);
        out.put("meta", index.meta());
        out.put("cropMetas", index.cropMetas());
        out.put("provinceStats", provStatsView(provStats));
        out.put("distribution", useArea ? "area_weighted" : "uniform");
        out.put("scopeFactor", round(scopeFactor, 4));
        out.put("notice", "省级数据来自国家统计局统计年鉴与官方公告；"
                + "区县值为按省值与区县面积推算的分布示意，非区县实测统计。"
                + (isCityAdcode(adcode)
                    ? "当前查询为地级市，已按该市面积占全省比例缩放市级合计。"
                    : ""));
        return out;
    }

    /** 省级统计原值视图（全国视图用：直接给各省的数值，不做区县派发）。 */
    public Map<String, Object> provinceOverview(List<String> cropIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!index.isLoaded()) {
            out.put("provinces", Collections.emptyList());
            out.put("notice", "作物分布数据未载入");
            return out;
        }
        List<String> targets = new ArrayList<>();
        if (cropIds == null || cropIds.isEmpty()) {
            targets.addAll(index.cropMetas().stream().map(CropDistributionIndex.CropMeta::cropId).toList());
        } else {
            for (String c : cropIds) {
                if (!targets.contains(c)) {
                    targets.add(c);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String provName : index.provinceNames()) {
            Map<String, CropDistributionIndex.CropStat> stats = index.ofProvince(provName);
            if (stats.isEmpty()) {
                continue;
            }
            Map<String, Object> crops = new LinkedHashMap<>();
            for (String cid : targets) {
                CropDistributionIndex.CropStat st = stats.get(cid);
                if (st == null) {
                    continue;
                }
                if (st.area() == null && st.output() == null) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("area", st.area());
                c.put("output", st.output());
                c.put("yield", st.yieldPerHa());
                crops.put(cid, c);
            }
            if (crops.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("adcode", index.adcodeOfProvince(provName));
            row.put("name", provName);
            row.put("crops", crops);
            rows.add(row);
        }
        out.put("level", "nation");
        out.put("provinces", rows);
        out.put("meta", index.meta());
        out.put("cropMetas", index.cropMetas());
        out.put("notice", "省级数据来自国家统计局《中国统计年鉴2025》表12-8/12-10 及官方产量公告，"
                + "为省级实测统计值；区县着色为按省值与区县面积推算的分布示意。");
        return out;
    }

    /** 省级的原始终值（权威统计），用于地图在省级视图着色。 */
    private Map<String, Object> provStatsView(Map<String, CropDistributionIndex.CropStat> stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, CropDistributionIndex.CropStat> e : stats.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("area", e.getValue().area());
            c.put("output", e.getValue().output());
            c.put("yield", e.getValue().yieldPerHa());
            c.put("sources", e.getValue().sources());
            m.put(e.getKey(), c);
        }
        return m;
    }

    /** 把舍入误差补回面积最大的区县，保证 Σ = 省值。 */
    private void fixRounding(Map<Integer, Map<String, Object>> perDistrict,
                             List<DistrictIndex.District> list,
                             Map<Integer, Double> w, boolean useArea,
                             String cid, String field, double target) {
        double sum = 0;
        DistrictIndex.District biggest = null;
        for (DistrictIndex.District d : list) {
            Object v = perDistrict.get(d.adcode()).get(cid + "." + field);
            if (v instanceof Double dv) {
                sum += dv;
                if (biggest == null || w.get(d.adcode()) > w.get(biggest.adcode())) {
                    biggest = d;
                }
            }
        }
        if (biggest != null && sum > 0) {
            double diff = round(target - sum, 2);
            Map<String, Object> rec = perDistrict.get(biggest.adcode());
            Object v = rec.get(cid + "." + field);
            if (v instanceof Double dv) {
                rec.put(cid + "." + field, round(dv + diff, 2));
            }
        }
    }

    /** 从区县列表反查所属省的 adcode（取区县 adcode 前 4 位 × 100）。 */
    private int provinceAdcodeOf(List<DistrictIndex.District> list) {
        if (list.isEmpty()) {
            return 0;
        }
        // 区县 adcode = 2 位省码 + 2 位市码 + 2 位县码，前 4 位补 0 即省码
        return list.get(0).adcode() / 10000 * 10000;
    }

    private boolean isCityAdcode(int adcode) {
        if (adcode <= 0 || adcode % 100 != 0) {
            return false;
        }
        return adcode % 10000 != 0;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
