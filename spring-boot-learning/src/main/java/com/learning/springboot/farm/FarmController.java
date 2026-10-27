package com.learning.springboot.farm;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.farm.achievement.FarmAchievementService;
import com.learning.springboot.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「稼穑 · 中国农事」演示版 API。
 *
 * <p>与其它小游戏一致，全部接口需登录：田地与成绩按 {@code userId} 隔离。
 * 成绩复用 {@code /api/games/{code}/stats}、{@code /history} 与游戏中心卡片，
 * 因此 {@code farm} 必须同时在 {@code GameService.SUPPORTED_GAMES} 里放行。
 *
 * <p><b>多地块</b>：一个用户最多 {@link FarmPlayService#MAX_PLOTS} 块田，
 * 每块地独立作物 / 播期 / 农事。地块用 {@code slot}（0..99）标识，
 * 所有操作类接口都接受 {@code ?slot=N}（缺省 0，兼容旧前端）。
 *
 * <p>🔴 演示模式说明（用户 2026-10-18 测试豁免）：
 * <ul>
 *   <li>{@code POST /advance}：手动推进 1–60 天；</li>
 *   <li>{@code POST /weather/mock}：注入一段模拟天气。</li>
 * </ul>
 * 这两条都只在 {@code farm.demo-mode=true} 时可用。正式环境必须关闭。
 */
@RestController
@RequestMapping("/api/games/farm")
public class FarmController {

    @Autowired
    private FarmPlayService farmService;

    @Autowired
    private FarmWarehouseService warehouseService;

    @Autowired
    private FarmStockService stockService;

    /** 商店服务（2026-10-23 需求 R）：买种子 / 农具 / 农资 / 水资源 */
    @Autowired
    private FarmShopService shopService;

    /** 测试用模拟天气（S2 从 FarmPlayService 抽出） */
    @Autowired
    private MockWeatherService mockWeatherService;

    /** 农艺目录（S3 从 FarmPlayService 抽出）：区域 / 作物 / 评级 / 风险 */
    @Autowired
    private FarmCatalogService catalogService;

    /** 成就 / 目标层：成就列表 + 作物图鉴（纯函数算，无新增表） */
    @Autowired
    private FarmAchievementService achievementService;

    /** 当前农田状态（物候 / 天气 / 产量预览 / 今日变化 / 历史成绩） */
    @GetMapping("/state")
    public ResponseEntity<?> state(@RequestParam(defaultValue = "0") int slot,
                                   @CurrentUser Long userId) {
        return Result.ok(farmService.view(userId, slot));
    }

    /**
     * 地块总览：按槽位升序返回每块地的作物、播期与当前物候。
     *
     * <p>多田渲染需要每块地的 stage/dvs 才能画出正确的作物外观，
     * 而 replay 只跑 SimEngine（<1ms/块），100 块也就几十毫秒。
     */
    @GetMapping("/plots")
    public ResponseEntity<?> plots(@CurrentUser Long userId) {
        return Result.ok(farmService.plotSummaries(userId));
    }

    /**
     * 季节档案：每块地每一季的种植与收获记录（2026-10-22）。
     *
     * <p>{@code farmId} 缺省时返回该用户全部农场的记录。记录含：
     * 作物、播种日、收获日、产量、出苗率、最终状态（正常收获 / 失败枯死 / 放弃）
     * 与失败原因（缺苗、霜冻、旱情等）。
     */
    @GetMapping("/seasons")
    public ResponseEntity<?> seasons(@RequestParam(required = false) Long farmId,
                                    @CurrentUser Long userId) {
        return Result.ok(farmService.seasonHistory(userId, farmId));
    }

    /** 演示模式：手动推进 N 天（1–60）。正式环境返回 400。 */
    @PostMapping("/advance")
    public ResponseEntity<?> advance(@RequestParam(defaultValue = "0") int slot,
                                     @RequestParam(defaultValue = "1") int days,
                                     @CurrentUser Long userId) {
        return Result.ok("已推进", farmService.advance(userId, slot, days));
    }

    // ==================== 仓库（S24） ====================

    /**
     * 仓库面板：存货清单 + 估值 + 临期提醒。
     *
     * <p>每个批次返回：数量、入库日、已仓储天数、距变质天数、品质分级、
     * 当前单价与总价、地区与供给压力系数。
     */
    @GetMapping("/warehouse")
    public ResponseEntity<?> warehouse(@CurrentUser Long userId) {
        // 顺带做一次临期检查（生成站内通知），保证「变质前通知到用户」
        warehouseService.checkExpiring(userId);
        return Result.ok(warehouseService.panel(userId));
    }

    /**
     * 变卖：把仓库里的货卖掉。
     *
     * <p>Body：{@code { cropId?, kg? }}。
     * {@code cropId} 缺省 = 全部卖掉；{@code kg} 缺省或 ≤0 = 该作物全部卖掉。
     *
     * <p>批次选择策略：优先卖临期的（先过期先卖），避免玩家白扔。
     */
    @PostMapping("/warehouse/sell")
    @OperationLog(module = "娱乐", action = "仓库变卖")
    public ResponseEntity<?> sellWarehouse(@RequestBody(required = false) Map<String, Object> body,
                                           @CurrentUser Long userId) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String cropId = b.get("cropId") == null ? null : String.valueOf(b.get("cropId"));
        Double kg = b.get("kg") == null ? null : ((Number) b.get("kg")).doubleValue();
        return Result.ok("变卖完成", warehouseService.sell(userId, cropId, kg));
    }

    /**
     * 购买农资入库（S25）。
     *
     * <p>Body：{@code { itemId, qty, farmId? }}。{@code farmId} 缺省 = 全局库存。
     *
     * <p><b>为什么要有购买</b>：没有购买，农资库存只会单向减少，
     * 用完就只能干看着——仓库就成了只读清单。买了才知道「该不该囤」。
     */
    @PostMapping("/warehouse/stocks/buy")
    @OperationLog(module = "娱乐", action = "购买农资")
    public ResponseEntity<?> buyStock(@RequestBody(required = false) Map<String, Object> body,
                                      @CurrentUser Long userId) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String itemId = b.get("itemId") == null ? null : String.valueOf(b.get("itemId"));
        Double qty = b.get("qty") == null ? null : ((Number) b.get("qty")).doubleValue();
        Long farmId = b.get("farmId") == null ? null : ((Number) b.get("farmId")).longValue();
        FarmStock s = stockService.buy(userId, itemId, qty == null ? 0 : qty, farmId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", s.getItemId());
        out.put("itemName", s.getItemName());
        out.put("quantity", s.getQuantity());
        out.put("unit", s.getUnit());
        return Result.ok("购买成功", out);
    }

    /** 农资库存视图（S25）：仓库面板的「农资」区。 */
    @GetMapping("/warehouse/stocks")
    public ResponseEntity<?> stocks(@RequestParam(required = false) Long farmId,
                                    @RequestParam(required = false) String kind,
                                    @CurrentUser Long userId) {
        return Result.ok(stockService.panel(userId, farmId, kind));
    }

    // ==================== 商店（2026-10-23 需求 R） ====================

    /**
     * 商店面板：买种子、农具、农资、水资源。
     *
     * <p>Query：{@code kind} = SEED / TOOL / FERT / PEST / HERB / WATER，缺省 = 全部。
     *
     * <p>响应含<b>钱包余额</b>（{@code balance}）与各类商品的持有量，
     * 前端据此显示「买得起 / 已拥有 / 还差多少钱」。
     */
    @GetMapping("/shop")
    public ResponseEntity<?> shop(@RequestParam(required = false) String kind,
                                  @CurrentUser Long userId) {
        return Result.ok(shopService.panel(userId, kind));
    }

    /**
     * 购买商品。
     *
     * <p>Body：{@code { itemId, qty }}。农具固定 qty=1（耐用品，重复买会被拒）。
     *
     * <p>扣款顺序：**先查余额 → 扣钱 → 入库**。
     * 余额不足直接 400，不会出现「钱扣了货没到」。
     */
    @PostMapping("/shop/buy")
    @OperationLog(module = "娱乐", action = "商店购物")
    public ResponseEntity<?> shopBuy(@RequestBody(required = false) Map<String, Object> body,
                                     @CurrentUser Long userId) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String itemId = b.get("itemId") == null ? null : String.valueOf(b.get("itemId"));
        if (itemId == null) {
            throw new com.learning.springboot.exception.BusinessException("itemId 不能为空");
        }
        Double qty = b.get("qty") == null ? null : ((Number) b.get("qty")).doubleValue();
        return Result.ok("购买成功", shopService.buy(userId, itemId, qty == null ? 1 : qty));
    }

    /** 钱包余额（供顶部栏显示）。 */
    @GetMapping("/shop/wallet")
    public ResponseEntity<?> wallet(@CurrentUser Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balance", shopService.balanceOf(userId));
        return Result.ok(out);
    }

    /** 收割：产量记入游戏成绩，地块进入下一季 */
    @PostMapping("/harvest")
    @OperationLog(module = "娱乐", action = "收割庄稼")
    public ResponseEntity<?> harvest(@RequestParam(defaultValue = "0") int slot,
                                     @CurrentUser Long userId) {
        return Result.ok("收割完成", farmService.harvest(userId, slot));
    }

    /** 提前重开一季（放弃当前作物，不记成绩） */
    @PostMapping("/restart")
    @OperationLog(module = "娱乐", action = "重开一季")
    public ResponseEntity<?> restart(@RequestParam(defaultValue = "0") int slot,
                                     @CurrentUser Long userId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok("已重开一季", farmService.restart(userId, slot));
    }

    /**
     * 结掉一季失败（绝收 / 苗期枯死），2026-10-22。
     *
     * <p>苗期枯死的地块 DVS 永远停在低位，{@code /harvest} 会因「没到黄熟期」拒绝，
     * 玩家既收不了也结不掉。这一季恰恰是最该进档案的一季（枯死原因、出苗率、沉没成本），
     * 所以单开一个入口：产量记 0、状态记 FAILED、<b>不记游戏成绩</b>（失败不占榜）。
     */
    @PostMapping("/close-failed")
    @OperationLog(module = "娱乐", action = "记录绝收")
    public ResponseEntity<?> closeFailed(@RequestParam(defaultValue = "0") int slot,
                                         @CurrentUser Long userId) {
        return Result.ok("本季已结", farmService.closeFailedSeason(userId, slot));
    }

    // ==================== 建田 / 适区（S2 / S23） ====================

    /** 作物库（建田选种用） */
    @GetMapping("/crops")
    public ResponseEntity<?> crops(@CurrentUser Long userId) {
        return Result.ok(catalogService.crops());
    }

    /** 可选农业区 + 各区在推荐播期下对该作物的评级摘要（建田选地页用） */
    @GetMapping("/zones")
    public ResponseEntity<?> zones(@RequestParam(required = false) String crop,
                                   @CurrentUser Long userId) {
        // 🔴 2026-10-23：必须把 userId 传下去 —— 「今天」要用**世界日期**，
        // 演示模式下现实日期与世界日期可能差一年多（详见 FarmCatalogService#userToday）。
        return Result.ok(catalogService.zones(userId, crop == null ? "rice_mid" : crop));
    }

    /** 指定区 + 播期的完整评级报告。播期改动时前端防抖调用。 */
    @GetMapping("/suitability")
    public ResponseEntity<?> suitability(@RequestParam String zone,
                                         @RequestParam String sowDate,
                                         @RequestParam(required = false) String crop,
                                         @CurrentUser Long userId) {
        return Result.ok(catalogService.suitability(zone, LocalDate.parse(sowDate),
                crop == null ? "rice_mid" : crop));
    }

    /**
     * 种植风险提示（2026-10-22）。
     *
     * <p>返回「区域风险 / 时机风险 / 结构风险」三类分项，每项带针对性说明与建议。
     * 数据来自十年回放，不是静态文案表。
     *
     * <p>与 {@code /suitability} 的区别：suitability 回答「这一季评级多少」，
     * planting-risk 回答「<b>为什么</b>是这个评级，以及该怎么办」。
     */
    @GetMapping("/planting-risk")
    public ResponseEntity<?> plantingRisk(@RequestParam String zone,
                                          @RequestParam String sowDate,
                                          @RequestParam(required = false) String crop,
                                          @CurrentUser Long userId) {
        // 🔴 2026-10-23：同 /zones，today 必须是世界日期，否则推荐播期会算成「已经过去」。
        return Result.ok(catalogService.plantingRisk(userId, zone, LocalDate.parse(sowDate),
                crop == null ? "rice_mid" : crop));
    }

    /**
     * 建田 / 换地。Body: { zoneId, sowDate?, fieldName?, cropId? }。
     *
     * <p>{@code slot} 缺省 -1 表示"新建一块地"——自动挑一个空槽位；
     * 传具体值则在该槽位上建/重建（换作物、换播期）。
     *
     * <p>S23 §2.1：这不是「许可」，任何区任何播期都能建——系统只负责告诉你
     * 这一季大概率会发生什么，不负责拦你。
     */
    /**
     * 建地块。
     *
     * <p>两种入口：
     * <ul>
     *   <li>带 {@code farmId}（地图建农场后的主路径）：农业区由农场所在区县决定，{@code zoneId} 可省；</li>
     *   <li>不带 {@code farmId}（v1 兼容路径）：必须给 {@code zoneId}。</li>
     * </ul>
     */
    @PostMapping("/field")
    @OperationLog(module = "娱乐", action = "建田选地")
    public ResponseEntity<?> createField(@RequestBody Map<String, Object> body,
                                         @RequestParam(defaultValue = "-1") int slot,
                                         @CurrentUser Long userId) {
        Long farmId = body.get("farmId") == null ? null
                : ((Number) body.get("farmId")).longValue();
        String zoneId = (String) body.get("zoneId");
        if (zoneId == null && farmId == null) {
            throw new com.learning.springboot.exception.BusinessException("zoneId 或 farmId 必须给一个");
        }
        LocalDate sow = body.get("sowDate") == null ? null : LocalDate.parse((String) body.get("sowDate"));
        String name = (String) body.get("fieldName");
        String cropId = (String) body.get("cropId");

        int target = slot;
        if (target < 0) {
            target = farmService.nextFreeSlot(userId);
            if (target < 0) {
                throw new com.learning.springboot.exception.BusinessException(
                        "地块数量已达上限（" + FarmPlayService.MAX_PLOTS + " 块）");
            }
        }
        return Result.ok("建田完成", farmService.createField(userId, target, farmId, zoneId, sow, name,
                cropId == null ? "rice_mid" : cropId));
    }

    // ==================== 农资（S12） ====================

    /**
     * 施用农资。Body: { itemId, dose? }，作用于 {@code ?slot=N} 指定的地块。
     *
     * <p>与 /action 的区别：超窗不拒绝，按效力 ×0.5 折算；打错靶标只算 5% 误伤；
     * 过量触发药害/徒长提示。三道闸门都在 Service 里折算，前端只负责展示。
     */
    @PostMapping("/input")
    @OperationLog(module = "娱乐", action = "施用农资")
    public ResponseEntity<?> applyInput(@RequestBody Map<String, Object> body,
                                        @RequestParam(defaultValue = "0") int slot,
                                        @CurrentUser Long userId) {
        String itemId = (String) body.get("itemId");
        if (itemId == null) {
            throw new com.learning.springboot.exception.BusinessException("itemId 不能为空");
        }
        Double dose = body.get("dose") == null ? null : ((Number) body.get("dose")).doubleValue();
        return Result.ok("已施用", farmService.applyInput(userId, slot, itemId, dose));
    }

    // ==================== 测试用 mock 天气 ====================

    /**
     * 注入一段模拟天气（对全部地块生效——天气按用户 + 区域，不按地块）。
     * Body: { fromDate, toDate, tmax, tmin, precip, label }。
     */
    @PostMapping("/weather/mock")
    @OperationLog(module = "娱乐", action = "注入模拟天气")
    public ResponseEntity<?> applyMock(@RequestBody Map<String, Object> body,
                                       @CurrentUser Long userId) {
        LocalDate from = LocalDate.parse((String) body.get("fromDate"));
        LocalDate to = LocalDate.parse((String) body.get("toDate"));
        double tmax = ((Number) body.get("tmax")).doubleValue();
        double tmin = ((Number) body.get("tmin")).doubleValue();
        double precip = body.get("precip") == null ? 0.0 : ((Number) body.get("precip")).doubleValue();
        String label = body.get("label") == null ? null : (String) body.get("label");
        return Result.ok("已注入", mockWeatherService.applyMock(userId, from, to, tmax, tmin, precip, label));
    }

    /** 列出该用户当前生效的 mock 段。 */
    @GetMapping("/weather/mocks")
    public ResponseEntity<?> listMocks(@CurrentUser Long userId) {
        List<Map<String, Object>> out = mockWeatherService.listMocks(userId);
        return Result.ok(out);
    }

    /** 清空该用户所有 mock 段（不影响其他用户）。 */
    @PostMapping("/weather/reset")
    @OperationLog(module = "娱乐", action = "清空模拟天气")
    public ResponseEntity<?> resetMocks(@CurrentUser Long userId) {
        return Result.ok("已清空", mockWeatherService.resetMocks(userId));
    }

    // ==================== 农事操作 ====================

    /**
     * 做一次农事。Body: { type: "IRRIGATE" | ... , note? }，作用于 {@code ?slot=N}。
     * Service 层做物候窗口校验，不在窗口直接 400 拒绝。
     */
    @PostMapping("/action")
    @OperationLog(module = "娱乐", action = "做农事")
    public ResponseEntity<?> performAction(@RequestBody Map<String, Object> body,
                                           @RequestParam(defaultValue = "0") int slot,
                                           @CurrentUser Long userId) {
        String typeStr = (String) body.get("type");
        if (typeStr == null) {
            throw new com.learning.springboot.exception.BusinessException("type 不能为空");
        }
        FarmAction.Type type;
        try {
            type = FarmAction.Type.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.learning.springboot.exception.BusinessException("未知 type: " + typeStr);
        }
        String note = body.get("note") == null ? null : (String) body.get("note");
        return Result.ok("已执行", farmService.performAction(userId, slot, type, note));
    }

    /** 列出玩家在指定地块做过的农事（最近 50 条）。 */
    @GetMapping("/actions")
    public ResponseEntity<?> listActions(@RequestParam(defaultValue = "0") int slot,
                                         @CurrentUser Long userId) {
        List<Map<String, Object>> out = farmService.listActions(userId, slot);
        return Result.ok(out);
    }

    // ==================== 成就 / 目标（S26） ====================

    /**
     * 成就面板：概览 + 成就列表 + 作物图鉴。
     *
     * <p>🔴 <b>成就是档案的纯函数，零新增表</b>：全部状态都由
     * {@code t_farm_season} 重算得出 —— 同一份档案任何时候重算都得到同样的结果，
     * 因此不存在「成就没弹窗所以没记录」这类静默失效，也不需要历史解锁数据迁移。
     * 详见 {@link FarmAchievementService} 的类注释。
     *
     * <p>成就口径（跨作物可比）：产量类成就用「实收 / 该作物参照产量」的比值，
     * 而非绝对公斤数 —— 22 种作物产量单位不可比（水稻 600 vs 白菜 5000 kg/亩），
     * 用绝对值等于「只有种水稻的人能得成就」。
     */
    @GetMapping("/achievements")
    public ResponseEntity<?> achievements(@CurrentUser Long userId) {
        return Result.ok(achievementService.panel(userId));
    }

    // ==================== 科学解释层（2026-10-27 P1） ====================

    /**
     * 本季科学解释：有效积温 / 氮素报酬递减 / 产量四要素 / 土壤水分平衡。
     *
     * <p><b>与 {@code /state} 的关系</b>：{@code /state} 已经带 {@code insights} 字段
     * （农场实时面板要一次拿全）。本端点是<b>同源数据的独立出口</b> ——
     * 给「只想取解释、不想拉整个面板」的调用方用（比如建田选地页、
     * 收割归因明细页），避免它们解析整个 {@code FarmView}。
     *
     * <p>🔴 <b>Controller 不拼文案</b>：解释文案的唯一出口是
     * {@link FarmInsightService}。这里如果自己拼一份，就是本项目最常见的分叉来源
     * ——同一个概念在三个界面出现，文案散落必然不一致。
     *
     * <p>🔴 <b>结构化 + 中文原文</b>：返回 {@code conceptId} + 四段文案 + metrics。
     * 英文界面由前端按 {@code conceptId} 查 i18n（与 {@code /achievements} 同一模式）。
     *
     * <p>🔴 <b>缺数据不编造</b>：拿不到引擎数据的概念整条不出现在列表里。
     */
    @GetMapping("/insight")
    public ResponseEntity<?> insight(@RequestParam(defaultValue = "0") int slot,
                                     @CurrentUser Long userId) {
        return Result.ok(farmService.insights(userId, slot));
    }
}
