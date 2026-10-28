package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P2「农业科学模块」相关表的迁移补丁（幂等，2026-10-28）。
 *
 * <p><b>为什么必须手写 DDL</b>：SQLite 下 {@code hibernate.ddl-auto: update} 不可靠，
 * 新列一律手写（与 {@link FarmActionSchemaPatch} / {@link FarmUserClockSchemaPatch} 同范式）。
 *
 * <p>本次补三列：
 * <ol>
 *   <li>{@code t_learning_lesson.concept_id} —— 「实操即课时」的<b>稳定绑定键</b>。
 *       少了它，农事动作找不到对应课时，整条「种一次水稻 = 学完有效积温」链路断掉。</li>
 *   <li>{@code t_todo.task_type} —— 区分「玩家自建待办」与「系统预置任务」。
 *       少了它，前端没法在界面上区分二者，玩家会去删系统任务。</li>
 *   <li>{@code t_todo.ref_key} —— 系统预置任务的<b>幂等引用键</b>。
 *       少了它，重复进农场会插出 N 条一样的任务。</li>
 * </ol>
 *
 * <p>🔴 <b>执行顺序（这是本类存在的全部理由）</b>：
 * 本 patch 补的列<b>恰好被农业课程种子立刻用到</b> ——
 * {@code t_learning_lesson.concept_id} 是 {@code AgriDataSeedService} 写课时的目标列。
 * 而 Spring Boot 的启动顺序是：
 *
 * <pre>
 *   ContextRefreshedEvent
 *        → CommandLineRunner（AdminBootstrap：跑课程种子）   ← 种子在这里
 *        → ApplicationReadyEvent                        ← 旧实现把 patch 放这里
 * </pre>
 *
 * <p>把 patch 挂在 {@code ApplicationReadyEvent} 上，结果是
 * <b>种子先跑、列后建</b> —— 种子写 {@code concept_id} 时列还不存在。
 * 虽然 {@code hibernate.ddl-auto: update} 通常会补上这个列（实体上已声明），
 * 但 SQLite 下 Hibernate 的 schema update 不可靠（本项目已因此踩过坑），
 * <b>不能把它当唯一保障</b>。
 *
 * <p>所以本类改为 {@link org.springframework.boot.CommandLineRunner}，
 * 并用 {@code @Order(HIGHEST_PRECEDENCE)} 保证它<b>比 AdminBootstrap 先跑</b>
 * （{@code CommandLineRunner} 之间按 {@code @Order} 排序）。
 * 这样列一定在种子之前就位，不依赖 Hibernate 补列。
 *
 * <p>🔴 <b>为什么不并进 AdminBootstrap</b>：那样虽然顺序确定，
 * 但会让「补列」与「灌数据」两件事混在一个入口里；
 * 而且既有其它 schema patch 已经是独立类，保持同范式更一致。
 */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class AgriLearningSchemaPatch implements org.springframework.boot.CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgriLearningSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public AgriLearningSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        // ① 实操即课时的绑定键
        ensureColumn("t_learning_lesson", "concept_id", "varchar(32)");
        // ② 任务链：类型 + 幂等引用键
        ensureColumn("t_todo", "task_type", "varchar(32)");
        ensureColumn("t_todo", "ref_key", "varchar(64)");
        // ③ 索引：任务链按 (user_id, task_type, ref_key) 查
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_todo_user_task "
                + "ON t_todo (user_id, task_type, ref_key)");
    }

    private void executeQuietly(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ex) {
            log.warn("[schema-patch] 执行失败 [{}]: {}", sql, ex.getMessage());
        }
    }

    /** 列不存在时补建（SQLite 支持 ADD COLUMN）。 */
    private void ensureColumn(String table, String column, String type) {
        try {
            var rows = jdbc.queryForList("PRAGMA table_info(" + table + ")");
            for (var row : rows) {
                Object name = row.get("name");
                if (name != null && name.toString().equalsIgnoreCase(column)) {
                    return;
                }
            }
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("[schema-patch] {}.{} 已补建", table, column);
        } catch (Exception ex) {
            log.warn("[schema-patch] 补建 {}.{} 失败: {}", table, column, ex.getMessage());
        }
    }
}
