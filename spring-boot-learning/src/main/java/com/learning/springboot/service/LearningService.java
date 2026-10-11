package com.learning.springboot.service;

import com.learning.springboot.dto.LearningVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学习中心 Service
 * <p>
 * 职责:课程树查询、课时详情、学习进度标记与统计。
 * 进度数据均以"当前登录用户"为维度,由 Controller 从 JWT 解析后传入 userId。
 */
public interface LearningService {

    /** 课程树(阶段 -> 章节 -> 课时),附带当前用户的学习状态 */
    List<LearningVO.StageVO> getCurriculum(Long userId);

    /** 课时详情(含正文、前后课时导航、当前用户学习状态) */
    LearningVO.LessonDetailVO getLessonDetail(Long lessonId, Long userId);

    /** 标记课时为"已学习"(幂等) */
    void markLearned(Long lessonId, Long userId);

    /** 取消"已学习"标记(幂等) */
    void unmarkLearned(Long lessonId, Long userId);

    /** 学习进度总览:总体 + 分阶段 + 最近学习记录 */
    LearningVO.ProgressStatsVO getProgressStats(Long userId);

    /**
     * 学习看板:管理员返回全员学习进度,普通用户仅返回自己一条。
     * 数据范围由 roles 决定,权限判断在服务层收口,避免前端绕过。
     */
    LearningVO.BoardOverviewVO getBoardOverview(Long userId, java.util.Set<String> roles);
}
