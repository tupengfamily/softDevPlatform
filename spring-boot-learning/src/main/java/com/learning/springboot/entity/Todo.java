package com.learning.springboot.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 个人待办(我的待办: 学习计划/日常任务提醒)
 * <p>
 * 仅本人可见:所有查询/写操作都按 user_id 过滤。
 * priority: 0=低 1=中 2=高;status: 0=待办 1=已完成。
 *
 * <p><b>🔴 2026-10-28 P2：任务类型与稳定引用键</b>
 * <p>原先本表只能表示「玩家手写的一条待办」，无法表达「系统预置、
 * 与某个农事里程碑绑定的任务」。农场任务链需要后者，因为：
 * <ul>
 *   <li>预置任务必须<b>幂等</b>——同一个用户反复进入农场不能插出 4 条一样的任务；</li>
 *   <li>预置任务必须<b>能自动完成</b>——玩家收完第一季，「收完第一季」这条要自己变已完成，
 *       而不是靠前端记得去勾；</li>
 *   <li>玩家手写待办与系统任务要<b>能在界面上区分</b>，否则玩家会去删一条系统任务。</li>
 * </ul>
 * 所以加两个字段：
 * <ul>
 *   <li>{@code taskType} —— 任务类型，null = 玩家自建；非 null = 系统预置（见 {@link TaskType}）；</li>
 *   <li>{@code refKey} —— 稳定引用键，与 taskType 组成唯一标识，
 *       用于幂等 upsert（同一个 refKey 只会有一条）。</li>
 * </ul>
 */
@Entity
@Table(name = "t_todo")
public class Todo {

    /**
     * 系统预置任务类型（2026-10-28 P2）。
     *
     * <p>🔴 <b>为什么用字符串枚举而不是数字</b>：数字在 SQLite 里看不出含义，
     * 而 {@code t_todo} 没有 CHECK 约束保护，写错数字就是静默失效。
     * 用字符串后，值写错会在代码里一眼看出来，且与 {@code FarmAction.Type}
     * 这类已有的「字符串枚举 + 启动期校验」范式一致。
     *
     * <p>🔴 <b>新增类型时必须在 {@code FarmTaskChain} 里补一条规则</b>，
     * 否则会出现「任务永远不完成」—— 因为完成判定是按类型分派的。
     */
    public static final class TaskType {
        private TaskType() {}

        /** 建第一块地（选区 + 定播期） */
        public static final String FIRST_FIELD = "FARM_FIRST_FIELD";
        /** 完成一次追肥（分蘖肥 / 穗肥 / 基肥） */
        public static final String FIRST_FERTILIZE = "FARM_FIRST_FERTILIZE";
        /** 收完第一季 */
        public static final String FIRST_HARVEST = "FARM_FIRST_HARVEST";
        /** 解锁一个农业科学概念卡（概念课时全部学完） */
        public static final String FIRST_CONCEPT = "FARM_FIRST_CONCEPT";
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 待办内容 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 优先级: 0=低 1=中 2=高 */
    @Column(nullable = false)
    private Integer priority = 1;

    /** 状态: 0=待办 1=已完成 */
    @Column(nullable = false)
    private Integer status = 0;

    /** 截止日期(yyyy-MM-dd,可空) */
    @Column(name = "due_date", length = 10)
    private String dueDate;

    /**
     * 任务类型（2026-10-28 P2）。null = 玩家自建待办；非 null = 系统预置任务。
     *
     * <p>取值见 {@link TaskType}。
     */
    @Column(name = "task_type", length = 32)
    private String taskType;

    /**
     * 稳定引用键（2026-10-28 P2）。系统预置任务的幂等标识。
     *
     * <p>🔴 <b>为什么需要它</b>：预置任务要「插一次就不再插」，
     * 但 {@code t_todo} 上没有合适的唯一约束（用户自建任务的 title 会重复，
     * 不能拿 title 当键）。用 {@code (user_id, task_type, ref_key)} 三元组做
     * 业务层幂等，比加唯一索引更安全 —— 加索引会让「玩家自建 3 条同名待办」直接报错。
     */
    @Column(name = "ref_key", length = 64)
    private String refKey;

    /** 完成时间 */
    @Column(name = "done_time")
    private LocalDateTime doneTime;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.priority == null) this.priority = 1;
        if (this.status == null) this.status = 0;
    }

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getRefKey() { return refKey; }
    public void setRefKey(String refKey) { this.refKey = refKey; }

    /**
     * 是否系统预置任务（2026-10-28 P2 收尾）。
     *
     * <p>🔴 <b>这是「系统任务 vs 玩家自建待办」的唯一判定口径</b>：
     * 服务端的不可编辑/不可删除保护、待办页的前端分区，都用这一条。
     * 前端按 {@code taskType != null} 判定（{@code taskType} 会被 Jackson 自动序列化），
     * 两端口径必须一致 —— 若前端按标题猜「哪些是系统任务」，
     * 玩家自建一条同名待办就会被误判成系统任务而失去编辑入口。
     *
     * <p>🔴 {@code @JsonIgnore}：这是<b>服务端内部判定方法</b>，
     * 不是对外契约的一部分。对外的唯一事实是 {@code taskType}（null = 玩家自建）。
     * 若让它被序列化，接口就会多出一个 {@code systemTask} 布尔字段 ——
     * 于是「判定口径」变成两个字段，前端挑一个用就可能挑错。
     * 契约越小越不容易分叉。
     *
     * @return true = 系统预置（不可编辑/不可删除/不可手动勾选）
     */
    @JsonIgnore
    public boolean isSystemTask() {
        return taskType != null;
    }

    public LocalDateTime getDoneTime() { return doneTime; }
    public void setDoneTime(LocalDateTime doneTime) { this.doneTime = doneTime; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
