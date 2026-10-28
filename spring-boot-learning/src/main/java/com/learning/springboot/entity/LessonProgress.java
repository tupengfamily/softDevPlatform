package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 学习进度实体(记录"哪个用户学完了哪节课")
 * <p>
 * - (userId, lessonId) 唯一: 一节课只会有一条进度记录
 * - 记录存在 = 已学(未学习天然区分);progressPercent 提供 0-100 中间进度以支持"看到一半"等场景
 * - updatedAt 用于多端同步冲突检测
 */
@Entity
@Table(name = "t_lesson_progress",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_lesson", columnNames = {"user_id", "lesson_id"})
    },
    indexes = {
        // 看板 "按用户最近学习" / 我的学习中心按时间线: WHERE user_id=? ORDER BY learned_at DESC
        @Index(name = "idx_user_learnedAt", columnList = "user_id, learned_at DESC"),
        // 运维查询 / 章节级进度聚合: WHERE lesson_id=?
        @Index(name = "idx_lesson", columnList = "lesson_id")
    }
)
public class LessonProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 id */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 课时 id */
    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    /** 学习进度 0-100, >= 80 视为已学完(可前端定时上报/滚动到底时上报) */
    @Column(name = "progress_percent", nullable = false)
    private Integer progressPercent = 0;

    /**
     * 遗留状态位（1 = 已学完）。
     *
     * <p>🔴 <b>为什么实体要映射一个代码里没人用的列</b>：
     * 活动库的 {@code t_lesson_progress} 上这一列是 {@code NOT NULL} 且<b>无默认值</b>，
     * 而早期实体没有映射它 —— 于是任何 insert 都带着 {@code status = NULL}，
     * 直接撞 NOT NULL 约束。
     *
     * <p>这不是本轮新引入的问题：这一列从项目早期就在库里（历史数据值恒为 1），
     * 只是<b>此前这张表几乎只被读、不被写</b>，所以一直没暴露。
     * P2「实操即课时」第一次真的往这张表写数据，才把它炸出来。
     *
     * <p>🔴 <b>为什么不在迁移脚本里把列删掉或放宽</b>：
     * 那要动存量数据的语义（历史行的 status=1 是有意义的），
     * 而补一个映射 + 写入时置 1 就能与历史完全一致 —— 改动面最小、可回滚。
     *
     * <p>🔴 <b>为什么不删掉这个字段</b>：删了它，insert 又会回到「status=NULL」的老问题。
     * 它必须留着，且**每次写入都要给值**。
     */
    @Column(name = "status", nullable = false)
    private Integer status = STATUS_LEARNED;

    /** 遗留状态位取值：已学完。 */
    public static final int STATUS_LEARNED = 1;

    /**
     * 首次完成时间。
     *
     * <p>🔴 <b>为什么「记录存在」就必须有时间</b>（而不是「进度 ≥ 80 才写」）：
     * 活动库这一列是 {@code NOT NULL}，而学习中心的「标记完成」写的是
     * {@code progress_percent = 0} —— 若按「≥ 80 才写 learned_at」的旧规则，
     * insert 会带 {@code learned_at = NULL}，<b>直接撞 NOT NULL 约束</b>，
     * 学习中心的「标记完成」在真实库上直接 500。
     *
     * <p>🔴 <b>而且这个旧规则本身语义就是矛盾的</b>：
     * 本实体的核心语义是「<b>记录存在 = 已学</b>」——
     * {@code findLearnedIds()} 就是「有记录就算学过」。
     * 那么「已学」却可能没有 {@code learned_at}，
     * 就会让「最近学习时间线」（{@code recentProgress} 过滤 {@code learnedAt != null}）
     * 与「课程完成数」给出<b>两个矛盾的答案</b>。
     *
     * <p>所以口径统一为：<b>写记录 = 已学 = 有完成时间</b>。
     * {@code progressPercent} 只表达「学到哪儿了」，不参与「算不算学过」的判定。
     */
    @Column(name = "learned_at", updatable = false)
    private LocalDateTime learnedAt;

    /** 记录创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 最后更新时间(用于多端同步冲突检测 / 审计) */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public LessonProgress() {}

    public LessonProgress(Long userId, Long lessonId) {
        this.userId = userId;
        this.lessonId = lessonId;
        this.progressPercent = 0;
        // 🔴 遗留 status 列是 NOT NULL 且无默认值 —— 构造时就给值，
        // 否则 insert 带 NULL 直接撞约束（实测踩过）。
        this.status = STATUS_LEARNED;
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        // 🔴 learned_at 在活动库是 NOT NULL —— 记录存在即「已学」，必须带完成时间。
        // 否则学习中心的「标记完成」（progress=0）在真实库上直接 500（实测踩过）。
        this.learnedAt = this.createTime;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
        if (progressPercent == null) progressPercent = 0;
        // 🔴 遗留 status 列 NOT NULL：兜底给值（构造器已给，这里防 JPA 无参构造路径）
        if (status == null) status = STATUS_LEARNED;
        // 🔴 learned_at NOT NULL：记录存在 = 已学 = 必须有完成时间（口径见字段注释）
        if (learnedAt == null) learnedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
        if (learnedAt == null) learnedAt = this.updateTime;
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }

    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getLearnedAt() { return learnedAt; }
    public void setLearnedAt(LocalDateTime learnedAt) { this.learnedAt = learnedAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "LessonProgress{id=" + id + ", userId=" + userId
                + ", lessonId=" + lessonId + ", progressPercent=" + progressPercent + "}";
    }
}
