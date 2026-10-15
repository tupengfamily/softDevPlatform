package com.learning.springboot.service;

import com.learning.springboot.dto.TrackEventDto;
import com.learning.springboot.entity.PageError;
import com.learning.springboot.entity.PagePerf;
import com.learning.springboot.entity.PageView;
import com.learning.springboot.repository.PageErrorRepository;
import com.learning.springboot.repository.PagePerfRepository;
import com.learning.springboot.repository.PageViewRepository;
import com.learning.springboot.util.UserAgentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 前端埋点接收服务
 *
 * <p>负责将 {@link TrackEventDto} 落到对应表 + 提取客户端 IP
 */
@Service
public class TrackService {

    private static final Logger log = LoggerFactory.getLogger(TrackService.class);

    private final PageViewRepository pageViewRepository;
    private final PageErrorRepository pageErrorRepository;
    private final PagePerfRepository pagePerfRepository;

    public TrackService(PageViewRepository pageViewRepository,
                        PageErrorRepository pageErrorRepository,
                        PagePerfRepository pagePerfRepository) {
        this.pageViewRepository = pageViewRepository;
        this.pageErrorRepository = pageErrorRepository;
        this.pagePerfRepository = pagePerfRepository;
    }

    @Transactional
    public void recordEvent(TrackEventDto e, String clientIp) {
        if (e == null || e.type == null || e.sessionId == null) {
            log.debug("[Track] 跳过(字段不全): type={}, sessionId={}", e.type, e.sessionId);
            return;
        }
        try {
            switch (e.type) {
                case "page_view" -> savePageView(e, clientIp, /*leave=*/false);
                case "page_leave" -> savePageView(e, clientIp, /*leave=*/true);
                case "page_error" -> savePageError(e, clientIp);
                case "page_perf" -> savePagePerf(e, clientIp);
                default -> log.debug("[Track] 忽略未知事件类型: {}", e.type);
            }
        } catch (Exception ex) {
            log.warn("[Track] 写入失败 type={} url={}: {}", e.type, e.url, ex.getMessage());
        }
    }

    private void savePageView(TrackEventDto e, String clientIp, boolean leave) {
        PageView v = new PageView();
        v.setSessionId(e.sessionId);
        v.setUserId(e.userId);
        v.setUrl(truncate(e.url, 500));
        v.setRouteName(truncate(e.routeName, 100));
        v.setPageTitle(truncate(e.pageTitle, 200));
        v.setReferrer(truncate(e.referrer, 500));
        v.setUserAgent(truncate(e.userAgent, 500));
        // UA 解析(OS / Browser / Device)
        UserAgentParser.Result ua = UserAgentParser.parse(e.userAgent);
        v.setOs(truncate(ua.os, 50));
        v.setBrowser(truncate(ua.browser, 50));
        v.setDeviceType(ua.deviceType);
        v.setIp(clientIp);
        v.setScreen(truncate(e.screen, 50));
        v.setEnterAt(toLocal(e.enterAt));
        if (leave) {
            v.setLeaveAt(toLocal(e.leaveAt));
            v.setDurationMs(e.durationMs);
        }
        pageViewRepository.save(v);
    }

    private void savePageError(TrackEventDto e, String clientIp) {
        PageError err = new PageError();
        err.setSessionId(e.sessionId);
        err.setUserId(e.userId);
        err.setUrl(truncate(e.url, 500));
        err.setRouteName(truncate(e.routeName, 100));
        err.setErrorType(e.errorType == null ? "Error" : e.errorType);
        err.setErrorMessage(truncate(e.errorMessage, 1000));
        err.setStack(truncate(e.stack, 4000));
        err.setFilename(truncate(e.filename, 500));
        err.setLineno(e.lineno);
        err.setColno(e.colno);
        err.setOccurAt(toLocal(e.timestamp));
        pageErrorRepository.save(err);
    }

    private void savePagePerf(TrackEventDto e, String clientIp) {
        PagePerf p = new PagePerf();
        p.setSessionId(e.sessionId);
        p.setUserId(e.userId);
        p.setUrl(truncate(e.url, 500));
        p.setRouteName(truncate(e.routeName, 100));
        p.setFcp(longOrNull(e.fcp));
        p.setLcp(longOrNull(e.lcp));
        p.setFid(longOrNull(e.fid));
        p.setCls(e.cls == null ? null : (int) Math.round(e.cls * 1000));
        p.setTtfb(longOrNull(e.ttfb));
        p.setDomInteractive(longOrNull(e.domInteractive));
        p.setLoadComplete(longOrNull(e.loadComplete));
        p.setSampleAt(toLocal(e.timestamp));
        pagePerfRepository.save(p);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Long longOrNull(Double d) {
        if (d == null) return null;
        return Math.round(d);
    }

    private static LocalDateTime toLocal(Long msEpoch) {
        if (msEpoch == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(msEpoch), ZoneId.systemDefault());
    }
}