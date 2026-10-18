package com.learning.springboot.service.impl;

import com.learning.springboot.repository.DashboardRepository;
import com.learning.springboot.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘服务实现
 * <p>
 * 高频 admin 访问页面,summary + 趋势桶加缓存(60s),减少 4 次/请求 → 5 分钟一次
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired private DashboardRepository dashboardRepository;

    /** 主指标 60s 缓存 — 高频访问页面,容忍分钟级延迟 */
    @Override
    @Cacheable(value = "dashboardSummary", key = "'all'")
    public Map<String, Object> summary() {
        return dashboardRepository.dashboardSummary();
    }

    @Override
    @Cacheable(value = "dashboardTrend", key = "'reg'")
    public List<Map<String, Object>> last7DaysRegistrations() {
        return dashboardRepository.last7DaysRegistrations();
    }

    @Override
    @Cacheable(value = "dashboardTrend", key = "'ops'")
    public List<Map<String, Object>> last7DaysOperations() {
        return dashboardRepository.last7DaysOperations();
    }

    /** 状态分布不加缓存 — 用户状态变化频(可能 60s 内禁用/启用),实时性重要 */
    @Override
    public List<Map<String, Object>> userStatusDistribution() {
        return dashboardRepository.userStatusDistribution();
    }

    @Override
    @Cacheable(value = "dashboardTrend", key = "'games'")
    public List<Map<String, Object>> last7DaysGamePlays() {
        return dashboardRepository.last7DaysGamePlays();
    }

    @Override
    @Cacheable(value = "dashboardTrend", key = "'learn'")
    public List<Map<String, Object>> last7DaysLearning() {
        return dashboardRepository.last7DaysLearning();
    }

    @Override
    @Cacheable(value = "dashboardTrend", key = "'feedback'")
    public List<Map<String, Object>> last7DaysFeedback() {
        return dashboardRepository.last7DaysFeedback();
    }

    /** 最近反馈 / 对局:实时数据,无缓存 */
    @Override
    public List<Map<String, Object>> recentFeedback(int limit) {
        return dashboardRepository.recentFeedback(limit);
    }

    @Override
    public List<Map<String, Object>> recentGameScores(int limit) {
        return dashboardRepository.recentGameScores(limit);
    }
}