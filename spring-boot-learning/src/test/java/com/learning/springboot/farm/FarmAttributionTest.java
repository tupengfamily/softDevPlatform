package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.DisasterEngine;
import com.learning.springboot.farm.engine.PestEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FarmAttribution} 归因明细契约测试（2026-10-26）。
 *
 * <p><b>为什么必须有这个测试</b>：2026-10-26 实测发现归因明细有三个真实缺陷 ——
 * 全部是「字段悄悄少了一个」型缺陷，<b>编译能过、测试全绿、运行时才发现数据不对</b>：
 * <ol>
 *   <li>{@code dmgPanicle}（穗数受损）压根没写 —— 产量四要素只显示了 3/4；</li>
 *   <li>{@code germination}/{@code withered} 在 {@code saveScore} <b>之后</b>才补写
 *       → 成绩表里永远没有（实测 14 局 farm 成绩命中 0 条）；</li>
 *   <li>{@code pests}/{@code disasters} 是拼接字符串，无法结构化消费。</li>
 * </ol>
 *
 * <p>「少一个字段」这种缺陷<b>没有任何现有测试能发现</b> ——
 * 因为没有测试断言过「归因明细应该有哪些字段」。
 * 所以本测试的作用就是<b>把这个契约写下来</b>，让以后再少字段时立刻红。
 *
 * <p>🔴 <b>本测试锁四条不变量</b>：
 * <ol>
 *   <li><b>字段全集</b>：收割归因必须包含全部约定字段（前端 {@code GameHistory.vue}
 *       的 {@code FarmAttributionPanel} 按此消费）；</li>
 *   <li><b>绝收路径也有字段</b>：绝收不能是「空壳」—— 缺苗率 / 枯死原因 /
 *       沉没成本必须都在（这是玩家最需要看的三件事）；</li>
 *   <li><b>病虫害是结构化的</b>：每项必须有 {@code id}（跨语言稳定标识），
 *       否则英文界面只能露中文；</li>
 *   <li><b>未显症的病虫害不计入</b>：潜伏期未过的实例不该出现在归因表里
 *       （那会让玩家以为「已经发生了病虫害」）。</li>
 * </ol>
 */
class FarmAttributionTest {

    /** 收割归因必须包含的字段全集（前端面板的输入契约）。 */
    private static final List<String> HARVEST_FIELDS = List.of(
            "crop", "cropId", "visual",
            "sowDate", "harvestDate", "days", "dvs",
            "yieldRaw", "yieldCap", "yieldCapped",
            // 🔴 2026-10-27：最终实收产量（与 yieldRaw 并存，各司其职）
            "yieldFinal",
            "fertility", "thousandGrainWeight",
            "dmgPanicle", "dmgFloret", "dmgFertility", "dmgWeight",
            "germination", "withered",
            "pestLoss", "disasterLoss", "pests", "disasters",
            "inputCost", "revenue", "net", "nApplied",
            "phiViolated", "phiViolations", "overdose",
            "weatherSource", "weatherSourceData");

    /** 绝收归因必须包含的字段全集。 */
    private static final List<String> FAILED_FIELDS = List.of(
            "crop", "cropId", "visual",
            "sowDate", "harvestDate", "days", "dvs",
            "yield", "germination", "withered",
            "failureType", "failureNote",
            "inputCost", "revenue", "net");

    private static FarmAttribution.Harvest sampleHarvest() {
        return new FarmAttribution.Harvest(
                "rice_mid",
                FarmAttribution.visualOf("rice_mid"),
                "水稻（中稻）",
                "2026-05-20", "2026-10-10", 143, 1.0,
                620.5, 700.0, false,
                // 🔴 2026-10-27：yieldFinal = 最终实收（kg/亩）。
                // 620.5 是「引擎原始潜力」，实收是叠加病虫害/灾害/土壤/出苗/杂草后的值。
                // 380.0 < 620.5 —— 这个差值正是归因面板要讲清楚的事。
                380.0,
                0.86, 28.4,
                0.031, 0.121, 0.044, 0.0,
                0.94, false,
                0.165, 0.0,
                List.of(new FarmAttribution.PestHit("blast", "稻瘟病", "DISEASE", 0.961)),
                List.of(),
                120.0, 4200.0, 4080.0, 6.5,
                false, 0, null, 0,
                "演示天气：2019 年南昌实测，按日历日重放（非实时，非预报）",
                Map.of("kind", "REPLAY", "zoneId", "jiangnan", "year", 2019, "mockedSegments", 0));
    }

    @Test
    @DisplayName("收割归因包含字段全集 —— 特别是 dmgPanicle 与 germination")
    void harvest_contains_all_contract_fields() {
        Map<String, Object> m = FarmAttribution.toMap(sampleHarvest());

        for (String f : HARVEST_FIELDS) {
            assertTrue(m.containsKey(f), "收割归因缺字段：" + f);
        }
        // 🔴 这两个是本次修复的核心目标，单独断言以免被误删
        assertNotNull(m.get("dmgPanicle"), "dmgPanicle 不能缺（产量四要素之一）");
        assertNotNull(m.get("germination"), "germination 不能缺（缺苗是主要减产原因）");
    }

    /**
     * 🔴 2026-10-27：<b>潜力与实收必须并存，且不能混用</b>。
     *
     * <p>{@code yieldRaw} 是引擎原始<b>潜力</b>（只含温度/积温损伤），
     * {@code yieldFinal} 是叠加病虫害/灾害/土壤/出苗/杂草/工序遗漏/农具后的
     * <b>实收</b>。两者语义完全不同，展示时不能互相顶替 ——
     * 拿潜力当实收，玩家会以为收成比实际好一大截；
     * 拿实收当潜力，归因面板就讲不清「损失吃掉多少」。
     *
     * <p>本测试钉住：① 两个字段都在；② 实收 ≤ 潜力（损失只会往下压）；
     * ③ 实收为 0 时（枯死绝收）也必须如实写 0，不能用 null 掩盖。
     */
    @Test
    @DisplayName("🔴 归因里潜力与实收并存：yieldRaw 是潜力，yieldFinal 是实收")
    void potentialAndActualYieldAreDistinct() {
        Map<String, Object> m = FarmAttribution.toMap(sampleHarvest());

        assertTrue(m.containsKey("yieldRaw"), "必须有 yieldRaw（引擎原始潜力）");
        assertTrue(m.containsKey("yieldFinal"), "必须有 yieldFinal（最终实收）");

        double potential = ((Number) m.get("yieldRaw")).doubleValue();
        double actual = ((Number) m.get("yieldFinal")).doubleValue();
        assertTrue(actual <= potential + 1e-6,
                "实收不能高于潜力：潜力 " + potential + " / 实收 " + actual
                        + " —— 实收 = 潜力 − 各类损失，只可能更低");
        assertTrue(actual < potential,
                "有病虫害/灾害损失时，实收必须严格低于潜力（否则说明两个字段写反了）");

        // 历史数据（没有 yieldFinal）→ 字段存在但为 null（前端显示「未记录」）
        FarmAttribution.Harvest legacy = new FarmAttribution.Harvest(
                "rice_mid", FarmAttribution.visualOf("rice_mid"), "水稻（中稻）",
                "2026-05-20", "2026-10-10", 143, 1.0,
                620.5, 700.0, false,
                null,
                0.86, 28.4,
                0.031, 0.121, 0.044, 0.0,
                0.94, false,
                0.165, 0.0,
                List.of(), List.of(),
                120.0, 4200.0, 4080.0, 6.5,
                false, 0, null, 0,
                "演示天气", null);
        Map<String, Object> legacyMap = FarmAttribution.toMap(legacy);
        assertTrue(legacyMap.containsKey("yieldFinal"),
                "历史数据也要有 yieldFinal 这个键（值为 null），否则前端「字段不存在」与「值为 0」分不清");
        assertEquals(null, legacyMap.get("yieldFinal"), "历史数据的 yieldFinal 应为 null");
    }

    @Test
    @DisplayName("绝收归因包含缺苗率 / 枯死原因 / 沉没成本")
    void failed_contains_all_contract_fields() {
        FarmAttribution.Failed f = new FarmAttribution.Failed(
                "corn_spring",
                FarmAttribution.visualOf("corn_spring"),
                "玉米（春/夏播）",
                "2026-10-24", "2027-03-01", 98, 0.106,
                0.308, true,
                "FROST", "苗期遭遇 0℃ 低温，幼苗冻死（该作物不耐寒）",
                340.0, -340.0);

        Map<String, Object> m = FarmAttribution.toMap(f);
        for (String k : FAILED_FIELDS) {
            assertTrue(m.containsKey(k), "绝收归因缺字段：" + k);
        }
        // 绝收的三件要紧事：出苗率 / 枯死原因 / 沉没成本（负数净收益）
        assertEquals(0.308, (Double) m.get("germination"));
        assertEquals("FROST", m.get("failureType"));
        // 🔴 用数值比较而不是 (Double) 强转：Jackson 会把 0.0 序列化成 Integer 0，
        //    强转会 ClassCastException（实测踩过）。这里统一走 Number。
        assertEquals(-340.0, ((Number) m.get("net")).doubleValue(), 0.001);
        assertEquals(0.0, ((Number) m.get("revenue")).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("病虫害是结构化的，且带跨语言稳定的 id")
    void pests_are_structured_with_id() {
        Map<String, Object> m = FarmAttribution.toMap(sampleHarvest());
        assertTrue(m.get("pests") instanceof List);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pests = (List<Map<String, Object>>) m.get("pests");
        assertEquals(1, pests.size());
        Map<String, Object> p = pests.get(0);
        assertEquals("blast", p.get("id"), "必须有 id —— 英文界面靠它映射，不能露中文");
        assertNotNull(p.get("name"));
        assertNotNull(p.get("type"));
        assertNotNull(p.get("severity"));
    }

    @Test
    @DisplayName("未显症的病虫害不计入归因（潜伏期未过 ≠ 已发生）")
    void undetected_pests_are_excluded() {
        PestEngine.Instance detected = new PestEngine.Instance(
                "blast", "稻瘟病", "DISEASE", "FUNGUS",
                0.9, 0.8, true, "2026-06-01", 0, "GRAIN");
        PestEngine.Instance latent = new PestEngine.Instance(
                "stem_borer", "二化螟", "INSECT", "INSECT",
                0.5, 0.6, false, "2026-06-10", 3, "STEM");

        PestEngine.Result pr = new PestEngine.Result(
                List.of(detected, latent), 0.1, 0.1, 0.0, 0.1, 0.05);

        List<FarmAttribution.PestHit> hits = FarmAttribution.pestHits(pr);
        assertEquals(1, hits.size(), "未显症实例不应计入");
        assertEquals("blast", hits.get(0).id());
    }

    @Test
    @DisplayName("天气来源有结构化字段（英文界面不能只靠中文原文）")
    void weather_source_is_structured() {
        Map<String, Object> m = FarmAttribution.toMap(sampleHarvest());
        @SuppressWarnings("unchecked")
        Map<String, Object> wd = (Map<String, Object>) m.get("weatherSourceData");
        assertNotNull(wd, "必须有结构化天气来源，否则英文界面露中文");
        assertEquals("REPLAY", wd.get("kind"));
        assertEquals("jiangnan", wd.get("zoneId"));
        assertEquals(2019, ((Number) wd.get("year")).intValue());
        assertEquals(0, ((Number) wd.get("mockedSegments")).intValue());
        // 中文原文仍然保留（后端唯一权威，前端缺 key 时退回它）
        assertTrue(String.valueOf(m.get("weatherSource")).contains("演示天气"));
    }

    @Test
    @DisplayName("灾害是结构化的，含类型 / 日期 / 损失")
    void disasters_are_structured() {
        DisasterEngine.Event e = new DisasterEngine.Event(
                "HEATWAVE", "持续高温", "2026-07-15", 0.8, 0.12, "连续 5 天高于 35℃");
        DisasterEngine.Result dr = new DisasterEngine.Result(List.of(e), 0.12);

        List<FarmAttribution.DisasterHit> hits = FarmAttribution.disasterHits(dr);
        assertEquals(1, hits.size());
        assertEquals("HEATWAVE", hits.get(0).type());
        assertEquals("2026-07-15", hits.get(0).date());
        assertEquals(0.12, hits.get(0).loss(), 0.0001);
    }

    @Test
    @DisplayName("visual 取自后端权威配置，不是猜的")
    void visual_comes_from_backend_catalog() {
        // 4 个粮食作物保留旧 visual（前端已有这 4 套贴图，改了会让历史数据失效）
        assertEquals("rice", FarmAttribution.visualOf("rice_mid"));
        assertEquals("wheat", FarmAttribution.visualOf("wheat_winter"));
        assertEquals("corn", FarmAttribution.visualOf("corn_spring"));
        assertEquals("soybean", FarmAttribution.visualOf("soybean"));
        // 园艺作物 visual = 作物 id 本身（2026-10-24 语义变更）
        assertEquals("cabbage", FarmAttribution.visualOf("cabbage"));
        // 查不到就空串，不猜
        assertEquals("", FarmAttribution.visualOf("no_such_crop"));
        assertEquals("", FarmAttribution.visualOf(null));
        assertEquals("", FarmAttribution.visualOf(""));
    }

    @Test
    @DisplayName("所有 22 种作物的 visual 都能解析出来（不能有漏网的）")
    void all_crops_resolve_visual() {
        Map<String, CropCatalog.Crop> all = CropCatalog.all();
        assertEquals(22, all.size(), "作物数量变了 —— 记得同步前端的 CROP_EN 表");
        for (CropCatalog.Crop c : all.values()) {
            String v = FarmAttribution.visualOf(c.id());
            assertFalse(v.isBlank(), "作物 " + c.id() + " 的 visual 解析为空");
        }
    }
}
