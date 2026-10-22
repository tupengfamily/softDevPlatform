package com.learning.springboot.farm;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
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
        return Result.ok(farmService.crops());
    }

    /** 可选农业区 + 各区在推荐播期下对该作物的评级摘要（建田选地页用） */
    @GetMapping("/zones")
    public ResponseEntity<?> zones(@RequestParam(required = false) String crop,
                                   @CurrentUser Long userId) {
        return Result.ok(farmService.zones(crop == null ? "rice_mid" : crop));
    }

    /** 指定区 + 播期的完整评级报告。播期改动时前端防抖调用。 */
    @GetMapping("/suitability")
    public ResponseEntity<?> suitability(@RequestParam String zone,
                                         @RequestParam String sowDate,
                                         @RequestParam(required = false) String crop,
                                         @CurrentUser Long userId) {
        return Result.ok(farmService.suitability(zone, LocalDate.parse(sowDate),
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
        return Result.ok("已注入", farmService.applyMock(userId, from, to, tmax, tmin, precip, label));
    }

    /** 列出该用户当前生效的 mock 段。 */
    @GetMapping("/weather/mocks")
    public ResponseEntity<?> listMocks(@CurrentUser Long userId) {
        List<Map<String, Object>> out = farmService.listMocks(userId);
        return Result.ok(out);
    }

    /** 清空该用户所有 mock 段（不影响其他用户）。 */
    @PostMapping("/weather/reset")
    @OperationLog(module = "娱乐", action = "清空模拟天气")
    public ResponseEntity<?> resetMocks(@CurrentUser Long userId) {
        return Result.ok("已清空", farmService.resetMocks(userId));
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
}
