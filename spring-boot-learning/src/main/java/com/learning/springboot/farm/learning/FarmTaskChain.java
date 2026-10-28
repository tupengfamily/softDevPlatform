package com.learning.springboot.farm.learning;

import com.learning.springboot.entity.Todo;
import com.learning.springboot.farm.FarmAction;
import com.learning.springboot.farm.FarmActionRepository;
import com.learning.springboot.farm.FarmPlotRepository;
import com.learning.springboot.farm.FarmSeason;
import com.learning.springboot.farm.FarmSeasonRepository;
import com.learning.springboot.farm.insight.AgriConcept;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.repository.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农场任务链（2026-10-28 P2）—— <b>系统预置待办的唯一实现</b>。
 *
 * <p><b>这个类解决什么问题</b>：实测发现 17 个季节里 10 个卡在 {@code GROWING}，
 * 多数季节<b>没有任何农事动作记录</b> —— 核心循环没被跑起来。
 * 缺口不是内容，而是<b>引导</b>：玩家打开农场看到一块长着的地，
 * 不知道下一步该干什么。
 *
 * <p>任务链是引导的<b>载体</b>（不拦截、不弹窗，只在「我的待办」里列出下一步）：
 * <pre>
 *   ① 建第一块地  →  ② 完成一次追肥  →  ③ 收完第一季  →  ④ 解锁一个概念卡
 * </pre>
 * 每一环都对应一次<b>真实可观测的农事</b>，做完自动打勾。
 *
 * <p>🔴 <b>三条硬约束</b>：
 * <ol>
 *   <li><b>幂等</b>：预置任务用 {@code (user_id, task_type, ref_key)} 三元组去重，
 *       反复进农场不能插出 4 条「建第一块地」。</li>
 *   <li><b>自动完成</b>：完成判定在服务端重新算，不靠前端勾选 ——
 *       否则玩家必须先学会「去待办页点一下勾」才能推进主线，这是荒谬的。</li>
 *   <li><b>失败不影响农事</b>：任务链是附属收益，查库/写库异常都不该让玩家的收割失败。</li>
 * </ol>
 *
 * <p>🔴 <b>为什么任务链放在 {@code farm.learning} 而不是 {@code service}</b>：
 * 它与 {@link FarmLearningBridge} 是同一件事的两面（学习进度 ↔ 待办进度），
 * 放在一起才不会各写一套「什么算完成」的判定。
 */
@Service
public class FarmTaskChain {

    private static final Logger log = LoggerFactory.getLogger(FarmTaskChain.class);

    @Autowired private TodoRepository todoRepository;
    @Autowired private FarmTaskChainWriter writer;
    @Autowired private FarmPlotRepository plotRepository;
    @Autowired private FarmSeasonRepository seasonRepository;
    @Autowired private FarmActionRepository actionRepository;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;

    /**
     * 施肥类动作（任务链「完成一次追肥」的判定范围）。
     *
     * <p>🔴 <b>为什么包含基肥</b>：基肥也是施肥，也是玩家第一次往地里上肥的动作。
     * 只认「追肥」（分蘖肥/穗肥）的话，玩家按工序链做基肥时会觉得任务没动 ——
     * <b>玩家在正确时机做的正确事被拒绝，比阈值偏高更糟</b>。
     */
    private static final List<FarmAction.Type> FERT_TYPES = List.of(
            FarmAction.Type.FERTILIZE,
            FarmAction.Type.BASE_FERT,
            FarmAction.Type.TILLER_FERT,
            FarmAction.Type.PANICLE_FERT);

    /** 任务链定义：type → (refKey, 标题模板, 优先级)。 */
    private record TaskDef(String type, String refKey, String title, int priority) {}

    /**
     * 任务链的唯一定义处（顺序 = 引导顺序）。
     *
     * <p>🔴 <b>为什么标题用中文硬编码而不是 i18n</b>：本项目的 i18n 在前端，
     * 而这条链是<b>服务端</b>写库的数据 —— 库里存的就是最终展示文本。
     * 若要做多语言，需要按语言各写一条任务（refKey 相同、title 不同），
     * 那属于另一件事，不在这里假装支持。
     *
     * <p>🔴 <b>refKey 唯一出口在 {@link FarmTaskChainWriter#refKeyOf}</b>：
     * 预置与「完成判定」是两条路（读侧/写侧），refKey 若各写一份字面量，
     * 写歪的后果是<b>任务永远不完成</b>且没有任何报错。两边都从同一个出口取。
     */
    private static final List<TaskDef> CHAIN = List.of(
            new TaskDef(Todo.TaskType.FIRST_FIELD, FarmTaskChainWriter.refKeyOf(Todo.TaskType.FIRST_FIELD),
                    "开垦第一块地：选一个农业区，定下播期", 2),
            new TaskDef(Todo.TaskType.FIRST_FERTILIZE, FarmTaskChainWriter.refKeyOf(Todo.TaskType.FIRST_FERTILIZE),
                    "完成一次施肥：给地块施基肥或追肥，看看氮素怎么算", 1),
            new TaskDef(Todo.TaskType.FIRST_HARVEST, FarmTaskChainWriter.refKeyOf(Todo.TaskType.FIRST_HARVEST),
                    "收完第一季：等作物成熟后收割，看看产量四要素", 1),
            new TaskDef(Todo.TaskType.FIRST_CONCEPT, FarmTaskChainWriter.refKeyOf(Todo.TaskType.FIRST_CONCEPT),
                    "点亮一个农业科学概念卡：做完上面的步骤，概念会自动解锁", 0));

    // ==================================================================
    //  预置（幂等）
    // ==================================================================

    /**
     * 为用户预置任务链（幂等）。
     *
     * <p>🔴 <b>什么时候调用</b>：玩家<b>第一次进入农场</b>时。
     * 不是启动时给所有用户预置 —— 那会给 6 个从不玩农场的账号塞 4 条待办，
     * 污染他们的待办列表。
     *
     * @return 本次新插入的任务（空列表 = 已全部存在）
     */
    @Transactional
    public List<Todo> ensureChain(Long userId) {
        List<Todo> created = new ArrayList<>();
        if (userId == null) {
            return created;
        }
        try {
            for (TaskDef def : CHAIN) {
                if (todoRepository.findFirstByUserIdAndTaskTypeAndRefKey(userId, def.type(), def.refKey())
                        .isPresent()) {
                    continue;
                }
                Todo t = new Todo();
                t.setUserId(userId);
                t.setTitle(def.title());
                t.setPriority(def.priority());
                t.setStatus(0);
                t.setTaskType(def.type());
                t.setRefKey(def.refKey());
                created.add(todoRepository.save(t));
            }
            if (!created.isEmpty()) {
                log.info("[稼穑] 用户 {} 预置农场任务链 {} 条", userId, created.size());
            }
        } catch (Exception e) {
            // 🔴 预置失败不该挡住玩家进农场。
            log.warn("[稼穑] 预置农场任务链失败（不影响游戏）: userId={}, err={}", userId, e.getMessage());
        }
        return created;
    }

    // ==================================================================
    //  完成判定（唯一出口）
    // ==================================================================

    /**
     * 重新判定该用户的任务链完成状态，并自动打勾。
     *
     * <p>🔴 <b>为什么是「重新判定」而不是「事件驱动打勾」</b>：
     * 事件驱动（做完施肥就调一次 complete）的前提是<b>每个触发点都记得调</b>。
     * 本项目已有过「药剂 id 拼错被悄悄跳过」的静默失效教训 ——
     * 少调一个钩子，那条任务就永远不完成，而没人知道为什么。
     *
     * <p>改成<b>读取时按真实数据重算</b>：判定依据是数据库里的客观事实
     * （有几块地、施过几次肥、收过几季、点亮几个概念），
     * <b>不依赖任何调用顺序</b>。少调一个钩子的后果从「任务卡死」变成「无」——
     * 因为下次进农场会重算。
     *
     * <p>🔴 <b>为什么这个方法刻意不加 {@code @Transactional}</b>：
     * 判定是「读数据库的客观事实」，写入由 {@link FarmTaskChainWriter}
     * 在<b>自己的独立事务</b>里完成。若本方法自己开一个事务，
     * 那么它启动的读快照会<b>早于</b>学习进度的写入 ——
     * 「点亮一个概念卡」这一环就读不到刚写进去的进度，判不出完成。
     *
     * <p>不加事务时，每次 repository 调用各自开一个短事务、看到的是最新数据，
     * 正好符合「按真实数据重算」的语义。
     *
     * @return 本次被自动标记完成的任务（空列表 = 无变化）
     */
    public List<Todo> refresh(Long userId) {
        List<Todo> newlyDone = new ArrayList<>();
        if (userId == null) {
            return newlyDone;
        }
        try {
            for (TaskDef def : CHAIN) {
                // 🔴 判定与写入分离：判定（读）在这里，写（独立事务）在
                // FarmTaskChainWriter 里 —— 跨 bean 调用，@Transactional 代理必然生效。
                // 若把写入留在本类内部调用，注解会被 AOP 自调用吃掉（项目第四例）。
                if (!isSatisfied(userId, def.type())) {
                    continue;
                }
                newlyDone.addAll(writer.markDone(userId, def.type()));
            }
            if (!newlyDone.isEmpty()) {
                log.info("[稼穑] 用户 {} 任务链自动完成 {} 条", userId, newlyDone.size());
            }
        } catch (Exception e) {
            log.warn("[稼穑] 刷新任务链状态失败（不影响游戏）: userId={}, err={}", userId, e.getMessage());
        }
        return newlyDone;
    }

    /**
     * 重新判定任务链状态，但<b>排在农事事务提交之后</b>执行（2026-10-28 P2）。
     *
     * <p>🔴 <b>为什么要延后到提交之后</b>：本项目的持久层是 SQLite，
     * 而 SQLite <b>同一时刻只允许一个写者</b>。若在农事事务里直接写任务表，
     * 会与农事自己的写入抢锁；而若另开独立事务（{@code REQUIRES_NEW}），
     * 内层提交又会把外层事务的读快照作废（{@code SQLITE_BUSY_SNAPSHOT}）。
     *
     * <p>🔴 <b>还有一个顺序问题</b>：「点亮一个概念卡」这一环的判定依赖
     * <b>刚写进去的学习进度</b>。若任务链先跑、进度后写，这一环永远判不出完成。
     * 所以两件事必须<b>按同一个顺序排队</b>：先写进度，再刷任务链。
     * 本方法与 {@link FarmLearningBridge#schedule} 都挂到 {@link PostCommit} 上，
     * 而 Spring 按<b>注册先后</b>触发 {@code afterCommit} 回调 ——
     * 桥接先注册（农事入口里先调 schedule），任务链后注册，顺序自然成立。
     *
     * <p>🔴 <b>失败不影响农事</b>：这是提交后的附带动作，失败只记日志。
     */
    public void refreshAfter(Long userId) {
        PostCommit.run("刷新任务链", () -> refresh(userId));
    }

    /**
     * 单条任务的完成判定（唯一口径）。
     *
     * <p>🔴 <b>每种类型的判定依据都必须是「客观事实」</b>，
     * 不能是「玩家点了某个按钮」—— 后者只是 UI 事件，不是学习成果。
     */
    private boolean isSatisfied(Long userId, String taskType) {
        return switch (taskType) {
            // ① 建过至少一块地（zoneChosen = true 的地块）
            case Todo.TaskType.FIRST_FIELD ->
                    plotRepository.countByUserId(userId) > 0
                            && !plotRepository.findByUserIdOrderBySlotAsc(userId).stream()
                            .allMatch(p -> !p.getZoneChosen());
            // ② 施过至少一次肥
            case Todo.TaskType.FIRST_FERTILIZE ->
                    actionRepository.countByUserIdAndTypeIn(userId, FERT_TYPES) > 0;
            // ③ 正常收获过至少一季（失败/放弃不算）
            case Todo.TaskType.FIRST_HARVEST ->
                    seasonRepository.countByUserIdAndStatus(userId, FarmSeason.Status.HARVESTED) > 0;
            // ④ 点亮至少一个概念卡（该概念课时全部学完）
            case Todo.TaskType.FIRST_CONCEPT -> anyConceptUnlocked(userId);
            default -> false;
        };
    }

    /** 是否有任一农业科学概念的课时全部学完。 */
    private boolean anyConceptUnlocked(Long userId) {
        for (AgriConcept.Concept c : AgriConcept.all()) {
            List<com.learning.springboot.entity.LearningLesson> ls =
                    lessonRepository.findByConceptIdOrdered(c.id());
            if (ls.isEmpty()) {
                continue;
            }
            boolean all = true;
            for (com.learning.springboot.entity.LearningLesson l : ls) {
                if (!progressRepository.existsByUserIdAndLessonId(userId, l.getId())) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    //  前端视图
    // ==================================================================

    /**
     * 任务链视图：给「我的待办」页显示「系统任务」分区。
     *
     * <p>🔴 <b>为什么单独一个端点而不是混在 {@code /api/todos} 里</b>：
     * {@code /api/todos} 返回的是 JPA 实体（含全部字段），
     * 混进系统任务会让前端要按 {@code taskType} 过滤才能显示，
     * 过滤逻辑一旦漏一处，界面就会把系统任务当玩家自建任务显示。
     * 单独一个端点 = 单一出口 = 前端不需要判断。
     *
     * @return 每条任务：id / type / title / priority / status / doneTime / progressHint
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> chainView(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (userId == null) {
            return out;
        }
        for (Todo t : todoRepository.findByUserIdAndTaskTypeIsNotNullOrderByIdAsc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("taskType", t.getTaskType());
            row.put("title", t.getTitle());
            row.put("priority", t.getPriority());
            row.put("status", t.getStatus());
            row.put("doneTime", t.getDoneTime());
            row.put("done", t.getStatus() != null && t.getStatus() == 1);
            out.add(row);
        }
        return out;
    }
}
