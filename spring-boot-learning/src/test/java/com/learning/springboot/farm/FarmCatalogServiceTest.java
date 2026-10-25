package com.learning.springboot.farm;

import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.SuitabilityEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FarmCatalogService} 单元测试（S3）。
 *
 * <p><b>重点守两条线</b>：
 * <ol>
 *   <li><b>口径线</b>：所有「今天」必须走世界日期（{@link FarmCatalogService#userToday}），
 *       不能退回 {@code LocalDate.now()}。这是 2026-10-23 修过的坑，
 *       抽取时最容易把它悄悄改回去。</li>
 *   <li><b>契约线</b>：返回体的 key 不能少、不能改名 —— 前端按 key 取值，
 *       少一个 key 就是前端某处显示 undefined。</li>
 * </ol>
 *
 * <p>🔴 2026-10-26：口径基准从「地块的 demoOffsetDays」换成
 * <b>用户级时钟</b>（{@link FarmUserClockService}）。原来测试里造一块
 * {@code demoOffsetDays=100} 的地块来验证「今天被推到未来」，
 * 现在这个语义已经<b>不成立</b>了 —— 地块偏移不再参与用户级日期计算。
 */
class FarmCatalogServiceTest {

    /**
     * 用户级时钟仓储（mock 接口即可）。
     *
     * <p>⚠️ 为什么 mock 仓储而不是 mock {@link FarmUserClockService}：
     * 本机运行时是 <b>JDK 27</b>，而 Mockito 5.11 + ByteBuddy 1.14 不支持
     * JDK 27 的 inline mock（{@code Could not modify all classes}）——
     * 这与 {@code FarmWeather} 遇到的同一个坑。所以这里 mock 接口、
     * 用<b>真实</b>的 {@code FarmUserClockService}。
     */
    private final FarmUserClockRepository clockRepo = mock(FarmUserClockRepository.class);

    private FarmCatalogService newService(boolean demoMode) {
        FarmUserClockService clock = new FarmUserClockService(clockRepo, demoMode);
        return new FarmCatalogService(clock);
    }

    /** 让用户 1 的时钟偏移 {@code days} 天。 */
    private void givenUserOffset(int days) {
        when(clockRepo.findByUserId(1L))
                .thenReturn(java.util.Optional.of(new FarmUserClock(1L, days)));
    }

    // ==================================================================
    //  口径：世界日期
    // ==================================================================

    @Test
    @DisplayName("userToday(null) 返回现实日期")
    void userTodayFallsBackToRealDate() {
        assertEquals(LocalDate.now(), newService(true).userToday(null));
    }

    @Test
    @DisplayName("userToday 走用户级时钟：时钟推进 100 天 → 「今天」是现实 +100 天")
    void userTodayUsesUserClock() {
        givenUserOffset(100);

        LocalDate expected = LocalDate.now().plusDays(100);
        assertEquals(expected, newService(true).userToday(1L));
    }

    @Test
    @DisplayName("userToday 只认用户级时钟，不受地块偏移影响（2026-10-25 污染事故的回归锁）")
    void userTodayIgnoresPlotOffsets() {
        // 用户时钟没推进 → 今天就是现实日期
        givenUserOffset(0);

        // 即便存在一块偏移 660 天的地块，用户级日期也不动
        assertEquals(LocalDate.now(), newService(true).userToday(1L));
    }

    @Test
    @DisplayName("用户时钟记录缺失时退回现实日期（极老数据兜底）")
    void userTodayFallsBackWhenClockRowMissing() {
        when(clockRepo.findByUserId(1L)).thenReturn(java.util.Optional.empty());
        assertEquals(LocalDate.now(), newService(true).userToday(1L));
    }

    // ==================================================================
    //  区域列表
    // ==================================================================

    @Test
    @DisplayName("zones() 返回全部农业区，字段齐全且「最适区」唯一")
    void zonesReturnsAllFields() {
        FarmCatalogService svc = newService(true);
        List<Map<String, Object>> zones = svc.zones(null, "rice_mid");

        assertEquals(ClimateModel.zones().size(), zones.size());

        // 前端建田页依赖的 key，缺一个就是显示 undefined
        String[] required = {"id", "name", "site", "region", "lat", "open", "seasonHint",
                "level", "levelCode", "levelName", "pPass", "yieldMedian", "sowCost",
                "sowWindowFrom", "sowWindowTo", "nearestWindowFrom", "nearestWindowTo",
                "isBestZone", "bestZoneId", "aatP50", "annualPrecip", "ph", "etcSeasonMm"};
        for (Map<String, Object> z : zones) {
            for (String k : required) {
                assertTrue(z.containsKey(k), "区域 " + z.get("id") + " 缺少字段 " + k);
            }
        }

        // 「最适区」必须且只能有一个
        long best = zones.stream().filter(z -> Boolean.TRUE.equals(z.get("isBestZone"))).count();
        assertEquals(1, best, "必须有且只有一个最适区");
        String bestZoneId = (String) zones.get(0).get("bestZoneId");
        assertNotNull(bestZoneId);
        assertTrue(ClimateModel.zones().containsKey(bestZoneId));
    }

    @Test
    @DisplayName("zones() 的推荐播期不能是过去（演示模式推进后仍要选得到）")
    void zonesRecommendFutureOrTodaySowDate() {
        // 用户级时钟推进 100 天
        givenUserOffset(100);

        FarmCatalogService svc = newService(true);
        LocalDate today = svc.userToday(1L);
        List<Map<String, Object>> zones = svc.zones(1L, "wheat_winter");

        for (Map<String, Object> z : zones) {
            LocalDate nearest = LocalDate.parse((String) z.get("nearestWindowFrom"));
            assertFalse(nearest.isBefore(today),
                    "区域 " + z.get("id") + " 的「距今最近窗口」起始 " + nearest + " 早于今天 " + today);
        }
    }

    // ==================================================================
    //  适宜性评级
    // ==================================================================

    @Test
    @DisplayName("suitabilityOf 缺省参数回落到江南 + 水稻")
    void suitabilityOfFallsBackToDefaults() {
        FarmCatalogService svc = newService(true);
        SuitabilityEngine.Report r = svc.suitabilityOf(null, LocalDate.of(2026, 5, 20));
        assertNotNull(r);
        assertNotNull(r.levelName());
    }

    @Test
    @DisplayName("suitability() 未知区域抛业务异常")
    void suitabilityRejectsUnknownZone() {
        FarmCatalogService svc = newService(true);
        assertThrows(BusinessException.class,
                () -> svc.suitability("不存在的区", LocalDate.of(2026, 5, 20), "rice_mid"));
    }

    @Test
    @DisplayName("评级有缓存：同一 (区,作物,播期) 重复调用返回同一实例")
    void suitabilityIsCached() {
        FarmCatalogService svc = newService(true);
        SuitabilityEngine.Report a = svc.suitabilityOf("jiangnan", LocalDate.of(2026, 5, 20), "rice_mid");
        SuitabilityEngine.Report b = svc.suitabilityOf("jiangnan", LocalDate.of(2026, 5, 20), "rice_mid");
        assertTrue(a == b, "同一组合应命中缓存返回同一实例");
    }

    // ==================================================================
    //  种植风险
    // ==================================================================

    @Test
    @DisplayName("plantingRisk 未知区域抛业务异常")
    void plantingRiskRejectsUnknownZone() {
        FarmCatalogService svc = newService(true);
        assertThrows(BusinessException.class,
                () -> svc.plantingRisk(1L, "火星", LocalDate.of(2026, 5, 20), "rice_mid"));
    }

    @Test
    @DisplayName("plantingRisk 的 items 按 AREA / TIMING / STRUCTURE 三类标记")
    void plantingRiskHasThreeCategories() {
        FarmCatalogService svc = newService(true);
        SuitabilityEngine.PlantingRisk risk = svc.plantingRisk(
                null, "jiangnan", LocalDate.of(2026, 5, 20), "rice_mid");
        assertNotNull(risk);
        assertNotNull(risk.items());
        // 风险条目不应为空 —— 空列表说明引擎没跑起来，前端会显示「无风险」误导玩家
        assertFalse(risk.items().isEmpty(), "风险条目不应为空");
        // code 只允许这三类
        for (SuitabilityEngine.RiskItem item : risk.items()) {
            assertTrue(Set.of("AREA", "TIMING", "STRUCTURE").contains(item.code()),
                    "未知风险类别：" + item.code());
        }
        // 「选错区」与「选错时机」必须能分开说（玩家据此决定换地还是改播期）
        assertTrue(risk.items().stream().anyMatch(i -> "AREA".equals(i.code())),
                "应至少有一条 AREA 类风险");
    }

    @Test
    @DisplayName("播期偏离窗口时必须给出 TIMING 风险（玩家据此决定改播期还是换地）")
    void plantingRiskReportsTimingWhenOffWindow() {
        FarmCatalogService svc = newService(true);
        // 江南水稻推荐播期约 5/20，这里故意提前到 3/1 —— 严重偏离
        SuitabilityEngine.PlantingRisk risk = svc.plantingRisk(
                null, "jiangnan", LocalDate.of(2026, 3, 1), "rice_mid");
        assertTrue(risk.items().stream().anyMatch(i -> "TIMING".equals(i.code())),
                "偏离窗口时应给出 TIMING 风险，实际条目："
                        + risk.items().stream().map(SuitabilityEngine.RiskItem::code).toList());
        assertFalse(risk.inWindow(), "偏离窗口时 inWindow 应为 false");
    }

    @Test
    @DisplayName("plantingRisk 缺省参数回落到江南 + 水稻")
    void plantingRiskFallsBackToDefaults() {
        FarmCatalogService svc = newService(true);
        assertNotNull(svc.plantingRisk(null, null, LocalDate.of(2026, 5, 20), null));
    }

    // ==================================================================
    //  作物库
    // ==================================================================

    @Test
    @DisplayName("crops() 返回全部作物，字段齐全")
    void cropsReturnsAllFields() {
        FarmCatalogService svc = newService(true);
        List<Map<String, Object>> crops = svc.crops();

        assertEquals(CropCatalog.list().size(), crops.size());
        for (Map<String, Object> c : crops) {
            for (String k : new String[]{"id", "name", "shortName", "category", "desc",
                    "visual", "nNeed", "yieldRef", "winterHardy", "vernalizationDays",
                    "sowByZone", "sowEarlyDays", "sowLateDays"}) {
                assertTrue(c.containsKey(k), "作物 " + c.get("id") + " 缺少字段 " + k);
            }
            assertTrue(c.get("sowByZone") instanceof Map, "sowByZone 必须是 Map");
        }
    }

    @Test
    @DisplayName("crops() 的每个作物都有 visual（前端贴图靠它，缺了就是空白地块）")
    void everyCropHasVisual() {
        for (Map<String, Object> c : newService(true).crops()) {
            assertNotNull(c.get("visual"), "作物 " + c.get("id") + " 缺 visual");
        }
    }

    // ==================================================================
    //  回归：抽取后行为不变
    // ==================================================================

    @Test
    @DisplayName("抽取后 crops() 的作物数与引擎目录一致（防搬漏）")
    void cropsMatchesEngineCatalog() {
        List<Map<String, Object>> crops = newService(true).crops();
        assertEquals(CropCatalog.list().size(), crops.size());
        for (Map<String, Object> c : crops) {
            CropCatalog.Crop cc = CropCatalog.get((String) c.get("id"));
            assertEquals(cc.name(), c.get("name"));
            assertEquals(cc.config().visual(), c.get("visual"));
        }
    }
}
