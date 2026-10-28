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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 P0 回归：<b>仓库补货必须扣钱包</b>（2026-10-28）。
 *
 * <h2>这个缺陷是怎么来的</h2>
 * <p>仓库面板的「补货」按钮调的是 {@code POST /warehouse/stocks/buy}，
 * 而这个接口原先直接调 {@code FarmStockService.buy(userId, itemId, qty, farmId)}
 * —— <b>完全绕过了 {@code FarmShopService.buy} 里的扣钱包逻辑</b>。
 *
 * <p>于是玩家点一下「补货」就能无限量白拿化肥/农药/种子：
 * <ul>
 *   <li>钱不变（{@code balance} 不动）；</li>
 *   <li>库存照涨；</li>
 *   <li>日志里连一条「余额不足」都不会出现。</li>
 * </ul>
 *
 * <p>这直接击穿了 2026-10-27 用户定案的「<b>买入扣钱，施用只扣库存</b>」口径 ——
 * 施用不扣钱没问题，但**买入必须扣钱**，否则整个经济闭环没有约束力。
 *
 * <h2>修法</h2>
 * <p>两个购买入口收敛到 {@code FarmShopService.buy}（唯一出口）：
 * 扣钱、校验「已拥有」、单位换算全部只有一份实现。
 *
 * <h2>本测试守什么</h2>
 * <ol>
 *   <li>补货后<b>余额必须减少</b>（这是本次修复的核心断言）；</li>
 *   <li>补货后<b>库存必须增加</b>（不能只扣钱不发货）；</li>
 *   <li>余额不足时必须<b>被拒绝</b>（不能因为绕过了商店就无限买）；</li>
 *   <li>qty 的单位是<b>售卖单位</b>（水=份、种子=包），不是入库单位。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("仓库补货资金流集成测试（P0）")
class WarehouseBuyMoneyFlowApiTest {

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
        token = objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("token").asText();
        assertTrue(token != null && !token.isEmpty(), "登录 token 不应为空");
    }

    private JsonNode dataOf(String path) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(r.getResponse().getContentAsString());
        JsonNode d = root.path("data");
        assertTrue(!d.isMissingNode() && !d.isNull(),
                "响应体缺少 data 包装层，实际结构：" + root.toString());
        return d;
    }

    private JsonNode postBuy(String itemId, double qty) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("itemId", itemId);
        body.put("qty", qty);
        MvcResult r = mockMvc.perform(post("/api/games/farm/warehouse/stocks/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
    }

    private double stockOf(String itemId) throws Exception {
        JsonNode data = dataOf("/api/games/farm/warehouse/stocks");
        for (JsonNode it : data.path("items")) {
            if (itemId.equals(it.path("itemId").asText())) {
                return it.path("quantity").asDouble();
            }
        }
        return 0.0;
    }

    /**
     * 🔴 **核心断言：补货必须扣钱包**。
     *
     * <p>这是本次 P0 修复存在的全部理由。修复前余额纹丝不动。
     */
    @Test
    @DisplayName("🔴 仓库补货后余额必须减少（修复前是免费白拿）")
    void warehouseBuyDecreasesBalance() throws Exception {
        double before = dataOf("/api/games/farm/shop/wallet").path("balance").asDouble();
        double stockBefore = stockOf("COMPOUND");

        JsonNode out = postBuy("COMPOUND", 10);

        double after = dataOf("/api/games/farm/shop/wallet").path("balance").asDouble();
        assertTrue(after < before,
                "补货后余额必须减少 —— 修复前这个接口绕过了扣款，玩家可以无限白拿农资。"
                        + " 购买前 ¥" + before + "，购买后 ¥" + after);

        // 扣的钱必须等于标价（10 kg 复合肥 × ¥3.2）
        double unitPrice = com.learning.springboot.farm.engine.InputCatalog.get("COMPOUND").pricePerUnit();
        double expected = 10 * unitPrice;
        assertEquals(expected, before - after, 0.01,
                "扣款金额必须等于标价 10 kg × ¥" + unitPrice + " = ¥" + expected);

        // 而且货必须到了（不能只扣钱不发货）
        assertTrue(stockOf("COMPOUND") > stockBefore,
                "补货后库存必须增加 —— 不能只扣钱不发货");
        assertEquals(stockBefore + 10, stockOf("COMPOUND"), 0.01,
                "买 10 kg 应入库存 10 kg");
        assertTrue(out.path("cost").asDouble() > 0, "购买结果应回带本次花费");
    }

    /**
     * 🔴 **余额不足必须被拒绝**。
     *
     * <p>修复前这个接口完全不查余额，可以买任意多 —— 现在必须挡下来。
     */
    @Test
    @DisplayName("🔴 余额不足时仓库补货必须被拒绝")
    void warehouseBuyRejectsWhenBalanceInsufficient() throws Exception {
        // 买一个贵到必然买不起的东西：植保无人机 ¥46000
        MvcResult r = mockMvc.perform(post("/api/games/farm/warehouse/stocks/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("itemId", "TOOL_DRONE", "qty", 1))))
                .andReturn();
        int status = r.getResponse().getStatus();
        assertNotEquals(200, status,
                "余额不足时必须被拒绝（返回 200 说明绕过了余额校验），实际状态：" + status);
    }

    /**
     * 🔴 **种子的 qty 单位是「包」**（不是亩）。
     *
     * <p>买 1 包中稻种子（¥28，可播 5 亩）→ 库存 +5 亩、扣 ¥28。
     * 如果单位搞反了（qty 按亩算），买 1 会被当成 1 亩 = ¥5.6，
     * 或者买 1 包被记成 1 亩 —— 两种都错。
     */
    @Test
    @DisplayName("🔴 买 1 包种子 = 入库 N 亩 + 扣一个包价")
    void seedBuyUsesPackUnit() throws Exception {
        double balanceBefore = dataOf("/api/games/farm/shop/wallet").path("balance").asDouble();
        double stockBefore = stockOf("SEED_rice_mid");

        postBuy("SEED_rice_mid", 1);

        double balanceAfter = dataOf("/api/games/farm/shop/wallet").path("balance").asDouble();
        double stockAfter = stockOf("SEED_rice_mid");

        assertEquals(stockBefore + 5, stockAfter, 0.01,
                "中稻种子 1 包 = 5 亩，库存应 +5 亩");
        assertEquals(28.0, balanceBefore - balanceAfter, 0.01,
                "买 1 包中稻种子应扣 ¥28（一包价），实际扣 ¥" + (balanceBefore - balanceAfter));
    }

    /**
     * 🔴 **水的 qty 单位是「份」**（1 份 = 20 次）。
     *
     * <p>买 1 份自来水（¥6）→ 库存 +20 次、扣 ¥6。
     */
    @Test
    @DisplayName("🔴 买 1 份水 = 入库 20 次 + 扣 ¥6")
    void waterBuyUsesPackUnit() throws Exception {
        double balanceBefore = dataOf("/api/games/farm/shop/wallet").path("balance").asDouble();
        double stockBefore = stockOf("WATER_TAP");

        postBuy("WATER_TAP", 1);

        double balanceAfter = dataOf("/api/games/farm/shop/wallet").path("balance").asDouble();
        double stockAfter = stockOf("WATER_TAP");

        assertEquals(stockBefore + 20, stockAfter, 0.01,
                "自来水 1 份 = 20 次灌溉，库存应 +20 次");
        assertEquals(6.0, balanceBefore - balanceAfter, 0.01,
                "买 1 份自来水应扣 ¥6（一份价），实际扣 ¥" + (balanceBefore - balanceAfter));
    }

    /**
     * 🔴 **农具不能重复买**（耐用品）。
     *
     * <p>这条是「耐用品已拥有」的守卫，走统一入口后仍然生效。
     */
    @Test
    @DisplayName("🔴 农具不能重复购买（耐用品拥有制）")
    void durableToolCannotBeBoughtTwice() throws Exception {
        postBuy("TOOL_SPRAYER", 1);
        MvcResult r = mockMvc.perform(post("/api/games/farm/warehouse/stocks/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("itemId", "TOOL_SPRAYER", "qty", 1))))
                .andReturn();
        assertNotEquals(200, r.getResponse().getStatus(),
                "已经有喷雾器了，不应允许重复购买");
    }
}
