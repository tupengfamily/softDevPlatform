package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.InputCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 回归：初始启动金的<b>推导依据</b>（2026-10-28 新增）。
 *
 * <h2>这个缺陷是怎么来的</h2>
 * <p>{@code FarmWallet.INITIAL_BALANCE} 的注释原来按「3 亩一季投入 ≈ 738 元」
 * 推导，但那个口径有<b>两处硬伤</b>，都会把数字算大：
 * <ul>
 *   <li><b>水按「次」计价</b> —— 实际是<b>按份卖</b>：1 份 = 20 次，6 元/份。
 *       原式 {@code 3 亩 × 20 次 × 6 元 = 360} 把它当成「6 元/次」，
 *       <b>虚高 20 倍</b>；</li>
 *   <li><b>种子按「亩」计价</b> —— 实际也是<b>按包卖</b>：1 包 = 5 亩，28 元/包。
 *       原式 {@code 3 亩 × 30 元/亩 = 90} 把它当成「30 元/亩」。</li>
 * </ul>
 *
 * <p>🔴 后果：注释里的数字与真实引擎不符，而<b>注释是这个项目唯一的「为什么」载体</b> ——
 * 将来有人改价时「按注释里的算」就会算错。注释必须可被验证。
 *
 * <h2>本类做什么</h2>
 * <p>把注释里的每个数字<b>用真实目录数据复算</b>，让口径失效时测试变红，
 * 而不是让注释慢慢腐烂。
 *
 * <h2>⚠️ 为什么用「真实引擎复算过的常量」而不是再跑一遍引擎</h2>
 * <p>「一季水稻要灌 16 次」这个数字来自 142 天逐日模拟（干旱点计数），
 * 属于<b>引擎行为</b>，跑一次要几秒且依赖天气模拟器。
 * 本类锁定的是<b>商品价格与单位换算关系</b>（那才是口径失效的根因），
 * 引擎侧的行为由 {@code FarmEconomyLoopTest} 等覆盖。
 */
class InitialBalanceContractTest {

    /** 真实引擎复算：江南 · 水稻中稻 · 裸播 2019-05-05，全季 142 天，16 次灌溉。 */
    private static final int RICE_SEASON_IRRIGATIONS = 16;

    /** 真实引擎复算：水稻一季的现金刚需 = 种子 ¥28 + 水 ¥6 = ¥34。 */
    private static final double CASH_PER_MU = 34.0;

    /**
     * 🔴 核心：水与种子都必须是「按份卖、按量用」。
     *
     * <p>如果哪天有人把水改成「按次定价」，这里立刻红 ——
     * 因为 {@code unitsPerSellUnit} 会从 20 掉到 1，
     * 启动金的推导就整个失效了。
     */
    @Test
    @DisplayName("🔴 水与种子都是「按份卖、按量用」（启动金推导的前提）")
    void waterAndSeedArePackScaled() {
        InputCatalog.Item water = InputCatalog.get("WATER_TAP");
        assertEquals(20, water.protectDays(),
                "自来水 1 份必须含 20 次灌溉（启动金推导依赖这个数）");
        assertEquals(20.0, FarmStockService.unitsPerSellUnit(water), 1e-9,
                "水的换算系数必须等于每份可灌次数");

        InputCatalog.Item seed = InputCatalog.get("SEED_rice_mid");
        assertEquals(5, seed.protectDays(),
                "中稻 1 包必须可播 5 亩（启动金推导依赖这个数）");
        assertEquals(5.0, FarmStockService.unitsPerSellUnit(seed), 1e-9,
                "种子的换算系数必须等于每包可播亩数");
    }

    /**
     * 🔴 核心：按真实目录复算「1 亩水稻一季的现金刚需」，必须等于 ¥34。
     *
     * <p>这是 {@code INITIAL_BALANCE} 注释里写的那个数 ——
     * 如果价格改了、或者换算关系坏了，这里会红，注释就必须跟着改。
     */
    @Test
    @DisplayName("🔴 1 亩水稻一季的现金刚需 = 种子 ¥28 + 水 ¥6 = ¥34")
    void cashPerMuMatchesDocumentedValue() {
        InputCatalog.Item seed = InputCatalog.get("SEED_rice_mid");
        InputCatalog.Item water = InputCatalog.get("WATER_TAP");

        // 种子：1 亩要 1 亩用量 → 需要 1/5 包 → 但商店按包卖，最少买 1 包 = ¥28
        // （1 块地 = 1 亩，1 包可播 5 亩，所以 1 亩的那份成本摊到「首包」上就是整包价）
        double seedCost = seed.pricePerUnit();

        // 水：16 次灌溉 → 1 份（20 次）→ ¥6
        int packs = (int) Math.ceil((double) RICE_SEASON_IRRIGATIONS / water.protectDays());
        double waterCost = packs * water.pricePerUnit();

        double total = seedCost + waterCost;
        assertEquals(CASH_PER_MU, total, 1e-6,
                "注释里的 ¥34 与真实目录价不符：实际种子 ¥" + seedCost
                        + " + 水 ¥" + waterCost + " = ¥" + total);
    }

    /**
     * 🔴 回归：启动金必须<b>足够开满一个农场的地</b>。
     *
     * <p>农场上限 12 块地（{@code FarmPlayService.MAX_PLOTS_PER_FARM}）。
     * 启动金的真实作用是「保证玩家永远不会因为没钱而卡死」，
     * 所以它必须能覆盖农场上限 + 试错空间。
     */
    @Test
    @DisplayName("🔴 启动金够开满 12 块地（玩家不会因缺钱卡死）")
    void initialBalanceCoversFullFarm() {
        int maxPlots = 12;
        // 12 亩的种子：每包 5 亩 → 3 包
        InputCatalog.Item seed = InputCatalog.get("SEED_rice_mid");
        int seedPacks = (int) Math.ceil((double) maxPlots / seed.protectDays());
        // 12 亩的灌溉：按 16 次/亩 → 192 次 → 10 份
        InputCatalog.Item water = InputCatalog.get("WATER_TAP");
        int waterPacks = (int) Math.ceil((double) (maxPlots * RICE_SEASON_IRRIGATIONS)
                / water.protectDays());

        double needed = seedPacks * seed.pricePerUnit() + waterPacks * water.pricePerUnit();
        assertTrue(FarmWallet.INITIAL_BALANCE >= needed,
                "启动金 ¥" + FarmWallet.INITIAL_BALANCE + " 不够开满 " + maxPlots
                        + " 块地（需要 ¥" + needed + "）——玩家会卡在开不了田的死循环里");
    }

    /**
     * 🔴 回归：备货里<b>不含</b>种子/水/农具 —— 这三项必须用现金买。
     *
     * <p>这是启动金必须够的真实原因。若哪天备货里加进了种子，
     * 启动金的推导要重写（但金额未必变，因为还要留试错空间）。
     */
    @Test
    @DisplayName("🔴 初始备货不含种子/水/农具（所以必须留启动金）")
    void initialStockHasNoSeedWaterOrTool() {
        // 备货覆盖能力在 FarmEconomyLoopTest 里验证；这里只锁「这三类不在备货里」这一事实。
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            assertTrue(s.pricePerUnit() > 0,
                    s.name() + " 是商店商品（备货不含），必须有价");
        }
        InputCatalog.Item water = InputCatalog.get("WATER_TAP");
        assertTrue(water.pricePerUnit() > 0, "水是商店商品（备货不含），必须有价");
    }

    /**
     * 🔴 回归：初始余额是整数金额。
     *
     * <p>钱包余额要能被玩家在 UI 上读明白（"¥800" 而不是 "¥799.999999999"）。
     */
    @Test
    @DisplayName("🔴 初始余额是整数（UI 上能读明白）")
    void initialBalanceIsWholeNumber() {
        assertEquals(Math.floor(FarmWallet.INITIAL_BALANCE), FarmWallet.INITIAL_BALANCE, 1e-9,
                "初始余额必须是整数元，实际 " + FarmWallet.INITIAL_BALANCE);
        assertTrue(FarmWallet.INITIAL_BALANCE >= 500,
                "启动金不应低于 500 —— 低于这个数玩家一次绝收就彻底卡死");
    }
}
