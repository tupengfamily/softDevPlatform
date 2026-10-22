package com.learning.springboot.farm;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 农场地图与三级看板 API。
 *
 * <p>与 {@link FarmController} 共用 {@code /api/games/farm} 前缀：
 * 那边是「一季怎么种」，这边是「在哪种、有几块地」。
 */
@RestController
@RequestMapping("/api/games/farm")
public class FarmMapController {

    @Autowired
    private FarmMapService mapService;

    @Autowired
    private CropDistributionService cropDistributionService;

    // ---------------- 地图 ----------------

    /** 全国省级聚合（我的农场数量 / 区县数量）。 */
    @GetMapping("/map/provinces")
    public ResponseEntity<?> provinces(@CurrentUser Long userId) {
        return Result.ok(mapService.provinceMap(userId));
    }

    /**
     * 某省（或某市）下辖的全部区县：主栽作物 + 我的农场标记。
     *
     * <p>{@code province} 参数沿用旧名以兼容已有调用，实际接受省级与地级市两种编码。
     */
    @GetMapping("/map/districts")
    public ResponseEntity<?> districts(@RequestParam int province,
                                       @CurrentUser Long userId) {
        return Result.ok(mapService.districtMap(userId, province));
    }

    /** 单区县详情：气候、四作物评级、主导作物当前物候、我的农场与地块。 */
    @GetMapping("/map/district")
    public ResponseEntity<?> district(@RequestParam int adcode,
                                      @CurrentUser Long userId) {
        return Result.ok(mapService.districtDetail(userId, adcode));
    }

    /** 区县名称搜索（地图左上角输入框）。 */
    @GetMapping("/map/search")
    public ResponseEntity<?> search(@RequestParam(defaultValue = "") String q) {
        return Result.ok(mapService.searchDistricts(q));
    }

    /**
     * 作物分布：某省（或某市）下辖区县的种植面积与产量，用于地图着色。
     *
     * <p>{@code crop} 可重复传（{@code ?crop=rice&crop=wheat}），不传则返回全部作物。
     */
    @GetMapping("/map/crop-distribution")
    public ResponseEntity<?> cropDistribution(@RequestParam int adcode,
                                              @RequestParam(name = "crop", required = false)
                                              List<String> crop) {
        return Result.ok(cropDistributionService.distribution(adcode, crop));
    }

    /**
     * 省级作物分布概览（全国视图用）。
     *
     * <p>全国视图里画的是省界，没有区县层可着色——如果只做区县着色，
     * 玩家一打开图层看到的是「一片空白 + 0.2 千公顷」的迷你图例，
     * 完全不知道分布长什么样。这个端点直接给各省的实测统计值，
     * 让全国视图能按省着色，下钻后再换成区县派发值。
     */
    @GetMapping("/map/crop-distribution/provinces")
    public ResponseEntity<?> cropDistributionProvinces(
            @RequestParam(name = "crop", required = false) List<String> crop) {
        return Result.ok(cropDistributionService.provinceOverview(crop));
    }

    // ---------------- 农场 ----------------

    @GetMapping("/farms")
    public ResponseEntity<?> farms(@CurrentUser Long userId) {
        // 复用看板的聚合结果：一次 plotSummaries 分组完成，避免逐农场重复回放
        return Result.ok(mapService.board(userId).get("farms"));
    }

    /** 在地图上选点建农场。重复点击同一区县返回已有农场（幂等）。 */
    @PostMapping("/farms")
    @OperationLog(module = "娱乐", action = "地图建农场")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body,
                                    @CurrentUser Long userId) {
        int adcode = body.get("adcode") == null ? 0 : ((Number) body.get("adcode")).intValue();
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        return Result.ok(mapService.create(userId, adcode, name));
    }

    @GetMapping("/farms/{id}")
    public ResponseEntity<?> farm(@PathVariable Long id, @CurrentUser Long userId) {
        return Result.ok(mapService.detail(userId, id));
    }

    @PostMapping("/farms/{id}/rename")
    @OperationLog(module = "娱乐", action = "重命名农场")
    public ResponseEntity<?> rename(@PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    @CurrentUser Long userId) {
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        return Result.ok(mapService.rename(userId, id, name));
    }

    @DeleteMapping("/farms/{id}")
    @OperationLog(module = "娱乐", action = "删除农场")
    public ResponseEntity<?> remove(@PathVariable Long id, @CurrentUser Long userId) {
        return Result.ok(mapService.delete(userId, id));
    }

    // ---------------- 看板 ----------------

    /** 统一看板：农场分组 → 地块 → 作物状态。 */
    @GetMapping("/board")
    public ResponseEntity<?> board(@CurrentUser Long userId) {
        return Result.ok(mapService.board(userId));
    }
}
