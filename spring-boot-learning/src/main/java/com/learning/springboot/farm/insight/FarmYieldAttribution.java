package com.learning.springboot.farm.insight;

import com.learning.springboot.farm.engine.CropConfig;
import com.learning.springboot.farm.engine.DisasterEngine;
import com.learning.springboot.farm.engine.PestEngine;
import com.learning.springboot.farm.engine.SoilEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 产量损失归因（产量四要素 × 损失来源）—— <b>唯一实现</b>。
 *
 * <p><b>为什么必须抽出来</b>：产量公式里有一段「病虫害 / 灾害 / 旱涝怎么打到
 * 穗数、颖花、结实率、粒重四个要素上」的映射，它原本<b>只存在于</b>
 * {@code FarmPlayService.yields()} 里。科学解释层要回答
 * 「减产是<b>哪个环节</b>崩的、主要来源是<b>什么</b>」，
 * 如果在解释层里再抄一份这段映射，就是本项目最常见的分叉来源
 * ——「同型代码两处维护 = 必然不一致」。
 *
 * <p><b>修法不是「解释层抄一份」，而是抽唯一实现</b>（本项目铁律）：
 * 本类算出四个要素的<b>乘性系数链</b>，{@code FarmPlayService.yields()} 直接用它，
 * 解释层也直接用它 —— 两边永远同源。
 *
 * <p><b>🔴 为什么用「系数链」而不是「一个乘好的系数」</b>：
 * 浮点乘法<b>不满足结合律</b> —— {@code (a*b)*c} 与 {@code a*(b*c)} 可能差 1 个 ULP。
 * 原表达式 {@code r.grains() * A * B * C * D} 是<b>左到右</b>相乘的，
 * 如果这里改成 {@code r.grains() * (A*B*C*D)}，产量会有 1e-16 量级的漂移，
 * 而 {@code ey} 最后要 {@code round(x, 1)} —— 理论上能翻转一位小数。
 * 所以本类保留<b>有序系数链</b>，由 {@link Chain#apply(double)} 按原顺序左到右相乘，
 * <b>结果与原表达式逐位一致</b>。
 *
 * <p><b>口径铁律</b>：
 * <ul>
 *   <li>本类<b>不改</b>任何产量数值，只做「分解」——
 *       {@code FarmPlayService} 里的乘法顺序与系数逐字保留；</li>
 *   <li>高温损伤（{@code SimEngine.Damage}）在引擎里已经<b>乘进</b>了
 *       {@code r.panicles()} / {@code r.grains()} 等，所以本类把它当第一层因子；</li>
 *   <li>「主要来源」用<b>边际贡献法</b>算：拿掉某来源的因子后，
 *       剩余部分能补回多少损失 —— 不是简单相加（因为各来源是<b>连乘</b>的）。</li>
 * </ul>
 */
public final class FarmYieldAttribution {

    private FarmYieldAttribution() {
    }

    // ==================================================================
    //  稳定标识（跨语言）
    // ==================================================================

    /** 产量要素：穗数（禾谷）/ 株数（园艺）。 */
    public static final String PANICLE = "PANICLE";
    /** 产量要素：颖花数 / 每穗粒数。 */
    public static final String FLORET = "FLORET";
    /** 产量要素：结实率。 */
    public static final String FERTILITY = "FERTILITY";
    /** 产量要素：粒重（千粒重 / 单果重）。 */
    public static final String WEIGHT = "WEIGHT";

    /** 损失来源：高温。 */
    public static final String SRC_HEAT = "HEAT";
    /** 损失来源：病虫害。 */
    public static final String SRC_PEST = "PEST";
    /** 损失来源：灾害。 */
    public static final String SRC_DISASTER = "DISASTER";
    /** 损失来源：干旱。 */
    public static final String SRC_DROUGHT = "DROUGHT";
    /** 损失来源：渍水。 */
    public static final String SRC_WATERLOG = "WATERLOG";

    /** 来源的规范顺序（用于归因展示，保证输出稳定）。 */
    private static final String[] SOURCE_ORDER = {
            SRC_HEAT, SRC_PEST, SRC_DISASTER, SRC_DROUGHT, SRC_WATERLOG
    };

    // ==================================================================
    //  输出结构
    // ==================================================================

    /**
     * 各损失通道的<b>裁剪后</b>取值。
     *
     * <p>裁剪上限与 {@code FarmPlayService.yields()} 原实现<b>逐字一致</b>：
     * 病虫害四通道 ≤0.60、灾害 ≤0.60、干旱 ≤0.30、渍水 ≤0.20。
     */
    public record Factors(
            double stemLoss, double leafLoss, double grainLoss, double flowerLoss,
            double disasterLoss, double droughtLoss, double waterlogLoss, double soilCoef) {
    }

    /** 一个损失来源及其保留系数（1 = 完全无损，0 = 全损）。 */
    public record SourceFactor(String source, double keep) {
    }

    /**
     * 一个产量要素的<b>有序乘性系数链</b>。
     *
     * <p>🔴 <b>顺序即语义</b>：{@link #apply(double)} 必须按链上的顺序左到右相乘，
     * 才能与 {@code FarmPlayService.yields()} 的原表达式<b>逐位一致</b>。
     */
    public record Chain(String key, List<SourceFactor> factors) {

        /** 把系数链乘到基数上（<b>左到右</b>，与原表达式同序）。 */
        public double apply(double base) {
            double v = base;
            for (SourceFactor f : factors) {
                v = v * f.keep();
            }
            return v;
        }

        /** 整条链的乘积。 */
        public double product() {
            return apply(1.0);
        }

        /**
         * 某个来源的<b>合并</b>保留系数（该来源在链上所有因子的乘积）。
         *
         * <p>用于「主要来源」的边际贡献计算：同一来源可能占链上多个位置
         * （如 FLORET 链上「花器受损」与「叶片/籽粒受损」都归 PEST），
         * 归因时它们应合成一个来源。
         */
        public double mergedKeep(String source) {
            double v = 1;
            for (SourceFactor f : factors) {
                if (f.source().equals(source)) {
                    v = v * f.keep();
                }
            }
            return v;
        }
    }

    /** 四个要素的乘性系数链（非高温来源）。 */
    public record ElementFactors(Chain panicle, Chain grain, Chain fertility, Chain weight) {
    }

    /**
     * 一个产量要素的损失分解。
     *
     * @param key        要素标识（{@link #PANICLE} 等）
     * @param totalLoss  总损失率（0..1，含高温与所有非高温来源）
     * @param heatLoss   其中高温造成的损失率（0..1）
     * @param mainSource 贡献最大的损失来源标识（{@link #SRC_HEAT} 等）
     */
    public record Element(String key, double totalLoss, double heatLoss, String mainSource) {
    }

    // ==================================================================
    //  通道裁剪（唯一实现）
    // ==================================================================

    /**
     * 各损失通道的裁剪后取值。
     *
     * <p>🔴 <b>系数逐字来自 {@code FarmPlayService.yields()} 的原实现</b>。
     * 改这里等于改产量 —— 必须同步跑全量回归。
     *
     * @param droughtRelief 农具给的干旱减免比例（滴灌 0.5）
     */
    public static Factors factorsOf(PestEngine.Result pr, DisasterEngine.Result dr,
                                    SoilEngine.Result sr, double droughtRelief) {
        double stem = clamp01(pr == null ? 0 : pr.stemLoss(), 0.60);
        double leaf = clamp01(pr == null ? 0 : pr.leafLoss(), 0.60);
        double grain = clamp01(pr == null ? 0 : pr.grainLoss(), 0.60);
        double flower = clamp01(pr == null ? 0 : pr.flowerLoss(), 0.60);

        double dis = clamp01(dr == null ? 0 : dr.totalLoss(), 0.60);
        double drought = clamp01(sr == null ? 0 : sr.droughtLoss(), 0.30) * (1 - droughtRelief);
        double waterlog = clamp01(sr == null ? 0 : sr.waterlogLoss(), 0.20);
        double soil = clamp01(sr == null ? 0.87 : sr.soilCoef(), 1.0);

        return new Factors(stem, leaf, grain, flower, dis, drought, waterlog, soil);
    }

    // ==================================================================
    //  要素系数链（唯一实现）
    // ==================================================================

    /**
     * 四个要素的<b>非高温</b>乘性系数链。
     *
     * <p>🔴 <b>逐字来自 {@code FarmPlayService.yields()} 的原实现</b>，
     * 且<b>因子顺序与原表达式一致</b>：
     * <pre>
     *   panicles  = r.panicles()   × (1-stem) × (1-waterlog*0.6)
     *   grains    = r.grains()     × (1-flower) × (1-grain*0.8-leaf*0.2)
     *                                  × (1-dis*0.6) × (1-drought*0.8)
     *   fertility = r.fertility()  × (1-flower)
     *   weight    = r.weight()     × (1-leaf*0.5-grain*0.3) × (1-dis*0.4)
     * </pre>
     */
    public static ElementFactors elementFactors(Factors f) {
        Chain panicle = new Chain(PANICLE, List.of(
                new SourceFactor(SRC_PEST, 1 - f.stemLoss()),
                new SourceFactor(SRC_WATERLOG, 1 - f.waterlogLoss() * 0.6)));

        Chain grain = new Chain(FLORET, List.of(
                new SourceFactor(SRC_PEST, 1 - f.flowerLoss()),
                new SourceFactor(SRC_PEST, 1 - f.grainLoss() * 0.8 - f.leafLoss() * 0.2),
                new SourceFactor(SRC_DISASTER, 1 - f.disasterLoss() * 0.6),
                new SourceFactor(SRC_DROUGHT, 1 - f.droughtLoss() * 0.8)));

        Chain fertility = new Chain(FERTILITY, List.of(
                new SourceFactor(SRC_PEST, 1 - f.flowerLoss())));

        Chain weight = new Chain(WEIGHT, List.of(
                new SourceFactor(SRC_PEST, 1 - f.leafLoss() * 0.5 - f.grainLoss() * 0.3),
                new SourceFactor(SRC_DISASTER, 1 - f.disasterLoss() * 0.4)));

        return new ElementFactors(panicle, grain, fertility, weight);
    }

    // ==================================================================
    //  要素分解
    // ==================================================================

    /**
     * 四个产量要素的损失分解。
     *
     * @param crop 作物配置（取各通道的封顶 cap）
     * @param heat 四通道的高温损伤率（已按各自 cap 裁剪）：
     *              {@code [0]=PANICLE [1]=FLORET [2]=FERTILITY [3]=WEIGHT}
     * @param f    损失通道系数
     */
    public static Element[] elements(CropConfig crop, double[] heat, Factors f) {
        ElementFactors ef = elementFactors(f);

        double heatPan = heatOf(heat, 0, capPan(crop));
        double heatFloret = heatOf(heat, 1, capFloret(crop));
        double heatFert = heatOf(heat, 2, capFert(crop));
        double heatWt = heatOf(heat, 3, capWt(crop));

        Element[] out = new Element[4];
        out[0] = element(PANICLE, heatPan,
                prepend(SRC_HEAT, 1 - heatPan, ef.panicle()));
        out[1] = element(FLORET, heatFloret,
                prepend(SRC_HEAT, 1 - heatFloret, ef.grain()));
        out[2] = element(FERTILITY, heatFert,
                prepend(SRC_HEAT, 1 - heatFert, ef.fertility()));
        out[3] = element(WEIGHT, heatWt,
                prepend(SRC_HEAT, 1 - heatWt, ef.weight()));
        return out;
    }

    /** 把高温因子加到系数链最前面（高温在引擎里已是第一层损失）。 */
    private static Chain prepend(String source, double keep, Chain chain) {
        List<SourceFactor> list = new ArrayList<>(chain.factors().size() + 1);
        list.add(new SourceFactor(source, keep));
        list.addAll(chain.factors());
        return new Chain(chain.key(), list);
    }

    /**
     * 单个要素的分解。
     *
     * <p>「主要来源」用<b>边际贡献</b>算：假设其它来源都在，
     * 拿掉某来源因子后损失会少多少 —— 因为各来源是<b>连乘</b>的，
     * 直接相加会高估。
     *
     * @param heatLoss 该要素的高温损失率（已按 cap 裁剪）
     */
    private static Element element(String key, double heatLoss, Chain chain) {
        double totalLoss = clamp01(1 - chain.product(), 1);

        // 逐来源合并保留系数（同一来源在链上可能占多位）
        double[] keeps = new double[SOURCE_ORDER.length];
        boolean[] present = new boolean[SOURCE_ORDER.length];
        for (int i = 0; i < SOURCE_ORDER.length; i++) {
            keeps[i] = chain.mergedKeep(SOURCE_ORDER[i]);
            present[i] = keeps[i] < 0.9999995;
        }

        String main = SRC_HEAT;
        double best = -1;
        for (int i = 0; i < SOURCE_ORDER.length; i++) {
            if (!present[i]) {
                continue;
            }
            // 拿掉这个来源后，剩余来源能补回多少损失
            double without = 1;
            for (int j = 0; j < SOURCE_ORDER.length; j++) {
                if (j != i && present[j]) {
                    without = without * keeps[j];
                }
            }
            double contribution = without - without * keeps[i];
            if (contribution > best) {
                best = contribution;
                main = SOURCE_ORDER[i];
            }
        }
        return new Element(key, totalLoss, heatLoss, best <= 0.0005 ? SRC_HEAT : main);
    }

    /** 高温损伤按该通道自己的 cap 裁剪（与 {@code SimEngine} 内部一致）。 */
    private static double heatOf(double[] heat, int idx, double cap) {
        if (heat == null || idx >= heat.length || heat[idx] == 0) {
            return 0;
        }
        return clamp01(heat[idx], cap <= 0 ? 1 : cap);
    }

    private static double capPan(CropConfig c) {
        return c == null ? 1 : c.capPan();
    }

    private static double capFloret(CropConfig c) {
        return c == null ? 1 : c.capFloret();
    }

    private static double capFert(CropConfig c) {
        return c == null ? 1 : c.capFert();
    }

    private static double capWt(CropConfig c) {
        return c == null ? 1 : c.capWt();
    }

    private static double clamp01(double v, double max) {
        return Math.max(0, Math.min(max, v));
    }
}
