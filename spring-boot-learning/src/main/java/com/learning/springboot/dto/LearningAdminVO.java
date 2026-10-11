package com.learning.springboot.dto;

/**
 * 学习配置(管理员)的请求体与视图对象
 * <p>
 * - SubjectUpsertRequest : 科目(语言)新增/编辑
 * - ChapterUpsertRequest : 章节新增/编辑(含阶段归属,支持管理员调整学习菜单结构)
 * - ChapterAdminVO       : 章节管理列表条目
 */
public class LearningAdminVO {

    /** 科目(语言)新增/编辑请求 */
    public static class SubjectUpsertRequest {
        public String code;
        public String name;
        public String description;
        public Integer sortNo;
        public Boolean enabled;
    }

    /** 章节新增/编辑请求 */
    public static class ChapterUpsertRequest {
        public Long subjectId;
        public Integer stageNo;
        public String stageName;
        public Integer chapterNo;
        public String title;
        public String description;
        public String codePath;
        public Integer estimatedHours;
    }

    /** 章节管理列表条目 */
    public static class ChapterAdminVO {
        public Long id;
        public Long subjectId;
        public String subjectName;
        public int stageNo;
        public String stageName;
        public int chapterNo;
        public String title;
        public String description;
        public String codePath;
        public int estimatedHours;
        public long lessonCount;
    }
}
