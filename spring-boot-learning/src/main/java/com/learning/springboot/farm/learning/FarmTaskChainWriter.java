package com.learning.springboot.farm.learning;

import com.learning.springboot.entity.Todo;
import com.learning.springboot.repository.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务链状态的<b>实际写入</b>（独立事务，2026-10-28 P2）。
 *
 * <p>🔴 <b>为什么必须抽成独立 Bean</b>：Spring 的 {@code @Transactional} 走 AOP 代理，
 * <b>同类内部的自调用不走代理</b>，事务注解直接失效。
 *
 * <p>本项目的痛点正是这个形态：
 * {@link FarmTaskChain#refreshAfter} 要在「农事事务提交之后」触发一次刷新，
 * 而它与 {@code refresh} 在<b>同一个类</b>里 —— 若把 {@code @Transactional}
 * 留在 {@code refresh} 上，由 lambda 内部调用，注解<b>完全不生效</b>，
 * 刷新就会跑在调用方的事务里（提交后回调里恰好没有事务，于是退化成无事务的
 * 自动提交写库 —— 语义变得不可控，且中途失败会留下半截数据）。
 *
 * <p>抽成独立 Bean 后调用是<b>跨 bean 的</b>，代理必然生效。
 *
 * <p>这是本项目「AOP 自调用陷阱」的第四例（前三例：{@code AdminBootstrap}
 * 委托 {@code DataSeedService}、{@code FarmPlayService} 委托 {@code SeasonContext}、
 * {@code FarmLearningBridge} 委托 {@code LessonProgressWriter}）。
 */
@Service
public class FarmTaskChainWriter {

    private static final Logger log = LoggerFactory.getLogger(FarmTaskChainWriter.class);

    @Autowired private TodoRepository todoRepository;

    /**
     * 把指定任务标记为完成（幂等）。
     *
     * <p>🔴 <b>为什么要 {@code REQUIRES_NEW}</b>：本方法跑在「农事事务已提交」之后，
     * 理论上没有外层事务。但写成 {@code REQUIRES_NEW} 可以保证
     * <b>即使将来有人在事务里调用它</b>，它也只写自己的事务，
     * 不会把调用方的事务标记成 rollback-only —— 那正是 P2 踩过的坑。
     *
     * @param userId  用户
     * @param taskType 任务类型
     * @return 本次被标记完成的任务（空列表 = 无变化）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Todo> markDone(Long userId, String taskType) {
        var opt = todoRepository.findFirstByUserIdAndTaskTypeAndRefKey(userId, taskType, refKeyOf(taskType));
        if (opt.isEmpty()) {
            return List.of();
        }
        Todo t = opt.get();
        if (t.getStatus() != null && t.getStatus() == 1) {
            return List.of();
        }
        t.setStatus(1);
        t.setDoneTime(java.time.LocalDateTime.now());
        todoRepository.save(t);
        log.info("[稼穑] 用户 {} 任务链自动完成: {}", userId, taskType);
        return List.of(t);
    }

    /**
     * 任务类型 → 稳定引用键（唯一出口）。
     *
     * <p>🔴 <b>为什么要在这里再存一份</b>：引用键是任务的稳定标识，
     * 它同时被「预置」（{@code FarmTaskChain}）与「完成判定」使用。
     * 两处各写一份字面量就可能写歪 —— 写歪的后果是<b>任务永远不完成</b>
     * （查不到那条任务），而且不会有任何报错。
     * 所以唯一出口放在这里，两边都从这里取。
     */
    static String refKeyOf(String taskType) {
        return switch (taskType) {
            case Todo.TaskType.FIRST_FIELD -> "field";
            case Todo.TaskType.FIRST_FERTILIZE -> "fert";
            case Todo.TaskType.FIRST_HARVEST -> "harvest";
            case Todo.TaskType.FIRST_CONCEPT -> "concept";
            default -> null;
        };
    }
}
