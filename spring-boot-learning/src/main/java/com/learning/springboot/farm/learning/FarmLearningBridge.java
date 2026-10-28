package com.learning.springboot.farm.learning;

import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.farm.FarmAction;
import com.learning.springboot.farm.insight.AgriConcept;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「稼穑」↔「学习平台」的桥接 —— <b>实操即课时的唯一实现</b>（2026-10-28 P2）。
 *
 * <p><b>这个类解决什么问题</b>：P2 的核心承诺是
 * 「<b>种一次水稻 = 学完有效积温</b>」—— 教学与实践是<b>同一个动作</b>，不分离。
 * 要让这句话成立，需要一条完整链路：
 *
 * <pre>
 *   农事动作（播种 / 追肥 / 灌溉 / 施药 / 收割）
 *        ↓  动作 → 概念（{@link #conceptOf}）
 *   农业科学概念（AAT / NITROGEN / WATER / ...）
 *        ↓  概念 → 课时（{@code t_learning_lesson.concept_id}）
 *   课时（LearningLesson）
 *        ↓  upsert
 *   学习进度（LessonProgress）
 * </pre>
 *
 * <p>🔴 <b>这条链路每一步都必须是「唯一出口」</b>：
 * <ul>
 *   <li>动作 → 概念的映射只在 {@link #conceptOf}；</li>
 *   <li>概念 → 课时的绑定只在 {@code t_learning_lesson.concept_id}
 *       （稳定标识，不依赖标题/序号）；</li>
 *   <li>进度写入只在 {@link #schedule}（进而到 {@link LessonProgressWriter}）。</li>
 * </ul>
 * 三处各写一份就必然分叉 —— 而分叉的后果是<b>玩家做对了事却学不到东西</b>。
 *
 * <p>🔴 <b>幂等是硬要求</b>：{@code t_lesson_progress} 上有
 * {@code (user_id, lesson_id)} 唯一约束，而农事动作<b>可以重复做</b>
 * （灌溉可以灌 16 次、追肥可以追多次）。所以必须先查后写，
 * 而不是无脑 insert —— 否则第二次灌溉就会撞唯一约束抛异常，
 * 整个农事事务回滚，<b>玩家的灌溉动作被吃掉</b>。
 *
 * <p>🔴 <b>进度写入必须发生在农事事务「之外」</b>（本轮实测结论）：
 * 持久层是 SQLite，而 SQLite 同一时刻只允许<b>一个写者</b>。
 * 详见 {@link PostCommit} 的类注释 —— 为什么「嵌套独立事务」这条常见的隔离手段
 * 在 SQLite 上根本不成立，以及为什么真正的墙是<b>事务边界之外</b>。
 */
@Service
public class FarmLearningBridge {

    private static final Logger log = LoggerFactory.getLogger(FarmLearningBridge.class);

    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;
    /** 🔴 进度写入委托给它 —— 它跑在自己的事务里（REQUIRES_NEW），失败不影响农事。 */
    @Autowired private LessonProgressWriter progressWriter;

    // ==================================================================
    //  动作 → 概念 映射（唯一出口）
    // ==================================================================

    /**
     * 农事动作 → 它能点亮的农业科学概念。
     *
     * <p>🔴 <b>为什么是「动作 → 概念」而不是「概念 → 动作」</b>：
     * 玩家做动作是因，概念是果。反过来写会让「这个动作该点亮哪几个概念」
     * 变成散落在 8 个概念里的清单，改一个动作要找 8 处。
     *
     * <p><b>映射设计的原则</b>：动作必须真的<b>碰到了</b>这个概念所解释的机制。
     * 比如「追肥」点亮氮素与地力（因为地力公式里的养分满足度就来自施肥），
     * 但<b>不</b>点亮水分（追肥不改变水量账）。
     */
    public static String conceptOf(FarmAction.Type type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            // 施肥：氮素账本 + 地力（含过量施肥惩罚）
            case FERTILIZE, BASE_FERT, TILLER_FERT, PANICLE_FERT -> AgriConcept.NITROGEN;
            // 灌溉/排水：土壤水分平衡
            case IRRIGATE, DRAIN, DRAIN_FINAL -> AgriConcept.WATER;
            // 打药/除草：安全间隔期（PHI 唯一相关的动作）
            case PESTICIDE -> AgriConcept.PHI;
            // 除草剂：抢水抢肥 → 影响的是养分与水分，先归到水分（争水）
            case WEED -> AgriConcept.WATER;
            // 晒田：断水控蘖 → 水分
            case DRY_FIELD -> AgriConcept.WATER;
            // 整地/插秧：建立群体 → 穗数
            case PUDDLE, TRANSPLANT -> AgriConcept.YIELD_ELEMENTS;
            // 揭帘：保温防冻 → 越冬
            case UNCOVER -> AgriConcept.VERNALIZATION;
            // 烘干：收后处理
            case DRY_GRAIN -> null;
        };
    }

    /**
     * 播种动作对应的概念（播种是「开始一季」，点亮积温 —— 作物从这天开始攒积温）。
     *
     * <p>🔴 <b>为什么播种单独一个入口</b>：{@link #conceptOf(FarmAction.Type)} 处理的是
     * {@code FarmAction.Type}，而<b>播种不是农事动作</b>（它是建地块时定的），
     * 所以没有对应的 Type。这个概念不能被塞进 Type 枚举 ——
     * 那会让「播种」看起来像一种可以重复做的农事。
     */
    public static final String CONCEPT_ON_SOW = AgriConcept.AAT;

    // ==================================================================
    //  进度写入（唯一出口）
    // ==================================================================

    /**
     * 把「点亮某概念的课时」排进<b>农事事务提交之后</b>执行。
     *
     * <p>🔴 <b>为什么是「排进提交之后」而不是「当场写」</b>：
     * 当场写 = 学习进度加入农事事务 → 一旦它失败（哪怕是被 catch 住的），
     * 共享事务已被标记 rollback-only → 农事提交时炸 500，
     * <b>玩家正确做的一次农事被整个吃掉</b>。
     * 而「嵌套一个独立事务」在 SQLite 上也不行（单写者限制）。
     * 所以唯一既安全又可行的位置就是<b>农事事务之外</b> ——
     * 见 {@link PostCommit}。
     *
     * <p>🔴 <b>失败语义</b>：提交后写入失败只记日志，农事结果<b>不受影响</b>；
     * 且写入是<b>幂等</b>的，下次做同类动作会补上漏掉的课时。
     *
     * @param userId    用户
     * @param conceptId 概念标识（{@link AgriConcept} 的常量）；null = 本次不点亮任何概念
     * @return 本次实操将点亮的课时 id（<b>同步预估</b>：已学过的已排除）；
     *         实际落库发生在农事事务提交之后
     */
    public List<Long> schedule(Long userId, String conceptId) {
        if (userId == null || conceptId == null) {
            return List.of();
        }
        List<Long> pending = pendingLessons(userId, conceptId);
        PostCommit.run("写学习进度 concept=" + conceptId,
                () -> progressWriter.markLearned(userId, conceptId));
        return pending;
    }

    /**
     * 收割后点亮多个概念（一次收割把「这一季学到的」全部点亮）。
     *
     * <p>🔴 <b>为什么收割要点亮多个概念</b>：收割是「一季的总结时刻」，
     * 此时玩家已经经历了积温、氮素、水分、热害、产量四要素的全过程。
     * 但我们**不能**只凭「玩家收了一季」就说他学会了 —— 那不叫教学。
     *
     * <p>所以口径是：<b>收割时只点亮「这一季实际产生过数据」的概念</b>，
     * 由调用方传入 {@code observedConcepts}（来自引擎实算的解释列表）。
     * 没经历过的（比如种的是不需要春化的作物）就不点亮春化那章。
     *
     * <p>🔴 <b>只排一个回调</b>：8 个概念排 8 个回调 = 8 次事务往返；
     * 合成一个回调后只开一次事务，且顺序更可控。
     *
     * @return 本次实操将点亮的课时 id（跨概念合并）
     */
    public List<Long> scheduleAll(Long userId, Collection<String> conceptIds) {
        if (userId == null || conceptIds == null || conceptIds.isEmpty()) {
            return List.of();
        }
        Set<String> valid = new LinkedHashSet<>();
        for (String conceptId : conceptIds) {
            if (AgriConcept.of(conceptId) != null) {
                valid.add(conceptId);
            }
        }
        if (valid.isEmpty()) {
            return List.of();
        }
        List<Long> pending = new ArrayList<>();
        for (String conceptId : valid) {
            pending.addAll(pendingLessons(userId, conceptId));
        }
        PostCommit.run("写学习进度 concepts=" + valid,
                () -> {
                    for (String conceptId : valid) {
                        progressWriter.markLearned(userId, conceptId);
                    }
                });
        return pending;
    }

    /**
     * 该概念下「尚未学过」的课时 id（同步只读，供响应展示）。
     *
     * <p>🔴 <b>为什么要同步算一份</b>：农事响应里要告诉玩家「这次动作学到了什么」，
     * 而真正落库在提交之后（那时响应早已返回）。这里算的是<b>同一套判定</b>
     * （同一个 exists 查询），只是时点不同，不会与实际落库口径分叉。
     */
    private List<Long> pendingLessons(Long userId, String conceptId) {
        List<Long> out = new ArrayList<>();
        for (LearningLesson l : lessonRepository.findByConceptIdOrdered(conceptId)) {
            if (!progressRepository.existsByUserIdAndLessonId(userId, l.getId())) {
                out.add(l.getId());
            }
        }
        return out;
    }

    // ==================================================================
    //  概念卡解锁状态（前端入口）
    // ==================================================================

    /**
     * 概念卡的解锁状态（8 个概念 × 课时进度）。
     *
     * <p>🔴 <b>为什么这个口径是「课时进度」而不是「有没有做过某个动作」</b>：
     * 概念卡是「学习卡」，它的解锁条件应该和<b>课程进度</b>一致 ——
     * 否则前端显示「已解锁」而学习看板显示「未学」，玩家看到两个矛盾的答案。
     * 而课时进度本身又是由实操推进的，两者天然同源。
     *
     * @return 每个概念：conceptId / title / chapterNo / totalLessons / learnedLessons /
     *         learnedPercent / unlocked
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> conceptCards(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (userId == null) {
            return out;
        }
        // 🔴 逐概念查（8 次），每次只取该概念的少量课时 —— 可接受。
        // 不用「先查全部再分组」是因为 {@code findByConceptIdOrdered} 按单概念查询，
        // 强行拼成 IN 查询会为了省 7 次往返而写出一坨更复杂的代码。
        for (AgriConcept.Concept c : AgriConcept.all()) {
            List<LearningLesson> ls = lessonRepository.findByConceptIdOrdered(c.id());
            int total = ls.size();
            int learned = 0;
            for (LearningLesson l : ls) {
                if (progressRepository.existsByUserIdAndLessonId(userId, l.getId())) {
                    learned++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("conceptId", c.id());
            row.put("title", c.title());
            row.put("shortName", c.shortName());
            row.put("chapterNo", c.chapterNo());
            row.put("engineMetric", c.engineMetric());
            row.put("totalLessons", total);
            row.put("learnedLessons", learned);
            row.put("learnedPercent", total <= 0 ? 0 : (int) Math.round(learned * 100.0 / total));
            // 🔴 解锁 = 该概念的课时全部学完（与课程树的「章节完成」口径一致）
            row.put("unlocked", total > 0 && learned >= total);
            row.put("hasLessons", total > 0);
            // 🔴 下一节该学的课时 id —— 前端「去学习」要能直达具体课时，
            // 而不是只把玩家丢到学习中心首页让他自己找。
            // 口径：第一个未学的课时；全学完则给最后一节（复习入口）。
            row.put("nextLessonId", nextLessonIdOf(userId, ls));
            out.add(row);
        }
        return out;
    }

    /**
     * 该概念下「下一节该学的课时」id。
     *
     * <p>🔴 <b>为什么由后端算</b>：「哪一节该学」是学习状态的一部分，
     * 与「学没学」同源。前端自己挑会多出一份「进度」的判断逻辑，
     * 迟早和后端的解锁口径分叉。
     *
     * @return 课时 id；该概念没有任何课时时返回 null
     */
    private Long nextLessonIdOf(Long userId, List<LearningLesson> lessons) {
        if (lessons.isEmpty()) {
            return null;
        }
        for (LearningLesson l : lessons) {
            if (!progressRepository.existsByUserIdAndLessonId(userId, l.getId())) {
                return l.getId();
            }
        }
        // 全学完 → 给最后一节作为复习入口
        return lessons.get(lessons.size() - 1).getId();
    }
}
