package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 科学解释层下发链路集成测试（2026-10-27 P1 收口）。
 *
 * <p>🔴 <b>这个测试守护的是什么</b>：
 *
 * <p>「稼穑 = 农业科学模块」的落点是<b>能把科学量讲清楚</b>。
 * 引擎早就能算出全套科学量（有效积温 / 氮素 / 产量四要素 / 水分平衡），
 * {@code FarmInsightService} 也把它变成了四段式文案（现象→量→原理→建议），
 * 前端也画出了 {@code FarmInsightPanel}。
 *
 * <p>但<b>中间那一环是静默的</b>：从「Service 算出来了」到「前端拿得到」之间隔着
 * <pre>
 *   FarmInsightService.insights(...)
 *     → FarmPlayService.view()  → FarmView.insights()
 *     → GET /api/games/farm/state 的 JSON 里的 insights 字段
 *     → GET /api/games/farm/insight 的 JSON
 * </pre>
 * 任何一环漏了，前端面板就是<b>永远空白</b> —— 不报错、不白屏，
 * 只是「科学模块」的核心卖点静默消失。
 *
 * <p>本测试从<b>真实 HTTP 响应体</b>核对：
 * <ol>
 *   <li>{@code /state} 里有 {@code insights} 字段；</li>
 *   <li>每条 insight 的四段文案<b>都非空</b>（缺一段就是残缺解释）；</li>
 *   <li>{@code /insight} 独立端点与 {@code /state} <b>内容一致</b>（同一真源的两次出口）；</li>
 *   <li>结构字段（conceptId / severity / metrics）符合约定。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("科学解释层下发链路集成测试")
class FarmInsightApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 已定义的全部农业科学概念 ID（唯一真源 = {@code AgriConcept}）。
     *
     * <p>🔴 <b>为什么从 AgriConcept 取而不是写死字符串</b>：
     * 写死会让新增概念时这个断言<b>静默过期</b> —— 新概念出现在接口里，
     * 测试却因为白名单没更新而红，开发者会误以为是 bug。
     * 反过来，写死也可能被「顺手改一下」绕过。
     */
    private static final Set<String> AGRI_CONCEPT_IDS =
            com.learning.springboot.farm.insight.AgriConcept.ids().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private String token;

    @BeforeEach
    void login() throws Exception {
        Map<String, String> loginReq = new HashMap<>();
        loginReq.put("username", "admin");
        loginReq.put("password", "admin123");
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        token = body.path("data").path("token").asText();
        assertTrue(token != null && !token.isEmpty(), "登录 token 不应为空");
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    /**
     * 🔴 **响应体顶层是包装结构，真实数据在 {@code data} 里**。
     *
     * <p>这是本项目前端的铁律：拦截器返回 {@code {code,message,data}}，
     * 消费要 {@code res.data}。测试里必须跟前端保持同一口径，
     * 否则会写出「后端没下发」的假结论。
     */
    private JsonNode dataOf(JsonNode root) {
        JsonNode d = root.path("data");
        assertTrue(!d.isMissingNode() && !d.isNull(),
                "响应体缺少 data 包装层，实际结构：" + root.toString().substring(0, Math.min(200, root.toString().length())));
        return d;
    }

    /**
     * 🔴 **{@code /state} 必须带 {@code insights} 字段**。
     *
     * <p>哪怕是待播种地块也该有这个键（值可以是空数组）——
     * <b>「缺键」与「空数组」是两回事</b>（本项目反复踩过的坑）。
     */
    @Test
    @DisplayName("GET /state 带 insights 字段（缺键 ≠ 空数组）")
    void stateCarriesInsightsField() throws Exception {
        JsonNode data = dataOf(getJson("/api/games/farm/state"));
        JsonNode insights = data.path("insights");
        assertFalse(insights.isMissingNode(),
                "/state 缺少 insights 字段 —— 前端科学解释面板会永远空白");
        assertTrue(insights.isArray(),
                "insights 应该是数组，实际是：" + insights.getNodeType());
    }

    /**
     * 🔴 **每条解释的四段文案都非空**。
     *
     * <p>四段式（现象 / 量 / 原理 / 建议）是「能教懂」的最小结构。
     * 缺任何一段，解释就退化成一句套话 —— 那不是科学模块。
     *
     * <p>同时校验结构字段：{@code conceptId} 必须落在已知的 4 个概念里，
     * {@code severity} 在 0..5，{@code metrics} 是对象。
     */
    @Test
    @DisplayName("每条 insight 的四段文案齐全，结构字段合规")
    void insightStructureIsComplete() throws Exception {
        JsonNode insights = dataOf(getJson("/api/games/farm/state")).path("insights");
        if (!insights.isArray() || insights.size() == 0) {
            // 当前地块没有可解释的概念（待播种 / 空农场）——
            // 这本身是合法状态，本条断言在这种情况下无事可验。
            return;
        }
        for (JsonNode ins : insights) {
            String conceptId = ins.path("conceptId").asText();
            assertTrue(conceptId != null && !conceptId.isBlank(),
                    "insight 缺少 conceptId");
            // 🔴 2026-10-28 P2：概念从 4 个扩到 8 个（补齐地力/热害/春化/PHI）。
            // 白名单必须跟着 AgriConcept 走 —— 写死在这里就会漏掉新概念。
            assertTrue(AGRI_CONCEPT_IDS.contains(conceptId),
                    "出现了未知的 conceptId：" + conceptId);

            for (String seg : new String[]{"observation", "quantity", "principle", "advice"}) {
                String v = ins.path(seg).asText("");
                assertTrue(v != null && !v.isBlank(),
                        "概念 " + conceptId + " 的「" + seg + "」段是空的 —— 四段式缺一段就不是解释");
            }
            assertTrue(ins.path("title").asText("").length() > 0,
                    "概念 " + conceptId + " 缺少标题");

            int sev = ins.path("severity").asInt(-1);
            assertTrue(sev >= 0 && sev <= 5,
                    "概念 " + conceptId + " 的 severity 越界：" + sev);

            JsonNode metrics = ins.path("metrics");
            assertTrue(metrics.isObject(),
                    "概念 " + conceptId + " 的 metrics 应该是对象");
            assertTrue(metrics.size() > 0,
                    "概念 " + conceptId + " 的 metrics 是空的 —— 没有量化指标就画不出进度条");
        }
    }

    /**
     * 🔴 **概念顺序按严重度降序**。
     *
     * <p>{@code FarmInsightService} 里排了 {@code out.sort(按 severity 降序)} ——
     * 最该先看的问题排在最前面。顺序错了玩家会先看到无关紧要的概念。
     */
    @Test
    @DisplayName("概念按严重度降序排列（最该看的排最前）")
    void insightsSortedBySeverityDesc() throws Exception {
        JsonNode insights = dataOf(getJson("/api/games/farm/state")).path("insights");
        if (!insights.isArray() || insights.size() < 2) {
            return;
        }
        int prev = insights.get(0).path("severity").asInt(0);
        for (JsonNode ins : insights) {
            int sev = ins.path("severity").asInt(0);
            assertTrue(sev <= prev,
                    "概念未按严重度降序：上一个 severity=" + prev + "，当前 " + sev);
            prev = sev;
        }
    }

    /**
     * 🔴 **独立端点 {@code /insight} 与 {@code /state} 内容一致**。
     *
     * <p>两个出口都读 {@code FarmInsightService} —— 这正是「文案只写一处」的保证。
     * 如果哪天有人让 {@code /insight} 自己拼一份，两者就会分叉。
     */
    @Test
    @DisplayName("GET /insight 与 /state 的 insights 内容一致（同一真源的两次出口）")
    void insightEndpointMatchesState() throws Exception {
        JsonNode fromState = dataOf(getJson("/api/games/farm/state")).path("insights");
        JsonNode fromEndpoint = dataOf(getJson("/api/games/farm/insight"));
        assertTrue(fromEndpoint.isArray(),
                "/insight 应返回数组，实际：" + fromEndpoint.getNodeType());
        assertEquals(fromState.size(), fromEndpoint.size(),
                "/insight 与 /state 的概念条数不一致 —— 两个出口已经分叉");
        for (int i = 0; i < fromState.size(); i++) {
            assertEquals(fromState.get(i).path("conceptId").asText(),
                    fromEndpoint.get(i).path("conceptId").asText(),
                    "第 " + i + " 条概念的 conceptId 不一致");
            assertEquals(fromState.get(i).path("observation").asText(),
                    fromEndpoint.get(i).path("observation").asText(),
                    "第 " + i + " 条概念的现象段不一致");
        }
    }

    /**
     * 🔴 **「量」段必须真的带数字**。
     *
     * <p>这是「教懂」与「说套话」的分界：量段是具体数字 + 单位
     * （如「1280 / 2100 ℃·d（61%）」）。
     * 如果量段是空的或没有数字，解释就只是套话。
     */
    @Test
    @DisplayName("「量」段必须含数字（量段是「教懂」与「套话」的分界）")
    void quantitySegmentContainsNumbers() throws Exception {
        JsonNode insights = dataOf(getJson("/api/games/farm/state")).path("insights");
        if (!insights.isArray() || insights.size() == 0) {
            return;
        }
        for (JsonNode ins : insights) {
            String q = ins.path("quantity").asText("");
            assertTrue(q.matches(".*\\d.*"),
                    "概念 " + ins.path("conceptId").asText()
                            + " 的量段没有数字：「" + q + "」");
        }
    }

    /**
     * 🔴 **文案里允许富文本标记，但不允许出现未翻译的占位符**。
     *
     * <p>{@code FarmInsightService} 的文案里带 {@code <b>} 标签（前端用 v-html 渲染），
     * 这没问题。但若出现 {@code {xxx}} 这种占位符，说明拼串时漏了格式化参数。
     */
    @Test
    @DisplayName("文案不含未替换的占位符（{xxx} 说明拼串漏了参数）")
    void noUnreplacedPlaceholders() throws Exception {
        JsonNode insights = dataOf(getJson("/api/games/farm/state")).path("insights");
        if (!insights.isArray() || insights.size() == 0) {
            return;
        }
        for (JsonNode ins : insights) {
            String conceptId = ins.path("conceptId").asText();
            for (String seg : new String[]{"observation", "quantity", "principle", "advice"}) {
                String v = ins.path(seg).asText("");
                assertFalse(v.matches(".*\\{[a-zA-Z_][a-zA-Z0-9_]*\\}.*"),
                        "概念 " + conceptId + " 的「" + seg + "」段含未替换的占位符：" + v);
                assertFalse(v.contains("%s") || v.contains("%d") || v.contains("%.0f"),
                        "概念 " + conceptId + " 的「" + seg + "」段含未替换的格式符：" + v);
            }
        }
    }

    /**
     * 🔴 **接口不该 500**。
     *
     * <p>解释层依赖引擎回放结果，若某处抛异常，控制器会返回 500，
     * 前端整块面板崩掉（而不是「这块没解释」）。
     */
    @Test
    @DisplayName("解释层接口不抛异常（不是 500）")
    void insightEndpointDoesNotThrow() throws Exception {
        assertNotNull(getJson("/api/games/farm/insight"));
    }
}
