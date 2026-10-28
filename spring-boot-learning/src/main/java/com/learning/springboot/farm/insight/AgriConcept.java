package com.learning.springboot.farm.insight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农业科学概念目录 —— <b>8 个概念的稳定标识与教学内核的唯一出口</b>（2026-10-28 P2）。
 *
 * <p><b>这个类解决什么问题</b>：P2 要把「稼穑」接进平台学习科目，
 * 让「种一次水稻」=「学完有效积温」这件事真的成立。
 * 而「一次农事动作 → 哪个概念 → 哪一课时」这条链路需要三处对齐：
 * <ol>
 *   <li><b>课程内容</b>（{@code data/agri-lessons.json}）：8 章，每章一个概念；</li>
 *   <li><b>引擎解释</b>（{@link FarmInsightService}）：每个概念的「现象→量→原理→建议」；</li>
 *   <li><b>动作映射</b>（{@code FarmLearningBridge}）：农事动作触发哪个概念的课时。</li>
 * </ol>
 * 三处如果各写一份 ID 字符串，迟早分叉 —— 章节里叫 {@code AAT}、引擎里叫 {@code aat}、
 * 映射表里叫 {@code effectiveTemp}，玩家点了「学完」却高亮不到对应卡片。
 * <b>所以概念 ID 只在这里定义一次</b>，其它地方一律引用常量。
 *
 * <p><b>🔴 ID 一经发布不可改</b>：它会写进 {@code t_learning_lesson.concept_id}
 * 与 {@code t_lesson_progress} 的关联语义。改 ID = 存量数据失联。
 *
 * <p><b>🔴 为什么不用枚举</b>：概念与「引擎解释」不是一一对应的 ——
 * {@code FarmInsightService} 只实现了 4 个概念的文案（AAT / NITROGEN / YIELD_ELEMENTS / WATER），
 * 另 4 个（地力轮作 / 热害 / 春化 / PHI）只有引擎数据还没有独立解释文案。
 * 用「枚举 + 可空解释」会让"概念存在"与"有解释文案"两件事纠缠在一起，
 * 所以这里只定义<b>标识与教学内核</b>（哪些农事动作能点亮它），
 * 文案仍然唯一地留在 {@link FarmInsightService}。
 */
public final class AgriConcept {

    private AgriConcept() {
    }

    // ==================================================================
    //  概念标识（与引擎 FarmInsightService 的 conceptId 对齐）
    // ==================================================================

    /** 概念：有效积温。 */
    public static final String AAT = "AAT";
    /** 概念：氮素报酬递减。 */
    public static final String NITROGEN = "NITROGEN";
    /** 概念：产量四要素。 */
    public static final String YIELD_ELEMENTS = "YIELD_ELEMENTS";
    /** 概念：土壤水分平衡。 */
    public static final String WATER = "WATER";
    /** 概念：地力与轮作。 */
    public static final String SOIL_ROTATION = "SOIL_ROTATION";
    /** 概念：热害与积温胁迫。 */
    public static final String HEAT_STRESS = "HEAT_STRESS";
    /** 概念：春化与越冬。 */
    public static final String VERNALIZATION = "VERNALIZATION";
    /** 概念：安全间隔期（PHI）。 */
    public static final String PHI = "PHI";

    /**
     * 一个概念的元信息。
     *
     * @param id            稳定标识（写库用，不可改）
     * @param title         中文标题
     * @param stageNo       所属阶段序号（农业科学只有 1 个阶段「农业科学基础」）
     * @param chapterNo     章节序号（1..8）
     * @param shortName     短名（用于待办标题等紧凑位置）
     * @param engineMetric  对应的引擎量（教学内核：这个概念靠哪个数解释）
     */
    public record Concept(String id, String title, int stageNo, int chapterNo,
                          String shortName, String engineMetric) {
    }

    /**
     * 8 个概念的完整目录（顺序 = 教学顺序 = 章节顺序）。
     *
     * <p>🔴 <b>顺序不是随便排的</b>：按「因果链」排 ——
     * 积温（作物长多快）→ 热害/春化（长不长的极端）→ 氮素/地力（长多壮）
     * → 水分（长得好不好）→ 产量四要素（最终收成）→ PHI（能不能卖）。
     * 玩家按这个顺序学，学到每一章都能对上前面学过的原因。
     */
    private static final List<Concept> ALL = List.of(
            new Concept(AAT, "有效积温", 1, 1, "积温", "aatCum / aatReq"),
            new Concept(HEAT_STRESS, "热害与积温胁迫", 1, 2, "热害", "heatForce / daysOverOpt"),
            new Concept(VERNALIZATION, "春化与越冬", 1, 3, "春化", "vernalizationDays / winterKilled"),
            new Concept(NITROGEN, "氮素报酬递减", 1, 4, "氮素", "nApplied / nNeed / nSatisfaction"),
            new Concept(SOIL_ROTATION, "地力与轮作", 1, 5, "地力", "sfi / soilCoef"),
            new Concept(WATER, "土壤水分平衡", 1, 6, "水分", "etToday / dryDays / waterlogDays"),
            new Concept(YIELD_ELEMENTS, "产量四要素", 1, 7, "产量四要素", "dmg.panicle / floret / fertility / weight"),
            new Concept(PHI, "安全间隔期（PHI）", 1, 8, "安全间隔期", "phiDays / phiViolated"));

    /** id -> 概念 */
    private static final Map<String, Concept> BY_ID = index();

    private static Map<String, Concept> index() {
        Map<String, Concept> m = new LinkedHashMap<>();
        for (Concept c : ALL) {
            m.put(c.id(), c);
        }
        return Map.copyOf(m);
    }

    /** 全部概念（教学顺序） */
    public static List<Concept> all() {
        return ALL;
    }

    /** 按 id 取概念；查不到返回 null（**不编造**） */
    public static Concept of(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return BY_ID.get(id);
    }

    /** 概念 id 集合（供前端/测试断言用） */
    public static List<String> ids() {
        return ALL.stream().map(Concept::id).toList();
    }

    /** 阶段名（农业科学科目的唯一阶段） */
    public static final String STAGE_NAME = "农业科学基础";
}
