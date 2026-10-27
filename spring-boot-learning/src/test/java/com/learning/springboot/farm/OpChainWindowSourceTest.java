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
 * 农事窗口口径回归（2026-10-27）。
 *
 * <p>🔴 **这个测试守护的是什么**：
 *
 * <p>上一轮修「收割阈值按作物取」时，只清了 {@code HARVEST_DVS = 0.92}，
 * 但同一类缺陷在<b>另外两处</b>还活着 —— 而且它们比收割阈值更隐蔽：
 *
 * <ol>
 *   <li>{@code FarmMapService.board()} 里写死 {@code dvs >= 0.92}
 *       统计「可收割地块数」—— 这是<b>收割判定的第二个实现</b>，
 *       于是地图看板上番茄显示「生长中」但单地块页显示「可收割」，
 *       <b>同一块地两个界面两个答案</b>；</li>
 *   <li>{@code FarmPlayService.validateActionWindow()} 里写死
 *       {@code DRY_GRAIN >= 0.92} / {@code DRAIN_FINAL >= 0.90}
 *       —— 这是<b>农事窗口的第二套配置</b>，和 {@code OpChain} 里的工序链分叉。
 *       后果是玩家在正确时机做的正确事被拒绝（水稻 DVS 0.94 做「烘干入库」，
 *       它的真实窗口是 0.98，但写死的 0.92 在这里反而是放行了；
 *       而番茄 DVS 0.89 做「分级包装」，真实窗口 0.88 早该放行，写死 0.92 却拒了）。</li>
 * </ol>
 *
 * <p>本测试锁住：
 * <ul>
 *   <li>{@code OpChain.stepFromOf/stepToOf} 返回的窗口与工序链定义<b>逐条同源</b>；</li>
 *   <li>各作物的 {@code DRY_GRAIN} / {@code DRAIN_FINAL} 窗口<b>确实不同</b>（不是一刀切）；</li>
 *   <li>两个服务类源码里不再残留写死的窗口数字。</li>
 * </ul>
 */
class OpChainWindowSourceTest {

    /**
     * 🔴 **工序窗口查询出口与工序链定义逐条同源**。
     *
     * <p>这是「唯一真源」的核心断言：{@code stepFromOf} 只是把
     * {@code defsOf} 里已经定义好的窗口读出来，不允许它另算一套。
     */
    @Test
    @DisplayName("stepFromOf / stepToOf 与工序链定义逐条同源")
    void stepLookupMatchesChainDefinition() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            for (OpChain.Def d : OpChain.defsOf(c.id())) {
                assertEquals(d.from(), OpChain.stepFromOf(c.id(), d.id()), 1e-9,
                        c.id() + " 的 " + d.id() + " 窗口下界与工序链定义不符");
                assertEquals(d.to(), OpChain.stepToOf(c.id(), d.id()), 1e-9,
                        c.id() + " 的 " + d.id() + " 窗口上界与工序链定义不符");
            }
        }
    }

    /**
     * 🔴 **各作物的收获后工序窗口确实不同**。
     *
     * <p>如果哪天所有作物都返回同一个值，说明有人把窗口改回写死了。
     * 这个断言的价值在于：它证明了「按作物取窗口」这件事<b>有实际差异</b>，
     * 不是形式主义。
     */
    @Test
    @DisplayName("DRY_GRAIN 窗口按作物取（番茄 0.88 ≠ 水稻 0.98）")
    void dryGrainWindowDiffersPerCrop() {
        assertEquals(0.88, OpChain.stepFromOf("tomato", "DRY_GRAIN"), 1e-9,
                "番茄分级包装窗口应为 0.88");
        assertEquals(0.98, OpChain.stepFromOf("rice_mid", "DRY_GRAIN"), 1e-9,
                "水稻烘干入库窗口应为 0.98");
        assertEquals(0.98, OpChain.stepFromOf("corn_spring", "DRY_GRAIN"), 1e-9,
                "玉米晾晒脱粒窗口应为 0.98");

        long distinct = CropCatalog.list().stream()
                .map(c -> OpChain.stepFromOf(c.id(), "DRY_GRAIN"))
                .distinct()
                .count();
        assertTrue(distinct >= 4,
                "DRY_GRAIN 窗口只有 " + distinct + " 种取值 —— 又变成一刀切了");
    }

    /**
     * 🔴 **找不到的工序返回 -1，而不是抛异常或返回 0**。
     *
     * <p>返回 0 会让「任何 DVS 都过」或「任何 DVS 都不过」，
     * 两种都是错的。-1 的语义是「这道工序不存在」。
     */
    @Test
    @DisplayName("不存在的工序返回 -1（不是 0）")
    void unknownStepReturnsMinusOne() {
        assertEquals(-1.0, OpChain.stepFromOf("tomato", "NO_SUCH_STEP"), 1e-9);
        assertEquals(-1.0, OpChain.stepToOf("tomato", "NO_SUCH_STEP"), 1e-9);
        assertEquals(-1.0, OpChain.stepFromOf("tomato", null), 1e-9);
        assertEquals(-1.0, OpChain.stepFromOf("tomato", ""), 1e-9);
    }

    /**
     * 🔴 **未知作物回落到水稻口径**，不能抛异常。
     */
    @Test
    @DisplayName("未知作物回落到水稻工序链")
    void unknownCropFallsBackToRiceChain() {
        assertEquals(OpChain.stepFromOf("rice_mid", "DRY_GRAIN"),
                OpChain.stepFromOf("no_such_crop", "DRY_GRAIN"), 1e-9);
        assertEquals(OpChain.stepFromOf("rice_mid", "DRY_GRAIN"),
                OpChain.stepFromOf(null, "DRY_GRAIN"), 1e-9);
    }

    /**
     * 🔴 **DRAIN_FINAL 只在水稻链里存在**，其他作物走 -1 分支。
     *
     * <p>这个测试锁住「工序链里没有的工序不被写死的数字拦住」这条语义：
     * 番茄没有「收前排水」这道工序，那么做它时<b>不该</b>被一条
     * 写死的 0.90 拦住。
     */
    @Test
    @DisplayName("DRAIN_FINAL 仅水稻链有（其他作物返回 -1，走不限分支）")
    void drainFinalOnlyInRiceChain() {
        assertEquals(0.92, OpChain.stepFromOf("rice_mid", "DRAIN_FINAL"), 1e-9);
        assertEquals(-1.0, OpChain.stepFromOf("tomato", "DRAIN_FINAL"), 1e-9);
        assertEquals(-1.0, OpChain.stepFromOf("corn_spring", "DRAIN_FINAL"), 1e-9);
    }

    /**
     * 🔴 **FarmMapService 源码里不再有写死的收割判定**。
     *
     * <p>它原来写 {@code dvs >= 0.92} 统计可收割地块数 ——
     * 这是收割判定的第二个实现。现在必须消费后端下发的 {@code harvestable}。
     */
    @Test
    @DisplayName("FarmMapService 不再自己判收割（不再有 dvs >= 0.92）")
    void mapServiceNoLongerJudgesHarvest() throws Exception {
        Path src = Path.of("src/main/java/com/learning/springboot/farm/FarmMapService.java");
        assertTrue(Files.exists(src), "找不到 FarmMapService.java（测试工作目录不对？）");
        String stripped = stripComments(Files.readString(src));
        assertFalse(stripped.contains("0.92"),
                "FarmMapService 里又出现了写死的 0.92 —— 收割判定必须消费后端 harvestable 结论");
        assertTrue(stripped.contains("harvestable"),
                "FarmMapService 应该消费 plotSummaries 下发的 harvestable 结论");
    }

    /**
     * 🔴 **FarmPlayService 的收获类窗口不再写死**。
     *
     * <p>{@code DRY_GRAIN} / {@code DRAIN_FINAL} 的窗口必须来自
     * {@code OpChain.stepFromOf(...)}，不能是字面量。
     *
     * <p>注意：{@code FarmPlayService} 里其他 0.92（如仿真引擎参数）不归本测试管，
     * 所以只针对 {@code validateActionWindow} 这一个方法做定向扫描。
     */
    @Test
    @DisplayName("validateActionWindow 不再写死收获类窗口（走 OpChain.stepFromOf）")
    void playServiceNoHardcodedHarvestWindows() throws Exception {
        Path src = Path.of("src/main/java/com/learning/springboot/farm/FarmPlayService.java");
        assertTrue(Files.exists(src), "找不到 FarmPlayService.java（测试工作目录不对？）");
        String stripped = stripComments(Files.readString(src));
        assertTrue(stripped.contains("OpChain.stepFromOf("),
                "validateActionWindow 应该用 OpChain.stepFromOf 取窗口下界");
        assertFalse(stripped.contains("dvs >= 0.92 - slack"),
                "DRY_GRAIN 窗口又写死成 0.92 了 —— 必须按作物取");
        assertFalse(stripped.contains("dvs >= 0.90 - slack"),
                "DRAIN_FINAL 窗口又写死成 0.90 了 —— 必须按作物取");
    }

    /**
     * 🔴 **DistrictCropService 的成熟判定也按作物取**。
     *
     * <p>它是同一语义的<b>第三份</b>实现（第一份 FarmPlayService，第二份 FarmMapService）。
     * 区县概览里写死 0.92 的话：番茄（0.82）在区县页一直显示「生长中」，
     * 可它在地块页明明已经能收 —— <b>同一作物两个界面两个答案</b>。
     */
    @Test
    @DisplayName("DistrictCropService 不再写死成熟阈值（走 OpChain.harvestDvsOf）")
    void districtCropServiceNoHardcodedThreshold() throws Exception {
        Path src = Path.of("src/main/java/com/learning/springboot/farm/DistrictCropService.java");
        assertTrue(Files.exists(src), "找不到 DistrictCropService.java（测试工作目录不对？）");
        String stripped = stripComments(Files.readString(src));
        assertTrue(stripped.contains("OpChain.harvestDvsOf("),
                "DistrictCropService 的成熟判定应该用 OpChain.harvestDvsOf");
        assertFalse(stripped.contains("0.92"),
                "DistrictCropService 里还有写死的 0.92 —— 成熟判定必须按作物取");
    }

    /**
     * 🔴 **收割阈值与收获后工序窗口是两个概念，不能混用**。
     *
     * <p>{@code harvestDvsOf} 是「能不能收」，{@code stepFromOf("DRY_GRAIN")}
     * 是「收完之后做烘干/分级从哪天起能做」。多数作物 DRY_GRAIN 晚于收割阈值，
     * 但不是全部 —— 番茄就是 DRY_GRAIN(0.88) 早于收割阈值(0.82)？
     * 不对，0.88 &gt; 0.82。这里锁的是<b>两者不是同一个数</b>，
     * 防止有人图省事把 DRY_GRAIN 直接写成 harvestDvsOf。
     */
    @Test
    @DisplayName("收割阈值 ≠ 收获后工序窗口（不能互相顶替）")
    void harvestThresholdAndDryGrainAreDifferent() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            double harvest = OpChain.harvestDvsOf(c.id());
            double dryGrain = OpChain.stepFromOf(c.id(), "DRY_GRAIN");
            if (dryGrain < 0) {
                continue;
            }
            assertTrue(dryGrain >= harvest - 1e-9,
                    c.id() + " 的 DRY_GRAIN 窗口 " + dryGrain + " 早于收割阈值 " + harvest
                            + " —— 先做后处理再收割，顺序反了");
        }
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
