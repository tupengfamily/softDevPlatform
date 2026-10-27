package com.learning.springboot.farm.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * 「3D 形态标识」与「形态族」的解析层（2026-10-24）。
 *
 * <p><b>为什么需要这一层</b>：{@link CropConfig#visual()} 原来存的是<b>通用形态族</b>
 * （{@code leafy / vine / bush / flower}），10 种园艺作物挤在 4 个族里——
 * 番茄和辣椒都叫 {@code bush}、黄瓜和西瓜都叫 {@code vine}、菊花和郁金香都叫 {@code flower}。
 * 前端只能按族加载贴图，于是「草莓长得像番茄、西瓜长得像黄瓜」，
 * 每种作物没有自己的 3D 标识。
 *
 * <p><b>新语义</b>：{@code visual} = <b>作物 id</b>（如 {@code cabbage}），
 * 前端可以按作物 id 加载专属贴图。
 *
 * <p><b>但存量数据不能作废</b>：{@code t_farm_season.visual} 里存的是旧值
 * （{@code leafy / vine / bush / flower}），历史季节记录继续用旧值，
 * <b>不做数据库迁移</b>（风险大且没必要，前端会做兜底映射）。
 * 所以凡是「按形态族取参数」的逻辑，都<b>不能直接吃 visual 字符串</b>，
 * 必须先经 {@link #formOf} 解析成形态族。
 *
 * <p><b>为什么用「形态族」而不是直接给每个作物写一份参数</b>：
 * 形态族是<b>农艺口径</b>（无霜期需求、适宜 pH、病虫害清单），
 * 这些参数按「栽培型」分，而不是按「贴图」分。
 * 一个新的 visual 值（比如 {@code grape}）若没登记形态族，
 * {@link #formOf} 会退回到 {@link #DEFAULT_FORM}，行为与改造前一致（不会崩、不会算错）。
 *
 * <p><b>改造前后的一致性保证</b>：4 个粮食作物的 visual 保持原值
 * （{@code rice / wheat / corn / soybean}）——它们既是前端已有贴图，
 * 也是存量数据库里最常见的值，不能动。
 * 它们的形态族参数（无霜期、pH）通过 {@link #minFrostFreeFor} /
 * {@link #phRangeFor} 里的<b>显式特例</b>保持与改造前逐个一致。
 */
public final class CropVisual {

    private CropVisual() {
    }

    /**
     * 形态族：决定「无霜期需求 / 适宜 pH / 病虫害清单」这类<b>栽培型</b>参数。
     *
     * <p>它<b>不是</b> 3D 贴图标识 —— 贴图标识是 {@code visual} 本身（作物 id）。
     */
    public enum Form {
        /** 禾谷类（水稻/小麦/玉米/大豆/谷子） */
        GRAIN,
        /** 叶菜类（大白菜/萝卜） */
        LEAFY,
        /** 瓜类与藤本（黄瓜/西瓜/甜瓜/豆角/葡萄） */
        VINE,
        /** 茄果类（番茄/辣椒/茄子/草莓） */
        BUSH,
        /** 花卉（菊花/向日葵/郁金香/月季） */
        FLOWER,
        /** 块茎类（马铃薯） */
        TUBER,
        /** 仁果类（梨） */
        TREE
    }

    /**
     * 形态族缺省值。
     *
     * <p><b>为什么是 GRAIN 而不是报错</b>：改造前 {@code minFrostFree} 的 default 分支
     * 就是 130 天、{@code phRange} 的 default 是 {@code 5.5–7.0}，
     * 且 {@code null} 输入也落到同一个值。
     * 保持这个行为，新登记的 visual 不会因为「忘了归族」就让评级算出离谱结果。
     */
    public static final Form DEFAULT_FORM = Form.GRAIN;

    /**
     * visual（作物 id 或旧形态族值）→ 形态族。
     *
     * <p><b>注意这里同时收录了「旧形态族值」和「新的作物 id」</b>：
     * 前者用于兼容存量 {@code t_farm_season.visual}，
     * 后者用于新语义下按作物 id 解析。
     */
    private static final Map<String, Form> FORMS = new HashMap<>();

    static {
        // ---- 旧形态族值（存量 t_farm_season.visual 兼容）----
        FORMS.put("rice", Form.GRAIN);
        FORMS.put("wheat", Form.GRAIN);
        FORMS.put("corn", Form.GRAIN);
        FORMS.put("soybean", Form.GRAIN);
        FORMS.put("leafy", Form.LEAFY);
        FORMS.put("vine", Form.VINE);
        FORMS.put("bush", Form.BUSH);
        FORMS.put("flower", Form.FLOWER);

        // ---- 粮食 ----
        FORMS.put("rice_mid", Form.GRAIN);
        FORMS.put("wheat_winter", Form.GRAIN);
        FORMS.put("corn_spring", Form.GRAIN);
        FORMS.put("millet", Form.GRAIN);

        // ---- 蔬菜 ----
        FORMS.put("cabbage", Form.LEAFY);
        FORMS.put("radish", Form.LEAFY);
        FORMS.put("tomato", Form.BUSH);
        FORMS.put("chili", Form.BUSH);
        FORMS.put("eggplant", Form.BUSH);
        FORMS.put("strawberry", Form.BUSH);
        FORMS.put("cucumber", Form.VINE);
        FORMS.put("watermelon", Form.VINE);
        FORMS.put("melon", Form.VINE);
        FORMS.put("yardlong_bean", Form.VINE);
        FORMS.put("potato", Form.TUBER);

        // ---- 水果 ----
        FORMS.put("grape", Form.VINE);
        FORMS.put("pear", Form.TREE);

        // ---- 花卉 ----
        FORMS.put("chrysanthemum", Form.FLOWER);
        FORMS.put("sunflower", Form.FLOWER);
        FORMS.put("tulip", Form.FLOWER);
        FORMS.put("rose", Form.FLOWER);
    }

    /**
     * 把 visual 解析成形态族。
     *
     * <p><b>为什么所有取形态族参数的地方都要走这里</b>：
     * 改造后 {@code visual} 可能是作物 id（{@code cabbage}），
     * 也可能是存量旧值（{@code leafy}）。直接 switch visual 的写法，
     * 在新语义下会全部落到 default 分支——比如白菜的适宜 pH 从
     * {@code 6.0–7.5} 掉回通用值 {@code 5.5–7.0}，评级会无声地变差。
     */
    public static Form formOf(String visual) {
        if (visual == null || visual.isBlank()) {
            return DEFAULT_FORM;
        }
        Form f = FORMS.get(visual);
        return f != null ? f : DEFAULT_FORM;
    }

    /**
     * 形态族 → 最短无霜期（天）。
     *
     * <p>数值口径见改造前 {@code SuitabilityEngine} 的实现，<b>逐个保持不变</b>：
     * 改造 visual 语义不该顺带改农艺参数，否则历史回放结果会变，
     * 无法区分「visual 改动」与「参数改动」的影响。
     */
    public static int minFrostFree(Form form) {
        return switch (form) {
            case GRAIN -> 130;   // 与改造前 default 分支一致
            case LEAFY -> 85;    // 大白菜短季，但怕初霜，窗口比生长期略长
            case VINE -> 110;    // 瓜类全生育期怕霜
            case BUSH -> 125;    // 茄果类/草莓：花期遇霜直接绝收
            case FLOWER -> 100;
            case TUBER -> 100;   // 马铃薯：春播短季，与花卉同量级
            case TREE -> 130;    // 梨：落叶果树，春季开花怕霜
        };
    }

    /** 形态族 → 适宜 pH 区间。数值与改造前逐个保持一致。 */
    public static double[] phRange(Form form) {
        return switch (form) {
            case GRAIN -> new double[]{5.5, 7.0};  // 与改造前 default 分支一致
            case LEAFY -> new double[]{6.0, 7.5};
            case VINE -> new double[]{6.0, 7.0};
            case BUSH -> new double[]{5.8, 7.0};
            case FLOWER -> new double[]{6.0, 7.2};
            case TUBER -> new double[]{5.0, 6.5};  // 马铃薯喜微酸，忌碱性土
            case TREE -> new double[]{6.0, 7.5};   // 梨适宜中性偏酸
        };
    }

    /**
     * 取某 visual 的最短无霜期，<b>保留改造前的逐作物特例</b>。
     *
     * <p><b>为什么需要这个方法</b>：改造前 {@code minFrostFree} 里
     * {@code "wheat"} → 200 天、{@code "corn"} → 120 天、{@code "soybean"} → 110 天，
     * 三个都是独立分支，不是走 default。
     * 粮食作物的 visual 保持原值不变，所以这三个特例必须原样保留，
     * 否则冬小麦的无霜期需求会从 200 掉到 130，评级凭空变好。
     *
     * @param visual 作物 visual（可能是作物 id，也可能是存量旧值）
     * @param form   已解析出的形态族
     */
    public static int minFrostFreeFor(String visual, Form form) {
        if ("wheat".equals(visual) || "wheat_winter".equals(visual)) {
            return 200;
        }
        if ("corn".equals(visual) || "corn_spring".equals(visual)) {
            return 120;
        }
        if ("soybean".equals(visual)) {
            return 110;
        }
        return minFrostFree(form);
    }

    /**
     * 取某 visual 的适宜 pH 区间，<b>保留改造前的逐作物特例</b>。
     *
     * <p><b>为什么需要这个方法</b>：同 {@link #minFrostFreeFor}——
     * 改造前 {@code "wheat"} → {@code 6.0–7.5}、{@code "corn"} → {@code 5.5–7.5}、
     * {@code "soybean"} → {@code 6.0–7.0}，三个都不是走 default。
     * 丢掉任何一个都会让对应作物的 pH 硬闸判定无声地变化。
     */
    public static double[] phRangeFor(String visual, Form form) {
        if ("wheat".equals(visual) || "wheat_winter".equals(visual)) {
            return new double[]{6.0, 7.5};
        }
        if ("corn".equals(visual) || "corn_spring".equals(visual)) {
            return new double[]{5.5, 7.5};
        }
        if ("soybean".equals(visual)) {
            return new double[]{6.0, 7.0};
        }
        return phRange(form);
    }

    // ==================== 栽培属性判定（2026-10-26） ====================

    /**
     * 是否<b>水田</b>（需要淹水/保水，区别于旱地）。
     *
     * <p><b>🔴 为什么不能直接判形态族</b>：水稻的形态族是 {@link Form#GRAIN}，
     * 但小麦/玉米/大豆/谷子也是 GRAIN —— 用 {@code form == GRAIN} 判水田会把
     * <b>四种旱地粮食作物全判成水田</b>，水田分支（保水、缺氧、直播湿播）
     * 会被错误套用到旱地作物上，产量与病虫害模型全错。
     *
     * <p><b>为什么必须收进本类</b>：{@code visual} 字段的语义在 2026-10-24 变过一次
     * （通用形态族 → 作物 id），现在靠「4 个粮食作物保持旧值」兼容。
     * 业务代码里散落着 {@code "rice".equals(crop.visual())} 这类判断，
     * 一旦有人把 visual 统一改成 crop id，这些判断会<b>静默失效</b> ——
     * 不报错，只是所有作物都走旱地分支，水稻完全算错。
     * 收进本类后，改动点只有一处，且有测试守着。
     *
     * <p>取值覆盖：{@code rice}（粮食作物保持的旧值）+ {@code rice_mid}（新语义下的作物 id）。
     */
    public static boolean isPaddy(String visual) {
        return "rice".equals(visual) || "rice_mid".equals(visual);
    }

    /**
     * 是否<b>豆科</b>（有固氮机制，氮素账本走另一条路）。
     *
     * <p>当前只有大豆是豆科。与 {@link #isPaddy} 同理，
     * 不能用形态族判（大豆是 GRAIN，但玉米/小麦/水稻不是豆科）。
     *
     * <p>取值覆盖：{@code soybean}（粮食作物保持的旧值）。
     */
    public static boolean isLegume(String visual) {
        return "soybean".equals(visual);
    }
}
