package com.learning.springboot.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习模块的视图对象集合
 * <p>
 * 为减少小文件数量,采用嵌套静态类组织:
 * - ProgressStatsVO  : 学习进度总览(总体 + 分阶段)
 * - StageVO          : 课程树中的"阶段"节点
 * - ChapterVO        : 课程树中的"章节"节点(含该章进度)
 * - LessonItemVO     : 课程树中的"课时"条目(目录展示用)
 * - LessonDetailVO   : 课时详情(正文 + 前后导航 + 当前用户学习状态)
 * - RecentLearnedVO  : 最近学习记录条目
 */
public class LearningVO {

    /** 学习进度总览 */
    public static class ProgressStatsVO {
        public long totalLessons;         // 平台全部课时数
        public long learnedLessons;       // 当前用户已学课时数
        public int percent;               // 总完成百分比(0-100)
        public List<StageProgressVO> stages = new ArrayList<>();
        public List<RecentLearnedVO> recent = new ArrayList<>();
    }

    /** 分阶段进度 */
    public static class StageProgressVO {
        public int stageNo;
        public String stageName;
        public long total;                // 该阶段课时总数
        public long learned;              // 该阶段已学课时数
        public int percent;               // 该阶段完成百分比
    }

    /** 最近学习条目 */
    public static class RecentLearnedVO {
        public Long lessonId;
        public String lessonTitle;
        public String chapterTitle;
        public LocalDateTime learnedAt;
    }

    /** 课程树 - 阶段 */
    public static class StageVO {
        public int stageNo;
        public String stageName;
        public List<ChapterVO> chapters = new ArrayList<>();
    }

    /** 课程树 - 章节(含进度统计) */
    public static class ChapterVO {
        public Long id;
        public int stageNo;
        public String stageName;
        public int chapterNo;
        public String title;
        public String description;
        public String codePath;
        public int estimatedHours;
        public long totalLessons;
        public long learnedLessons;
        public List<LessonItemVO> lessons = new ArrayList<>();
    }

    /** 课程树 - 课时条目(目录展示) */
    public static class LessonItemVO {
        public Long id;
        public int lessonNo;
        public String title;
        public String summary;
        public int difficulty;            // 1=简单 2=中等 3=较难
        public int durationMinutes;
        public boolean learned;           // 当前用户是否已学
    }

    /** 课时详情 */
    public static class LessonDetailVO {
        public Long id;
        public Long chapterId;
        public int stageNo;
        public String stageName;
        public int chapterNo;
        public String chapterTitle;
        public int lessonNo;
        public String title;
        public String summary;
        public String content;            // 轻量 Markdown 正文
        public String codeExample;
        public int difficulty;
        public int durationMinutes;
        public boolean learned;
        public Long prevLessonId;         // 上一课(可能为 null)
        public Long nextLessonId;         // 下一课(可能为 null)
    }

    /** 学习看板: 单个用户的学习概况 */
    public static class BoardRowVO {
        public Long userId;
        public String username;
        public String email;
        public String roles;              // 角色编码,逗号分隔(如 ADMIN,USER)
        public long learnedLessons;       // 已完成课时数
        public long totalLessons;         // 课程总课时数
        public int percent;               // 完成百分比(0-100)
        public String lastLearnedAt;      // 最近学习时间 yyyy-MM-dd HH:mm,未学习为 null
        public boolean self;              // 是否为当前登录用户(前端可高亮)
    }

    /** 学习看板: 汇总概览 */
    public static class BoardOverviewVO {
        public String scope;              // ALL=管理员视角全员 / SELF=普通用户仅自己
        public int totalUsers;            // 统计范围内的用户数
        public int activeUsers;           // 其中已开始学习的用户数
        public long totalLessons;         // 课程总课时数
        public int avgPercent;            // 平均完成度
        public int maxPercent;            // 最高完成度
        public List<BoardRowVO> rows;     // 明细行(按完成度降序)
    }
}
