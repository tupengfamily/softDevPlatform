package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.InputCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 资金流口径回归（2026-10-27，用户决策：<b>买入扣钱，施用不扣</b>）。
 *
 * <p><b>被修的缺陷</b>：同一批货被<b>扣了两遍钱</b>。
 * <pre>
 *   商店 buy()      → 扣钱 + 入库          （FarmShopService.java:219/235）
 *   施用 applyInput → 再扣一次钱 + 扣库存   （FarmPlayService.java:2313）
 *   灌溉 performAction → 再扣一次钱 + 扣次数 （FarmPlayService.java:1782）
 * </pre>
 *
 * <p><b>后果不是「多扣了点钱」，而是整个经济闭环的账算不平</b>：
 * <ul>
 *   <li>玩家的现金支出 = 账面成本的 <b>两倍</b>，「本季投入产出比」全线失真；</li>
 *   <li>余额被双重消耗，第一次施药就可能「欠款」；</li>
 *   <li>而仓库里的货明明是<b>已经付过钱买来的</b> —— 施用只是消耗它，
 *       再扣一次等于「用一次种子收两次地租」。</li>
 * </ul>
 *
 * <p><b>本测试锁四条不变量</b>：
 * <ol>
 *   <li>施用通道<b>不碰钱包</b>（源码扫描：{@code applyInput} 里不再有扣款调用）；</li>
 *   <li>灌溉通道<b>不碰钱包</b>（同上）；</li>
 *   <li>商店 {@code buy()} 是<b>唯一</b>的扣钱出口；</li>
 *   <li>水的<b>单位换算</b>正确（库存存「次」、价格按「份」——
 *       两者混算会让水的成本虚高 20~30 倍）。</li>
 * </ol>
 */
class FarmMoneyFlowTest {

    // ==================================================================
    //  不变量一 & 二：施用 / 灌溉不碰钱包
    // ==================================================================

    /**
     * 🔴 回归：施用农资<b>不扣钱</b>（钱在商店买货时已经付过了）。
     *
     * <p>用<b>源码扫描</b>而不是运行时断言 —— 因为要检验的正是
     * 「这段代码里有没有扣款调用」这件事本身，
     * 而运行时只能看到余额的最终数字（分不清是扣了一次还是两次）。
     */
    @Test
    @DisplayName("🔴 施用农资不扣钱（applyInput 里没有任何扣款调用）")
    void applyInputDoesNotChargeWallet() throws Exception {
        String src = readSource("FarmPlayService.java");
        String body = stripComments(methodBody(src, "public Map<String, Object> applyInput("));

        assertFalse(body.contains("chargeOrOwe("),
                "applyInput 里不该再有 chargeOrOwe() —— 施用不扣钱（钱在商店买货时付）");
        assertFalse(body.contains("shopService.spend("),
                "applyInput 里不该再有 shopService.spend() —— 施用不扣钱");
        assertFalse(body.contains("shopService.earn("),
                "applyInput 里不该有 earn() —— 施用不产生收入");
    }

    /**
     * 🔴 回归：灌溉<b>不扣钱</b>，只扣次数。
     *
     * <p>水在商店买「份」时已经付过钱了（¥6/份 = 20 次），
     * 灌溉一次只是消耗 1 次额度 —— 再扣一次钱就是同一份水收两次费。
     */
    @Test
    @DisplayName("🔴 灌溉不扣钱（performAction 里没有扣款调用）")
    void irrigationDoesNotChargeWallet() throws Exception {
        String src = readSource("FarmPlayService.java");
        String body = stripComments(methodBody(src, "public Map<String, Object> performAction("));

        assertFalse(body.contains("chargeOrOwe("),
                "performAction 里不该再有 chargeOrOwe() —— 灌溉只扣次数，不扣钱");
        assertFalse(body.contains("shopService.spend("),
                "performAction 里不该再有 shopService.spend() —— 灌溉只扣次数，不扣钱");
    }

    /**
     * 🔴 回归：{@code FarmShopService} 里<b>不再有第二个扣款出口</b>。
     *
     * <p>资金流必须收敛成：{@code buy()} 扣钱、{@code earn()} 进账。
     * 曾经存在的 {@code spend()} 注释写着「花钱（施用农资、灌溉等消耗）」，
     * 但描述的是一个不存在的资金流 —— 接上它就是重复扣款的根源。
     */
    @Test
    @DisplayName("🔴 商店是唯一扣钱出口（spend() 已删除）")
    void shopIsTheOnlyChargeExit() throws Exception {
        String src = readSource("FarmShopService.java");
        String code = stripComments(src);

        assertFalse(code.contains("public boolean spend("),
                "FarmShopService.spend() 应该已删除 —— 施用/灌溉不碰钱包，留着会诱导再接一次");

        // buy() 里必须真的扣钱（资金流的另一半）
        String buy = stripComments(methodBody(src, "public Map<String, Object> buy("));
        assertTrue(buy.contains("setBalance(") && buy.contains("setTotalExpense("),
                "buy() 必须扣余额并累计总支出 —— 这是唯一的扣钱出口");
    }

    /**
     * 🔴 回归：全仓<b>没有第三处扣款</b>。
     *
     * <p>防止下一个人又在别处加一个 {@code spend()} 调用。
     * 扫描范围是 farm 包的全部源文件。
     */
    @Test
    @DisplayName("🔴 全仓没有任何 spend()/chargeOrOwe() 调用（资金流唯一）")
    void noOtherChargeCallSitesExist() throws Exception {
        List<String> offenders = new ArrayList<>();
        java.nio.file.Path dir = farmSourceDir();
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // ⚠️ 必须去掉注释再扫 —— 否则「注释里提到 chargeOrOwe」也算命中，
                //    而注释恰恰是解释「为什么删掉它」的地方。
                String code = stripComments(java.nio.file.Files.readString(f));
                if (code.contains(".spend(") || code.contains("chargeOrOwe(")) {
                    offenders.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下文件里仍有扣款调用（资金流必须只在商店 buy 发生）: " + offenders);
    }

    // ==================================================================
    //  不变量三：成本摊销语义
    // ==================================================================

    /**
     * 🔴 回归：{@code FarmAction.cost} 的语义是「成本摊销」，不是实付额。
     *
     * <p>施用一次的成本摊销 = 用量 × 单价（与 {@code applyInput} 里的
     * {@code cost = round(dose * pricePerUnit, 2)} 一致）。
     * 这个数字进「本季投入」核算，是农艺信息（每亩用了多少钱的肥药）。
     */
    @Test
    @DisplayName("🔴 施用成本摊销 = 用量 × 单价（供本季投入核算）")
    void applicationCostIsAmortizedDoseTimesUnitPrice() {
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (it.kind() != InputCatalog.Kind.FERT
                    && it.kind() != InputCatalog.Kind.PEST
                    && it.kind() != InputCatalog.Kind.HERB) {
                continue;
            }
            double cost = Math.round(it.doseOpt() * it.pricePerUnit() * 100) / 100.0;
            assertTrue(cost > 0, it.name() + " 按推荐量施一次必须有正的摊销成本");
            // 半量 → 半价（线性）
            double half = Math.round(it.doseOpt() / 2 * it.pricePerUnit() * 100) / 100.0;
            assertEquals(Math.round(cost / 2 * 100) / 100.0, half, 1e-6,
                    it.name() + " 的成本摊销必须线性于用量");
        }
    }

    /**
     * 🔴 回归：灌溉的成本摊销 = 份价 ÷ 次数（不是份价全额）。
     *
     * <p>水按「份」定价、库存存「次」。若摊销按份价算，
     * 一次灌溉的成本会被放大 20~30 倍。
     */
    @Test
    @DisplayName("🔴 灌溉成本摊销 = 份价 ÷ 次数（灌满一份的总摊销 == 份价）")
    void irrigationAmortizationIsPackPriceDividedByTimes() {
        for (InputCatalog.Item w : InputCatalog.waters()) {
            assertTrue(w.protectDays() > 0, w.name() + " 每份应含正数次灌溉");
            double perUse = w.pricePerUnit() / w.protectDays();
            assertTrue(perUse > 0, w.name() + " 每次灌溉的摊销成本必须是正数");
            assertEquals(w.pricePerUnit(), perUse * w.protectDays(), 1e-6,
                    w.name() + " 灌满一份的总摊销必须等于份价");
        }
    }

    // ==================================================================
    //  不变量四：单位换算（水的成本曾虚高 20~30 倍）
    // ==================================================================

    /**
     * 🔴 回归：单位换算唯一出口 {@code unitsPerSellUnit} 的口径正确。
     *
     * <p><b>「按份卖、按量用」的商品有两类</b>：
     * <ul>
     *   <li><b>水</b>：1 份 = {@code protectDays} 次 → 库存存「次」，价格按「份」，
     *       所以「每入库 1 次对应的成本」= 份价 ÷ 次数；</li>
     *   <li><b>种子</b>（2026-10-28 新增）：1 包 = {@code protectDays} 亩 →
     *       库存存「亩」，价格按「包」，所以「每入库 1 亩对应的成本」= 份价 ÷ 亩数。</li>
     * </ul>
     *
     * <p>其它商品：库存单位 == 售卖单位 → 换算系数 = 1。
     */
    @Test
    @DisplayName("🔴 单位换算：水/种子的每「单位」成本 = 份价 ÷ 次数，其它商品为 1")
    void unitConversionIsCorrect() {
        for (InputCatalog.Item w : InputCatalog.waters()) {
            double units = FarmStockService.unitsPerSellUnit(w);
            assertEquals(w.protectDays(), units, 1e-9,
                    w.name() + " 的单位换算系数必须是「每份含几次」");
        }
        // 🔴 2026-10-28：种子同样是「按份卖、按亩用」，换算系数 = 每包可播亩数。
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            double units = FarmStockService.unitsPerSellUnit(s);
            assertEquals(s.protectDays(), units, 1e-9,
                    s.name() + " 的单位换算系数必须是「每包可播几亩」");
            assertTrue(s.protectDays() > 0, s.name() + " 每包应能播正数亩");
        }
        for (InputCatalog.Item it : InputCatalog.all().values()) {
            if (it.kind() == InputCatalog.Kind.WATER || it.kind() == InputCatalog.Kind.SEED) {
                continue;
            }
            assertEquals(1.0, FarmStockService.unitsPerSellUnit(it), 1e-9,
                    it.name() + " 的库存单位与售卖单位一致，换算系数应为 1");
        }
    }

    /**
     * 🔴 回归（2026-10-28）：买 1 包种子的<b>账面成本</b>必须等于包价。
     *
     * <p>这是「种子的估值虚高 N 倍」这个缺陷的直接断言。
     * 买 1 包中稻种子（¥28，可播 5 亩）→ 库存 +5 亩 → 账面成本 ¥28（不是 ¥140）。
     */
    @Test
    @DisplayName("🔴 买 1 包种子的账面成本 = 包价（不是包价 × 亩数）")
    void buyingOnePackOfSeedCostsThePackPrice() {
        for (InputCatalog.Item s : InputCatalog.seeds()) {
            int mu = s.protectDays();
            double addQty = mu;                    // 商店换算：买 1 包 → 入库 N 亩
            double cost = addQty * s.pricePerUnit() / FarmStockService.unitsPerSellUnit(s);
            assertEquals(s.pricePerUnit(), cost, 1e-6,
                    s.name() + " 买 1 包（" + mu + " 亩）的账面成本必须是包价 ¥"
                            + s.pricePerUnit() + "，实际算成 ¥" + cost
                            + " —— 这会让仓库「农资累计投入」虚高 " + mu + " 倍");
        }
    }

    /**
     * 🔴 回归：买 1 份水的<b>账面成本</b>必须等于份价（不是份价 × 次数）。
     *
     * <p>这是「水的成本虚高 20~30 倍」这个缺陷的直接断言。
     * 买 1 份自来水（¥6，20 次）→ 库存 +20 次 → 账面成本 ¥6（不是 ¥120）。
     */
    @Test
    @DisplayName("🔴 买 1 份水的账面成本 = 份价（不是份价 × 次数）")
    void buyingOnePackOfWaterCostsThePackPrice() {
        for (InputCatalog.Item w : InputCatalog.waters()) {
            int times = w.protectDays();
            double addQty = times;                 // 商店换算：买 1 份 → 入库 N 次
            double cost = addQty * w.pricePerUnit() / FarmStockService.unitsPerSellUnit(w);
            assertEquals(w.pricePerUnit(), cost, 1e-6,
                    w.name() + " 买 1 份（" + times + " 次）的账面成本必须是份价 ¥"
                            + w.pricePerUnit() + "，实际算成 ¥" + cost
                            + " —— 这会让仓库「农资累计投入」虚高 " + times + " 倍");
        }
    }

    // ==================================================================
    //  辅助
    // ==================================================================

    private static String readSource(String fileName) throws Exception {
        java.nio.file.Path f = farmSourceDir().resolve(fileName);
        assertTrue(java.nio.file.Files.exists(f),
                "找不到源文件：" + f.toAbsolutePath() + "（源码扫描测试需要项目根目录作为工作目录）");
        return java.nio.file.Files.readString(f);
    }

    /**
     * 去掉注释（行注释 {@code //} 与块注释 {@code /* *}{@code /}），只留可执行代码。
     *
     * <p>🔴 为什么必须去注释再扫：解释性注释里<b>经常要提到被删掉的方法名</b>
     * （比如「原来这里调 chargeOrOwe()」）。带着注释扫，这些历史说明会被
     * 误判成「代码还在调它」—— 测试就会假红，而且改注释就得改测试，
     * 逼着人不去写清楚为什么。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        boolean inLine = false;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
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
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 抽取某个方法的方法体（从方法签名起，到<b>大括号配对</b>的右括号为止）。
     *
     * <p>🔴 2026-10-29 <b>为什么不能靠 {@code "\n    }"} 猜结尾</b>：
     * <ul>
     *   <li>原实现找的是「下一个 4 空格缩进的右括号」——
     *       但方法体<b>内部</b>的 {@code if}/{@code for} 块闭合行同样是 4 空格缩进，
     *       于是会提前截断；</li>
     *   <li>更致命的是<b>重载方法</b>：{@code buy(userId, itemId, qty)} 委托给
     *       {@code buy(userId, itemId, qty, farmId)}，两个重载各自独立。
     *       按签名取到的是第一个重载（只有一行 {@code return ...}），
     *       <b>真正的扣款逻辑在第二个重载里</b> —— 于是
     *       「buy() 必须扣余额」这条断言读的是空壳，<b>假绿</b>。</li>
     * </ul>
     *
     * <p>现在改成<b>按大括号配对</b>精确提取，并且把<b>所有同名重载</b>拼起来 ——
     * 断言的语义是「这个方法（及其重载）里必须有扣款调用」，
     * 那就应该把重载全算上，而不是只看其中一个。
     */
    private static String methodBody(String src, String signatureStart) {
        StringBuilder all = new StringBuilder();
        int cursor = 0;
        while (true) {
            int s = src.indexOf(signatureStart, cursor);
            if (s < 0) break;
            int bodyStart = src.indexOf('{', s);
            assertTrue(bodyStart > s, "方法签名后找不到 '{'：" + signatureStart);
            int end = matchingBrace(src, bodyStart);
            assertTrue(end > bodyStart, "找不到方法结尾：" + signatureStart);
            all.append(src, s, end + 1).append('\n');
            cursor = end + 1;
        }
        assertTrue(all.length() > 0, "找不到方法体：" + signatureStart);
        return all.toString();
    }

    /**
     * 从 {@code open} 处的 '{' 起，按大括号配对找到匹配的 '}'（跳过字符串与注释）。
     *
     * @param open 指向 '{' 的下标
     * @return 匹配 '}' 的下标
     */
    private static int matchingBrace(String src, int open) {
        int depth = 0;
        boolean inBlock = false;
        boolean inLine = false;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inBlock) {
                if (c == '*' && n == '/') { inBlock = false; i++; }
                continue;
            }
            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '/' && n == '/') { inLine = true; i++; continue; }
            if (c == '/' && n == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static java.nio.file.Path farmSourceDir() {
        java.nio.file.Path base = java.nio.file.Paths.get("src/main/java/com/learning/springboot/farm");
        if (!java.nio.file.Files.exists(base)) {
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
        assertTrue(java.nio.file.Files.exists(base),
                "找不到 farm 源码目录（源码扫描测试需要项目根目录作为工作目录）");
        return base;
    }
}
