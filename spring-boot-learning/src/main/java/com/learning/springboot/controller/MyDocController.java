package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.MyDocDirectory;
import com.learning.springboot.entity.MyDocFile;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.MyDocService;
import com.learning.springboot.service.doc.MyDocType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 个人文档 API(个人中心)
 * <p>
 * 目录: 增 / 改名 / 删(非空拒绝) / 移动父目录
 * 文件: 新建空白 / 导入上传 / 改名 / 移动 / 复制 / 删除 / 导出下载
 * 编辑: 读编辑器模型 / 保存模型(Word 重建, Excel/PPT 原位回写) / 历史版本查看与恢复
 * <p>
 * 所有接口都按当前登录用户隔离数据(service 层强制带 userId)。
 */
@RestController
@RequestMapping("/api/my-docs")
public class MyDocController {

    private final MyDocService myDocService;

    public MyDocController(MyDocService myDocService) {
        this.myDocService = myDocService;
    }

    // ==================== 目录树 ====================

    @GetMapping("/tree")
    public ResponseEntity<?> tree(@CurrentUser Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("directories", myDocService.tree(userId));
        data.put("rootFiles", myDocService.rootFiles(userId));
        data.put("stats", myDocService.stats(userId));
        return Result.ok(data);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@CurrentUser Long userId) {
        return Result.ok(myDocService.stats(userId));
    }

    /** 最近访问过的文档(右侧内容区默认展示), limit 默认 8 */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(@CurrentUser Long userId,
                                    @RequestParam(value = "limit", defaultValue = "8") Integer limit) {
        return Result.ok(myDocService.recent(userId, limit == null ? 8 : limit));
    }

    @PostMapping("/directories")
    @OperationLog(module = "个人文档", action = "新建目录")
    public ResponseEntity<?> createDirectory(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        MyDocDirectory d = myDocService.createDirectory(userId, str(body.get("name")), toLong(body.get("parentId")));
        return Result.created("目录已创建", dirVO(d));
    }

    @PutMapping("/directories/{id}")
    @OperationLog(module = "个人文档", action = "重命名目录")
    public ResponseEntity<?> renameDirectory(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                            @CurrentUser Long userId) {
        MyDocDirectory d = myDocService.renameDirectory(userId, id, str(body.get("name")));
        return Result.ok("已重命名", dirVO(d));
    }

    @DeleteMapping("/directories/{id}")
    @OperationLog(module = "个人文档", action = "删除目录")
    public ResponseEntity<?> deleteDirectory(@PathVariable Long id, @CurrentUser Long userId) {
        myDocService.deleteDirectory(userId, id);
        return Result.ok("目录已删除", null);
    }

    @PutMapping("/directories/{id}/parent")
    @OperationLog(module = "个人文档", action = "移动目录")
    public ResponseEntity<?> moveDirectory(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                          @CurrentUser Long userId) {
        MyDocDirectory d = myDocService.moveDirectory(userId, id, toLong(body.get("parentId")));
        return Result.ok("已移动", dirVO(d));
    }

    // ==================== 文件 ====================

    @GetMapping("/files")
    public ResponseEntity<?> listFiles(@RequestParam(required = false) Long directoryId, @CurrentUser Long userId) {
        return Result.ok(myDocService.listFiles(userId, directoryId));
    }

    /** 新建空白文档: {name, docType: word|excel|ppt, directoryId} */
    @PostMapping("/files")
    @OperationLog(module = "个人文档", action = "新建文档")
    public ResponseEntity<?> createFile(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        MyDocFile f = myDocService.createFile(userId, str(body.get("name")), str(body.get("docType")),
                toLong(body.get("directoryId")));
        return Result.created("文档已创建", myDocService.listFiles(userId, f.getDirectoryId()).stream()
                .filter(x -> f.getId().equals(x.get("id"))).findFirst().orElse(null));
    }

    /** 导入 Word / Excel / PPT 文件(multipart: file + directoryId) */
    @PostMapping("/files/import")
    @OperationLog(module = "个人文档", action = "导入文档")
    public ResponseEntity<?> importFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam(required = false) Long directoryId,
                                        @CurrentUser Long userId) throws IOException {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择要导入的文件");
        MyDocFile f = myDocService.importFile(userId, file.getOriginalFilename(), file.getBytes(), directoryId);
        return Result.created("导入成功", myDocService.listFiles(userId, f.getDirectoryId()).stream()
                .filter(x -> f.getId().equals(x.get("id"))).findFirst().orElse(null));
    }

    @PutMapping("/files/{id}")
    @OperationLog(module = "个人文档", action = "重命名文档")
    public ResponseEntity<?> renameFile(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                        @CurrentUser Long userId) {
        MyDocFile f = myDocService.renameFile(userId, id, str(body.get("name")));
        return Result.ok("已重命名", f.getName() + "." + f.getExt());
    }

    @PutMapping("/files/{id}/move")
    @OperationLog(module = "个人文档", action = "移动文档")
    public ResponseEntity<?> moveFile(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                      @CurrentUser Long userId) {
        myDocService.moveFile(userId, id, toLong(body.get("directoryId")));
        return Result.ok("已移动", null);
    }

    @PostMapping("/files/{id}/copy")
    @OperationLog(module = "个人文档", action = "复制文档")
    public ResponseEntity<?> copyFile(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                      @CurrentUser Long userId) {
        Map<String, Object> b = body == null ? Map.of() : body;
        MyDocFile f = myDocService.copyFile(userId, id, toLong(b.get("directoryId")));
        return Result.ok("已创建副本: " + f.getName() + "." + f.getExt(), null);
    }

    @DeleteMapping("/files/{id}")
    @OperationLog(module = "个人文档", action = "删除文档")
    public ResponseEntity<?> deleteFile(@PathVariable Long id, @CurrentUser Long userId) {
        myDocService.deleteFile(userId, id);
        return Result.ok("文档已删除", null);
    }

    /** 导出下载(原格式) */
    @GetMapping("/files/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @CurrentUser Long userId) {
        MyDocFile f = myDocService.own(userId, id);
        byte[] bytes = myDocService.download(userId, id);
        return fileResponse(f.getName() + "." + f.getExt(), f.getDocType(), bytes, false);
    }

    /**
     * 在线预览(按 docType 走不同 MIME, 全部 inline)。
     * <ul>
     *   <li>image/* → image/jpeg/png/...</li>
     *   <li>pdf     → application/pdf</li>
     *   <li>md/txt  → text/plain; charset=utf-8</li>
     *   <li>html    → text/html; charset=utf-8</li>
     *   <li>其它 code → text/plain</li>
     * </ul>
     * Office 三件套走 OO iframe 预览(由前端 OnlyOfficeEditor 接管), 本端点不支持 office。
     */
    @GetMapping("/files/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id, @CurrentUser Long userId) {
        MyDocFile f = myDocService.own(userId, id);
        MyDocType type = MyDocType.ofCode(f.getDocType());
        if (type.mode() == MyDocType.Mode.OFFICE) {
            throw new BusinessException("Office 文档请使用 OnlyOffice 编辑器预览");
        }
        byte[] bytes = myDocService.download(userId, id);
        HttpHeaders headers = new HttpHeaders();
        // 修复: 图片的 MIME 按实际扩展名推断, 不再统一发 image/png(否则 jpg/gif/svg/webp/bmp 会被
        // 标错 MIME → 浏览器解码失败)
        headers.setContentType(previewMediaType(type, f.getExt()));
        headers.setContentLength(bytes.length);
        String fname = f.getName() + "." + f.getExt();
        String safe = fname.replaceAll("[\\r\\n\"]", "_");
        String ascii = safe.replaceAll("[^\\x20-\\x7e]", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        headers.setCacheControl("private, max-age=300");
        // 🔴 2026-10-25：HTML / 富文本预览加 CSP sandbox（纵深防御第二层）。
        // 即使写入侧的消毒被绕过，sandbox 也能阻止脚本执行 / 窃取同域 Cookie。
        // sandbox 不带 allow-scripts → 页面里的 <script> 不会执行；
        // 不带 allow-same-origin → 沙箱页面拿不到本域的 localStorage（JWT 就在那）。
        if (type == MyDocType.HTML || type == MyDocType.RICHTEXT) {
            headers.set("Content-Security-Policy", "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'");
            headers.set("X-Content-Type-Options", "nosniff");
        }
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    private MediaType previewMediaType(MyDocType type, String ext) {
        switch (type) {
            case IMAGE:
                // 按实际扩展名推断 MIME, 不再统一 image/png(jpg/gif/svg/webp/bmp 等会被错认)
                return imageMediaType(ext);
            case PDF:
                return MediaType.APPLICATION_PDF;
            case MARKDOWN:
            case TEXT:
            case CODE:
                return MediaType.parseMediaType("text/plain;charset=UTF-8");
            case HTML:
            case RICHTEXT:
                return MediaType.parseMediaType("text/html;charset=UTF-8");
            default:
                return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** 按图片扩展名推断精确 MIME, 未知扩展名兜底 octet-stream(浏览器按文件名查表渲染) */
    private MediaType imageMediaType(String ext) {
        if (ext == null) return MediaType.APPLICATION_OCTET_STREAM;
        switch (ext.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "png":  return MediaType.IMAGE_PNG;
            case "jpg":
            case "jpeg": return MediaType.IMAGE_JPEG;
            case "gif":  return MediaType.IMAGE_GIF;
            case "webp": return MediaType.parseMediaType("image/webp");
            case "bmp":  return MediaType.parseMediaType("image/bmp");
            case "svg":
            case "svg+xml": return MediaType.parseMediaType("image/svg+xml");
            default:     return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    // ==================== 在线编辑 ====================

    /** 读取编辑器模型 */
    @GetMapping("/files/{id}/model")
    public ResponseEntity<?> readModel(@PathVariable Long id, @CurrentUser Long userId) {
        return Result.ok(myDocService.readModel(userId, id));
    }

    /** 保存编辑器模型(Word 重建 / Excel、PPT 原位回写) */
    @PutMapping("/files/{id}/model")
    @OperationLog(module = "个人文档", action = "在线保存文档")
    public ResponseEntity<?> saveModel(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                       @CurrentUser Long userId) {
        Map<String, String> res = myDocService.saveModel(userId, id, body);
        return Result.ok("已保存", res);
    }

    // ==================== 历史版本 ====================

    @GetMapping("/files/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable Long id, @CurrentUser Long userId) {
        return Result.ok(myDocService.versions(userId, id));
    }

    @GetMapping("/files/{id}/versions/{name}/download")
    public ResponseEntity<byte[]> downloadVersion(@PathVariable Long id, @PathVariable String name,
                                                  @CurrentUser Long userId) {
        MyDocFile f = myDocService.own(userId, id);
        byte[] bytes = myDocService.downloadVersion(userId, id, name);
        return fileResponse(f.getName() + "-" + name, f.getDocType(), bytes, false);
    }

    @PostMapping("/files/{id}/versions/{name}/restore")
    @OperationLog(module = "个人文档", action = "恢复文档版本")
    public ResponseEntity<?> restoreVersion(@PathVariable Long id, @PathVariable String name,
                                            @CurrentUser Long userId) {
        myDocService.restoreVersion(userId, id, name);
        return Result.ok("已恢复到该版本", null);
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> dirVO(MyDocDirectory d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("parentId", d.getParentId());
        m.put("sortNo", d.getSortNo());
        m.put("createTime", d.getCreateTime());
        return m;
    }

    /** 文件下载响应: 中文名按 RFC 5987 编码, 同时给 ASCII 兜底名 */
    private ResponseEntity<byte[]> fileResponse(String filename, String docType, byte[] bytes, boolean inline) {
        String safe = filename.replaceAll("[\\r\\n\"]", "_");
        String ascii = safe.replaceAll("[^\\x20-\\x7e]", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaTypeOf(docType));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    private MediaType mediaTypeOf(String docType) {
        String t = docType == null ? "" : docType.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "word" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "excel" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new BusinessException("参数格式错误: 期望数字, 收到 " + s);
        }
    }
}
