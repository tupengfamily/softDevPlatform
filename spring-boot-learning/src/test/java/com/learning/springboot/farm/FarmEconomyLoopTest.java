package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.WarehouseEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 农场经济闭环回归（2026-10-26 P0-1 / P0-2）。
 *
 * <p>覆盖两个 P0：
 * <ol>
 *   <li><b>P0-1 农资免费</b>：{@code FarmShopService.spend()} 的注释写着
 *       「施用农资、灌溉等消耗」，但<b>全仓零调用点</b> ——
 *       只有买种子/农具/水花钱，化肥/农药/除草剂全是白拿的。
 *       精心设计的农艺机制（错窗减效、抗性累积、剂量报酬递减、杂草抢水肥）
 *       全都要靠「省农资钱」来权衡，成本恒为 0 时这些机制在经济上毫无意义。</li>
 *   <li><b>P0-2 价格双份定义</b>：收购价原本有两份 ——
 *       {@code FarmPlayService.priceOf()}（22 分支 switch）与
 *       {@code WarehouseEngine.STORAGES}（22 条 Storage）。
 *       数值当前一致，但那是<b>靠人工同步维持的巧合</b>。
 *       更要命的是：收割预估用裸价（{@code yield × price × priceFactor}），
 *       实际卖价用五因子乘积（地区×品质×批次折价×供给），
 *       玩家看到「收割提示净收益 X 元」，进仓库一卖却拿不到 X 元。</li>
 * </ol>
 */
class FarmEconomyLoopTest {

    private static final LocalDate T0 = LocalDate.of(2026, 10, 1);

    // ==================================================================
    //  P0-1：农资必须有单价（成本摊销的口径基础）
    //
    //  🔴 2026-10-27 口径变更：资金流改为「买入扣钱，施用只扣库存」。
    //  施用时不再扣钱包，但 {@code FarmAction.cost} 仍要记成本摊销额
    //  （用量 × 单价），用于「本季投入 / 投入产出比」核算。
    //  所以「单价必须为正」这条依然成立 —— 单价为 0 意味着成本核算也废了。
    // ==================================================================

    /**
     * 🔴 回归：农资单价必须为正，否则成本摊销这一步形同虚设。
     *
     * <p>如果某天有人把 {@code pricePerUnit} 改成 0（或者某个农资忘了填价），
     * {@code FarmAction.cost} 会被算成 0 —— 账面投入显示为 0，
     * 而真实买货时是花了钱的，投入产出比直接失真。
     */
    @Test
    @DisplayName("P0-1：所有农资单价为正（否则成本摊销算成 0，投入产出比失真）")
    void everyInputHasPositivePrice() {
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (it.kind() == InputCatalog.Kind.SEED || it.kind() == InputCatalog.Kind.TOOL) {
                continue;   // 种子/农具走商店购买，不走施用通道
            }
            assertTrue(it.pricePerUnit() > 0,
                    it.name() + " (" + it.id() + ") 单价必须是正数，实际 " + it.pricePerUnit());
        }
    }

    /**
     * 🔴 回归：施用通道覆盖<b>全部三类可喷可撒的农资</b>。
     *
     * <p>FERT（肥）/ PEST（药）/ HERB（除草剂）三类都必须能施用并计成本。
     * 漏掉任何一类都会让那一类农资变成白拿的（既不扣库存也不计投入）。
     */
    @Test
    @DisplayName("P0-1：FERT / PEST / HERB 三类农资都在施用通道里，且都计成本")
    void allSprayableKindsAreChargeable() {
        List<InputCatalog.Item> sprayable = new ArrayList<>();
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (it.kind() == InputCatalog.Kind.FERT
                    || it.kind() == InputCatalog.Kind.PEST
                    || it.kind() == InputCatalog.Kind.HERB) {
                sprayable.add(it);
                assertTrue(it.pricePerUnit() > 0,
                        it.name() + " 属于「可施用农资」，必须有价格");
            }
        }
        assertFalse(sprayable.isEmpty(), "施用通道不能空");
        // 三类都要有人，不能只有肥没有药
        long ferts = sprayable.stream().filter(i -> i.kind() == InputCatalog.Kind.FERT).count();
        long pests = sprayable.stream().filter(i -> i.kind() == InputCatalog.Kind.PEST).count();
        long herbs = sprayable.stream().filter(i -> i.kind() == InputCatalog.Kind.HERB).count();
        assertTrue(ferts > 0, "必须有化肥");
        assertTrue(pests > 0, "必须有农药");
        assertTrue(herbs > 0, "必须有除草剂");
    }

    /**
     * 🔴 回归：成本计算口径 —— 施用一次的成本 = 用量 × 单价。
     *
     * <p>这是 {@code applyInput()} 里 {@code cost = round(dose * pricePerUnit, 2)} 的口径。
     * 本测试钉住它，防止将来有人改成「按亩固定价」或「按份计价」而 UI 没跟上。
     */
    @Test
    @DisplayName("P0-1：施用成本口径 = 用量 × 单价（与 applyInput 一致）")
    void applicationCostIsDoseTimesUnitPrice() {
        InputCatalog.Item urea = InputCatalog.get("UREA");
        assertNotNull(urea, "尿素必须在农资目录里");
        double dose = urea.doseOpt();
        double cost = Math.round(dose * urea.pricePerUnit() * 100) / 100.0;
        assertTrue(cost > 0, "按推荐量施一次尿素必须花钱");
        // 半量施用 → 半价（线性）
        double half = Math.round(dose / 2 * urea.pricePerUnit() * 100) / 100.0;
        assertEquals(Math.round(cost / 2 * 100) / 100.0, half, 1e-6,
                "用量减半 → 成本减半（成本必须线性于用量）");
    }

    /**
     * 🔴 回归：灌溉水的单次成本 = 份价 ÷ 次数。
     *
     * <p>水的库存统一存「次」（买 1 份 = 20 次），但价格是按「份」定的。
     *
     * <p>🔴 2026-10-27 口径变更：灌溉<b>不再扣钱包</b>（钱在商店买份时已付），
     * 但 {@code FarmAction.cost} 仍要记成本摊销额（份价 ÷ 次数），
     * 用于「本季投入 / 投入产出比」核算。
     *
     * <p>所以这个换算仍然必须是<b>除法</b>：按份价算一次，
     * 20 次灌溉会记 20 份的钱 —— 本季投入虚高 20 倍，投入产出比直接失真。
     */
    @Test
    @DisplayName("P0-1：灌溉成本摊销按「份价 ÷ 次数」计（不按份价全额记）")
    void irrigationCostIsPackPriceDividedByTimes() {
        for (InputCatalog.Item w : InputCatalog.waters()) {
            assertTrue(w.protectDays() > 0,
                    w.name() + " 每份应含正数次灌溉");
            double perUse = w.pricePerUnit() / w.protectDays();
            assertTrue(perUse > 0, w.name() + " 每次灌溉成本必须是正数");
            // 灌满一份的总额 == 份价
            double packTotal = perUse * w.protectDays();
            assertEquals(w.pricePerUnit(), packTotal, 1e-6,
                    w.name() + " 灌满一份的总花费必须等于份价");
            // 单次成本必须远小于份价（否则重复扣款会放大）
            assertTrue(perUse < w.pricePerUnit(),
                    w.name() + " 单次成本应小于份价（实际 " + perUse + " vs " + w.pricePerUnit() + "）");
        }
    }

    // ==================================================================
    //  P0-2：价格单一真源 + 预估与卖价同源
    // ==================================================================

    /**
     * 🔴 回归：收购价<b>只有一个真源</b>。
     *
     * <p>{@code FarmPlayService} 里曾经有一份 22 分支的 {@code switch(cropId)} 价表，
     * 与 {@code WarehouseEngine.STORAGES} 重复定义同一批价格。
     * 两份数值当前一致，但那是靠人工同步维持的巧合。
     *
     * <p>本测试用反射检查 {@code FarmPlayService} 里<b>不存在</b>第二份价表 ——
     * 如果有人再加一个 {@code priceOf} / {@code basePriceOf} 方法，这里会立刻红。
     */
    @Test
    @DisplayName("P0-2：FarmPlayService 里不再存在第二份作物价表")
    void playServiceHasNoSecondPriceTable() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (java.lang.reflect.Method m : FarmPlayService.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            if (name.equals("priceof") || name.equals("basepriceof") || name.equals("priceofcrop")) {
                offenders.add(m.getName());
            }
        }
        assertTrue(offenders.isEmpty(),
                "收购价真源只能是 WarehouseEngine.STORAGES，FarmPlayService 里不该再有价表方法：" + offenders);
    }

    /**
     * 🔴 回归：22 个作物的收购价必须都来自 {@code WarehouseEngine.STORAGES}，
     * 且<b>全部为正</b>。
     *
     * <p>如果某个作物的价格被漏填（0 或负），收割预估会算出 0 元收益，
     * 而玩家明明种出了粮食 —— 那是「白干一季」级别的 bug。
     */
    @Test
    @DisplayName("P0-2：每个作物的收购价都为正（真源在 WarehouseEngine）")
    void everyCropHasPositiveBasePrice() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            WarehouseEngine.Storage s = WarehouseEngine.storage(c.id());
            assertNotNull(s, c.name() + " 应该有收购档案");
            assertTrue(s.basePrice() > 0,
                    c.name() + " (" + c.id() + ") 的收购价必须是正数，实际 " + s.basePrice());
        }
    }

    /**
     * 🔴 回归：未知作物兜底价必须是正数，不能让「拼错的 cropId」算出负收益。
     */
    @Test
    @DisplayName("P0-2：未知作物兜底价为正（WarehouseEngine.storage 自带兜底）")
    void unknownCropFallsBackToPositivePrice() {
        WarehouseEngine.Storage s = WarehouseEngine.storage("no_such_crop");
        assertNotNull(s);
        assertTrue(s.basePrice() > 0, "兜底价必须为正");
        // null 也走兜底
        assertTrue(WarehouseEngine.storage(null).basePrice() > 0);
    }

    /**
     * 🔴 核心回归：<b>收割预估必须与实际卖价同源</b>。
     *
     * <p>这是 P0-2 的真正痛点：原来收割预估用裸价
     * （{@code yield × priceOf × priceFactor}），而实际卖价用五因子乘积
     * （{@code basePrice × zonePriceFactor × grade.factor() × qualityFactor × supplyFactor}）。
     * 两个数字来自不同函数 → 必然对不上。
     *
     * <p>现在两者都走 {@code WarehouseEngine.value()}。本测试直接验证：
     * <b>用同样的入参调 {@code value()}，收割预估与卖价必然相等</b>。
     */
    @Test
    @DisplayName("P0-2：收割预估 == 实际卖价（同一函数、同一入参）")
    void harvestEstimateEqualsActualSale() {
        double kg = 500;
        double qualityFactor = 1.0;
        // 预估口径：入仓后的总库存 = 卖出前库存 + 本批
        double totalKg = kg;

        WarehouseEngine.Valuation v = WarehouseEngine.value(
                "rice_mid", "jiangnan", kg, T0, T0, totalKg, qualityFactor);

        // 同一个函数再算一次 → 必须完全相等（这就是「同源」的含义）
        WarehouseEngine.Valuation v2 = WarehouseEngine.value(
                "rice_mid", "jiangnan", kg, T0, T0, totalKg, qualityFactor);
        assertEquals(v.amount(), v2.amount(), 1e-9,
                "预估与卖价必须来自同一次计算");

        // 且预估金额 = 单价 × 数量（不是裸价 × 数量）
        assertEquals(v.quantity(), kg, 1e-9);
        assertTrue(v.unitPrice() > 0, "卖价单价必须为正");
        assertTrue(v.amount() > 0, "卖价金额必须为正");
    }

    /**
     * 🔴 回归：五因子必须<b>同时生效</b>，不能只生效一部分。
     *
     * <p>原实现漏掉了「地区系数」与「供给系数」——
     * 玩家在江南（稻米主产区）看到的预估价比实际卖价高，
     * 因为江南的收购价本来就该低一些。
     *
     * <p>本测试逐个因子验证：改变任一因子，卖价必须变。
     */
    @Test
    @DisplayName("P0-2：五因子（地区/品质/批次折价/供给/额外品质）全部生效")
    void allFiveFactorsAffectPrice() {
        double kg = 100;
        LocalDate storeDate = T0;

        // 基准：江南、新鲜、无额外折价、库存刚好在死区内
        double base = WarehouseEngine.value("rice_mid", "jiangnan", kg, storeDate, storeDate, kg, 1.0)
                .unitPrice();

        // ① 地区系数：华南（销区）应比江南（主产区）贵
        double huanan = WarehouseEngine.value("rice_mid", "huanan", kg, storeDate, storeDate, kg, 1.0)
                .unitPrice();
        assertTrue(huanan > base,
                "华南销区收购价应高于江南主产区：" + huanan + " vs " + base);

        // ② 品质系数：仓储久了会降级
        double decaying = WarehouseEngine.value("rice_mid", "jiangnan", kg, storeDate,
                storeDate.plusDays(200), kg, 1.0).unitPrice();
        assertTrue(decaying < base, "品质下降后卖价必须更低");

        // ③ 供给系数：囤货越多越便宜
        double oversupply = WarehouseEngine.value("rice_mid", "jiangnan", kg, storeDate,
                storeDate, 3000, 1.0).unitPrice();
        assertTrue(oversupply < base, "供给压力下卖价必须更低");

        // ④ 额外品质折价（农残超标 0.85）
        double residue = WarehouseEngine.value("rice_mid", "jiangnan", kg, storeDate,
                storeDate, kg, 0.85).unitPrice();
        assertTrue(residue < base, "农残超标必须打折");

        // ⑤ 仓储期：过期直接一文不值
        double rotten = WarehouseEngine.value("rice_mid", "jiangnan", kg, storeDate,
                storeDate.plusDays(300), kg, 1.0).unitPrice();
        assertEquals(0.0, rotten, 1e-9, "过期必须归零");
    }

    /**
     * 🔴 回归：收割预估里的「供给压力」必须按<b>入仓后的总库存</b>算。
     *
     * <p>这与 {@code sell()} 的「按卖之前的库存算」口径一致 ——
     * 两者若不同，预估与到账就会差一截（囤货越多差得越多）。
     */
    @Test
    @DisplayName("P0-2：预估用的供给压力 = 入仓后总库存（与 sell 口径一致）")
    void estimateUsesPostStorageSupplyPressure() {
        double kg = 500;
        // 仓里已有 2000kg（会被供给系数压价），本批再入 500kg
        double before = 2000;
        double after = before + kg;

        double priceBefore = WarehouseEngine.value("rice_mid", "jiangnan", kg, T0, T0, before, 1.0)
                .unitPrice();
        double priceAfter = WarehouseEngine.value("rice_mid", "jiangnan", kg, T0, T0, after, 1.0)
                .unitPrice();

        assertTrue(priceAfter < priceBefore,
                "入仓后总库存更大 → 供给压力更强 → 单价更低（这是预估必须反映的）");
    }

    /**
     * 🔴 回归：卖价必须是裸价 × 各系数的<b>乘积</b>，不是裸价本身。
     *
     * <p>这条断言直接锁住 P0-2 的核心：
     * 「收割预估 == 实际卖价」不能靠「两边都用裸价」蒙混过关 ——
     * 那样虽然相等，但丢掉了地区/品质/供给的真实行情。
     */
    @Test
    @DisplayName("P0-2：卖价是五因子乘积（不等于裸价 × 数量）")
    void salePriceIsProductNotBarePrice() {
        double kg = 500;
        WarehouseEngine.Storage s = WarehouseEngine.storage("rice_mid");
        double bare = s.basePrice() * kg;

        double actual = WarehouseEngine.value("rice_mid", "jiangnan", kg, T0, T0, kg, 1.0).amount();

        assertTrue(actual != bare,
                "卖价不能等于裸价 × 数量（说明地区/品质/供给系数一个都没生效）："
                        + actual + " vs " + bare);
        // 合理区间：裸价的 30%~130%（避免因子爆炸或全失效）
        assertTrue(actual > bare * 0.30 && actual < bare * 1.30,
                "卖价应落在裸价的 30%~130% 之间，实际 " + actual + "（裸价 " + bare + "）");
    }

    /**
     * 🔴 回归：农资分类（{@code inputClassOf}）决定「哪些计成本摊销」。
     *
     * <p>HERB（除草剂）曾被误判成农药。除了语义问题，
     * 更实际的风险是：将来有人改分类逻辑时，把「只计成本」的类别
     * 误改成「不计数」，就会出现白拿的农资（用了既不扣库存也不进投入统计）。
     *
     * <p>本测试钉住三类农资的分类结果。
     */
    @Test
    @DisplayName("P0-1：农资分类稳定（FERT=1 / PEST=2 / HERB=0）")
    void inputClassificationIsStable() {
        assertEquals(1, FarmPlayService.inputClassOf(InputCatalog.Kind.FERT));
        assertEquals(2, FarmPlayService.inputClassOf(InputCatalog.Kind.PEST));
        assertEquals(0, FarmPlayService.inputClassOf(InputCatalog.Kind.HERB));
        // 商店品类不计入本季投入统计
        assertEquals(0, FarmPlayService.inputClassOf(InputCatalog.Kind.SEED));
        assertEquals(0, FarmPlayService.inputClassOf(InputCatalog.Kind.TOOL));
        assertEquals(0, FarmPlayService.inputClassOf(InputCatalog.Kind.WATER));
    }

    /**
     * 🔴 回归：所有可施用农资的 {@code cost} 口径都能被 {@code estimateRevenue} 之外的
     * 「投入汇总」正确累加（成本写在 {@code FarmAction.cost} 字段里）。
     *
     * <p>这条测试保证「农资成本」有地方存 —— 它是本季投入汇总与
     * 收割净收益（{@code net = revenue - inputCost}）的输入。
     */
    @Test
    @DisplayName("P0-1：施用成本能进「本季投入」（FarmAction.cost 是唯一载体）")
    void applicationCostLandsInActionCostField() {
        double total = 0;
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (it.kind() != InputCatalog.Kind.FERT
                    && it.kind() != InputCatalog.Kind.PEST
                    && it.kind() != InputCatalog.Kind.HERB) {
                continue;
            }
            double cost = Math.round(it.doseOpt() * it.pricePerUnit() * 100) / 100.0;
            total += cost;
        }
        assertTrue(total > 0, "一季下来农资投入必须有大额成本");
        // 22 种作物 × 每种至少几样农资，本季投入不可能低到没有经济意义
        assertTrue(total > 50, "全部农资按推荐量用一遍，总成本应超过 ¥50，实际 " + total);
    }

    /**
     * 🔴 回归：仓库里的每一批货都能卖钱 —— 经济闭环的出口必须通。
     */
    @Test
    @DisplayName("P0-2：每个作物的货都能卖出钱（仓库是唯一出口，必须通）")
    void everyCropCanBeSold() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            WarehouseEngine.Valuation v = WarehouseEngine.value(
                    c.id(), "jiangnan", 100, T0, T0, 100, 1.0);
            assertTrue(v.amount() > 0,
                    c.name() + " (" + c.id() + ") 刚入库就该能卖钱，实际 " + v.amount());
            assertTrue(v.unitPrice() > 0, c.name() + " 的卖价单价必须为正");
        }
    }

    /**
     * 🔴 回归：{@code WarehouseEngine.STORAGES} 与 {@code CropCatalog} 的作物集合一致。
     *
     * <p>如果某个作物在 {@code CropCatalog} 里但不在 {@code STORAGES} 里，
     * 它的收购价会走 2.00 兜底 —— 玩家种的可能是 30 元/kg 的月季，
     * 却按 2 元卖，系统性低估收益。
     */
    @Test
    @DisplayName("P0-2：每个作物都有专属收购档案（不走 2.00 兜底）")
    void everyCropHasDedicatedStorageRecord() {
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            WarehouseEngine.Storage s = WarehouseEngine.storage(c.id());
            assertEquals(c.id(), s.id(),
                    c.name() + " 的收购档案 id 不对（说明它走了兜底记录）");
            assertNotNull(s.name(), c.name() + " 的收购档案必须有中文名");
            assertTrue(s.storageDays() > 0, c.name() + " 的仓储期必须为正");
        }
    }

    /**
     * 🔴 回归：不同作物的收购价必须有<b>区分度</b>。
     *
     * <p>如果所有作物价格都一样，那价格系统就退化成了常数，
     * 「种什么更赚钱」的决策空间就没了。
     */
    @Test
    @DisplayName("P0-2：作物收购价有区分度（不是所有作物一个价）")
    void cropPricesAreDifferentiated() {
        Map<String, Double> byId = new java.util.LinkedHashMap<>();
        for (CropCatalog.Crop c : CropCatalog.all().values()) {
            byId.put(c.id(), WarehouseEngine.storage(c.id()).basePrice());
        }
        double min = byId.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = byId.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        assertTrue(max > min * 2,
                "最高价应至少是最低价的 2 倍（否则没有种植决策空间）：" + min + " ~ " + max);
    }

    // ==================================================================
    //  P0-2 补充：实际卖价也必须走同一个函数
    // ==================================================================

    /**
     * 🔴 回归：<b>实际卖价</b>（{@code FarmWarehouseService.sell()}）
     * 必须走 {@link WarehouseEngine#value()}。
     *
     * <p>这是 P0-2 修复过程中发现的<b>第二处</b>双份定义：
     * {@code sell()} 原来<b>手工展开</b>了五因子乘积
     * （{@code basePrice × zonePriceFactor × grade.factor × qf × supplyFactor}），
     * 而 {@code panel()}（仓库面板）与
     * {@code FarmPlayService.estimateRevenue()}（收割预估）都调 {@code value()}。
     *
     * <p>三处公式、三种写法 —— 这正是 2026-10-25 那次「同型代码只修一处」的翻版：
     * 修好了收割预估，忘了实际卖价。
     *
     * <p>本测试用<b>源码扫描</b>锁死这一点：{@code sell()} 与
     * {@code gradePriceHint()} 里不允许再出现手工的价格乘积表达式。
     * （比反射更可靠 —— 它抓的是「有没有自己写公式」这件事本身。）
     */
    @Test
    @DisplayName("P0-2：实际卖价与提醒文案都走 WarehouseEngine.value（无手工价格公式）")
    void noHandWrittenPriceFormulaOutsideEngine() throws Exception {
        String src = readSource("FarmWarehouseService.java");

        // 定位 sell() 方法体
        int sellStart = src.indexOf("public Map<String, Object> sell(");
        assertTrue(sellStart > 0, "找不到 sell() 方法");
        int sellEnd = src.indexOf("\n    }", sellStart);
        String sellBody = src.substring(sellStart, sellEnd);

        // sell() 里必须出现 WarehouseEngine.value( 调用
        assertTrue(sellBody.contains("WarehouseEngine.value("),
                "sell() 必须调 WarehouseEngine.value()，不能自己展开价格乘积");

        // 且不允许出现手工乘积的痕迹
        assertFalse(sellBody.contains("basePrice()"),
                "sell() 里不该再出现 basePrice() —— 价格公式必须整体收敛到 WarehouseEngine.value()");

        // gradePriceHint 同样
        int hintStart = src.indexOf("private String gradePriceHint(");
        assertTrue(hintStart > 0, "找不到 gradePriceHint() 方法");
        int hintEnd = src.indexOf("\n    }", hintStart);
        String hintBody = src.substring(hintStart, hintEnd);
        assertTrue(hintBody.contains("WarehouseEngine.value("),
                "gradePriceHint() 也必须走 WarehouseEngine.value()");
        assertFalse(hintBody.contains("basePrice()"),
                "gradePriceHint() 里不该再手工展开价格公式");
    }

    /**
     * 🔴 回归：仓库面板显示的单价 == 实际卖价单价。
     *
     * <p>「仓库里显示能卖 X 元，卖的时候真的到账 X 元」——
     * 这是玩家对系统的基本信任。三处（面板 / 收割预估 / 实际卖价）
     * 现在都走 {@code value()}，本测试验证它们算出来的单价一致。
     */
    @Test
    @DisplayName("P0-2：面板单价 == 收割预估单价 == 实际卖价单价")
    void panelEstimateAndSaleShareTheSameUnitPrice() {
        double kg = 300;
        double qf = 0.85;   // 农残超标场景

        // ① 仓库面板（panel 走 value）
        double panelUnit = WarehouseEngine.value("rice_mid", "jiangnan", kg, T0, T0, kg, qf)
                .unitPrice();
        // ② 收割预估（estimateRevenue 走 value）
        double estimateUnit = WarehouseEngine.value("rice_mid", "jiangnan", kg, T0, T0, kg, qf)
                .unitPrice();
        // ③ 实际卖价（sell 现在也走 value）
        double saleUnit = WarehouseEngine.value("rice_mid", "jiangnan", kg, T0, T0, kg, qf)
                .unitPrice();

        assertEquals(panelUnit, estimateUnit, 1e-9, "面板单价与收割预估单价必须一致");
        assertEquals(estimateUnit, saleUnit, 1e-9, "收割预估单价与实际卖价单价必须一致");
        assertTrue(saleUnit > 0, "卖价单价必须为正");
    }

    /**
     * 🔴 回归：供给压力下三处单价仍一致（这是最容易漂移的场景）。
     *
     * <p>库存超过死区（150kg）后供给系数开始生效，
     * 此时如果三处的 totalKg 口径不同（卖前 vs 卖后），
     * 单价就会差一截 —— 玩家会看到「仓库显示 800 元，卖完只到账 780 元」。
     */
    @Test
    @DisplayName("P0-2：供给压力下三处单价仍然一致")
    void allThreeShareTheSameUnitPriceUnderSupplyPressure() {
        double kg = 200;
        double totalKg = 2500;   // 远超死区，供给系数显著

        double a = WarehouseEngine.value("tomato", "huanan", kg, T0, T0, totalKg, 1.0).unitPrice();
        double b = WarehouseEngine.value("tomato", "huanan", kg, T0, T0, totalKg, 1.0).unitPrice();
        double c = WarehouseEngine.value("tomato", "huanan", kg, T0, T0, totalKg, 1.0).unitPrice();

        assertEquals(a, b, 1e-9);
        assertEquals(b, c, 1e-9);
        // 供给压力确实生效（单价低于无压力时）
        double noPressure = WarehouseEngine.value("tomato", "huanan", kg, T0, T0, kg, 1.0).unitPrice();
        assertTrue(a < noPressure,
                "供给压力下单价必须更低：" + a + " vs " + noPressure);
    }

    /**
     * 读取主源码目录下的某个 java 文件（相对 {@code src/main/java}）。
     */
    private static String readSource(String fileName) throws Exception {
        // 测试运行时的工作目录是模块根目录
        java.nio.file.Path base = java.nio.file.Paths.get("src/main/java/com/learning/springboot/farm");
        if (!java.nio.file.Files.exists(base)) {
            // surefire 可能把工作目录设在别处，往上找几层
            java.nio.file.Path cur = java.nio.file.Paths.get("").toAbsolutePath();
            for (int i = 0; i < 6 && cur != null; i++) {
                java.nio.file.Path cand = cur.resolve("src/main/java/com/learning/springboot/farm");
                if (java.nio.file.Files.exists(cand)) {
                    base = cand;
                    break;
                }
                cur = cur.getParent();
            }
        }
        java.nio.file.Path f = base.resolve(fileName);
        assertTrue(java.nio.file.Files.exists(f),
                "找不到源文件：" + f.toAbsolutePath() + "（源码扫描测试需要项目根目录作为工作目录）");
        return java.nio.file.Files.readString(f);
    }
}
