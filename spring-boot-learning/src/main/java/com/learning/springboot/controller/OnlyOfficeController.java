package com.learning.springboot.controller;

import com.learning.springboot.config.OnlyOfficeProperties;
import com.learning.springboot.entity.MyDocFile;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.security.OnlyOfficeJwtUtil;
import com.learning.springboot.service.MyDocService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OnlyOffice Editor 前端接入端点.
 * <p>
 * 前端点 "打开" → GET /api/my-docs/oo/{id}/info → 拿到 { editorUrl, accessToken, ... }
 * 然后用 iframe src = editorUrl 嵌入 OnlyOffice.
 */
@RestController
@RequestMapping("/api/my-docs/oo")
public class OnlyOfficeController {

    @Autowired private OnlyOfficeProperties ooProps;
    @Autowired private OnlyOfficeJwtUtil jwt;
    @Autowired private MyDocService myDocService;
    @Autowired private UserRepository userRepo;
    @Autowired private ObjectMapper objectMapper;

    @Value("${server.servlet.context-path:}")
    private String ctxPath;

    /** 给前端组装 OnlyOffice iframe src + access token */
    @GetMapping("/{id}/info")
    public ResponseEntity<?> editorInfo(@PathVariable Long id, @CurrentUser Long userId) throws Exception {
        MyDocFile f = myDocService.own(userId, id);
        String username = userRepo.findById(userId).map(u -> u.getUsername()).orElse("anon");

        // 给前端 iframe src 用的 access_token
        Map<String, Object> tokPayload = new LinkedHashMap<>();
        tokPayload.put("userId", userId);
        tokPayload.put("username", username);
        String accessToken = jwt.generateForClient(tokPayload);

        // 给 Document Server 用的 editorUrl 模板
        Map<String, Object> editorPayload = new LinkedHashMap<>();
        editorPayload.put("userId", userId);
        editorPayload.put("username", username);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", switch (f.getDocType()) {
            case "word" -> "docx";
            case "excel" -> "xlsx";
            case "ppt" -> "pptx";
            default -> "docx";
        });
        document.put("key", "u" + userId + "-f" + f.getId() + "-v" + f.getVersion() + "-" + Instant.now().getEpochSecond());
        document.put("title", f.getName() + "." + f.getExt());
        document.put("url", joinUrl(ooProps.getInternalUrl(), "wopi/files/" + id + "/contents?access_token=" + accessToken));
        editorPayload.put("document", document);

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", "u-" + userId);
        userInfo.put("name", username);
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("edit", true);
        permissions.put("download", true);
        permissions.put("print", true);
        userInfo.put("permissions", permissions);
        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", "edit");
        editorConfig.put("lang", "zh-CN");
        editorConfig.put("user", userInfo);
        editorConfig.put("customization", customization());
        editorPayload.put("editorConfig", editorConfig);

        // OO 9.x 的官方集成方式: 把整个 config 用 base64 编码后作为 ?config= 参数
        String editorJwt = jwt.generateForClient(editorPayload);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("document", document);
        config.put("editorConfig", editorConfig);
        if (!editorJwt.isEmpty()) {
            // 把 config 再嵌一层 token 一起签, payload = { token: <jwt>, payload: { document, editorConfig } }
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("token", editorJwt);
            wrapped.put("payload", config);
            config = wrapped;
        }
        String configB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsString(config).getBytes(StandardCharsets.UTF_8));
        String editorBase = ooProps.getPublicUrl()
                + "/web-apps/apps/" + switch (f.getDocType()) {
                    case "word" -> "documenteditor/main/index.html";
                    case "excel" -> "spreadsheeteditor/main/index.html";
                    case "ppt" -> "presentationeditor/main/index.html";
                    default -> "documenteditor/main/index.html";
                };
        String editorUrl = editorBase + "?config=" + configB64;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", ooProps.isEnabled());
        resp.put("editorUrl", editorUrl);
        resp.put("accessToken", accessToken);
        resp.put("fileId", id);
        resp.put("fileName", f.getName() + "." + f.getExt());
        resp.put("docType", f.getDocType());
        resp.put("docServerUrl", ooProps.getPublicUrl());
        // JS API 集成: 把 document + editorConfig 一起返 (前端用 DocsAPI.DocEditor 时直接传 config)
        Map<String, Object> jsConfig = new LinkedHashMap<>();
        jsConfig.put("document", document);
        jsConfig.put("editorConfig", editorConfig);
        if (!editorJwt.isEmpty()) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("token", editorJwt);
            wrapped.put("payload", jsConfig);
            jsConfig = wrapped;
        }
        resp.put("config", jsConfig);
        return ResponseEntity.ok(resp);
    }

    /** 紧凑工具栏 + 关闭协作评论 UI */
    private Map<String, Object> customization() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("compact", true);
        u.put("toolbar", true);
        // 关闭 OO 默认评论面板(避免与我们的批注冲突,可后续单独接)
        Map<String, Object> comments = new LinkedHashMap<>();
        comments.put("visible", false);
        u.put("comments", comments);
        // 不显示左侧菜单栏的"插件"按钮
        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("visible", false);
        u.put("plugins", plugins);
        return u;
    }

    private static String joinUrl(String base, String path) {
        if (base == null || base.isEmpty()) return path;
        if (path == null) return base;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return b + "/" + p;
    }
}
