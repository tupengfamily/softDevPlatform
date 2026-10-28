package com.learning.springboot.farm.learning;

import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 学习进度写入器 —— <b>「实操即课时」的进度落库唯一执行体</b>（2026-10-28 P2）。
 *
 * <p><b>这个类解决什么问题</b>：P2 的核心承诺之一是
 * 「<b>学习进度写失败绝不影响农事</b>」。但光靠 try-catch 是<b>做不到</b>的 ——
 * 实测踩过两次：
 *
 * <pre>
 *   ① 共享事务：进度写入加入农事事务 → insert 撞 NOT NULL 约束 → 抛异常
 *      → catch 住了 ✅，但 Hibernate 已把**共享事务**标记为 rollback-only
 *      → 农事事务 commit → UnexpectedRollbackException → 玩家看到 500 ❌
 *
 *   ② 独立事务（REQUIRES_NEW）：SQLite 同一时刻只允许**一个写者**
 *      → 内层事务与外层农事事务抢写锁 → database is locked / SQLITE_BUSY_SNAPSHOT ❌
 * </pre>
 *
 * <p>🔴 <b>「独立事务」这条隔离手段在 SQLite 上根本不成立</b>（这是本轮实测结论，
 * 不是推测）：内层提交要么抢不到锁，要么把外层事务的读快照作废。
 *
 * <p>🔴 <b>所以真正的墙是「农事事务之外」</b>：
 * 本类改为 —— <b>把实际写库动作挂到农事事务提交之后</b>（见 {@link PostCommit}）。
 * 于是同时满足三件事：
 * <ol>
 *   <li>写学习进度时，农事事务<b>已经提交并释放了写锁</b> → 不抢锁；</li>
 *   <li>写失败只回滚附属写入自己的事务 → <b>农事毫发无损</b>；</li>
 *   <li>写成功后，紧接着刷任务链 —— 能读到刚写进去的进度。</li>
 * </ol>
 *
 * <p>🔴 <b>为什么仍要抽成独立 Bean</b>：{@code markLearned} 需要跑在<b>自己的事务</b>里
 * （不能加入农事事务）。若它留在 {@link FarmLearningBridge} 里由
 * {@code onAction/onSow/onHarvest} 内部调用，{@code @Transactional} 会因
 * <b>AOP 自调用</b>而不走代理、注解直接失效 —— 独立事务这个保证就成了摆设。
 * 抽成独立 Bean 后调用是<b>跨 bean</b>的，代理必然生效。
 *
 * <p>这是本项目「AOP 自调用陷阱」的第三例（前两例：{@code AdminBootstrap}
 * 委托 {@code DataSeedService}、{@code FarmPlayService} 委托 {@code SeasonContext}）。
 */
@Service
public class LessonProgressWriter {

    private static final Logger log = LoggerFactory.getLogger(LessonProgressWriter.class);

    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;

    /**
     * 推进某概念的课时进度（幂等）。
     *
     * <p>🔴 <b>这个方法自己跑在独立事务里</b>（{@code REQUIRES_NEW}）：
     * 调用它时农事事务已经提交，写锁已释放，不存在抢锁问题。
     *
     * @param userId    用户
     * @param conceptId 概念标识（{@link com.learning.springboot.farm.insight.AgriConcept} 的常量）
     * @return 本次新点亮的课时 id（已学过的会被跳过）；无对应课时时返回空列表
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> markLearned(Long userId, String conceptId) {
        List<Long> newly = new ArrayList<>();
        if (userId == null || conceptId == null) {
            return newly;
        }
        try {
            List<LearningLesson> lessons = lessonRepository.findByConceptIdOrdered(conceptId);
            if (lessons.isEmpty()) {
                // 🔴 不算错误：可能课程种子还没跑。但要留痕 —— 否则「点了农事学不到东西」
                // 会变成一个查不出原因的问题。
                log.debug("概念 {} 下没有课时（课程种子未同步?），跳过进度写入", conceptId);
                return newly;
            }
            for (LearningLesson lesson : lessons) {
                if (progressRepository.existsByUserIdAndLessonId(userId, lesson.getId())) {
                    continue;
                }
                // 🔴 进度设 100（不是 0）：LessonProgress 的语义是「记录存在 = 已学」，
                // 而 >= 80 会自动写 learned_at —— 不写 learned_at 的话，
                // 学习看板的「最近学习时间线」会漏掉这次实操。
                LessonProgress p = new LessonProgress(userId, lesson.getId());
                p.setProgressPercent(100);
                progressRepository.saveAndFlush(p);
                newly.add(lesson.getId());
            }
            if (!newly.isEmpty()) {
                log.info("[稼穑] 用户 {} 实操点亮概念 {} 的 {} 个课时",
                        userId, conceptId, newly.size());
            }
        } catch (Exception e) {
            // 🔴 绝不让学习进度写失败影响农事本身（详见类注释）。
            log.warn("[稼穑] 写入学习进度失败（不影响农事）: userId={}, concept={}, err={}",
                    userId, conceptId, e.getMessage());
        }
        return newly;
    }
}
