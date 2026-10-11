package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘 API
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
        List<Map<String, Object>> data = dashboardService.last7DaysRegistrations();
        return Result.ok(data);
    }

    @GetMapping("/operations")
    public ResponseEntity<?> operations() {
        List<Map<String, Object>> data = dashboardService.last7DaysOperations();
        return Result.ok(data);
    }

    @GetMapping("/status-distribution")
    public ResponseEntity<?> statusDistribution() {
        return Result.ok(dashboardService.userStatusDistribution());
    }
}