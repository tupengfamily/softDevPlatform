package com.learning.springboot.service;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘服务接口
 * <p>
 * 数据驱动:summary 同时包含 admin 维度的传统指标 + 各业务模块(游戏/学习/反馈/沙箱)的实时指标
 */
public interface DashboardService {

    /** 仪表盘首页关键指标(后台账户 + 各业务模块聚合) */
    Map<String, Object> summary();

    /** 最近 7 天每天新增用户 */
    List<Map<String, Object>> last7DaysRegistrations();

    /** 最近 7 天每天操作次数 */
    List<Map<String, Object>> last7DaysOperations();

    /** 用户状态分布 */
    List<Map<String, Object>> userStatusDistribution();

    /** 最近 7 天每天对局数(各游戏汇总) */
    List<Map<String, Object>> last7DaysGamePlays();

    /** 最近 7 天每天学习完成数(lesson_progress.learned_at) */
    List<Map<String, Object>> last7DaysLearning();

    /** 最近 7 天每天反馈数 */
    List<Map<String, Object>> last7DaysFeedback();

    /** 最近 N 条反馈 */
    List<Map<String, Object>> recentFeedback(int limit);

    /** 最近 N 条游戏对局 */
    List<Map<String, Object>> recentGameScores(int limit);
}