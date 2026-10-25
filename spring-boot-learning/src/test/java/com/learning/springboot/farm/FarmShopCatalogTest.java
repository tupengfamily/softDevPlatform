package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.InputCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「稼穑」商店货架回归（2026-10-23 需求 R）。
 *
 * <p>覆盖 6 个点：
 * <ol>
 *   <li><b>种子齐备</b>：每个作物都有对应种子（否则玩家种不下去）；</li>
 *   <li><b>种子反查</b>：{@code seedOf(cropId)} 能反查到正确的种子；</li>
 *   <li><b>农具是耐用品</b>：{@code durable() == true}，且数量语义是「拥有/未拥有」；</li>
 *   <li><b>水按次计</b>：一份含 N 次灌溉，{@code protectDays} 即次数；</li>
 *   <li><b>农资不受污染</b>：种子/农具/水不混进「农资」货架；</li>
 *   <li><b>种子商品 id 唯一</b>：{@code SEED_xxx} 前缀与作物 id 一一对应。</li>
 * </ol>
 */
class FarmShopCatalogTest {

    @Test
    @DisplayName("每个作物都有对应的种子（否则玩家种不下去）")
    void everyCropHasSeed() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            InputCatalog.Item seed = InputCatalog.seedOf(c.id());
            assertNotNull(seed, "作物「" + c.name() + "」(" + c.id() + ") 没有对应种子");
            assertEquals(InputCatalog.Kind.SEED, seed.kind());
            assertEquals(c.id(), seed.cropId(), "种子反查的作物 id 不对：" + c.id());
        }
    }

    @Test
    @DisplayName("种子反查：seedOf 能按作物 id 找到唯一的种子")
    void seedLookup() {
        assertNotNull(InputCatalog.seedOf("rice_mid"));
        assertEquals("SEED_rice_mid", InputCatalog.seedOf("rice_mid").id());
        // 不存在的作物 → null
        assertNull(InputCatalog.seedOf("no_such_crop"));
        assertNull(InputCatalog.seedOf(null));
        assertNull(InputCatalog.seedOf(""));
    }

    @Test
    @DisplayName("种子商品 id 全局唯一")
    void seedIdsUnique() {
        Set<String> ids = new HashSet<>();
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            assertTrue(ids.add(s.id()), "种子 id 重复：" + s.id());
            assertTrue(s.id().startsWith("SEED_"), "种子 id 命名不规范：" + s.id());
        }
        assertEquals(InputCatalog.seeds().size(), ids.size());
    }

    @Test
    @DisplayName("农具是耐用品：durable() 为 true，且不按次消耗")
    void toolsAreDurable() {
        List<InputCatalog.Item> tools = InputCatalog.tools();
        assertFalse(tools.isEmpty(), "农具货架不能为空");
        for (InputCatalog.Item t : tools) {
            assertTrue(t.durable(), t.name() + " 应该标记为耐用品");
            assertEquals(InputCatalog.Kind.TOOL, t.kind());
            // 耐用品的「可用作业次数」是 -1（= 无限次）
            assertEquals(-1, t.protectDays(), t.name() + " 的 protectDays 应为 -1（无限次）");
            // 效率加成必须为正，否则买了等于没买
            assertTrue(t.efficacy() > 0, t.name() + " 的效率加成应为正");
        }
    }

    @Test
    @DisplayName("水按次计：一份含 N 次灌溉，protectDays 即次数")
    void waterIsCountedByTimes() {
        List<InputCatalog.Item> waters = InputCatalog.waters();
        assertFalse(waters.isEmpty(), "水资源货架不能为空");
        for (InputCatalog.Item w : waters) {
            assertEquals(InputCatalog.Kind.WATER, w.kind());
            assertEquals("次", w.unit(), w.name() + " 的单位应该是「次」");
            assertTrue(w.protectDays() > 0, w.name() + " 每份应含正数次灌溉");
            assertFalse(w.durable(), "水是消耗品，不是耐用品");
        }
    }

    /**
     * 🔴 回归：种子「可播亩数」的数量级。
     *
     * <p>2026-10-23 踩过的坑：{@code InputCatalog} 里种子的 {@code protectDays}
     * 存的就是<b>亩数本身</b>（5 / 6 / 4…），但 {@code FarmShopService} 里写成
     * {@code protectDays() / 1000.0}（注释说「×1000」），结果前端显示
     * 「可播 0.005 亩」——少了三个数量级，玩家完全看不懂。
     *
     * <p>这里钉住语义：{@code protectDays} 就是亩数，取值应在 1~10 之间。
     * 将来谁要是「好心」把它改成 ×1000 或再除一次，这条测试会先炸。
     */
    @Test
    @DisplayName("种子的 protectDays 就是「可播亩数」（不是亩数×1000）")
    void seedProtectDaysIsMuNotMuTimes1000() {
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            int mu = s.protectDays();
            assertTrue(mu >= 1 && mu <= 10,
                    s.name() + " 的 protectDays 应该是可播亩数（1~10），实际是 " + mu
                            + " —— 如果这是亩数×1000，前端会显示成 0.00x 亩");
        }
    }

    @Test
    @DisplayName("种子/农具/水不混进农资货架（农残、抗性、PHI 都不该算到它们头上）")
    void shopKindsAreNotInputs() {
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            switch (it.kind()) {
                case FERT, PEST, HERB -> {
                    // 农资：可以喷可以撒
                    assertFalse(it.durable());
                }
                case SEED, TOOL, WATER -> {
                    // 商店品类：不是「农资」
                    assertTrue(it.kind() != InputCatalog.Kind.PEST,
                            it.name() + " 不该被当作农药");
                }
            }
        }
        // 交叉验证：三类商品各自独立
        assertEquals(InputCatalog.seeds().size() + InputCatalog.tools().size()
                        + InputCatalog.waters().size()
                        + InputCatalog.fertilizers().size()
                        + InputCatalog.pesticides().size()
                        + InputCatalog.herbicides().size(),
                InputCatalog.all().size());
    }

    @Test
    @DisplayName("toolsOwned：只返回用户真正拥有的农具")
    void toolsOwnedFilters() {
        assertTrue(InputCatalog.toolsOwned(java.util.Set.of()).isEmpty());
        assertTrue(InputCatalog.toolsOwned(null).isEmpty());
        List<InputCatalog.Item> owned = InputCatalog.toolsOwned(
                Set.of("TOOL_TILLER", "TOOL_IRRIGATION"));
        assertEquals(2, owned.size());
        for (InputCatalog.Item t : owned) {
            assertEquals(InputCatalog.Kind.TOOL, t.kind());
        }
        // 不存在的 id 不返回
        assertTrue(InputCatalog.toolsOwned(Set.of("TOOL_NOT_EXIST")).isEmpty());
    }
}
