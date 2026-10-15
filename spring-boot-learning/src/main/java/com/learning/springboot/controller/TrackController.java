package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.dto.TrackEventDto;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.TrackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前端埋点上报端点(免鉴权)
 *
 * <p>路径: {@code POST /api/track/batch}
 * <p>请求体: 数组(支持批量,Beacon 单次能发一个 JSON 数组)
 */
@RestController
@RequestMapping("/api/track")
public class TrackController {

    private final TrackService trackService;

    public TrackController(TrackService trackService) {
        this.trackService = trackService;
    }

    /**
     * 批量上报(Beacon / fetch 都支持)
     * <p>支持单事件(对象)或批量(数组)两种 body 形式
     */
    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> batch(@RequestBody Object body,
                                   @CurrentUser Long userId,
                                   HttpServletRequest req) {
        String ip = clientIp(req);
        List<TrackEventDto> events = parse(body, list -> list.forEach(ev -> {
            // 注入服务端识别的 userId(覆盖前端传的, 更可信)
            if (ev != null) ev.userId = userId;
            trackService.recordEvent(ev, ip);
        }));
        // Beacon 不需要响应体, 但仍返回 200 让前端 fetch 有正常返回
        return Result.ok("已接收 " + events.size() + " 条事件", null);
    }

    /** 解析 body: 支持单事件对象 或 数组 */
    @SuppressWarnings("unchecked")
    private List<TrackEventDto> parse(Object body, java.util.function.Consumer<List<TrackEventDto>> sink) {
        List<TrackEventDto> list = new java.util.ArrayList<>();
        if (body instanceof List) {
            for (Object o : (List<Object>) body) {
                if (o == null) continue;
                TrackEventDto e = mapper().convertValue(o, TrackEventDto.class);
                list.add(e);
            }
        } else if (body != null) {
            TrackEventDto e = mapper().convertValue(body, TrackEventDto.class);
            list.add(e);
        }
        sink.accept(list);
        return list;
    }

    private static com.fasterxml.jackson.databind.ObjectMapper MAPPER;
    private static synchronized com.fasterxml.jackson.databind.ObjectMapper mapper() {
        if (MAPPER == null) {
            MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
            MAPPER.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
        return MAPPER;
    }

    private String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) return ip.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}