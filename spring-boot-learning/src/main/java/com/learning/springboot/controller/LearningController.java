package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.LearningVO;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.LearningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习中心 API
 * <p>
 * 全部接口需要登录(JWT),进度以"当前登录用户"为维度。
 * 任何角色(USER/ADMIN)都可以学习并跟踪自己的进度。
 * 支持 subjectId(语言/科目维度)筛选,为空表示全部科目。
 */
@RestController
@RequestMapping("/api/learning")
public class LearningController {

    @Autowired private LearningService learningService;

    /** 启用的科目(语言)列表(学习中心/看板维度切换) */
    @GetMapping("/subjects")
    public ResponseEntity<?> subjects() {
        List<LearningVO.SubjectVO> data = learningService.getSubjects();
        return Result.ok(data);
    }

    /** 课程树(阶段 -> 章节 -> 课时,含当前用户学习状态);subjectId 可选 */
    @GetMapping("/curriculum")
    public ResponseEntity<?> curriculum(@CurrentUser Long userId,
                                        @RequestParam(required = false) Long subjectId) {
        List<LearningVO.StageVO> data = learningService.getCurriculum(userId, subjectId);
        return Result.ok(data);
    }

    /** 学习进度总览(总体 + 分阶段 + 分科目 + 最近学习);subjectId 可选 */
    @GetMapping("/progress")
    public ResponseEntity<?> progress(@CurrentUser Long userId,
                                      @RequestParam(required = false) Long subjectId) {
        LearningVO.ProgressStatsVO data = learningService.getProgressStats(userId, subjectId);
        return Result.ok(data);
    }

    /**
     * 学习看板
     * <p>
     * 管理员: 全员学习进度(教学管理视角);普通用户: 仅返回自己一条。
     * subjectId: 语言维度筛选(可选),并返回分科目概览。
     * 数据范围由服务端的角色判断决定,普通用户即使改请求参数也拿不到他人数据。
     */
    @GetMapping("/board")
    public ResponseEntity<?> board(@CurrentUser Long userId, @CurrentUser("roles") Set<String> roles,
                                   @RequestParam(required = false) Long subjectId) {
        LearningVO.BoardOverviewVO data = learningService.getBoardOverview(userId, roles, subjectId);
        return Result.ok(data);
    }

    /**
     * 最近 N 条学习完成事件(看板"实时活动"用,管理员/普通用户都能用)
     */
    @GetMapping("/recent-progress")
    public ResponseEntity<?> recentProgress(@RequestParam(defaultValue = "5") int limit) {
        int n = Math.max(1, Math.min(limit, 20));
        return Result.ok(learningService.recentProgress(n));
    }

    /**
     * 学习偏好(正在学习的语言/科目)
     * <p>
     * 头像旁语言选择框的记忆:登录后拉取、切换时保存,下次进入默认展示该语言的学习内容。
     */
    @GetMapping("/preference")
    public ResponseEntity<?> getPreference(@CurrentUser Long userId) {
        Long subjectId = learningService.getPreferredSubject(userId);
        String locale = learningService.getPreferredLocale(userId);
        return Result.ok(Map.of(
            "subjectId", subjectId == null ? 0 : subjectId,
            "locale", locale == null ? "" : locale));
    }

    /** 保存学习偏好(请求体 {subjectId: 数字或 null, locale: zh-CN/en-US 或 null},0/null 表示清除) */
    @PutMapping("/preference")
    @OperationLog(module = "学习中心", action = "切换学习语言")
    public ResponseEntity<?> savePreference(@CurrentUser Long userId, @RequestBody Map<String, Object> body) {
        Object raw = body.get("subjectId");
        Long subjectId = null;
        if (raw instanceof Number n) {
            subjectId = n.longValue();
            if (subjectId == 0) subjectId = null;
        } else if (raw instanceof String s && !s.isBlank()) {
            subjectId = Long.parseLong(s);
            if (subjectId == 0) subjectId = null;
        }
        // 只有传了 subjectId 字段才更新科目(仅传 locale 的请求不清理科目偏好)
        if (body.containsKey("subjectId")) {
            learningService.savePreferredSubject(userId, subjectId);
        }
        // 界面语言(可选): 传了 locale 字段才更新,空串/无效值按未设置处理
        String locale = null;
        if (body.containsKey("locale")) {
            Object l = body.get("locale");
            locale = (l instanceof String s && !s.isBlank()) ? s : null;
            learningService.savePreferredLocale(userId, locale);
        }
        return Result.ok("学习语言已更新", Map.of(
            "subjectId", subjectId == null ? 0 : subjectId,
            "locale", locale == null ? "" : locale));
    }

    /** 课时详情 */
    @GetMapping("/lessons/{id}")
    public ResponseEntity<?> lesson(@PathVariable Long id, @CurrentUser Long userId) {
        LearningVO.LessonDetailVO data = learningService.getLessonDetail(id, userId);
        return Result.ok(data);
    }

    /** 标记课时已学 */
    @PostMapping("/lessons/{id}/learn")
    @OperationLog(module = "学习中心", action = "完成课时")
    public ResponseEntity<?> markLearned(@PathVariable Long id, @CurrentUser Long userId) {
        learningService.markLearned(id, userId);
        return Result.ok("已标记为完成", Map.of("lessonId", id, "learned", true));
    }

    /** 取消已学标记 */
    @DeleteMapping("/lessons/{id}/learn")
    @OperationLog(module = "学习中心", action = "取消课时完成")
    public ResponseEntity<?> unmarkLearned(@PathVariable Long id, @CurrentUser Long userId) {
        learningService.unmarkLearned(id, userId);
        return Result.ok("已取消完成标记", Map.of("lessonId", id, "learned", false));
    }
}
