package com.learning.springboot.service;

import com.learning.springboot.dto.LearningVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学习中心 Service
 * <p>
 * 职责:科目(语言)维度、课程树查询、课时详情、学习进度标记与统计。
 * 进度数据均以"当前登录用户"为维度,由 Controller 从 JWT 解析后传入 userId。
 * 课程/进度均支持 subjectId(语言维度)筛选;为空表示全部科目。
 */
public interface LearningService {

    /** 启用的科目(语言)列表(含课时数),学习中心/看板维度切换用 */
    List<LearningVO.SubjectVO> getSubjects();

    /** 课程树(阶段 -> 章节 -> 课时),附带当前用户的学习状态;subjectId 可空 */
    List<LearningVO.StageVO> getCurriculum(Long userId, Long subjectId);

    /** 课时详情(含正文、前后课时导航、当前用户学习状态) */
    LearningVO.LessonDetailVO getLessonDetail(Long lessonId, Long userId);

    /** 标记课时为"已学习"(幂等) */
    void markLearned(Long lessonId, Long userId);

    /** 取消"已学习"标记(幂等) */
    void unmarkLearned(Long lessonId, Long userId);

    /** 学习进度总览:总体 + 分阶段 + 分科目 + 最近学习记录;subjectId 可空 */
    LearningVO.ProgressStatsVO getProgressStats(Long userId, Long subjectId);

    /**
     * 学习看板:管理员返回全员学习进度,普通用户仅返回自己一条。
     * 数据范围由 roles 决定,权限判断在服务层收口,避免前端绕过。
     * subjectId: 语言维度筛选(可空=全部),并返回分科目概览。
     */
    LearningVO.BoardOverviewVO getBoardOverview(Long userId, java.util.Set<String> roles, Long subjectId);

    /** 当前用户的学习偏好(正在学习的语言/科目 id),未设置返回 null */
    Long getPreferredSubject(Long userId);

    /** 保存学习偏好(subjectId 可空=清除);科目必须存在且启用 */
    void savePreferredSubject(Long userId, Long subjectId);

    /** 界面语言偏好(zh-CN/en-US),未设置返回 null */
    String getPreferredLocale(Long userId);

    /** 保存界面语言偏好(locale 可空=清除;仅接受 zh-CN/en-US) */
    void savePreferredLocale(Long userId, String locale);
}
