package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全国分省农作物分布（离线）。
 *
 * <p>数据来源：{@code resources/farm/crop-distribution.json}，
 * 由国家统计局《中国统计年鉴2025》表 12-8（农作物播种面积，千公顷）、
 * 表 12-10（主要农产品产量，万吨）以及官方产量公告汇编而成。
 *
 * <p><b>口径必须说清</b>：
 * <ul>
 *   <li>年鉴 2025 版载的是 <b>2024 年</b>数据；</li>
 *   <li>数据层级是 <b>省级</b>。县级/地市级播种面积没有可靠的公开机读源，
 *       所以地图上「按区县着色」是把省级数值按区县在省内的权重做分布估算，
 *       UI 上必须标注是「按省值推算的分布示意」，不能冒充统计年鉴口径；</li>
 *   <li>单产由 {@code 产量(万吨)×10000 / 面积(千公顷)} 反算，单位公斤/公顷。</li>
 * </ul>
 *
 * <p>文件缺失时降级为空索引（接口返回空列表），不阻断应用启动。
 */
@Component
public class CropDistributionIndex {

    private static final Logger log = LoggerFactory.getLogger(CropDistributionIndex.class);

    /** 一个作物的元信息。 */
    public record CropMeta(String cropId, String name, String group, int level) {
    }

    /**
     * 作物层级（父 → 子）。
     *
     * <p><b>为什么必须知道层级</b>：年鉴里的作物是**嵌套**的——
     * 「粮食」= 谷物 + 豆类 + 薯类，「谷物」= 稻谷 + 小麦 + 玉米。
     * 如果筛选时把所有选中的作物直接相加，「粮食 + 稻谷 + 小麦 + 玉米」
     * 会把同一块地数四遍，图例上的数字完全失真。
     *
     * <p>前端据此做「去重聚合」：选中了父作物就不再累加它的子作物。
     */
    private static final Map<String, List<String>> HIERARCHY = Map.of(
            "grain", List.of("cereal", "beans", "tubers"),
            "cereal", List.of("rice", "wheat", "corn"),
            "beans", List.of("soybean", "broad_bean", "pea_broad_bean"),
            "oil", List.of("peanut", "rapeseed", "sesame"),
            "sugar", List.of("sugarcane", "beet"),
            "tobacco", List.of("flue_cured"),
            "fruit", List.of("apple", "citrus", "pear", "grape", "banana")
    );

    /**
     * 「农作物总播种面积」是**独立口径**，不是其他作物的父项——
     * 它含复种面积（同一块地一年种两季算两次），所以比各作物之和还大。
     * 聚合时必须单独处理，不能和别的作物相加。
     */
    public static final String TOTAL_AREA = "total_area";

    /**
     * 顶层作物（没有父作物的那些）——「未选任何作物」时的聚合集合。
     *
     * <p>不含 {@code total_area}：它与其它作物不是同一口径，混在一起会重复。
     */
    private static final List<String> ROOT_CROPS = List.of(
            "grain", "oil", "sugar", "tobacco", "vegetable", "fruit", "tea");

    /** 某个作物的直接子作物 */
    public static List<String> childrenOf(String cropId) {
        return HIERARCHY.getOrDefault(cropId, List.of());
    }

    /** 顶层作物清单（聚合用的默认集合） */
    public static List<String> rootCrops() {
        return ROOT_CROPS;
    }

    /** 一个省份的一个作物指标。 */
    public record CropStat(String cropId, Double area, Double output, Double yieldPerHa,
                           List<String> sources) {
        public boolean hasArea() {
            return area != null;
        }

        public boolean hasOutput() {
            return output != null;
        }
    }

    private final Map<String, CropMeta> crops = new LinkedHashMap<>();
    /** 省名 → 作物Id → 指标 */
    private final Map<String, Map<String, CropStat>> provinces = new LinkedHashMap<>();
    /** 省名 → 省 adcode（110000 等），用于把省值派发到下辖区县 */
    private final Map<String, Integer> provinceAdcode = new LinkedHashMap<>();
    /** 作物展示顺序（先出现的 = 更常见） */
    private List<String> cropOrder = Collections.emptyList();
    private Map<String, Object> meta = Collections.emptyMap();
    private boolean loaded;

    @PostConstruct
    void load() {
        ClassPathResource res = new ClassPathResource("farm/crop-distribution.json");
        if (!res.exists()) {
            log.warn("[crop-dist] 未找到 farm/crop-distribution.json，作物分布图层不可用");
            return;
        }
        try (InputStream in = res.getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode m = root.path("meta");
            JsonNode cropNode = m.path("crops");
            for (JsonNode c : cropNode) {
                String id = c.path("id").asText(null);
                if (id == null || id.isEmpty()) {
                    // meta.crops 是 map，key 在字段遍历里拿不到，改用 fields()
                    continue;
                }
            }
            // meta.crops 是对象映射：{cropId: {name, group, level}}
            cropNode.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                crops.put(e.getKey(), new CropMeta(
                        e.getKey(),
                        v.path("name").asText(e.getKey()),
                        v.path("group").asText("其他"),
                        v.path("level").asInt(1)));
            });

            List<String> order = new ArrayList<>();
            for (JsonNode n : m.path("cropOrder")) {
                order.add(n.asText());
            }
            cropOrder = order.isEmpty() ? new ArrayList<>(crops.keySet()) : order;

            JsonNode metaCopy = (JsonNode) m.deepCopy();
            Map<String, Object> metaMap = new LinkedHashMap<>();
            metaCopy.fields().forEachRemaining(e -> metaMap.put(e.getKey(), e.getValue().asText()));
            // 作物层级：前端聚合时要避免父子作物重复计数
            Map<String, List<String>> hier = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : HIERARCHY.entrySet()) {
                hier.put(e.getKey(), e.getValue());
            }
            metaMap.put("hierarchy", hier);
            metaMap.put("rootCrops", ROOT_CROPS);
            metaMap.put("totalAreaCrop", TOTAL_AREA);
            meta = metaMap;

            JsonNode provNode = root.path("provinces");
            provNode.fields().forEachRemaining(e -> {
                Map<String, CropStat> cropsOfProv = new LinkedHashMap<>();
                e.getValue().fields().forEachRemaining(c -> {
                    JsonNode v = c.getValue();
                    List<String> src = new ArrayList<>();
                    for (JsonNode s : v.path("sources")) {
                        src.add(s.asText());
                    }
                    cropsOfProv.put(c.getKey(), new CropStat(
                            c.getKey(),
                            v.hasNonNull("area") ? v.get("area").asDouble() : null,
                            v.hasNonNull("output") ? v.get("output").asDouble() : null,
                            v.hasNonNull("yield") ? v.get("yield").asDouble() : null,
                            src));
                });
                provinces.put(e.getKey(), cropsOfProv);
                provinceAdcode.put(e.getKey(), adcodeOfProvince(e.getKey()));
            });
            loaded = true;
            log.info("[crop-dist] 载入 {} 省 / {} 种作物，展示顺序 {} 项",
                    provinces.size(), crops.size(), cropOrder.size());
        } catch (Exception ex) {
            log.warn("[crop-dist] 载入失败: {}", ex.getMessage());
        }
    }

    /**
     * 归一化省名：把「山东省 / 内蒙古自治区」统一成「山东 / 内蒙古」。
     *
     * <p>两套数据的省名写法不同（{@code districts.json} 带行政区后缀，
     * 统计年鉴整理时用的是简称），不归一化就永远匹配不上。
     */
    public static String normalizeProvince(String name) {
        if (name == null) {
            return null;
        }
        String s = name.trim();
        for (String suffix : new String[]{"维吾尔自治区", "壮族自治区", "回族自治区",
                "特别行政区", "自治区", "省", "市"}) {
            if (s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        return s;
    }

    /**
     * 省名 → 省级 adcode。
     *
     * <p>adcode 规则是「2 位省码 + 4 位 0」，但「内蒙古」「广西」「西藏」「宁夏」「新疆」
     * 这几个自治区的真实 adcode 分别是 150000/450000/540000/640000/650000，
     * 不能按拼音或枚举顺序猜。
     */
    static int adcodeOfProvince(String name) {
        return switch (name) {
            case "北京" -> 110000;
            case "天津" -> 120000;
            case "河北" -> 130000;
            case "山西" -> 140000;
            case "内蒙古" -> 150000;
            case "辽宁" -> 210000;
            case "吉林" -> 220000;
            case "黑龙江" -> 230000;
            case "上海" -> 310000;
            case "江苏" -> 320000;
            case "浙江" -> 330000;
            case "安徽" -> 340000;
            case "福建" -> 350000;
            case "江西" -> 360000;
            case "山东" -> 370000;
            case "河南" -> 410000;
            case "湖北" -> 420000;
            case "湖南" -> 430000;
            case "广东" -> 440000;
            case "广西" -> 450000;
            case "海南" -> 460000;
            case "重庆" -> 500000;
            case "四川" -> 510000;
            case "贵州" -> 520000;
            case "云南" -> 530000;
            case "西藏" -> 540000;
            case "陕西" -> 610000;
            case "甘肃" -> 620000;
            case "青海" -> 630000;
            case "宁夏" -> 640000;
            case "新疆" -> 650000;
            default -> 0;
        };
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public List<CropMeta> cropMetas() {
        List<CropMeta> out = new ArrayList<>();
        for (String id : cropOrder) {
            CropMeta c = crops.get(id);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public Map<String, CropStat> ofProvince(String provinceName) {
        return provinces.getOrDefault(provinceName, Collections.emptyMap());
    }

    public List<String> provinceNames() {
        return new ArrayList<>(provinces.keySet());
    }
}
