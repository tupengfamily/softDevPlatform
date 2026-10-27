package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.CropVisual;
import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.SimEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-2「静默失效型缺陷」回归（2026-10-26）。
 *
 * <p><b>为什么这三个缺陷要合在一起测</b>：
 * 它们的共同点是<b>不报错、结果错、玩家无从察觉</b>。
 * 普通单元测试抓不住它们 —— 因为「少了一件初始农资」「水稻走旱地分支」
 * 「评级分母偏小」这三件事，代码层面都是<b>合法</b>的，
 * 断言里也没有任何地方写着「不应该这样」。
 *
 * <p>所以本测试不测「函数返回值对不对」，而测<b>不变式</b>：
 * <ol>
 *   <li><b>配置引用完整性</b>：初始备货的每个 id 必须在 {@link InputCatalog} 有定义
 *       —— 防拼写错复发（真实事故：{@code CHLORANBENILIPROLE}）。</li>
 *   <li><b>visual 语义单一真源</b>：水田/豆科判定必须收敛到 {@link CropVisual}，
 *       且<b>形态族判不出来</b>（{@code Form.GRAIN} 含 5 种作物，会把 4 种旱地粮食全判成水田）。</li>
 *   <li><b>评级分母不越界</b>：满产 / {@code yieldRef} 比值应 ≈1.0
 *       —— 防 {@code yieldRef} 与 {@code yieldCap} 语义混淆导致评级虚高。</li>
 * </ol>
 *
 * <p>🔴 <b>第 3 条必须用真实引擎公式复算</b>：
 * 手工抄 {@code CropConfig} 参数算产量曾得出「水稻满产 0.0 kg/亩」的荒谬结果。
 * 这里直接调 {@link SimEngine} 的真实公式。
 */
class FarmP1ConsistencyTest {

    // ==================================================================
    //  不变式一：初始备货引用完整性（防 id 拼写错）
    // ==================================================================

    @Test
    @DisplayName("P1-2：初始备货的每个农资 id 都能在 InputCatalog 找到（防拼写错复发）")
    void initialStockIdsAllExistInInputCatalog() {
        // FarmStockService 的类加载期自检已覆盖这条，
        // 但自检是「启动时炸」，这里是「测试时炸」—— 跑测试就能发现，不必启服务。
        List<String> missing = new ArrayList<>();
        for (String id : FarmStockService.initialStockForTest().keySet()) {
            if (InputCatalog.get(id) == null) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(),
                "初始备货里有 id 在 InputCatalog 中不存在: " + missing
                        + "（id 拼写错误会让这件农资初始备货为 0，且玩家看不到任何提示）");
    }

    @Test
    @DisplayName("P1-2：初始备货覆盖全部 17 种消耗型农资（缺一项就是玩家进游戏就没法用）")
    void initialStockCoversAllConsumableInputs() {
        // 逐类核对：初始备货应覆盖 InputCatalog 里所有消耗型农资
        // （耐用品「农具」按拥有制，不进初始备货清单）。
        Set<String> consumableIds = new HashSet<>();
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (!it.durable()) {
                consumableIds.add(it.id());
            }
        }
        Set<String> stocked = FarmStockService.initialStockForTest().keySet();

        List<String> notStocked = new ArrayList<>();
        for (String id : consumableIds) {
            if (!stocked.contains(id)) {
                notStocked.add(id);
            }
        }
        // 种子/水属于「商店购买」类，不在初始备货（软校验：老玩家没种子只提示不拦）
        // 所以这里只断言「农药/化肥/除草剂」三类必须全覆盖。
        List<String> hardMissing = new ArrayList<>();
        for (String id : notStocked) {
            InputCatalog.Item it = InputCatalog.get(id);
            String kind = it == null ? "?" : it.kind().name();
            if ("PEST".equals(kind) || "FERT".equals(kind) || "HERB".equals(kind)) {
                hardMissing.add(id + "(" + kind + ")");
            }
        }
        assertTrue(hardMissing.isEmpty(),
                "以下 PEST/FERT/HERB 类农资没有初始备货: " + hardMissing
                        + "（玩家第一次进游戏会「库存不足」，且没有任何提示）");
    }

    @Test
    @DisplayName("P1-2：初始备货数量必须为正（有 id 但数量为 0 = 同样不可用）")
    void initialStockQuantitiesArePositive() {
        for (var e : FarmStockService.initialStockForTest().entrySet()) {
            assertTrue(e.getValue() > 0,
                    "初始备货数量必须为正: " + e.getKey() + " = " + e.getValue());
        }
    }

    // ==================================================================
    //  不变式二：visual 语义单一真源（防硬编码散落）
    // ==================================================================

    @Test
    @DisplayName("P1-2：isPaddy 只认水稻两种 visual，不误伤其它旱地粮食作物")
    void isPaddyOnlyMatchesRice() {
        assertTrue(CropVisual.isPaddy("rice"), "rice 是水稻（粮食作物保持的旧 visual 值）");
        assertTrue(CropVisual.isPaddy("rice_mid"), "rice_mid 是新语义下的水稻作物 id");

        // 🔴 关键：形态族同为 GRAIN，但这四种都是旱地作物
        assertFalse(CropVisual.isPaddy("wheat"), "小麦不是水田");
        assertFalse(CropVisual.isPaddy("corn"), "玉米不是水田");
        assertFalse(CropVisual.isPaddy("soybean"), "大豆不是水田");
        assertFalse(CropVisual.isPaddy("millet"), "谷子不是水田");
        assertFalse(CropVisual.isPaddy("cabbage"), "大白菜不是水田");
        assertFalse(CropVisual.isPaddy(null), "null 不是水田");
    }

    @Test
    @DisplayName("P1-2：isLegume 只认大豆（固氮机制不能错挂到别的作物上）")
    void isLegumeOnlyMatchesSoybean() {
        assertTrue(CropVisual.isLegume("soybean"));
        assertFalse(CropVisual.isLegume("rice"));
        assertFalse(CropVisual.isLegume("wheat"));
        assertFalse(CropVisual.isLegume("corn"));
        assertFalse(CropVisual.isLegume("peanut_visual_not_exist"));
        assertFalse(CropVisual.isLegume(null));
    }

    @Test
    @DisplayName("P1-2：🔴 形态族判不出水田/豆科（GRAIN 含 5 种作物，不能用 form 判）")
    void formCannotDistinguishPaddyOrLegume() {
        // 这条是「不变式的不变式」—— 如果哪天 CropVisual 加了 GRAIN 专属形态族，
        // 说明「用形态族判水田」这条路变得可行，本测试会提醒重新评估。
        List<String> grainCrops = new ArrayList<>();
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            if (CropVisual.formOf(c.config().visual()) == CropVisual.Form.GRAIN) {
                grainCrops.add(c.id());
            }
        }
        assertTrue(grainCrops.size() >= 5,
                "GRAIN 形态族应至少含 5 种作物（实际 " + grainCrops.size() + ": " + grainCrops + "）");

        // 同族里只有水稻是水田 —— 这正是「不能用形态族判」的根据
        long paddyInGrain = grainCrops.stream().filter(CropVisual::isPaddy).count();
        assertEquals(1, paddyInGrain,
                "GRAIN 族里的水田作物应恰好 1 种（实际 " + paddyInGrain + "）");

        long legumeInGrain = grainCrops.stream().filter(CropVisual::isLegume).count();
        assertEquals(1, legumeInGrain,
                "GRAIN 族里的豆科作物应恰好 1 种（实际 " + legumeInGrain + "）");
    }

    @Test
    @DisplayName("P1-2：所有已注册作物的 visual 都能解析出形态族（不留 null）")
    void everyRegisteredCropResolvesToAForm() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            CropVisual.Form form = CropVisual.formOf(c.config().visual());
            assertNotNull(form, "作物 " + c.id() + " 的 visual=" + c.config().visual() + " 解析不出形态族");
        }
    }

    // ==================================================================
    //  不变式三：评级分母不越界（yieldRef vs yieldCap 语义不同）
    // ==================================================================

    @Test
    @DisplayName("P1-2：所有作物的「满产 / yieldRef」比值在 [0.75, 1.25] 内（防评级虚高）")
    void fullYieldOverYieldRefStaysInSaneBand() {
        List<String> outliers = new ArrayList<>();
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            double full = fullYield(c);
            double ref = CropCatalog.yieldRef(c.id());
            if (ref <= 0) {
                outliers.add(c.id() + ": yieldRef=" + ref + "（<=0，评级分母非法）");
                continue;
            }
            double ratio = full / ref;
            if (ratio < 0.75 || ratio > 1.25) {
                outliers.add(c.id() + ": 满产=" + round(full) + " yieldRef=" + ref
                        + " 比值=" + round(ratio));
            }
        }
        assertTrue(outliers.isEmpty(),
                "以下作物的「满产/yieldRef」比值越界（评级会失真）:\n  " + String.join("\n  ", outliers)
                        + "\n  真实事故：rose 的 yieldRef=1000 但 yieldCap=1500 → 比值 1.50，评级虚高。");
    }

    @Test
    @DisplayName("P1-2：月季 yieldRef 已修正为 1500（满产 1500，比值 1.00）")
    void roseYieldRefFixed() {
        CropCatalog.Crop rose = CropCatalog.get("rose");
        assertNotNull(rose, "rose 必须已注册");
        assertEquals(1500.0, CropCatalog.yieldRef("rose"), 1e-9,
                "rose 的 yieldRef 应为 1500（与 yieldCap 一致），比值才是 1.00");
        double ratio = fullYield(rose) / CropCatalog.yieldRef("rose");
        assertEquals(1.00, ratio, 0.02, "rose 满产/yieldRef 应≈1.00");
    }

    @Test
    @DisplayName("P1-2：每种作物的 yieldRef 必须为正（评级分母为 0 会除零）")
    void everyCropHasPositiveYieldRef() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            double ref = CropCatalog.yieldRef(c.id());
            assertTrue(ref > 0, "作物 " + c.id() + " 的 yieldRef 必须为正，实际 " + ref);
        }
    }

    // ==================================================================
    //  工具
    // ==================================================================

    /**
     * 用 <b>真实引擎公式</b>复算「零损伤满管理」下的产量上限。
     *
     * <p>🔴 不能手工抄 {@code CropConfig} 参数算 —— 那样算过「水稻满产 0.0 kg/亩」的荒谬结果。
     * 这里直接调 {@link SimEngine} 的同一套公式（封顶 {@code yieldCap}）。
     */
    private static double fullYield(CropCatalog.Crop c) {
        // SimEngine.fullPotentialYield() 是从 simulate() 的产量公式抽出来的公开方法，
        // 与生产走同一份公式 —— 避免校验脚本「手工抄参数」抄错（曾抄出「水稻满产 0.0 kg/亩」）。
        return SimEngine.fullPotentialYield(c.config());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
