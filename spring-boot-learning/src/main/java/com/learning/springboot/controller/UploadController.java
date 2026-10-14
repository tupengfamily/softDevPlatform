package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通用图片上传接口(供富文本/Markdown 编辑器、Wiki 业务共用):
 * 把图片存到 data/uploads/wiki/{yyyy-MM}/{uuid}.{ext}, 返回可直接在 Markdown/HTML 中引用的相对 URL。
 * <p>
 * 安全约束:
 * - 必须登录(@CurrentUser 注入, 由 JwtAuthFilter 兜底 401)
 * - 5MB 单图上限
 * - 扩展名 + content-type 双校验(常见白名单)
 * - 文件落地用 UUID 重命名(避免路径注入 / 覆盖 + 文件名中文/特殊字符乱码)
 * <p>
 * 静态映射复用 WebConfig 的 /uploads/** → data/uploads/, 所以返回的 URL 可被前端直接访问。
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    /** 5MB 单图上限(图片单张基本够用; Word 导入大图走单独目录) */
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final Set<String> ALLOWED_CT = Set.of(
        "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp"
    );

    /**
     * @param file 图片二进制
     * @param scope 业务域(wiki=Wiki 文章; 其他预留)。用于隔离目录
     */
    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "scope", defaultValue = "wiki") String scope,
                                         @CurrentUser Long userId) throws IOException {
        if (userId == null) throw new BusinessException(401, "未登录");
        if (file == null || file.isEmpty()) throw new BusinessException("文件为空");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new BusinessException("图片过大, 单张最大 5MB");

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String ext = extension(original);
        // 兜底: 扩展名空时按 content-type 推断
        if (ext.isEmpty()) ext = extFromContentType(file.getContentType());
        if (!ALLOWED_EXT.contains(ext)) throw new BusinessException("不支持的图片格式, 仅支持 png/jpg/jpeg/gif/webp/bmp");
        // 双保险: 已知危险 content-type 直接拒
        String ct = file.getContentType();
        if (ct != null && !ct.isEmpty() && !ALLOWED_CT.contains(ct.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("不支持的图片格式");
        }

        // 目录: data/uploads/wiki/{yyyy-MM}/, 业务域扩展放 scope 段
        String safeScope = scope.replaceAll("[^a-zA-Z0-9_-]", "");
        if (safeScope.isEmpty()) safeScope = "wiki";
        String month = LocalDate.now().toString().substring(0, 7);
        Path dir = Paths.get("data", "uploads", safeScope, month);
        Files.createDirectories(dir);

        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        }
        String url = "/uploads/" + safeScope + "/" + month + "/" + name;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("url", url);
        res.put("size", file.getSize());
        res.put("originalName", original);
        return Result.ok("上传成功", res);
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String extFromContentType(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "";
        };
    }
}
