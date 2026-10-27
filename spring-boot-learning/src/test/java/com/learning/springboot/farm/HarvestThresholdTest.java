package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收割阈值口径回归（2026-10-27）。
 *
 * <p>🔴 **这个测试守护的是什么**：
 *
 * <p>{@code FarmPlayService} 里原先有一个写死的 {@code HARVEST_DVS = 0.92}，
 * 被 <b>9 处</b>引用。0.92 是<b>水稻</b>的口径，而各作物的收割阈值
 * 本来就不同（取自 {@code OpChain} 的 {@code HARVEST} 步骤下界）：
 *
 * <pre>
 *   番茄 0.82 · 大白菜 0.85 · 瓜类 0.88 · 梨/马铃薯/谷子 0.90
 *   大豆 0.90 · 水稻 0.92 · 小麦 0.92 · 玉米 0.95
 * </pre>
 *
 * <p>写死 0.92 的后果是 <b>21 种作物全判错，而且往两个相反方向错</b>：
 * <ul>
 *   <li>番茄（0.82）：DVS 0.82~0.92 本该可收割，却被判「还没到黄熟期」→ 玩家白等十天；</li>
 *   <li>玉米（0.95）：DVS 0.92~0.95 被判「可以收割」，但此时尚未完熟 → 收了一茬不熟的玉米。</li>
 * </ul>
 *
 * <p>修法：阈值一律走 {@code OpChain.harvestDvsOf(cropId)}，工序链是唯一真源。
 * 本测试锁住三件事：
 * <ol>
 *   <li>各作物阈值确实按作物取（不是 0.92 一刀切）；</li>
 *   <li>阈值与该作物工序链里 {@code HARVEST} 步骤严格同源；</li>
 *   <li>源码里不再残留写死的 {@code HARVEST_DVS} 常量。</li>
 * </ol>
 */
class HarvestThresholdTest {

    /**
     * 🔴 **各作物的阈值快照**。
     *
     * <p>这些数字来自「工序链 HARVEST 下界」与「物候成熟段起点」的较大者。
     * 快照的作用是：一旦有人改了工序窗口或物候切点而没意识到
     * 「收割阈值跟着变了」，本测试会失败 —— 逼他确认这是不是有意的。
     */
    @Test
    @DisplayName("收割阈值按作物取：番茄 0.82 / 白菜 0.85 / 玉米 0.95，各不相同")
    void thresholdsDifferPerCrop() {
        assertEquals(0.82, OpChain.harvestDvsOf("tomato"), 1e-9, "番茄应为 0.82");
        assertEquals(0.85, OpChain.harvestDvsOf("cabbage"), 1e-9, "大白菜应为 0.85");
        assertEquals(0.95, OpChain.harvestDvsOf("corn_spring"), 1e-9, "玉米应为 0.95");
        assertEquals(0.90, OpChain.harvestDvsOf("soybean"), 1e-9, "大豆应为 0.90");
        assertEquals(0.92, OpChain.harvestDvsOf("rice_mid"), 1e-9, "水稻应为 0.92");
        assertEquals(0.92, OpChain.harvestDvsOf("wheat_winter"), 1e-9, "冬小麦应为 0.92");
    }

    /**
     * 🔴 **工序窗口早于物候成熟段时，取物候成熟段**。
     *
     * <p>2026-10-27 实测发现：园艺作物按栽培型共用工序链，但物候切点不同，
     * 于是 6 种作物的工序窗口早于自己的成熟段：
     * <pre>
     *   辣椒/茄子  工序 0.82  vs  成熟段 0.85
     *   豆角/萝卜  工序 0.85  vs  成熟段 0.88
     *   葡萄/向日葵 工序 0.88  vs  成熟段 0.90
     * </pre>
     * 若按工序口径走，玩家在 DVS 0.83 就能收辣椒 —— 但此时界面上的物候阶段
     * 还写着「果实膨大」，同时又说「可收割」，<b>自相矛盾</b>。
     *
     * <p>生物学上没熟的作物不该能收，所以取两者较大值。
     */
    @Test
    @DisplayName("工序窗口早于物候成熟段时取成熟段（辣椒/茄子 0.85、豆角/萝卜 0.88）")
    void takesLaterOfOpWindowAndPhenologyRipe() {
        // 这 6 种作物的工序链 HARVEST 下界确实早于自己的成熟段
        assertEquals(0.85, OpChain.harvestDvsOf("chili"), 1e-9, "辣椒应取物候成熟段 0.85");
        assertEquals(0.85, OpChain.harvestDvsOf("eggplant"), 1e-9, "茄子应取物候成熟段 0.85");
        assertEquals(0.88, OpChain.harvestDvsOf("yardlong_bean"), 1e-9, "豆角应取物候成熟段 0.88");
        assertEquals(0.88, OpChain.harvestDvsOf("radish"), 1e-9, "萝卜应取物候成熟段 0.88");
        assertEquals(0.90, OpChain.harvestDvsOf("grape"), 1e-9, "葡萄应取物候成熟段 0.90");
        assertEquals(0.90, OpChain.harvestDvsOf("sunflower"), 1e-9, "向日葵应取物候成熟段 0.90");
    }

    /**
     * 🔴 **绝不能再有一刀切的 0.92**。
     *
     * <p>22 种作物里只有水稻与小麦是 0.92 —— 其余全部不同。
     * 如果哪天所有作物都返回同一个值，说明有人把阈值改回写死了。
     */
    @Test
    @DisplayName("22 种作物里大多数的阈值不是 0.92（一刀切即失败）")
    void notAllCropsShareRiceThreshold() {
        List<CropCatalog.Crop> crops = CropCatalog.list();
        assertTrue(crops.size() >= 20, "作物数异常：" + crops.size());
        long riceLike = crops.stream()
                .filter(c -> Math.abs(OpChain.harvestDvsOf(c.id()) - 0.92) < 1e-9)
                .count();
        assertTrue(riceLike <= 4,
                "有 " + riceLike + " 种作物共用 0.92 —— 阈值又变成一刀切了");
    }

    /**
     * 🔴 **阈值必须落在该作物的物候段内**。
     *
     * <p>如果阈值落在最后一段之外（比如 > 末段 hi），那么「到阈值」这个时刻
     * 在物候上根本不存在 —— 玩家永远等不到。
     * 反过来，阈值也<b>不能早于</b>成熟段起点 —— 那意味着「还没熟就能收」。
     */
    @Test
    @DisplayName("阈值落在该作物最后一个物候段内（不是不可能达到的数）")
    void thresholdInsideLastPhenologyStage() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            List<com.learning.springboot.farm.engine.CropConfig.PhenoStage> stages =
                    c.config().phenology();
            double lastLo = stages.get(stages.size() - 1).lo();
            double lastHi = stages.get(stages.size() - 1).hi();
            double thr = OpChain.harvestDvsOf(c.id());
            assertTrue(thr >= lastLo - 1e-9 && thr < lastHi,
                    c.id() + " 的收割阈值 " + thr + " 落在末段 [" + lastLo + ", " + lastHi + ") 之外");
        }
    }

    /**
     * 🔴 **阈值必须不早于工序链的 {@code HARVEST} 下界**。
     *
     * <p>这是「取较大者」规则的另一半：阈值要么等于工序窗口下界，
     * 要么因为物候成熟段更晚而更大 —— 但<b>绝不会更早</b>。
     * 如果哪天它比工序窗口还早，说明有人把「较小者」写回去了。
     */
    @Test
    @DisplayName("阈值不早于该作物工序链 HARVEST 下界")
    void thresholdNotEarlierThanOpHarvestWindow() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            double fromChain = OpChain.defsOf(c.id()).stream()
                    .filter(d -> "HARVEST".equals(d.id()))
                    .mapToDouble(OpChain.Def::from)
                    .findFirst()
                    .orElse(-1.0);
            assertTrue(fromChain >= 0,
                    c.id() + " 的工序链里没有 HARVEST 步骤 —— 阈值无从谈起");
            assertTrue(OpChain.harvestDvsOf(c.id()) >= fromChain - 1e-9,
                    c.id() + " 的收割阈值 " + OpChain.harvestDvsOf(c.id())
                            + " 早于工序链 HARVEST 下界 " + fromChain);
        }
    }

    /**
     * 🔴 **未知作物回落到水稻口径，而不是抛异常或返回 0**。
     *
     * <p>返回 0 会让「任何地块都能收」，比写死阈值更危险。
     */
    @Test
    @DisplayName("未知 / null 作物回落到水稻口径 0.92")
    void unknownCropFallsBackToRice() {
        assertEquals(0.92, OpChain.harvestDvsOf(null), 1e-9);
        assertEquals(0.92, OpChain.harvestDvsOf("no_such_crop"), 1e-9);
        assertEquals(0.92, OpChain.harvestDvsOf(""), 1e-9);
    }

    /**
     * 🔴 **源码里不再残留写死的阈值常量**。
     *
     * <p>防止有人把 {@code HARVEST_DVS = 0.92} 加回来。
     * 扫描前先剥掉注释 —— 否则解释性注释里的 {@code HARVEST_DVS} 会造成假红。
     */
    @Test
    @DisplayName("FarmPlayService 源码中不再有 HARVEST_DVS 常量")
    void noHardcodedConstantLeftInSource() throws Exception {
        Path src = Path.of("src/main/java/com/learning/springboot/farm/FarmPlayService.java");
        assertTrue(Files.exists(src), "找不到 FarmPlayService.java（测试工作目录不对？）");
        String text = Files.readString(src);
        String stripped = stripComments(text);
        assertFalse(stripped.contains("HARVEST_DVS"),
                "FarmPlayService 里又出现了写死的收割阈值常量 —— 阈值必须走 OpChain.harvestDvsOf()");
    }

    /** 剥掉行注释与块注释，避免注释里的类名/方法名造成假红。 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inBlock = false;
        boolean inLine = false;
        boolean inStr = false;
        boolean inChar = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(s.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(s.charAt(++i));
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
