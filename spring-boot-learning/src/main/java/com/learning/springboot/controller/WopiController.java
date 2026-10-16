package com.learning.springboot.controller;

import com.learning.springboot.config.OnlyOfficeProperties;
import com.learning.springboot.entity.MyDocFile;
import com.learning.springboot.repository.MyDocFileRepository;
import com.learning.springboot.security.WopiAuthFilter;
import com.learning.springboot.service.MyDocService;
import com.learning.springboot.service.doc.MyDocStorage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WOPI (Web Application Open Platform Interface) 端点.
 * <p>
 * OnlyOffice Document Server 通过这组端点读写我们的 docx/xlsx/pptx.
 * 三件套共用同一组端点,后端根据 fileId 定位真实文件.
 *
 * <p>协议规范: https://learn.microsoft.com/en-us/microsoft-365/cloud-storage/partner/wopi/rest
 * <p>关键头:
 * <ul>
 *   <li>X-WOPI-Override - 锁操作: GET_LOCK / REFRESH_LOCK / UNLOCK / GET_SHARE_URL / PUT_USER_INFO</li>
 *   <li>X-WOPI-Lock - 当前期望的 lock token (GET/REFRESH 时 = 旧 lock; UNLOCK 时 = lock 值)</li>
 *   <li>X-WOPI-MaxExpectedSize - 保存字节的最大期望大小(可拒绝超大文件)</li>
 * </ul>
 *
 * <p>我们的简化: 文件级 lock = "userId-version",用 file.version 字段做 WOPI 版本.
 */
@RestController
@RequestMapping("/wopi")
public class WopiController {

    private static final Logger log = LoggerFactory.getLogger(WopiController.class);

    @Autowired private OnlyOfficeProperties ooProps;
    @Autowired private MyDocService myDocService;
    @Autowired private MyDocFileRepository fileRepo;
    @Autowired private MyDocStorage storage;

    @Value("${server.servlet.context-path:}")
    private String ctxPath;

    /** 不存在 / 无权文件统一返 401(防枚举) */
    private MyDocFile requireOwnedFile(Long userId, Long fileId) {
        try {
            return myDocService.own(userId, fileId);
        } catch (Exception ex) {
            throw new ResponseStatus(HttpStatus.UNAUTHORIZED, "无权访问");
        }
    }

    // -------------------------------------------------------------------------
    //  GET /wopi/files/{id} -> 文件元信息 (CheckFileInfo)
    // -------------------------------------------------------------------------
    @GetMapping("/files/{id}")
    public ResponseEntity<Map<String, Object>> fileInfo(@PathVariable("id") Long id,
                                                       HttpServletRequest req) {
        Long userId = (Long) req.getAttribute(WopiAuthFilter.ATTR_USER_ID);
        if (ooProps.isEnabled() && !ooProps.isJwtEnabled() && userId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "no auth"));
        }
        if (ooProps.isJwtEnabled() && userId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "no auth"));
        }
        // 没启用 OO 时让前端用 v5 编辑器
        if (!ooProps.isEnabled() && userId == null) {
            // demo 模式: 不验证 userId, 任何 token 都允许但 userId = 1
            userId = 1L;
        }
        if (userId == null) userId = 1L;
        MyDocFile f = requireOwnedFile(userId, id);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("BaseFileName", f.getName() + "." + f.getExt());
        info.put("OwnerId", "u-" + f.getUserId());
        info.put("Size", f.getSizeBytes());
        info.put("UserId", "u-" + userId);
        info.put("Version", String.valueOf(f.getVersion()));
        info.put("UserFriendlyName", req.getAttribute(WopiAuthFilter.ATTR_USERNAME) != null
                ? req.getAttribute(WopiAuthFilter.ATTR_USERNAME) : "anon");
        info.put("UserCanWrite", true);
        info.put("SupportsUpdate", true);
        info.put("SupportsLocks", true);
        info.put("SupportsGetLock", true);
        info.put("ReadOnly", false);
        info.put("LastModifiedTime", f.getUpdateTime() != null
                ? f.getUpdateTime().toString() : Instant.now().toString());
        info.put("FileUrl", joinUrl(ooProps.getInternalUrl(), "wopi/files/" + id + "/contents"));
        log.info("WOPI fileInfo id={} size={} ver={} user={}", id, f.getSizeBytes(), f.getVersion(), userId);
        return ResponseEntity.ok(info);
    }

    // -------------------------------------------------------------------------
    //  GET /wopi/files/{id}/contents -> 字节流 (GetFile)
    // -------------------------------------------------------------------------
    @GetMapping("/files/{id}/contents")
    public ResponseEntity<byte[]> getContents(@PathVariable("id") Long id, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute(WopiAuthFilter.ATTR_USER_ID);
        if (ooProps.isJwtEnabled() && userId == null) return ResponseEntity.status(401).build();
        if (!ooProps.isEnabled() && userId == null) userId = 1L;
        if (userId == null) userId = 1L;
        MyDocFile f = requireOwnedFile(userId, id);
        byte[] bytes = storage.read(userId, f.getStorageKey());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mimeOf(f.getExt()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                .body(bytes);
    }

    // -------------------------------------------------------------------------
    //  POST /wopi/files/{id}/contents -> OnlyOffice 保存回调 (PutFile)
    //  Header: X-WOPI-Override = "" 或 "PUT"
    //  Header: X-WOPI-Lock (期望新 lock, 留空则用文件 version 字段)
    //  Body: 完整字节
    // -------------------------------------------------------------------------
    @PostMapping("/files/{id}/contents")
    public ResponseEntity<Map<String, Object>> putContents(@PathVariable("id") Long id,
                                                            @RequestHeader(value = "X-WOPI-Override", required = false) String override,
                                                            @RequestHeader(value = "X-WOPI-Lock", required = false) String expectedLock,
                                                            @RequestHeader(value = "X-WOPI-MaxExpectedSize", required = false) Long maxSize,
                                                            HttpServletRequest req,
                                                            @RequestBody byte[] body) {
        Long userId = (Long) req.getAttribute(WopiAuthFilter.ATTR_USER_ID);
        if (ooProps.isJwtEnabled() && userId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "no auth"));
        }
        if (!ooProps.isEnabled() && userId == null) userId = 1L;
        if (userId == null) userId = 1L;
        MyDocFile f = requireOwnedFile(userId, id);
        // 大小限制
        long limit = maxSize != null ? Math.max(maxSize, 5_242_880L) : 0L;
        if (limit > 0 && body.length > limit) {
            return ResponseEntity.status(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                    .body(Map.of("code", 413, "message", "文件超过容器限制 " + limit + " 字节"));
        }
        String currentLock = String.valueOf(f.getVersion());
        if (expectedLock != null && !expectedLock.isEmpty()
                && !expectedLock.equals(currentLock)
                && !"".equals(expectedLock)) {
            // 锁校验失败: 返 409 + LockMismatch(新的 lock 值,供客户端重试)
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header("X-WOPI-Lock", currentLock)
                    .body(Map.of("code", 409, "message", "lock mismatch"));
        }
        // 快照旧版本,写新版本
        try {
            byte[] old = storage.read(userId, f.getStorageKey());
            storage.snapshot(userId, f, old);
        } catch (Exception ex) {
            log.warn("snapshot before save failed: {}", ex.toString());
        }
        storage.write(userId, f.getStorageKey(), body);
        // 自增 version + 更新元数据
        f.setVersion(f.getVersion() + 1);
        f.setSizeBytes((long) body.length);
        f.setUpdateTime(java.time.LocalDateTime.now());
        fileRepo.save(f);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("LastModifiedTime", f.getUpdateTime().toString());
        resp.put("Version", String.valueOf(f.getVersion()));
        resp.put("Size", f.getSizeBytes());
        log.info("WOPI putContents id={} size={} newVer={}", id, body.length, f.getVersion());
        return ResponseEntity.ok(resp);
    }

    // -------------------------------------------------------------------------
    //  POST /wopi/files/{id} -> 锁操作 (Lock 操作一览)
    // -------------------------------------------------------------------------
    @PostMapping("/files/{id}")
    public ResponseEntity<?> lockOps(@PathVariable("id") Long id,
                                     @RequestHeader(value = "X-WOPI-Override", required = false) String override,
                                     @RequestHeader(value = "X-WOPI-Lock", required = false) String suppliedLock,
                                     HttpServletRequest req) {
        Long userId = (Long) req.getAttribute(WopiAuthFilter.ATTR_USER_ID);
        if (ooProps.isJwtEnabled() && userId == null) return ResponseEntity.status(401).build();
        if (!ooProps.isEnabled() && userId == null) userId = 1L;
        if (userId == null) userId = 1L;
        MyDocFile f = requireOwnedFile(userId, id);
        String currentLock = String.valueOf(f.getVersion());
        String op = override == null ? "" : override.toUpperCase();
        switch (op) {
            case "GET_LOCK":
                return ResponseEntity.ok().header("X-WOPI-Lock", currentLock).build();
            case "REFRESH_LOCK":
                if (suppliedLock != null && !suppliedLock.isEmpty() && !suppliedLock.equals(currentLock)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .header("X-WOPI-Lock", currentLock)
                            .build();
                }
                return ResponseEntity.ok().header("X-WOPI-Lock", currentLock).build();
            case "UNLOCK":
                if (suppliedLock != null && !suppliedLock.isEmpty() && !suppliedLock.equals(currentLock)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
                return ResponseEntity.ok().build();
            case "PUT_USER_INFO":
                // OO 偶尔会用,简单 log 即可
                log.info("WOPI PUT_USER_INFO id={} user={}", id, userId);
                return ResponseEntity.ok().build();
            default:
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                        .body(Map.of("code", 501, "message", "Unsupported override: " + override));
        }
    }

    private static String joinUrl(String base, String path) {
        if (base == null || base.isEmpty()) return path;
        if (path == null) return base;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return b + "/" + p;
    }

    private String mimeOf(String ext) {
        if (ext == null) return "application/octet-stream";
        return switch (ext.toLowerCase()) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }

    /** 简化版的 ResponseStatus 异常: 全局异常处理可能未接管,直接 throw runtime */
    private static class ResponseStatus extends RuntimeException {
        HttpStatus status; String msg;
        ResponseStatus(HttpStatus s, String m) { this.status = s; this.msg = m; }
    }
}
