package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.WarehouseEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「稼穑」仓库引擎回归（2026-10-22 需求 P）。
 *
 * <p>覆盖 6 个点：
 * <ol>
 *   <li><b>仓储期分级</b>：新鲜 → 品质下降 → 已变质，边界准确；</li>
 *   <li><b>不可出售</b>：过期的货 {@code sellable() == false}；</li>
 *   <li><b>地区价差</b>：主产区便宜、销区贵（江南水稻 &lt; 华南水稻）；</li>
 *   <li><b>供给压力</b>：囤得越多价格越低，且有下界不会变成负数/零；</li>
 *   <li><b>临期判定</b>：距变质 ≤ 提前量才提醒；</li>
 *   <li><b>估值单调性</b>：同一批货越放越便宜。</li>
 * </ol>
 */
class WarehouseEngineTest {

    private static final LocalDate T0 = LocalDate.of(2026, 10, 1);

    @Test
    @DisplayName("仓储期分级：新鲜 → 品质下降 → 已变质")
    void gradeProgression() {
        WarehouseEngine.Storage rice = WarehouseEngine.storage("rice_mid");
        assertEquals(270, rice.storageDays());
        // 刚入库
        assertEquals(WarehouseEngine.Grade.FRESH, WarehouseEngine.grade("rice_mid", T0, T0));
        // 临界点 = 仓储期 60% = 162 天
        assertEquals(WarehouseEngine.Grade.FRESH,
                WarehouseEngine.grade("rice_mid", T0, T0.plusDays(161)));
        assertEquals(WarehouseEngine.Grade.DECAYING,
                WarehouseEngine.grade("rice_mid", T0, T0.plusDays(162)));
        // 超过仓储期 → 变质
        assertEquals(WarehouseEngine.Grade.ROTTEN,
                WarehouseEngine.grade("rice_mid", T0, T0.plusDays(270)));
        assertEquals(WarehouseEngine.Grade.ROTTEN,
                WarehouseEngine.grade("rice_mid", T0, T0.plusDays(400)));
    }

    @Test
    @DisplayName("易腐作物分级更早（草莓 3 天就变质）")
    void perishableSpoilsFaster() {
        WarehouseEngine.Storage s = WarehouseEngine.storage("strawberry");
        assertEquals(3, s.storageDays());
        assertTrue(s.perishable());
        assertEquals(WarehouseEngine.Grade.FRESH, WarehouseEngine.grade("strawberry", T0, T0));
        // 临界点 = 仓储期 50% = 1.5 → 2 天起就降级
        assertEquals(WarehouseEngine.Grade.DECAYING, WarehouseEngine.grade("strawberry", T0, T0.plusDays(2)));
        assertEquals(WarehouseEngine.Grade.ROTTEN, WarehouseEngine.grade("strawberry", T0, T0.plusDays(3)));
    }

    @Test
    @DisplayName("变质的货不可出售")
    void rottenNotSellable() {
        assertFalse(WarehouseEngine.Grade.ROTTEN.sellable());
        assertTrue(WarehouseEngine.Grade.DECAYING.sellable());
        assertTrue(WarehouseEngine.Grade.FRESH.sellable());
        // 品质系数：变质为 0
        assertEquals(0.0, WarehouseEngine.Grade.ROTTEN.factor(), 1e-9);
        assertEquals(1.0, WarehouseEngine.Grade.FRESH.factor(), 1e-9);
    }

    @Test
    @DisplayName("地区价差：主产区便宜、销区贵")
    void zonePriceFactor() {
        double jiangnan = WarehouseEngine.zonePriceFactor("jiangnan", "rice_mid");
        double huanan = WarehouseEngine.zonePriceFactor("huanan", "rice_mid");
        assertTrue(jiangnan < huanan,
                "江南是水稻主产区，价格应低于华南销区：" + jiangnan + " vs " + huanan);
        // 主产区明显低于全国均价
        assertTrue(jiangnan < 0.95, "江南稻米应低于均价：" + jiangnan);
        // 黄淮海是玉米主产区
        assertTrue(WarehouseEngine.zonePriceFactor("huanghuaihai", "corn_spring") < 0.95);
        // 未知区返回 1.0
        assertEquals(1.0, WarehouseEngine.zonePriceFactor("nowhere", "rice_mid"), 1e-9);
    }

    @Test
    @DisplayName("供给压力：死区内不压价，囤得越多压得越狠，有 0.45 下界")
    void supplyPressure() {
        // 死区内（≤150kg）不压价 —— 正常农户的量不该被压价
        assertEquals(1.0, WarehouseEngine.supplyFactor(0), 1e-9);
        assertEquals(1.0, WarehouseEngine.supplyFactor(100), 1e-9);
        assertEquals(1.0, WarehouseEngine.supplyFactor(150), 1e-9);

        // 超过死区后开始压价，且越囤越狠
        double f500 = WarehouseEngine.supplyFactor(500);
        double f1000 = WarehouseEngine.supplyFactor(1000);
        double f2000 = WarehouseEngine.supplyFactor(2000);
        assertTrue(f500 < 1.0, "超过死区就该压价");
        assertTrue(f1000 < f500, "囤得更多压得更狠");
        assertTrue(f2000 < f1000);
        // 曲线要有区分度：2000kg 应比 500kg 明显低，但还没触到下界
        assertTrue(f2000 < 0.80, "2000kg 应压掉两成以上：" + f2000);
        assertTrue(f2000 > 0.45);
        assertTrue(f500 > f2000);

        // 下界：囤到天荒地老也不会跌破 0.45
        assertTrue(WarehouseEngine.supplyFactor(1_000_000) >= 0.45 - 1e-9);
        assertEquals(0.45, WarehouseEngine.supplyFactor(1_000_000), 1e-9);

        // 供给压力说明文案
        assertNull(WarehouseEngine.supplyNote(0), "没库存不该有压价提示");
        assertNull(WarehouseEngine.supplyNote(120), "死区内不该有压价提示");
        assertNotNull(WarehouseEngine.supplyNote(2000));
        assertTrue(WarehouseEngine.supplyNote(2000).contains("压价"));
    }

    @Test
    @DisplayName("临期判定：距变质 ≤ 提前量才提醒")
    void expiringWindow() {
        WarehouseEngine.Storage rice = WarehouseEngine.storage("rice_mid");
        // 30 天提前量
        assertTrue(WarehouseEngine.isExpiring(rice, T0, T0.plusDays(270 - 30)));
        assertTrue(WarehouseEngine.isExpiring(rice, T0, T0.plusDays(270 - 1)));
        assertFalse(WarehouseEngine.isExpiring(rice, T0, T0.plusDays(270 - 31)));
        // 已变质的不再算「临期」（那是过期不是临期）
        assertFalse(WarehouseEngine.isExpiring(rice, T0, T0.plusDays(270)));
    }

    @Test
    @DisplayName("估值单调：同一批货越放越便宜")
    void valuationDecreasesOverTime() {
        double fresh = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0, 0).unitPrice();
        double decaying = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0.plusDays(200), 0).unitPrice();
        double rotten = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0.plusDays(300), 0).unitPrice();
        assertTrue(decaying < fresh, "品质下降后应更便宜");
        assertEquals(0.0, rotten, 1e-9);

        // 总额 = 单价 × 数量
        WarehouseEngine.Valuation v = WarehouseEngine.value("rice_mid", "jiangnan", 200, T0, T0, 0);
        assertEquals(200.0, v.quantity(), 1e-9);
        assertEquals(Math.round(v.unitPrice() * 200 * 10) / 10.0, v.amount(), 0.11);
    }

    @Test
    @DisplayName("已仓储天数不为负（未来日期入库也不算负）")
    void agedDaysNeverNegative() {
        assertEquals(0, WarehouseEngine.agedDays(T0, T0.minusDays(10)));
        assertEquals(10, WarehouseEngine.agedDays(T0, T0.plusDays(10)));
    }

    @Test
    @DisplayName("额外品质折价生效（农残超标 0.85 折）")
    void extraQualityFactor() {
        double normal = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0, 0).unitPrice();
        double residue = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0, 0, 0.85).unitPrice();
        assertTrue(residue < normal, "农残超标应卖得更便宜");
        assertEquals(Math.round(normal * 0.85 * 1000) / 1000.0, residue, 0.002);
    }

    @Test
    @DisplayName("🔴 折价系数 0 = 该批货一文不值（不是「没传参」）")
    void zeroQualityFactorMeansWorthless() {
        // 🔴 2026-10-26 修复轻级问题：原实现是
        //   extraFactor <= 0 ? 1.0 : extraFactor
        // 把 0 当成「没传」→ 该归零的批次被按原价卖出，玩家白赚。
        // 现在：0 = 价值归零；只有负数（脏数据）才退回 1.0。
        double normal = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0, 0).unitPrice();
        double zero = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0, 0, 0).unitPrice();
        assertEquals(0.0, zero, 1e-9, "折价系数 0 应让这批货单价归零");

        // 负数才是脏数据 → 兜底按 1.0 处理
        double negative = WarehouseEngine.value("rice_mid", "jiangnan", 100, T0, T0, 0, -1).unitPrice();
        assertEquals(normal, negative, 1e-9, "负数折价是脏数据，应退回 1.0");
    }

    @Test
    @DisplayName("🔴 刚入仓的货必须是新鲜的（回归：口径打架导致秒变已变质）")
    void justStoredIsFresh() {
        // 这是 2026-10-22 实测踩到的真 bug：
        // 入库日期用地块的世界日期、老化基准用用户级的最靠后日期，
        // 两个口径不一致 → 刚收的番茄直接显示「已变质」。
        // 修法：入库与老化必须同一条时间轴（都用用户级世界日期）。
        // 这里模拟「今天入库」的场景：storedDate == today。
        for (String crop : new String[]{"tomato", "cabbage", "strawberry", "rice_mid", "tulip"}) {
            WarehouseEngine.Grade g = WarehouseEngine.grade(crop, T0, T0);
            assertEquals(WarehouseEngine.Grade.FRESH, g,
                    crop + " 刚入库就该是新鲜的");
            assertTrue(WarehouseEngine.value(crop, "jiangnan", 100, T0, T0, 0).unitPrice() > 0,
                    crop + " 刚入库单价不该为 0");
        }
    }

    @Test
    @DisplayName("仓储天数按世界日期算，不是真实日期")
    void agingUsesWorldDate() {
        // 演示模式下世界日期远超前，仓储天数必须跟着世界日期走
        LocalDate worldNow = T0.plusDays(100);
        assertEquals(100, WarehouseEngine.agedDays(T0, worldNow));
        // 番茄仓储期 14 天，世界日期过了 14 天就变质
        assertEquals(WarehouseEngine.Grade.ROTTEN,
                WarehouseEngine.grade("tomato", T0, worldNow));
    }
}
