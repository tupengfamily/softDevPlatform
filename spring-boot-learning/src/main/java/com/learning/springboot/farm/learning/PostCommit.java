package com.learning.springboot.farm.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「农事事务提交<b>之后</b>」执行附属写入的调度器（2026-10-28 P2）。
 *
 * <p>🔴 <b>为什么附属写入不能放进农事事务里</b>：
 * 本项目持久层是 SQLite，而 <b>SQLite 同一时刻只允许一个写者</b>。
 * 于是「农事事务里再开一个独立事务（{@code REQUIRES_NEW}）写学习进度」这条路<b>走不通</b>——
 * 这是本轮<b>实测</b>出来的，不是推测：
 *
 * <pre>
 *   外层：农事事务（REQUIRED）           内层：REQUIRES_NEW 的独立事务
 *   ├─ INSERT t_farm_action  → 持有写锁
 *   │                              ├─ INSERT t_lesson_progress
 *   │                              │   → database is locked（SQLITE_BUSY）
 *   │                              └─ 若侥幸提交：外层事务的读快照被作废
 *   └─ 外层再写 → SQLITE_BUSY_SNAPSHOT
 *   └─ 外层 commit → UnexpectedRollbackException → 玩家的收割动作被吃掉 ❌
 * </pre>
 *
 * <p>换句话说：<b>「独立事务」这条隔离手段在 SQLite 上根本不成立</b>。
 * 内层提交要么抢不到锁，要么把外层事务的快照搞坏。
 *
 * <p>🔴 <b>所以真正的墙不是「独立事务」，而是「农事事务之外」</b>：
 * 只有等农事事务<b>提交并释放写锁</b>之后再去写附属数据，
 * 才既不抢锁，也<b>结构性地</b>保证「附属写入失败 ≠ 农事失败」。
 *
 * <p>本类就是这个挂载点：
 * <ul>
 *   <li>当前有事务在途 → 注册 {@code afterCommit} 回调，<b>提交后</b>执行；</li>
 *   <li>当前没有事务 → 直接执行（本来就不存在抢锁问题）。</li>
 * </ul>
 *
 * <p>🔴 <b>回调里吞掉所有异常</b>：农事此时已经提交，
 * 附属写入失败只该被记录，<b>绝不能把一个已成功的农事动作变成报错</b>。
 *
 * <p>🔴 <b>注册顺序 = 执行顺序</b>：Spring 按注册先后触发 {@code afterCommit}。
 * 这条被用来保证「先写学习进度，再刷任务链」——
 * 否则「点亮一个概念卡」这条任务会因为读不到刚写进去的进度而判不出完成。
 */
public final class PostCommit {

    private static final Logger log = LoggerFactory.getLogger(PostCommit.class);

    private PostCommit() {}

    /**
     * 在当前事务提交后执行一个附属写入动作（不阻塞农事提交）。
     *
     * @param label  动作名（用于日志定位）
     * @param action 要执行的动作
     */
    public static void run(String label, Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invoke(label, action);
                }
            });
            return;
        }
        // 没有事务在途 = 没有外层写锁，直接执行
        invoke(label, action);
    }

    /**
     * 执行动作并吞掉异常。
     *
     * <p>🔴 <b>为什么连「提交期」的异常也要在这里吞</b>：
     * 附属写入跑在<b>独立事务</b>里，若写入过程中出错，事务管理器会在
     * <b>方法返回之后</b>（提交时）抛出 {@code UnexpectedRollbackException} ——
     * 那个异常发生在事务方法<b>外面</b>，动作内部是 catch 不到的。
     * 所以捕获必须在这里（最外层）才拦得住。
     */
    private static void invoke(String label, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            log.warn("[稼穑] 提交后附属写入失败（不影响农事）: {}, err={}", label, e.getMessage());
        }
    }
}
