package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘 API
 * <p>
 * - /summary /registrations /operations /status-distribution:单接口(细粒度缓存)
 * - /all:一次返回所有数据(前端主入口,减 4 次 RTT)
 */
@RestController
@RequestMapping("/api/dashboard")
@RequireAdmin("系统统计仅管理员可操作")
public class DashboardController {

    @Autowired private DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        return Result.ok(dashboardService.summary());
    }

    @GetMapping("/registrations")
    public ResponseEntity<?> registrations() {
        return Result.ok(dashboardService.last7DaysRegistrations());
    }

    @GetMapping("/operations")
    public ResponseEntity<?> operations() {
        return Result.ok(dashboardService.last7DaysOperations());
    }

    @GetMapping("/status-distribution")
    public ResponseEntity<?> statusDistribution() {
        return Result.ok(dashboardService.userStatusDistribution());
    }

    /** 7 日对局趋势 */
    @GetMapping("/game-plays")
    public ResponseEntity<?> gamePlays() {
        return Result.ok(dashboardService.last7DaysGamePlays());
    }

    /** 7 日学习完成趋势 */
    @GetMapping("/learning")
    public ResponseEntity<?> learning() {
        return Result.ok(dashboardService.last7DaysLearning());
    }

    /** 7 日反馈趋势 */
    @GetMapping("/feedback-trend")
    public ResponseEntity<?> feedbackTrend() {
        return Result.ok(dashboardService.last7DaysFeedback());
    }

    /** 最近 5 条反馈 */
    @GetMapping("/recent-feedback")
    public ResponseEntity<?> recentFeedback() {
        return Result.ok(dashboardService.recentFeedback(5));
    }

    /** 最近 5 条游戏对局 */
    @GetMapping("/recent-games")
    public ResponseEntity<?> recentGames() {
        return Result.ok(dashboardService.recentGameScores(5));
    }

    /**
     * 一次拉全:summary + 4 个 7 日趋势桶 + status + 2 个实时列表
     * <p>
     * 替代前端 4 次串行请求(原版)→ 1 次
     */
    @GetMapping("/all")
    public ResponseEntity<?> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", dashboardService.summary());
        out.put("registrations", dashboardService.last7DaysRegistrations());
        out.put("operations", dashboardService.last7DaysOperations());
        out.put("statusDist", dashboardService.userStatusDistribution());
        out.put("gamePlays", dashboardService.last7DaysGamePlays());
        out.put("learning", dashboardService.last7DaysLearning());
        out.put("feedbackTrend", dashboardService.last7DaysFeedback());
        out.put("recentFeedback", dashboardService.recentFeedback(5));
        out.put("recentGames", dashboardService.recentGameScores(5));
        return Result.ok(out);
    }
}