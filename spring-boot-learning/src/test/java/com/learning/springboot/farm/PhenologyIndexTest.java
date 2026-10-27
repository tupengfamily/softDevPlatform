package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.CropConfig;
import com.learning.springboot.farm.engine.SimEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物候段索引口径回归（2026-10-26）。
 *
 * <p>🔴 **这个测试守护的是什么**：
 *
 * <p>前端原先用一套写死的 DVS 切点（0.16 / 0.36 / 0.41 / 0.63 / 0.72 / 0.92）把
 * 作物切成 6 段形态 —— 那是<b>水稻</b>的口径。水稻恰好是 7 段且切点一致，
 * 所以「水稻看着对」，但其余 21 种作物全错位：
 * 番茄 DVS 0.45 的真实阶段是「开花坐果」，前端却会算成「拔节」。
 *
 * <p>修法是后端下发「物候段索引 + 段边界」，前端只消费不复算。
 * 于是<b>索引与文案必须严格同源</b> —— 这就是本测试的核心断言。
 *
 * <p>覆盖面：22 种作物 × 每段边界 × 段首尾衔接。
 */
class PhenologyIndexTest {

    /**
     * 🔴 **索引与文案必须严格对应**（本测试的第一条铁律）。
     *
     * <p>对每种作物的每一段，抽查三个采样点：
     * 段起点、段中点、段终点前一丁点。
     * 三者都必须落在同一段 —— 即 {@code phenologyIndex} 与 {@code phenologyAt}
     * 用的是同一套判据，不存在「文案第 3 段、画面第 4 段」的错位。
     */
    @Test
    @DisplayName("22 种作物：物候段索引与阶段文案严格同源")
    void indexMatchesStageTextForEveryCrop() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            CropConfig cfg = c.config();
            List<CropConfig.PhenoStage> stages = cfg.phenology();
            assertNotNull(stages, c.id() + " 缺物候段定义");
            assertTrue(!stages.isEmpty(), c.id() + " 物候段为空");

            for (int i = 0; i < stages.size(); i++) {
                CropConfig.PhenoStage s = stages.get(i);
                assertEquals(s.name(), s.name().trim(), c.id() + " 段名含空白");

                // 段起点（+ 极小量，避开 lo 的左开区间边界）
                double atLo = s.lo() + 1e-6;
                // 段中点
                double atMid = (s.lo() + s.hi()) / 2.0;
                // 段终点前一丁点
                double atHi = s.hi() - 1e-6;

                for (double dvs : new double[]{atLo, atMid, atHi}) {
                    int idx = SimEngine.phenologyIndex(dvs, cfg);
                    String text = SimEngine.phenologyAt(dvs, cfg);
                    assertEquals(i, idx,
                            c.id() + " 第 " + i + " 段（DVS=" + dvs + "）索引应为 " + i
                                    + "，实际 " + idx + "（阶段文案：" + text + "）");
                    assertEquals(s.name(), text,
                            c.id() + " DVS=" + dvs + " 的文案与段定义不一致");
                }
            }
        }
    }

    /**
     * 🔴 **未出苗必须返回 -1**（不是 0）。
     *
     * <p>理由：前端拿 -1 表示「还没进入任何物候段」→ 不渲染成株形态。
     * 如果这里返回 0，前端会把「刚播下没出苗」画成一株幼苗 —— 又是「阶段与真实不符」。
     */
    @Test
    @DisplayName("未出苗（dvs ≤ 0）返回 -1，不落入第 0 段")
    void notSproutedReturnsMinusOne() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            CropConfig cfg = c.config();
            assertEquals(-1, SimEngine.phenologyIndex(0.0, cfg), c.id() + " dvs=0 应为 -1");
            assertEquals(-1, SimEngine.phenologyIndex(-0.5, cfg), c.id() + " dvs<0 应为 -1");
            // 首段 lo=0，所以「刚出苗」是第 0 段而不是 -1
            assertEquals(0, SimEngine.phenologyIndex(0.0001, cfg),
                    c.id() + " dvs 极小但 >0 应落在第 0 段（首段 lo=0）");
        }
    }

    /**
     * 🔴 **段必须首尾相接、无缝隙无重叠**。
     *
     * <p>如果第 i 段的 hi 与第 i+1 段的 lo 对不上，中间会出现一段 DVS
     * 落不进任何段 → 前端只能夹到相邻段，形态就会跳变。
     */
    @Test
    @DisplayName("物候段首尾相接：第 i 段 hi == 第 i+1 段 lo")
    void stagesAreContiguous() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            List<CropConfig.PhenoStage> stages = c.config().phenology();
            for (int i = 0; i < stages.size() - 1; i++) {
                assertEquals(stages.get(i).hi(), stages.get(i + 1).lo(),
                        c.id() + " 第 " + i + " 段与第 " + (i + 1) + " 段不衔接");
            }
            assertEquals(0.0, stages.get(0).lo(), c.id() + " 首段 lo 必须为 0");
            // 最后一段 hi 必须 > 1，否则 DVS 到 1.0 时会落不进任何段
            assertTrue(stages.get(stages.size() - 1).hi() > 1.0,
                    c.id() + " 末段 hi 必须 > 1（否则成熟期落不进段内）");
        }
    }

    /**
     * 🔴 **段数分布的现状快照**（防止有人偷偷改段数却没同步前端 STAGE_PLAN）。
     *
     * <p>22 种作物里：水稻 7 段、冬小麦 7 段、玉米 6 段，其余 19 种 5 段。
     * 前端 {@code STAGE_PLAN} 的段数必须与此一致 —— 前端那边一改，
     * 本测试会失败（这就是「前端不能自己数段」的护栏）。
     */
    @Test
    @DisplayName("物候段数快照：水稻/冬小麦 7、玉米 6、其余 5")
    void stageCountSnapshot() {
        assertEquals(7, CropCatalog.get("rice_mid").config().phenology().size(), "水稻应为 7 段");
        assertEquals(7, CropCatalog.get("wheat_winter").config().phenology().size(), "冬小麦应为 7 段");
        assertEquals(6, CropCatalog.get("corn_spring").config().phenology().size(), "玉米应为 6 段");

        List<String> five = List.of("millet", "potato", "cabbage", "tomato", "cucumber", "chili",
                "eggplant", "yardlong_bean", "radish", "strawberry", "watermelon", "melon",
                "grape", "pear", "chrysanthemum", "sunflower", "tulip", "rose");
        for (String id : five) {
            assertEquals(5, CropCatalog.get(id).config().phenology().size(), id + " 应为 5 段");
        }
    }

    /**
     * 🔴 **越界 DVS 归入最后一段**（不能返回 -1 也不能越界）。
     *
     * <p>DVS 理论上封顶 1.3，但浮点与「过熟」场景可能出现 > 末段 hi 的值。
     * 此时应夹到最后一段，而不是返回一个越界索引让前端数组越界。
     */
    @Test
    @DisplayName("DVS 超过末段边界时夹到最后一段")
    void overflowClampsToLastStage() {
        for (CropCatalog.Crop c : CropCatalog.list()) {
            CropConfig cfg = c.config();
            List<CropConfig.PhenoStage> stages = cfg.phenology();
            int last = stages.size() - 1;
            assertEquals(last, SimEngine.phenologyIndex(1.29, cfg), c.id() + " DVS=1.29 应夹到末段");
            assertEquals(last, SimEngine.phenologyIndex(5.0, cfg), c.id() + " DVS=5.0 应夹到末段");
        }
    }
}
