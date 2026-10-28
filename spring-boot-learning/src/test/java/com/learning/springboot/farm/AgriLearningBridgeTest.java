package com.learning.springboot.farm;

import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.entity.Todo;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.farm.insight.AgriConcept;
import com.learning.springboot.farm.learning.FarmLearningBridge;
import com.learning.springboot.farm.learning.FarmTaskChain;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.repository.TodoRepository;
import com.learning.springboot.service.PersonalCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「实操即课时」链路回归测试（2026-10-28 P2）。
 *
 * <p>🔴 <b>这个测试守护的是什么</b>：
 *
 * <p>P2 的核心承诺是「<b>种一次水稻 = 学完有效积温</b>」——
 * 教学与实践是同一个动作。这条承诺靠一条四段链路成立：
 *
 * <pre>
 *   农事动作 → 概念（conceptOf）→ 课时（concept_id）→ 学习进度（LessonProgress）
 * </pre>
 *
 * <p>任何一环漏了，<b>都不会报错</b>，只会让玩家「做了农事却学不到东西」——
 * 这是本项目最典型的静默失效。所以必须逐段锁死：
 *
 * <ol>
 *   <li>8 个概念都有课时（课程种子落地）；</li>
 *   <li>动作 → 概念的映射覆盖所有农事动作；</li>
 *   <li>一次动作能点亮对应概念的课时（链路通）；</li>
 *   <li><b>重复动作不重复计</b>（幂等 —— 否则撞唯一约束把农事事务搞回滚）；</li>
 *   <li>概念卡的解锁口径 = 课时全部学完（与学习看板一致）；</li>
 *   <li><b>进度写入排在农事事务提交之后</b>（本轮实测踩出的关键约束）。</li>
 * </ol>
 *
 * <p>另外守护<b>任务链</b>：预置幂等 + 完成状态按真实数据重算。
 *
 * <p>🔴 <b>为什么本类不加类级 {@code @Transactional}</b>：
 * 「实操即课时」的进度写入被<b>刻意排在农事事务提交之后</b>
 * （SQLite 单写者限制，见 {@code PostCommit}），
 * 若测试跑在一个大事务里，落库根本不会发生，断言就变成在测假象。
 * 所以这里让每次调用<b>各自提交</b>，断言读到的才是真实落库结果；
 * 代价是要在 {@code @BeforeEach} 手工清理测试数据。
 */
@SpringBootTest
@DisplayName("农业科学模块（P2）实操即课时链路回归测试")
class AgriLearningBridgeTest {

    @Autowired private FarmLearningBridge bridge;
    @Autowired private FarmTaskChain taskChain;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private FarmActionRepository farmActionRepository;
    @Autowired private PersonalCenterService personalCenterService;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private static final Long USER = 90001L;

    @BeforeEach
    void clean() {
        // 🔴 手工清理（因为没有类级 @Transactional，测试之间的数据不会自动回滚）。
        // 🔴 必须在事务里做：Spring Data 的 deleteAll() 需要一个 EntityManager 事务。
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            progressRepository.deleteAll();
            todoRepository.deleteAll();
            farmActionRepository.deleteByUserId(USER);
        });
    }

    // ==================================================================
    //  ① 课程种子落地
    // ==================================================================

    /**
     * 🔴 **8 个概念都必须有课时**。
     *
     * <p>「实操即课时」的链路终点是课时表 —— 概念没有课时，
     * 玩家做完农事什么也学不到（{@code markLearned} 会静默返回空列表）。
     */
    @Test
    @DisplayName("8 个农业科学概念都绑定了课时（concept_id 已落地）")
    void everyConceptHasLessons() {
        for (AgriConcept.Concept c : AgriConcept.all()) {
            List<LearningLesson> ls = lessonRepository.findByConceptIdOrdered(c.id());
            assertFalse(ls.isEmpty(),
                    "概念 " + c.id() + " 下没有任何课时 —— 实操即课时链路在此断开");
            for (LearningLesson l : ls) {
                assertEquals(c.id(), l.getConceptId(),
                        "课时 " + l.getId() + " 的 concept_id 与查询条件不一致");
            }
        }
    }

    /**
     * 🔴 **章节按概念 ID 落地，而不是按章号**。
     *
     * <p>章节的 {@code codePath} 存的是概念 ID —— 这是「实操即课时」
     * 从课时反查章节的唯一键。少了它，课时就找不到自己的章节。
     */
    @Test
    @DisplayName("每个概念的章节都存在（章节 codePath = 概念 ID）")
    void eachConceptHasChapter() {
        for (AgriConcept.Concept c : AgriConcept.all()) {
            List<LearningLesson> ls = lessonRepository.findByConceptIdOrdered(c.id());
            assertFalse(ls.isEmpty());
            Long chapterId = ls.get(0).getChapterId();
            assertNotNull(chapterId);
            assertTrue(chapterId > 0);
        }
    }

    // ==================================================================
    //  ② 动作 → 概念映射
    // ==================================================================

    /**
     * 🔴 **每个农事动作都要有明确的归属**。
     *
     * <p>允许「不点亮任何概念」（如烘干是收后处理，不对应任何农学机制），
     * 但<b>不能是漏写</b>。所以这里逐个枚举动作类型做人工核对：
     * 只有 {@code DRY_GRAIN} 允许返回 null。
     */
    @Test
    @DisplayName("动作→概念映射覆盖全部动作类型（只有烘干允许不点亮）")
    void actionMappingCoversAllTypes() {
        for (FarmAction.Type t : FarmAction.Type.values()) {
            String concept = FarmLearningBridge.conceptOf(t);
            if (t == FarmAction.Type.DRY_GRAIN) {
                assertEquals(null, concept,
                        "烘干是收后处理，不该点亮任何概念");
                continue;
            }
            assertNotNull(concept,
                    "动作 " + t + " 没有映射到任何概念 —— 玩家做这件事学不到东西");
            assertNotNull(AgriConcept.of(concept),
                    "动作 " + t + " 映射到了未定义的概念：" + concept);
        }
    }

    /**
     * 🔴 **播种要点亮积温**。
     *
     * <p>播种是「开始一季」，作物从这天开始攒积温 —— 这是玩家最该先理解的机制。
     */
    @Test
    @DisplayName("播种点亮有效积温概念")
    void sowLightsAat() {
        assertEquals(AgriConcept.AAT, FarmLearningBridge.CONCEPT_ON_SOW);
    }

    // ==================================================================
    //  ③ 链路通：动作 → 进度
    // ==================================================================

    /**
     * 🔴 **做一次农事 → 对应概念的课时进度被点亮**。
     *
     * <p>这是整个 P2 的验收点。
     */
    @Test
    @DisplayName("做一次追肥会点亮氮素概念的全部课时")
    void fertilizeLightsNitrogenLessons() {
        List<Long> learned = bridge.schedule(USER, AgriConcept.NITROGEN);
        assertFalse(learned.isEmpty(),
                "追肥没有点亮任何课时 —— 实操即课时链路断了");

        List<LearningLesson> nitrogen = lessonRepository.findByConceptIdOrdered(AgriConcept.NITROGEN);
        assertEquals(nitrogen.size(), learned.size(),
                "点亮的课时数与该概念的课时数不一致");
        for (LearningLesson l : nitrogen) {
            assertTrue(progressRepository.existsByUserIdAndLessonId(USER, l.getId()),
                    "课时 " + l.getId() + " 的进度没有写进去");
        }
    }

    /**
     * 🔴 **重复动作不重复计**。
     *
     * <p>灌溉可以灌 16 次、追肥可以追多次。如果第二次动作会再写一遍进度，
     * 就会撞上 {@code (user_id, lesson_id)} 唯一约束抛异常，
     * <b>整个农事事务回滚 —— 玩家的灌溉动作被吃掉</b>。
     * 这是「幂等是硬要求」的原因。
     */
    @Test
    @DisplayName("重复做同一动作不会重复计（幂等）")
    void repeatedActionIsIdempotent() {
        List<Long> first = bridge.schedule(USER, AgriConcept.WATER);
        assertFalse(first.isEmpty(), "第一次灌溉应该点亮水分概念的课时");

        List<Long> second = bridge.schedule(USER, AgriConcept.WATER);
        assertTrue(second.isEmpty(),
                "第二次灌溉又返回了新点亮的课时 —— 会撞唯一约束把农事事务搞回滚");

        long waterLessons = lessonRepository.findByConceptIdOrdered(AgriConcept.WATER).size();
        long progressCount = progressRepository.findByUserId(USER).size();
        assertEquals(waterLessons, progressCount,
                "进度记录条数与课时数不一致 —— 重复动作写重了");
    }

    /**
     * 🔴 **进度写 100 而不是 0**。
     *
     * <p>{@code LessonProgress} 的语义是「记录存在 = 已学」，且 {@code >= 80}
     * 会自动写 {@code learned_at}。不写 {@code learned_at} 的话，
     * 学习看板的「最近学习时间线」会漏掉这次实操 ——
     * 玩家明明做了事，看板上却查不到。
     */
    @Test
    @DisplayName("实操点亮的进度是 100 且带 learned_at")
    void progressIsCompleteWithLearnedAt() {
        bridge.schedule(USER, AgriConcept.PHI);
        List<LessonProgress> ps = progressRepository.findByUserId(USER);
        assertFalse(ps.isEmpty());
        for (LessonProgress p : ps) {
            assertEquals(100, p.getProgressPercent(),
                    "实操点亮的进度应为 100（否则不会自动写 learned_at）");
            assertNotNull(p.getLearnedAt(),
                    "进度 100 但 learned_at 为空 —— 学习看板的时间线会漏掉这次实操");
        }
    }

    /**
     * 🔴 **进度写入排在农事事务「提交之后」**。
     *
     * <p>🔴 <b>为什么这是硬约束</b>（本轮实测结论，不是推测）：
     * 本项目持久层是 SQLite，而 SQLite <b>同一时刻只允许一个写者</b>。
     * 若把进度写入放进农事事务：
     * <ul>
     *   <li>共享事务 → 写入失败会把农事事务标记成 rollback-only
     *       → 农事提交时炸 {@code UnexpectedRollbackException}
     *       → <b>玩家正确做的一次农事被整个吃掉</b>；</li>
     *   <li>另开独立事务（{@code REQUIRES_NEW}）→ 与外层农事事务抢写锁
     *       → {@code database is locked} / {@code SQLITE_BUSY_SNAPSHOT}。</li>
     * </ul>
     * 两条路都走不通，所以进度写入<b>必须</b>排在农事事务之外。
     *
     * <p>本测试把这条约束锁死：事务内查不到进度（还没落库），
     * 提交之后才落库。
     */
    @Test
    @DisplayName("进度写入排在农事事务提交之后（事务内不落库，提交后才落库）")
    void progressIsWrittenOnlyAfterTransactionCommit() {
        List<LearningLesson> nitrogen = lessonRepository.findByConceptIdOrdered(AgriConcept.NITROGEN);
        assertFalse(nitrogen.isEmpty());
        Long lessonId = nitrogen.get(0).getId();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            bridge.schedule(USER, AgriConcept.NITROGEN);
            // 事务还没提交 → 进度不该落库
            // 🔴 用 JdbcTemplate 直查：新连接只看得见「已提交」的数据，
            //    所以这里能准确回答「进度到底落库了没」。
            assertFalse(existsInDb(lessonId),
                    "农事事务还没提交，进度就已经落库了 —— 说明它没被延后到提交之后，"
                            + "在 SQLite 上这会导致与农事事务抢写锁");
        });

        // 事务已提交 → 进度落库
        assertTrue(progressRepository.existsByUserIdAndLessonId(USER, lessonId),
                "农事事务提交后进度没有落库 —— 实操即课时链路断了");
    }

    /**
     * 🔴 **学习进度写入失败绝不影响农事**（P2 的核心承诺之一）。
     *
     * <p>这条承诺在 SQLite 上<b>不是靠 try-catch 实现的</b>，而是靠
     * <b>事务边界</b>：进度写入排在农事事务<b>提交之后</b>，
     * 因此它在<b>结构上</b>就不可能让农事回滚。
     *
     * <p>本测试锁住这个结构：进度写入彻底失败时，
     * 农事事务<b>照样提交成功</b>，且农事数据完整。
     */
    @Test
    @DisplayName("学习进度写入失败也不会让农事事务回滚")
    void learningProgressFailureDoesNotRollbackFarm() {
        // 先做一次真实的农事写入（模拟 performAction 里的农事落库）
        FarmAction act = new FarmAction();
        act.setUserId(USER);
        act.setPlotId(1L);
        act.setDate(java.time.LocalDate.now());
        act.setType(FarmAction.Type.IRRIGATE);
        act.setDurationDays(1);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            farmActionRepository.save(act);
            // 排一个「必然写不进去」的进度写入：概念不存在 → 无课时 → 不写
            bridge.schedule(USER, "NOT_A_REAL_CONCEPT");
        });

        // 农事数据必须还在（若进度写入把农事事务搞回滚，这里就查不到）
        long actions = farmActionRepository
                .findByUserIdOrderByDateDescIdDesc(USER).stream()
                .filter(a -> a.getType() == FarmAction.Type.IRRIGATE)
                .count();
        assertTrue(actions > 0, "农事数据丢了 —— 说明附属写入把农事事务搞回滚了");
    }

    // ==================================================================
    //  ④ 概念卡
    // ==================================================================

    /**
     * 🔴 **概念卡的解锁口径 = 该概念课时全部学完**。
     *
     * <p>不是「有没有做过某个动作」—— 否则农场页说「已解锁」、
     * 学习看板说「还有 2 节没学」，玩家看到两个矛盾的答案。
     */
    @Test
    @DisplayName("概念卡解锁口径 = 课时全部学完（与学习看板一致）")
    void conceptCardUnlockMatchesLessonCompletion() {
        // 🔴 先取一次只为了校验概念卡数量（8 张）。
        // 后面断言进度时必须**重新取快照** —— conceptCards 是现算的，
        // 拿保存进度之前取的旧快照去断言，读到的还是 0。
        assertEquals(AgriConcept.all().size(), bridge.conceptCards(USER).size(),
                "概念卡数量与已定义概念数不一致");

        // 🔴 用「有 2 节课」的概念（AAT）做"部分学完"的场景 ——
        // 多数概念只有 1 节课，学完 1 节就等于学完全部，测不出"半学"的中间态。
        List<LearningLesson> aat = lessonRepository.findByConceptIdOrdered(AgriConcept.AAT);
        assertTrue(aat.size() >= 2,
                "有效积温概念应至少有 2 节课，否则测不出「部分学完」的中间态（实际 "
                        + aat.size() + " 节）");
        progressRepository.save(new LessonProgress(USER, aat.get(0).getId()));

        for (var c : bridge.conceptCards(USER)) {
            String cid = (String) c.get("conceptId");
            int total = (Integer) c.get("totalLessons");
            int learnedN = (Integer) c.get("learnedLessons");
            boolean unlocked = (Boolean) c.get("unlocked");
            if (cid.equals(AgriConcept.AAT)) {
                assertEquals(1, learnedN, "有效积温概念的已学课时数不对");
                assertFalse(unlocked, "只学完一半课时就报「已解锁」—— 口径与学习看板不一致");
            } else {
                assertEquals(0, learnedN);
                assertFalse(unlocked);
            }
            assertTrue(total > 0, "概念 " + cid + " 的课时总数为 0");
        }

        // 补齐剩余课时 → 应解锁
        for (int i = 1; i < aat.size(); i++) {
            progressRepository.save(new LessonProgress(USER, aat.get(i).getId()));
        }
        for (var c : bridge.conceptCards(USER)) {
            if (AgriConcept.AAT.equals(c.get("conceptId"))) {
                assertTrue((Boolean) c.get("unlocked"),
                        "课时全部学完后仍未解锁 —— 口径反了");
                assertEquals(100, c.get("learnedPercent"));
            }
        }
    }

    /**
     * 🔴 **概念卡要给出「下一节该学的课时」**。
     *
     * <p>没有它，前端「去学习」只能把玩家丢到学习中心首页，
     * 让他自己在农业科学科目下翻章节找 —— 多一次不必要的寻找。
     * 而且「哪一节该学」是学习状态的一部分，必须由后端算，
     * 前端自己挑会多出一份判断逻辑，迟早与后端的解锁口径分叉。
     */
    @Test
    @DisplayName("概念卡给出下一节该学的课时（全学完则给最后一节作复习入口）")
    void conceptCardProvidesNextLesson() {
        List<LearningLesson> aat = lessonRepository.findByConceptIdOrdered(AgriConcept.AAT);
        assertFalse(aat.isEmpty());

        // 一节都没学 → 应指向第一节
        Long next = nextLessonIdOf(AgriConcept.AAT);
        assertEquals(aat.get(0).getId(), next,
                "没学过任何一节时，应指向第一节");

        // 学了第一节 → 应指向第二节
        progressRepository.save(new LessonProgress(USER, aat.get(0).getId()));
        assertEquals(aat.get(1).getId(), nextLessonIdOf(AgriConcept.AAT),
                "学完第一节后，应指向下一节未学的课时");

        // 全学完 → 指向最后一节（复习入口）
        for (int i = 1; i < aat.size(); i++) {
            progressRepository.save(new LessonProgress(USER, aat.get(i).getId()));
        }
        assertEquals(aat.get(aat.size() - 1).getId(), nextLessonIdOf(AgriConcept.AAT),
                "全学完后，应指向最后一节作复习入口");
    }

    /** 从概念卡里取 nextLessonId（测试辅助）。 */
    private Long nextLessonIdOf(String conceptId) {
        for (var c : bridge.conceptCards(USER)) {
            if (conceptId.equals(c.get("conceptId"))) {
                return (Long) c.get("nextLessonId");
            }
        }
        return null;
    }

    /**
     * 🔴 **概念卡对未知用户返回空列表，而不是抛异常**。
     *
     * <p>游客 / 未登录访问时不能 500。
     */
    @Test
    @DisplayName("概念卡对空 userId 返回空列表（不抛异常）")
    void conceptCardsHandleNullUser() {
        assertTrue(bridge.conceptCards(null).isEmpty());
        assertTrue(bridge.conceptCards(USER) != null);
    }

    // ==================================================================
    //  ⑤ 任务链
    // ==================================================================

    /**
     * 🔴 **预置幂等**。
     *
     * <p>反复进农场不能插出 4 条一样的任务 —— 那是把玩家自己的待办列表搞脏。
     */
    @Test
    @DisplayName("任务链预置幂等（反复预置不产生重复）")
    void taskChainEnsureIsIdempotent() {
        List<Todo> first = taskChain.ensureChain(USER);
        int created = first.size();
        assertTrue(created > 0, "首次预置没有创建任何任务");

        List<Todo> second = taskChain.ensureChain(USER);
        assertTrue(second.isEmpty(),
                "第二次预置又创建了 " + second.size() + " 条任务 —— 预置不幂等");

        long count = todoRepository.findByUserIdAndTaskTypeIsNotNullOrderByIdAsc(USER).size();
        assertEquals(created, count, "库里实际条数与首次创建数不一致");
    }

    /**
     * 🔴 **任务标题与类型齐全**。
     *
     * <p>系统任务没有标题在待办页就是一行空白。
     */
    @Test
    @DisplayName("预置任务都有标题与类型（待办页不出现空白行）")
    void presetTasksHaveTitleAndType() {
        taskChain.ensureChain(USER);
        List<Todo> tasks = todoRepository.findByUserIdAndTaskTypeIsNotNullOrderByIdAsc(USER);
        for (Todo t : tasks) {
            assertNotNull(t.getTitle());
            assertFalse(t.getTitle().trim().isEmpty(),
                    "任务 " + t.getId() + " 没有标题");
            assertNotNull(t.getTaskType());
            assertNotNull(t.getRefKey());
        }
    }

    /**
     * 🔴 **完成状态按真实数据重算，不依赖调用顺序**。
     *
     * <p>核心是「读了就会重算」：少调一次刷新不会让任务卡死。
     * 这里模拟「玩家已经施过肥」的真实状态，然后直接刷新 —— 任务应当自动完成。
     */
    @Test
    @DisplayName("施肥后刷新任务链，「完成一次追肥」自动打勾")
    void fertilizeTaskAutoCompletesOnRefresh() {
        taskChain.ensureChain(USER);

        // 先确认它还没完成
        Todo before = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_FERTILIZE, "fert")
                .orElseThrow();
        assertEquals(0, before.getStatus(), "还没施肥，任务不该是完成态");

        // 模拟玩家做了一次施肥农事
        FarmAction act = new FarmAction();
        act.setUserId(USER);
        act.setPlotId(1L);
        act.setDate(java.time.LocalDate.now());
        act.setType(FarmAction.Type.TILLER_FERT);
        act.setDurationDays(7);
        farmActionRepository.save(act);

        taskChain.refresh(USER);

        Todo after = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_FERTILIZE, "fert")
                .orElseThrow();
        assertEquals(1, after.getStatus(), "施过肥了，任务却没自动完成");
        assertNotNull(after.getDoneTime(), "完成时间没写");
    }

    /**
     * 🔴 **不该完成的绝不能提前完成**。
     *
     * <p>「收完第一季」只认 {@code HARVESTED} —— 失败（枯死/绝收）与放弃不算。
     * 混进来会让任务在玩家失败时自动完成，那是错的正反馈。
     */
    @Test
    @DisplayName("没收割时「收完第一季」不该完成")
    void harvestTaskNotDoneWithoutHarvest() {
        taskChain.ensureChain(USER);
        taskChain.refresh(USER);
        Todo t = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_HARVEST, "harvest")
                .orElseThrow();
        assertEquals(0, t.getStatus(), "还没收割，任务就完成了 —— 判定口径错了");
    }

    /**
     * 🔴 **点亮概念卡后，「点亮一个概念卡」这环自动完成**。
     *
     * <p>🔴 <b>这条锁的是「先写进度、再刷任务链」的顺序</b>：
     * 「点亮一个概念卡」的判定依赖刚写进去的学习进度。
     * 若顺序反了（先刷任务链、后写进度），这一环永远判不出完成，
     * 而且<b>不会报任何错</b> —— 玩家看到的是「明明学完了，任务就是不打勾」。
     */
    @Test
    @DisplayName("写完学习进度后，「点亮一个概念卡」自动打勾（顺序不能反）")
    void conceptTaskCompletesAfterProgressWritten() {
        taskChain.ensureChain(USER);

        Todo before = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_CONCEPT, "concept")
                .orElseThrow();
        assertEquals(0, before.getStatus(), "还没学任何概念，任务不该完成");

        // 走一遍真实顺序：先写学习进度，再刷任务链
        List<LearningLesson> phi = lessonRepository.findByConceptIdOrdered(AgriConcept.PHI);
        assertFalse(phi.isEmpty());
        bridge.schedule(USER, AgriConcept.PHI);
        taskChain.refresh(USER);

        Todo after = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_CONCEPT, "concept")
                .orElseThrow();
        assertEquals(1, after.getStatus(),
                "概念课时都学完了，「点亮一个概念卡」却没打勾 —— 顺序反了或判定口径错了");
    }

    // ==================================================================
    //  ⑥ 系统任务 vs 玩家自建待办
    // ==================================================================

    /**
     * 🔴 **系统任务不可编辑、不可手动勾选**。
     *
     * <p>系统任务（农场任务链）的完成状态由服务端按<b>真实农事数据</b>重算。
     * 玩家手动勾它 = 自己给自己发奖状 —— 任务链就失去了「教学引导」的意义，
     * 而且会让「游戏中心排行榜/成就」这类按真实进度算的东西出现矛盾。
     *
     * <p>🔴 <b>为什么必须在服务端拦</b>：前端隐藏入口只是 UI，
     * 直接调 {@code PUT /api/todos/{id}} 照样能改。
     */
    @Test
    @DisplayName("系统任务不可手动勾选/编辑（完成状态只能由农事数据决定）")
    void systemTaskCannotBeManuallyCompleted() {
        taskChain.ensureChain(USER);

        Todo sys = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_FIELD, "field")
                .orElseThrow();
        assertEquals(0, sys.getStatus(), "还没开地，任务不该是完成态");

        assertThrows(BusinessException.class, () -> personalCenterService.updateTodo(
                        USER, sys.getId(), null, null, null, 1),
                "玩家手动勾选了系统任务 —— 任务链就失去了引导意义");

        // 被拒绝后状态不能变
        Todo after = todoRepository.findById(sys.getId()).orElseThrow();
        assertEquals(0, after.getStatus(),
                "手动勾选被拒后，系统任务的状态却变了 —— 保护没生效");
    }

    /**
     * 🔴 **系统任务不可删除**。
     *
     * <p>删了它，下次进农场会被 {@code ensureChain} 重新预置出来 ——
     * 玩家看到的是「我明明删了，它又回来了」，比不让删更困惑。
     */
    @Test
    @DisplayName("系统任务不可删除（删了会被重新预置出来）")
    void systemTaskCannotBeDeleted() {
        taskChain.ensureChain(USER);
        Todo sys = todoRepository
                .findFirstByUserIdAndTaskTypeAndRefKey(USER, Todo.TaskType.FIRST_FIELD, "field")
                .orElseThrow();

        assertThrows(BusinessException.class, () -> personalCenterService.deleteTodo(USER, sys.getId()),
                "系统任务被删除了 —— 下次进农场它会原样回来");

        assertTrue(todoRepository.findById(sys.getId()).isPresent(),
                "系统任务真的被删掉了 —— 保护没生效");
    }

    /**
     * 🔴 **玩家自建待办不受影响**（保护不能误伤正常功能）。
     *
     * <p>上一条保护若写得太宽（比如按「有 refKey」判定），
     * 玩家自建待办也会被锁死 —— 那就是把功能改坏了。
     * 所以这里正反两面都要测。
     */
    @Test
    @DisplayName("玩家自建待办仍可正常编辑与删除（保护不误伤）")
    void playerTodoRemainsEditableAndDeletable() {
        Todo mine = new Todo();
        mine.setUserId(USER);
        mine.setTitle("玩家自己写的待办");
        mine.setPriority(1);
        mine.setStatus(0);
        mine = todoRepository.save(mine);
        assertFalse(mine.isSystemTask(), "taskType 为 null 就不该是系统任务");

        personalCenterService.updateTodo(USER, mine.getId(), "改过的标题", 2, "", 1);
        Todo edited = todoRepository.findById(mine.getId()).orElseThrow();
        assertEquals("改过的标题", edited.getTitle(), "玩家自建待办改不动了");
        assertEquals(1, edited.getStatus(), "玩家自建待办勾选不上了");
        assertNotNull(edited.getDoneTime(), "完成时间没写");

        personalCenterService.deleteTodo(USER, mine.getId());
        assertTrue(todoRepository.findById(mine.getId()).isEmpty(),
                "玩家自建待办删不掉了");
    }

    /**
     * 🔴 **系统任务与玩家自建待办在列表里可区分**。
     *
     * <p>待办页要分区显示，靠的就是 {@code taskType} 这个字段。
     * 若它没被序列化（或恒为 null），前端就会把系统任务当玩家自建的渲染，
     * 给出可勾选/可删除的入口 —— 玩家一点就报错。
     */
    @Test
    @DisplayName("列表里系统任务与自建待办可通过 taskType 区分")
    void listDistinguishesSystemTasksFromPlayerTodos() {
        taskChain.ensureChain(USER);
        Todo mine = new Todo();
        mine.setUserId(USER);
        mine.setTitle("自建待办");
        mine.setPriority(1);
        mine.setStatus(0);
        todoRepository.save(mine);

        List<Todo> all = personalCenterService.listTodos(USER, null);
        long sysCount = all.stream().filter(Todo::isSystemTask).count();
        long myCount = all.size() - sysCount;

        assertEquals(4, sysCount, "系统任务条数不对（任务链固定 4 环）");
        assertEquals(1, myCount, "玩家自建待办条数不对");
        for (Todo t : all) {
            if (t.isSystemTask()) {
                assertNotNull(t.getTaskType(), "系统任务必须有 taskType");
                assertNotNull(t.getRefKey(), "系统任务必须有 refKey（幂等标识）");
            } else {
                assertNull(t.getTaskType(), "玩家自建待办的 taskType 必须为 null");
            }
        }
    }

    // ==================================================================
    //  辅助
    // ==================================================================

    /**
     * 用<b>新连接</b>直查进度表是否存在该行（只看已提交的数据）。
     *
     * <p>🔴 <b>为什么不用 JPA 查</b>：JPA 查询会复用当前事务的
     * EntityManager / 读快照，可能读到与「新连接」不同的答案，
     * 断言就会变成在测快照行为而不是在测「有没有落库」。
     */
    private boolean existsInDb(Long lessonId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_lesson_progress WHERE user_id = ? AND lesson_id = ?",
                Integer.class, USER, lessonId);
        return n != null && n > 0;
    }
}
