package com.learning.springboot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 {@code extra_json} 长度收敛回归（2026-10-27 P0-1）。
 *
 * <p><b>被修的缺陷</b>：{@code GameService.saveScore()} 原来用
 * <pre>
 *   extraJson.substring(0, MAX_EXTRA_LEN)
 * </pre>
 * 按字符数硬切 —— 切点落在对象/数组中间时，存进去的是<b>半个 JSON</b>。
 *
 * <p><b>后果不是「少几个字段」，而是整条记录报废</b>：前端
 * {@code JSON.parse(extra)} 直接抛异常 → 归因面板整块崩掉 →
 * 玩家在「游戏历史」里点开这一局，看到的是空白或报错。
 *
 * <p><b>为什么农场真的会触发</b>：归因明细带 {@code pests[]} 与 {@code disasters[]}
 * 两个不定长数组（每类作物 4~6 种病虫害 + 累积灾害）。
 * <b>「病虫害多的那一季」恰好是「查不出减产原因的那一季」</b> ——
 * 而那正是玩家最需要看归因的时刻。
 *
 * <p><b>本测试锁三条不变量</b>：
 * <ol>
 *   <li><b>结果永远是合法 JSON</b>（这是核心 —— 原来的实现会在超长时产出半个 JSON）；</li>
 *   <li><b>汇总数值永远不丢</b>（{@code pestLoss} / {@code inputCost} / {@code revenue}
 *       / {@code net} / 产量四要素）—— 只允许丢「明细数组」；</li>
 *   <li><b>未超长时零行为变化</b>（原样返回，不做任何改写）。</li>
 * </ol>
 */
class GameExtraJsonLengthTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_EXTRA_LEN = 4000;

    /**
     * {@code fitExtraJson} 是 private —— 用反射调（与 {@code FarmEconomyLoopTest}
     * 里的反射用法一致）。它是纯函数（不碰数据库），反射调是安全的。
     */
    private static String fit(String json) throws Exception {
        Method m = GameService.class.getDeclaredMethod("fitExtraJson", String.class);
        m.setAccessible(true);
        GameService svc = null;   // fitExtraJson 不依赖实例状态
        return (String) m.invoke(svc, json);
    }

    // ==================================================================
    //  辅助：构造一个「像真的一样」的归因 JSON
    // ==================================================================

    /** 构造带 N 条病虫害 + M 条灾害的归因 JSON（模拟真实规模）。 */
    private static String buildAttributionJson(int pests, int disasters) {
        StringBuilder pestSb = new StringBuilder();
        for (int i = 0; i < pests; i++) {
            if (i > 0) {
                pestSb.append(',');
            }
            pestSb.append("{\"id\":\"pest_").append(i)
                    .append("\",\"name\":\"稻瘟病").append(i)
                    .append("\",\"type\":\"DISEASE\",\"severity\":0.123}");
        }
        StringBuilder disSb = new StringBuilder();
        for (int i = 0; i < disasters; i++) {
            if (i > 0) {
                disSb.append(',');
            }
            disSb.append("{\"type\":\"HEATWAVE\",\"name\":\"持续高温").append(i)
                    .append("\",\"date\":\"2026-07-1").append(i % 10)
                    .append("\",\"intensity\":0.8,\"loss\":0.12,\"note\":\"连续 5 天高于 35℃\"}");
        }
        return "{"
                + "\"crop\":\"水稻（中稻）\",\"cropId\":\"rice_mid\",\"visual\":\"rice\","
                + "\"sowDate\":\"2026-05-20\",\"harvestDate\":\"2026-10-10\",\"days\":143,\"dvs\":1.0,"
                + "\"yieldRaw\":620.5,\"yieldCap\":700.0,\"yieldCapped\":false,"
                + "\"fertility\":0.86,\"thousandGrainWeight\":28.4,"
                + "\"dmgPanicle\":0.031,\"dmgFloret\":0.121,\"dmgFertility\":0.044,\"dmgWeight\":0.0,"
                + "\"germination\":0.94,\"withered\":false,"
                + "\"pestLoss\":0.165,\"disasterLoss\":0.24,"
                + "\"pests\":[" + pestSb + "],"
                + "\"disasters\":[" + disSb + "],"
                + "\"inputCost\":120.0,\"revenue\":4200.0,\"net\":4080.0,\"nApplied\":6.5,"
                + "\"phiViolated\":false,\"phiViolations\":0,\"overdose\":0,"
                + "\"weatherSource\":\"演示天气：2019 年南昌实测，按日历日重放（非实时，非预报）\","
                + "\"weatherSourceData\":{\"kind\":\"REPLAY\",\"zoneId\":\"jiangnan\",\"year\":2019,\"mockedSegments\":0}"
                + "}";
    }

    // ==================================================================
    //  不变量一：结果永远是合法 JSON（核心）
    // ==================================================================

    @Test
    @DisplayName("🔴 超长 extra_json 收敛后仍是合法 JSON（原实现会产出半个 JSON）")
    void oversized_extra_json_stays_valid_json() throws Exception {
        // 造一个必然超长的（400 条病虫害 + 200 条灾害）
        String huge = buildAttributionJson(400, 200);
        assertTrue(huge.length() > MAX_EXTRA_LEN, "测试前提：构造的 JSON 必须超长，实际 " + huge.length());

        String fitted = fit(huge);

        assertNotNull(fitted, "不能返回 null");
        assertTrue(fitted.length() <= MAX_EXTRA_LEN,
                "收敛后长度必须 <= " + MAX_EXTRA_LEN + "，实际 " + fitted.length());
        // 🔴 核心断言：能被解析成合法 JSON
        JsonNode node = JSON.readTree(fitted);
        assertNotNull(node, "必须是合法 JSON（不能是半个 JSON）");
        assertTrue(node.isObject(), "必须还是一个 JSON 对象");
    }

    @Test
    @DisplayName("🔴 各种超长规模下都保持合法 JSON（含刚好越界一点点）")
    void valid_json_across_sizes() throws Exception {
        // 覆盖：刚好超长 / 严重超长 / 极端超长
        int[][] sizes = {{60, 30}, {200, 100}, {1000, 500}};
        for (int[] s : sizes) {
            String raw = buildAttributionJson(s[0], s[1]);
            String fitted = fit(raw);
            assertNotNull(fitted, "size=" + s[0] + "/" + s[1] + " 不得返回 null");
            JsonNode node = JSON.readTree(fitted);
            assertTrue(node != null && node.isObject(),
                    "size=" + s[0] + "/" + s[1] + " 收敛后必须是合法 JSON 对象");
            assertTrue(fitted.length() <= MAX_EXTRA_LEN,
                    "size=" + s[0] + "/" + s[1] + " 收敛后长度 " + fitted.length() + " 超限");
        }
    }

    // ==================================================================
    //  不变量二：汇总数值永远不丢
    // ==================================================================

    @Test
    @DisplayName("🔴 收敛时只丢明细数组，汇总数值与产量四要素必须保留")
    void summary_values_survive_while_details_are_dropped() throws Exception {
        String huge = buildAttributionJson(400, 200);
        String fitted = fit(huge);
        JsonNode node = JSON.readTree(fitted);

        // 汇总数值：这些是玩家真正要看的数字，一个都不能丢
        String[] mustKeep = {
                "crop", "cropId", "sowDate", "harvestDate", "dvs",
                "yieldRaw", "yieldCap", "fertility", "thousandGrainWeight",
                "dmgPanicle", "dmgFloret", "dmgFertility", "dmgWeight",
                "germination", "pestLoss", "disasterLoss",
                "inputCost", "revenue", "net", "nApplied"
        };
        for (String f : mustKeep) {
            assertTrue(node.has(f), "收敛后丢了汇总字段：" + f
                    + "（明细可以丢，但汇总数值丢了玩家就不知道为什么减产）");
        }
        // 数值本身不能被改成 0
        assertTrue(node.get("pestLoss").asDouble() > 0, "pestLoss 不能被清零");
        assertTrue(node.get("revenue").asDouble() > 0, "revenue 不能被清零");
        assertTrue(node.get("net").asDouble() > 0, "net 不能被清零");
    }

    @Test
    @DisplayName("🔴 明细数组优先被丢弃（pests → disasters → weatherSourceData）")
    void detail_arrays_are_dropped_in_priority_order() throws Exception {
        String huge = buildAttributionJson(400, 200);
        String fitted = fit(huge);
        JsonNode node = JSON.readTree(fitted);

        // 严重超长时，明细数组应该已经被丢弃
        assertTrue(!node.has("pests"), "严重超长时 pests 明细应被丢弃（汇总 pestLoss 保留）");
        // 而 weatherSource（文字标签）应保留 —— 它不是明细数组
        assertTrue(node.has("weatherSource"), "weatherSource 文字标签应保留");
    }

    // ==================================================================
    //  不变量三：未超长时零行为变化
    // ==================================================================

    @Test
    @DisplayName("未超长的 extra_json 原样返回（零行为变化）")
    void short_extra_json_untouched() throws Exception {
        String normal = buildAttributionJson(2, 1);
        assertTrue(normal.length() < MAX_EXTRA_LEN);
        assertEquals(normal, fit(normal), "未超长时必须原样返回，一个字符都不能改");
    }

    @Test
    @DisplayName("null 与非法 JSON 不抛异常（不该因长度问题让整局记录写不进去）")
    void null_and_invalid_json_are_handled_gracefully() throws Exception {
        assertEquals(null, fit(null), "null 应原样返回");
        String invalid = "{not json at all";
        assertEquals(invalid, fit(invalid), "非法 JSON 应原样返回（不抛异常）");
    }

    @Test
    @DisplayName("非对象 JSON（数组/数字）原样返回，不被改写")
    void non_object_json_untouched() throws Exception {
        String arr = "[" + buildAttributionJson(200, 100).substring(1);
        assertEquals(arr, fit(arr), "非对象 JSON 应原样返回");
    }

    // ==================================================================
    //  回归：原实现确实会产出非法 JSON（证明这个测试有意义）
    // ==================================================================

    @Test
    @DisplayName("🔴 回归证明：旧的硬切实现会产出非法 JSON（说明这个修复不是多余的）")
    void old_hard_cut_produces_invalid_json() {
        String huge = buildAttributionJson(400, 200);
        // 复现旧实现
        String old = huge.substring(0, MAX_EXTRA_LEN);
        assertTrue(old.length() == MAX_EXTRA_LEN, "旧实现就是切到 MAX_EXTRA_LEN");
        // 旧实现产出的是半个 JSON —— 解析必然失败
        boolean parseFailed = false;
        try {
            JSON.readTree(old);
        } catch (Exception e) {
            parseFailed = true;
        }
        assertTrue(parseFailed, "旧实现应当产出非法 JSON —— 若这条断言失败，说明测试数据不再能复现原缺陷");
    }

    /**
     * 附带的结构校验：{@code DETAIL_FIELDS_TO_DROP} 里的字段必须真的存在于
     * 归因明细里 —— 否则「丢弃明细」这个策略会静默失效（字段名拼错 = 白改）。
     */
    @Test
    @DisplayName("丢弃清单里的字段名必须在归因明细中真实存在")
    void drop_list_field_names_are_real() {
        List<String> drop = GameService.detailFieldsToDropForTest();
        assertTrue(drop.contains("pests"), "清单必须含 pests");
        assertTrue(drop.contains("disasters"), "清单必须含 disasters");
        // 用真实归因明细核对字段名
        Map<String, Object> m = com.learning.springboot.farm.FarmAttribution.toMap(
                sampleHarvestForFieldCheck());
        for (String f : drop) {
            assertTrue(m.containsKey(f), "归因明细里没有字段 " + f + "（字段名拼错 → 丢弃策略静默失效）");
        }
    }

    private static com.learning.springboot.farm.FarmAttribution.Harvest sampleHarvestForFieldCheck() {
        return new com.learning.springboot.farm.FarmAttribution.Harvest(
                "rice_mid", "rice", "水稻（中稻）",
                "2026-05-20", "2026-10-10", 143, 1.0,
                620.5, 700.0, false,
                // 🔴 2026-10-27：yieldFinal = 最终实收（kg/亩）
                380.0,
                0.86, 28.4,
                0.031, 0.121, 0.044, 0.0,
                0.94, false,
                0.165, 0.0,
                new ArrayList<>(), new ArrayList<>(),
                120.0, 4200.0, 4080.0, 6.5,
                false, 0, null, 0,
                "演示天气",
                Map.of("kind", "REPLAY"));
    }
}
