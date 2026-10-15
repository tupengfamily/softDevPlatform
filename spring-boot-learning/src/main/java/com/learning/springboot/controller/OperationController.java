package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.entity.OperationLog;
import com.learning.springboot.entity.PageError;
import com.learning.springboot.entity.PageView;
import com.learning.springboot.repository.OperationLogRepository;
import com.learning.springboot.repository.PageErrorRepository;
import com.learning.springboot.repository.PagePerfRepository;
import com.learning.springboot.repository.PageViewRepository;
import com.learning.springboot.annotation.RequireOperation;
import com.learning.springboot.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 运营管理(运营仪表板,ADMIN 角色)
 *
 * <p>5 个端点:
 * <ul>
 *   <li>/overview  今日 / 本周 / 本月 UV/PV/时长/错误数(总体 KPI)</li>
 *   <li>/pages     页面排行(按 PV 降序)</li>
 *   <li>/journey   用户旅程样本(最近的 N 个 session 路径)</li>
 *   <li>/perf      性能面板(FCP/LCP/FID/CLS/TTFB 平均)</li>
 *   <li>/errors    错误日志(最近 N 条)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/operation")
@RequireOperation
public class OperationController {

    private final PageViewRepository pageViewRepository;
    private final PageErrorRepository pageErrorRepository;
    private final PagePerfRepository pagePerfRepository;
    private final com.learning.springboot.repository.OperationLogRepository operationLogRepository;

    public OperationController(PageViewRepository pageViewRepository,
                                PageErrorRepository pageErrorRepository,
                                PagePerfRepository pagePerfRepository,
                                com.learning.springboot.repository.OperationLogRepository operationLogRepository) {
        this.pageViewRepository = pageViewRepository;
        this.pageErrorRepository = pageErrorRepository;
        this.pagePerfRepository = pagePerfRepository;
        this.operationLogRepository = operationLogRepository;
    }

    /** 站点总览: 今日 / 本周 / 本月 */
    @GetMapping("/overview")
    public ResponseEntity<?> overview(@CurrentUser Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", buildPeriodStats(now.toLocalDate().atStartOfDay(), now));
        result.put("week",  buildPeriodStats(now.toLocalDate().minusDays(7).atStartOfDay(), now));
        result.put("month", buildPeriodStats(now.toLocalDate().minusDays(30).atStartOfDay(), now));
        result.put("dailyTrend7d", dailyTrend(now.toLocalDate().minusDays(7).atStartOfDay(), now));
        return Result.ok(result);
    }

    /** 页面排行 */
    @GetMapping("/pages")
    public ResponseEntity<?> pages(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "50") int limit,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        List<Object[]> rows = pageViewRepository.pageRanking(f, t, Math.min(limit, 200));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", r[0]);
            m.put("url", r[1]);
            m.put("pv", asLong(r[2]));
            m.put("uv", asLong(r[3]));
            m.put("avgDurationMs", asLong(r[4]));
            m.put("completed", asLong(r[5]));
            result.add(m);
        }
        return Result.ok(result);
    }

    /** 用户旅程: 最近 N 个 session 的完整路径(Sankey 数据源) */
    @GetMapping("/journey")
    public ResponseEntity<?> journey(
        @RequestParam(defaultValue = "20") int sessionLimit,
        @CurrentUser Long adminId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<String> sessionIds = pageViewRepository.recentSessionIds(since, Math.min(sessionLimit, 100));
        // 构造 Sankey 图: 节点 + 边(从前一路由 -> 后一路由的转移)
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Integer> nodeIndex = new HashMap<>();
        List<String> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        for (String sid : sessionIds) {
            List<Object[]> views = pageViewRepository.userJourneyBySession(sid);
            for (int i = 0; i < views.size() - 1; i++) {
                Object[] rowFrom = views.get(i);
                Object[] rowTo = views.get(i + 1);
                String from = rowFrom[0] == null ? null : String.valueOf(rowFrom[0]);
                String to = rowTo[0] == null ? null : String.valueOf(rowTo[0]);
                if (from == null || to == null || from.equals(to)) continue;
                addNode(nodes, nodeIndex, from);
                addNode(nodes, nodeIndex, to);
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("source", from);
                link.put("target", to);
                link.put("value", 1);
                links.add(link);
            }
        }
        // 聚合重复边
        Map<String, Long> agg = new LinkedHashMap<>();
        for (Map<String, Object> l : links) {
            String k = l.get("source") + "->" + l.get("target");
            agg.merge(k, 1L, Long::sum);
        }
        List<Map<String, Object>> aggregated = new ArrayList<>();
        for (var e : agg.entrySet()) {
            String[] parts = e.getKey().split("->");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", parts[0]);
            m.put("target", parts[1]);
            m.put("value", e.getValue());
            aggregated.add(m);
        }
        result.put("nodes", nodes);
        result.put("links", aggregated);
        return Result.ok(result);
    }

    private void addNode(List<String> nodes, Map<String, Integer> idx, String name) {
        if (!idx.containsKey(name)) {
            idx.put(name, nodes.size());
            nodes.add(name);
        }
    }

    /** 性能面板 */
    @GetMapping("/perf")
    public ResponseEntity<?> perf(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        List<Object[]> avgRows = pagePerfRepository.perfAverages(f, t);
        Object[] avgs = avgRows.isEmpty() ? new Object[]{0, 0, 0, 0, 0, 0, 0, 0} : avgRows.get(0);
        List<Object[]> byRoute = pagePerfRepository.perfByRoute(f, t, 30);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("fcp", asLong(avgs[0]));
        overall.put("lcp", asLong(avgs[1]));
        overall.put("fid", asLong(avgs[2]));
        overall.put("cls", asDouble(avgs[3]) / 1000);
        overall.put("ttfb", asLong(avgs[4]));
        overall.put("domInteractive", asLong(avgs[5]));
        overall.put("loadComplete", asLong(avgs[6]));
        overall.put("sampleCount", asLong(avgs[7]));
        result.put("overall", overall);

        List<Map<String, Object>> routes = new ArrayList<>();
        for (Object[] r : byRoute) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", r[0]);
            m.put("url", r[1]);
            m.put("avgFcp", asLong(r[2]));
            m.put("avgLcp", asLong(r[3]));
            m.put("avgCls", asDouble(r[4]) / 1000);
            m.put("avgTtfb", asLong(r[5]));
            m.put("sampleCount", asLong(r[6]));
            routes.add(m);
        }
        result.put("byRoute", routes);
        return Result.ok(result);
    }

    /** 错误日志 + 错误类型分布 */
    @GetMapping("/errors")
    public ResponseEntity<?> errors(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "50") int limit,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        List<PageError> recent = pageErrorRepository.recentErrors(f, t, Math.min(limit, 200));
        List<Object[]> dist = pageErrorRepository.errorTypeDistribution(f, t);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PageError e : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("type", e.getErrorType());
            m.put("message", e.getErrorMessage());
            m.put("filename", e.getFilename());
            m.put("lineno", e.getLineno());
            m.put("colno", e.getColno());
            m.put("url", e.getUrl());
            m.put("route", e.getRouteName());
            m.put("userId", e.getUserId());
            m.put("occurAt", e.getOccurAt());
            m.put("stack", e.getStack());
            list.add(m);
        }
        result.put("recent", list);
        List<Map<String, Object>> typeDist = new ArrayList<>();
        for (Object[] r : dist) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r[0]);
            m.put("count", asLong(r[1]));
            typeDist.add(m);
        }
        result.put("typeDistribution", typeDist);
        return Result.ok(result);
    }

    /** OS / Browser / 设备分布(支持页面清单头部展示) */
    @GetMapping("/client-metrics")
    public ResponseEntity<?> clientMetrics(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("os",       toMapList(pageViewRepository.osDistribution(f, t),       "name", "pv", "uv"));
        result.put("browser",  toMapList(pageViewRepository.browserDistribution(f, t),  "name", "pv", "uv"));
        result.put("device",   toMapList(pageViewRepository.deviceDistribution(f, t),   "name", "pv", null));
        return Result.ok(result);
    }

    /** 页面详情: 单页面 PV/UV 趋势 + 访问者列表 + 错误列表 */
    @GetMapping("/page-detail")
    public ResponseEntity<?> pageDetail(
        @RequestParam String route,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", route);
        result.put("trend", toMapList(pageViewRepository.pageDetailTrend(route, f, t), "day", "pv", "uv"));
        List<Map<String, Object>> visitors = new ArrayList<>();
        for (Object[] r : pageViewRepository.pageDetailVisitors(route, f, t, 30)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", r[0]);
            m.put("userId", r[1]);
            m.put("ip", r[2]);
            m.put("os", r[3]);
            m.put("browser", r[4]);
            m.put("device", r[5]);
            m.put("enterAt", r[6]);
            visitors.add(m);
        }
        result.put("visitors", visitors);
        List<Map<String, Object>> errs = new ArrayList<>();
        for (Object[] r : pageViewRepository.pageDetailErrors(route, f, t)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("type", r[1]);
            m.put("message", r[2]);
            m.put("filename", r[3]);
            m.put("lineno", r[4]);
            m.put("occurAt", r[5]);
            errs.add(m);
        }
        result.put("errors", errs);
        return Result.ok(result);
    }

    /** 活跃用户排行 */
    @GetMapping("/users")
    public ResponseEntity<?> activeUsers(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "20") int limit,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        List<Object[]> rows = pageViewRepository.activeUsers(f, t, Math.min(limit, 100));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", r[0]);
            m.put("pv", asLong(r[1]));
            m.put("uv", asLong(r[2]));
            m.put("avgDurationMs", asLong(r[3]));
            m.put("lastSeen", r[4]);
            result.add(m);
        }
        return Result.ok(result);
    }

    /** 单用户旅程: 按用户 ID + 时间区间筛选(最近 500 条) */
    @GetMapping("/user-journey/{userId}")
    public ResponseEntity<?> userJourney(
        @PathVariable Long userId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(30) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        List<Object[]> rows = pageViewRepository.userJourneyByUserId(userId, f, t);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("sessionId", r[1]);
            m.put("url", r[2]);
            m.put("route", r[3]);
            m.put("pageTitle", r[4]);
            m.put("referrer", r[5]);
            m.put("enterAt", r[6]);
            m.put("leaveAt", r[7]);
            m.put("durationMs", r[8]);
            m.put("os", r[9]);
            m.put("browser", r[10]);
            m.put("device", r[11]);
            m.put("ip", r[12]);
            result.add(m);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("items", result);
        envelope.put("from", from == null ? "" : from.toString());
        envelope.put("to", to == null ? "" : to.toString());
        envelope.put("total", result.size());
        return Result.ok(envelope);
    }

    /** 接口报错: t_operation_log 中 status=0 失败的记录 */
    @GetMapping("/errors/api")
    public ResponseEntity<?> apiErrors(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String module,
        @CurrentUser Long adminId) {
        LocalDateTime f = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime t = (to == null ? LocalDate.now() : to).atTime(LocalTime.MAX);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        List<OperationLog> logs = operationLogRepository.search(
            module, null, f, t,
            org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 200))
        ).getContent();
        for (OperationLog l : logs) {
            if (l.getStatus() == null || l.getStatus() != 0) continue;  // 只要失败
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("userId", l.getUserId());
            m.put("username", l.getUsername());
            m.put("module", l.getModule());
            m.put("action", l.getAction());
            m.put("requestMethod", l.getRequestMethod());
            m.put("requestUrl", l.getRequestUrl());
            m.put("requestParams", l.getRequestParams());
            m.put("ip", l.getIp());
            m.put("userAgent", l.getUserAgent());
            m.put("costMs", l.getCostMs());
            m.put("errorMsg", l.getErrorMsg());
            m.put("createTime", l.getCreateTime());
            list.add(m);
        }
        result.put("recent", list);
        // 按模块分布
        List<Object[]> dist = operationLogRepository.apiErrorByModule(f, t);
        List<Map<String, Object>> moduleDist = new ArrayList<>();
        for (Object[] r : dist) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", r[0]);
            m.put("count", asLong(r[1]));
            moduleDist.add(m);
        }
        result.put("moduleDistribution", moduleDist);
        return Result.ok(result);
    }

    // ----- 内部辅助 -----

    private List<Map<String, Object>> toMapList(List<Object[]> rows, String nameKey, String pvKey, String uvKey) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(nameKey, r[0]);
            m.put(pvKey, asLong(r[1]));
            if (uvKey != null && r.length > 2) m.put(uvKey, asLong(r[2]));
            list.add(m);
        }
        return list;
    }

    // ----- 内部辅助 -----

    private Map<String, Object> buildPeriodStats(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = pageViewRepository.overviewStats(from, to);
        Object[] pvRow = rows.isEmpty() ? new Object[]{0, 0, 0, 0} : rows.get(0);
        long pv = asLong(pvRow[0]);
        long uv = asLong(pvRow[1]);
        long avgDur = asLong(pvRow[2]);
        long loggedUv = asLong(pvRow[3]);
        long errors = pageViewRepository.errorCount(from, to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pv", pv);
        m.put("uv", uv);
        m.put("loggedUv", loggedUv);
        m.put("avgDurationMs", avgDur);
        m.put("errors", errors);
        m.put("errorRate", pv > 0 ? Math.round(errors * 10000.0 / pv) / 100.0 : 0);  // 百分比两位
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    private List<Map<String, Object>> dailyTrend(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = pageViewRepository.dailyTrend(from, to);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            // SQLite 用 LocalDateTime 存为 epoch ms 字符串, 需要解析回 yyyy-MM-dd
            String day = r[0] == null ? "" : String.valueOf(r[0]);
            if (day.matches("\\d+")) {
                try {
                    long ms = Long.parseLong(day);
                    java.time.LocalDate d2 = java.time.Instant.ofEpochMilli(ms)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    day = d2.toString();
                } catch (Exception ignore) {}
            }
            m.put("day", day);
            m.put("pv", asLong(r[1]));
            m.put("uv", asLong(r[2]));
            list.add(m);
        }
        return list;
    }

    private static long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
    }

    private static double asDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }
}