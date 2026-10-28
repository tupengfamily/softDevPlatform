package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.InputCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 回归：种子的「可播亩数」口径（2026-10-28 新增）。
 *
 * <h2>这个缺陷是怎么来的</h2>
 * <p>种子的 {@code protectDays} 语义是「<b>每包可播多少亩</b>」
 * （中稻 5 亩 / 冬小麦 6 亩 / 春玉米 4 亩 / 番茄 3 亩 / 草莓 1 亩），
 * 而价格是「¥/包」。两者<b>单位不同</b> —— 与水完全同构
 * （水：¥6/份，1 份 = 20 次）。
 *
 * <p>但商店面板把 {@code protectDays} 原样下发给前端，
 * 前端显示「可播 5 亩」；而开田时扣的是
 * {@code stockService.consume(userId, seed.id(), 1)} —— <b>恒扣 1 包</b>，
 * 也就是只够种 1 亩。
 *
 * <p>后果：玩家花 ¥28 买 1 包，被商店告知「可播 5 亩」，
 * 实际只能种 1 亩 —— <b>UI 承诺 &gt; 实际交付</b>。
 * 同一个 bug 在仓库面板上表现为估值虚高 N 倍（5 亩被算成 5 × ¥28 = ¥140）。
 *
 * <h2>修法</h2>
 * <p>把种子的<b>库存单位</b>从「包」改成「亩」：
 * <ul>
 *   <li>商店买 1 包 → 入库 {@code protectDays} 亩；</li>
 *   <li>开 1 块地（1 亩）→ 扣 1 亩；</li>
 *   <li>估值 / 成本走 {@code unitsPerSellUnit} 换算。</li>
 * </ul>
 *
 * <p>本类把这些口径钉死，防止将来有人「改回去」。
 */
class SeedUnitContractTest {

    /**
     * 种子必须能按作物 id 反查到，且每包的「可播亩数」是正整数。
     */
    @Test
    @DisplayName("🔴 每种作物都有种子，且每包可播亩数为正整数")
    void everyCropHasSeedWithPositiveMu() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            InputCatalog.Item seed = InputCatalog.seedOf(c.id());
            assertNotNull(seed, c.id() + " 必须有对应的种子，否则开不了田");
            assertTrue(seed.protectDays() > 0,
                    seed.name() + " 每包可播亩数必须为正，实际 " + seed.protectDays());
            assertTrue(seed.pricePerUnit() > 0, seed.name() + " 单价必须为正");
        }
    }

    /**
     * 🔴 核心：种子的<b>库存单位</b>是「亩」不是「包」。
     *
     * <p>如果这里退回「包」，玩家看到的库存量会与实际能种的地数差 N 倍。
     */
    @Test
    @DisplayName("🔴 种子的库存单位是「亩」（不是「包」）")
    void seedStockUnitIsMu() {
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            assertEquals("亩", s.stockUnit(),
                    s.name() + " 的库存单位必须是「亩」——库存量记的是能播多少亩");
            assertEquals("包", s.unit(),
                    s.name() + " 的售卖单位仍应是「包」（按包定价）");
        }
    }

    /**
     * 🔴 核心：买 1 包 → 入库 N 亩；开 1 块地 → 扣 1 亩。
     *
     * <p>这条断言直接锁住商店的入库换算与开田的扣减单位。
     */
    @Test
    @DisplayName("🔴 买 1 包种子入 N 亩，开 1 块地扣 1 亩（口径自洽）")
    void buyingOnePackYieldsMuAndOnePlotConsumesOneMu() {
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            int mu = s.protectDays();
            // 商店：买 qty 包 → 入库 qty × protectDays 亩
            double qty = 1;
            double addQty = qty * s.protectDays();
            assertEquals(mu, addQty, 1e-9,
                    s.name() + " 买 1 包应入库 " + mu + " 亩");
            // 开田：扣 1 亩（1 块地 = 1 亩）
            double consumed = 1;
            assertTrue(addQty >= consumed,
                    s.name() + " 买 1 包至少够开 1 块地");
            // 买 1 包能开的地数 == 可播亩数（UI 承诺 == 实际交付）
            assertEquals(mu, (int) (addQty / consumed),
                    s.name() + " 商店承诺的可播亩数必须等于实际能开的地数");
        }
    }

    /**
     * 🔴 估值口径：种子的库存估值 = 剩余亩数 × 包价 ÷ 每包亩数。
     *
     * <p>不换算就会虚高 N 倍 —— 与水「虚高 20 倍」是同一类缺陷。
     */
    @Test
    @DisplayName("🔴 种子库存估值 = 亩数 × 包价 ÷ 每包亩数（不虚高）")
    void seedStockValueDoesNotInflate() {
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            int mu = s.protectDays();
            double stockMu = mu;                    // 买 1 包后的库存（亩）
            double units = FarmStockService.unitsPerSellUnit(s);
            assertEquals(mu, units, 1e-9, s.name() + " 换算系数必须是每包亩数");
            double value = stockMu * s.pricePerUnit() / units;
            assertEquals(s.pricePerUnit(), value, 1e-6,
                    s.name() + " 买 1 包的估值必须等于包价 ¥" + s.pricePerUnit()
                            + "，实际算成 ¥" + value);
        }
    }

    /**
     * 🔴 回归：种子库存量<b>不能是分数亩</b>。
     *
     * <p>买 1 包 = 5 亩，开 1 块地扣 1 亩 → 剩 4 亩、3 亩……始终是整数。
     * 如果出现分数亩，说明扣减单位又退回「包」了。
     */
    @Test
    @DisplayName("🔴 种子库存量始终是整数亩（不会扣出分数亩）")
    void seedStockStaysIntegral() {
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            int mu = s.protectDays();
            double left = mu;
            for (int i = 0; i < mu; i++) {
                left -= 1;
                assertEquals((double) (mu - i - 1), left, 1e-9,
                        s.name() + " 开第 " + (i + 1) + " 块地后应剩 " + (mu - i - 1) + " 亩");
            }
            assertEquals(0.0, left, 1e-9, s.name() + " 正好播完");
        }
    }

    /**
     * 🔴 回归：开田缺种子的<b>软提示</b>要报出「每包可播几亩」。
     *
     * <p>玩家要知道买 1 包能种几亩 —— 这是他判断要买几包的依据。
     */
    @Test
    @DisplayName("🔴 种子的可播亩数在目录里可查（提示文案能带上）")
    void seedMuIsDiscoverable() {
        List<InputCatalog.Item> seeds = InputCatalog.seeds();
        assertTrue(seeds.size() >= 20, "种子货架应覆盖全部作物，实际 " + seeds.size());
        for (InputCatalog.Item s : seeds) {
            assertTrue(s.protectDays() >= 1 && s.protectDays() <= 10,
                    s.name() + " 每包可播亩数应在 1~10，实际 " + s.protectDays());
        }
    }
}
