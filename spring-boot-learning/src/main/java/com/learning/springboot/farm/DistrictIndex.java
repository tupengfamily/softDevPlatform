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
import java.util.Optional;

/**
 * 全国区县索引（离线）。
 *
 * <p>数据由 {@code scripts/geo/build_geo.py} 从阿里 DataV GeoAtlas 抓取并简化后落到
 * {@code resources/farm/districts.json}（约 2400 个县级单位，含中心点）。
 *
 * <p><b>为什么后端也要这份数据</b>：前端只负责画图，判定「这个区县属于哪个农业区、
 * 能种什么」必须有服务端权威口径——否则前端改个参数就能在漠河种双季稻。
 *
 * <p>文件缺失时降级为空索引（地图接口会返回空列表），不阻断应用启动。
 */
@Component
public class DistrictIndex {

    private static final Logger log = LoggerFactory.getLogger(DistrictIndex.class);

    /** 一个县级单位。{@code provinceAdcode} 取 adcode 前两位，用于按省聚合。 */
    public record District(int adcode, String name, String province, String city,
                           double lng, double lat) {
        public int provinceAdcode() {
            return adcode / 10000 * 10000;
        }

        /**
         * 所属地级市的 adcode（取 adcode 前 4 位）。
         *
         * <p>直辖市（北京 110101 / 上海 310101 …）的区县直挂省级，
         * 前 4 位（如 1101）并不对应任何真实的地级市，此时归到省本身，
         * 免得按市查询时返回空。
         */
        public int cityAdcode() {
            // city 与 province 同名 = 直辖市（build_geo 对直辖市就是这么记的）
            if (city == null || city.equals(province)) {
                return provinceAdcode();
            }
            return adcode / 100 * 100;
        }

        public String fullName() {
            if (province == null || province.equals(name)) {
                return name;
            }
            if (city == null || city.equals(name) || city.equals(province)) {
                return province + name;
            }
            return province + city + name;
        }
    }

    private final Map<Integer, District> byAdcode = new LinkedHashMap<>();
    private final Map<Integer, List<District>> byProvince = new LinkedHashMap<>();
    /**
     * 按地级市聚合。
     *
     * <p>adcode 规则：省=2 位 + 市 2 位 + 县 2 位。直辖市（省 adcode 末两位为 00）
     * 的区县与省同级，市聚合时归到省本身，避免出现「找不到市」的空结果。
     */
    private final Map<Integer, List<District>> byCity = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        ClassPathResource res = new ClassPathResource("farm/districts.json");
        if (!res.exists()) {
            log.warn("[district] 未找到 farm/districts.json，地图选点功能不可用"
                    + "（运行 scripts/geo/build_geo.py 生成）");
            return;
        }
        try (InputStream in = res.getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode arr = root.path("districts");
            for (JsonNode n : arr) {
                District d = new District(
                        n.path("adcode").asInt(),
                        n.path("name").asText(),
                        n.path("province").asText(),
                        n.path("city").asText(),
                        n.path("lng").asDouble(),
                        n.path("lat").asDouble());
                if (d.adcode() <= 0 || d.name().isEmpty()) {
                    continue;
                }
                byAdcode.put(d.adcode(), d);
                byProvince.computeIfAbsent(d.provinceAdcode(), k -> new ArrayList<>()).add(d);
                byCity.computeIfAbsent(d.cityAdcode(), k -> new ArrayList<>()).add(d);
            }
            log.info("[district] 载入 {} 个县级单位，覆盖 {} 个省级行政区 / {} 个地级市",
                    byAdcode.size(), byProvince.size(), byCity.size());
        } catch (Exception ex) {
            log.warn("[district] 载入失败: {}", ex.getMessage());
        }
    }

    public int size() {
        return byAdcode.size();
    }

    public boolean isEmpty() {
        return byAdcode.isEmpty();
    }

    public Optional<District> get(int adcode) {
        return Optional.ofNullable(byAdcode.get(adcode));
    }

    public List<District> ofProvince(int provinceAdcode) {
        return byProvince.getOrDefault(provinceAdcode, Collections.emptyList());
    }

    /** 某地级市下辖的全部区县（地图「省 → 市 → 区县」下钻用）。 */
    public List<District> ofCity(int cityAdcode) {
        return byCity.getOrDefault(cityAdcode, Collections.emptyList());
    }

    public Map<Integer, List<District>> all() {
        return Collections.unmodifiableMap(byProvince);
    }

    /** 按名称模糊搜索（前端「直接搜索区县」用），最多 30 条。 */
    public List<District> search(String keyword, int limit) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return Collections.emptyList();
        }
        List<District> hit = new ArrayList<>();
        for (District d : byAdcode.values()) {
            if (d.name().contains(q) || d.fullName().contains(q)) {
                hit.add(d);
                if (hit.size() >= limit) {
                    break;
                }
            }
        }
        return hit;
    }
}
