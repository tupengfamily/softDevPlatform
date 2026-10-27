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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收割阈值下发链路集成测试（2026-10-27）。
 *
 * <p>🔴 <b>这个测试守护的是什么</b>：
 *
 * <p>{@code HarvestThresholdTest} 验的是<b>纯函数</b>（{@code OpChain.harvestDvsOf}）
 * 算得对不对。但从「算得对」到「前端拿得到」之间还有一段链路：
 * <pre>
 *   OpChain.harvestDvsOf(cropId)
 *     → FarmPlayService.plotSummaries()  → m.put("harvestDvs" / "harvestable")
 *     → FarmPlayService.view()           → FarmView.CropView.harvestDvs()
 *     → JSON 响应体
 * </pre>
 * 任何一环漏了，前端就会退回写死的 0.92 —— 而前端退回是<b>静默</b>的：
 * 番茄收不了、玉米提前收，界面不会报错，只会「看起来怪」。
 *
 * <p>所以本测试从<b>真实 HTTP 响应体</b>里核对这两个字段确实存在且口径一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("收割阈值下发链路集成测试")
class FarmHarvestThresholdApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

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

    /**
     * 🔴 **地块列表必须带 {@code harvestDvs} 与 {@code harvestable}**。
     *
     * <p>这两个字段是前端 {@code isHarvestable(p)} 的输入。
     * 缺了任何一个，前端就只能退回 0.92 的老口径。
     */
    @Test
    @DisplayName("GET /api/games/farm/plots 每块地都下发 harvestDvs 与 harvestable")
    void plotSummariesCarryHarvestFields() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/games/farm/plots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        JsonNode data = body.path("data");
        assertTrue(data.isArray(), "plots 应返回数组，实际：" + body.toString().substring(0, Math.min(300, body.toString().length())));

        for (JsonNode p : data) {
            assertTrue(p.has("harvestDvs"),
                    "地块 " + p.path("slot").asInt() + " 缺 harvestDvs 字段");
            assertTrue(p.has("harvestable"),
                    "地块 " + p.path("slot").asInt() + " 缺 harvestable 字段");
            double thr = p.path("harvestDvs").asDouble(-1);
            assertTrue(thr > 0 && thr <= 1.0,
                    "地块 " + p.path("slot").asInt() + " 的 harvestDvs 越界：" + thr);
        }
    }

    /**
     * 🔴 **单地块视图必须带 {@code harvestDvs}**。
     *
     * <p>{@code GameFarm.vue} 顶部的「成熟阈值」chip 读的就是这个字段。
     */
    @Test
    @DisplayName("GET /api/games/farm/state 的 crop 段带 harvestDvs")
    void stateCarriesHarvestDvs() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/games/farm/state")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        JsonNode crop = body.path("data").path("crop");
        assertTrue(crop.has("harvestDvs"), "crop 段缺 harvestDvs 字段");
        assertTrue(crop.has("harvestable"), "crop 段缺 harvestable 字段");
        double thr = crop.path("harvestDvs").asDouble(-1);
        assertTrue(thr > 0 && thr <= 1.0, "harvestDvs 越界：" + thr);
    }

    /**
     * 🔴 **地块列表里的阈值必须与该作物自己的唯一真源一致**。
     *
     * <p>如果有人在地块列表里另写一份阈值（而不是调 {@code OpChain.harvestDvsOf}），
     * 本测试会失败 —— 这正是「同一口径只能有一处」的意义。
     */
    @Test
    @DisplayName("地块列表的 harvestDvs 与 OpChain.harvestDvsOf 逐块一致")
    void plotHarvestDvsMatchesSingleSource() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/games/farm/plots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        for (JsonNode p : body.path("data")) {
            boolean chosen = p.path("chosen").asBoolean(false);
            if (!chosen) {
                // 未选作物：阈值取默认（后端会回落到水稻口径）
                continue;
            }
            String cropId = p.path("cropId").asText(null);
            if (cropId == null || cropId.isEmpty()) {
                continue;
            }
            double expected = OpChain.harvestDvsOf(cropId);
            double actual = p.path("harvestDvs").asDouble(-1);
            assertTrue(Math.abs(expected - actual) < 1e-6,
                    "作物 " + cropId + " 的 harvestDvs 不一致：接口给 " + actual
                            + "，唯一真源给 " + expected);

            // harvestable 必须与 dvs >= 阈值一致（前端不再自己判断）
            double dvs = p.path("dvs").asDouble(0);
            boolean harvestable = p.path("harvestable").asBoolean(false);
            assertTrue(harvestable == (dvs >= expected - 1e-9),
                    "作物 " + cropId + " 的 harvestable(" + harvestable + ") 与 dvs(" + dvs
                            + ") >= 阈值(" + expected + ") 不一致");
        }
    }

    /**
     * 🔴 **不能出现写死的 0.92 兜底**。
     *
     * <p>前端 {@code isHarvestable} 在后端没给字段时才退回 0.92。
     * 只要后端<b>始终</b>给了 {@code harvestable}，那 0.92 就永远不会生效 ——
     * 也就是说「番茄提前收 / 玉米收不了」这类问题不会复发。
     *
     * <p>本测试锁住「后端始终给结论」这件事。
     */
    @Test
    @DisplayName("后端始终给结论 —— 前端不会退回 0.92 兜底")
    void backendAlwaysProvidesConclusion() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/games/farm/plots")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        for (JsonNode p : body.path("data")) {
            assertFalse(p.path("harvestable").isMissingNode(),
                    "地块 " + p.path("slot").asInt() + " 没有 harvestable 字段 —— 前端会退回 0.92");
            assertTrue(p.path("harvestable").isBoolean(),
                    "地块 " + p.path("slot").asInt() + " 的 harvestable 不是布尔值");
        }
    }
}
