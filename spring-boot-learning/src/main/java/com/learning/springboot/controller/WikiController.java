package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.WikiArticle;
import com.learning.springboot.entity.WikiDirectory;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.DocumentImportService;
import com.learning.springboot.service.PersonalCenterService;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人 Wiki API: 目录/文章增删改 + 排序 + 文章分享 + 文档导入
 */
@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    @Autowired private PersonalCenterService personalCenterService;
    @Autowired private DocumentImportService documentImportService;

    /** 目录树(目录 + 目录内文章,均按 sortNo 排序) */
    @GetMapping("/tree")
    public ResponseEntity<?> tree(@CurrentUser Long userId) {
        List<Map<String, Object>> data = personalCenterService.wikiTree(userId);
        return Result.ok(data);
    }

    // ==================== 目录 ====================

    @PostMapping("/directories")
    @OperationLog(module = "个人Wiki", action = "新增目录")
    public ResponseEntity<?> createDir(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        Long parentId = body.get("parentId") instanceof Number n ? n.longValue() : null;
        WikiDirectory data = personalCenterService.createDirectory(userId, str(body.get("name")), parentId);
        return Result.created("目录已创建", data);
    }

    @PutMapping("/directories/{id}")
    @OperationLog(module = "个人Wiki", action = "编辑目录")
    public ResponseEntity<?> updateDir(@PathVariable Long id, @RequestBody Map<String, String> body, @CurrentUser Long userId) {
        WikiDirectory data = personalCenterService.updateDirectory(userId, id, body.get("name"));
        return Result.ok("已更新", data);
    }

    @DeleteMapping("/directories/{id}")
    @OperationLog(module = "个人Wiki", action = "删除目录")
    public ResponseEntity<?> deleteDir(@PathVariable Long id, @CurrentUser Long userId) {
        personalCenterService.deleteDirectory(userId, id);
        return Result.ok("目录及其子目录/文章已删除", null);
    }

    /** 目录排序(拖动): {position: 目标下标}(同级内) */
    @PutMapping("/directories/{id}/move")
    public ResponseEntity<?> moveDir(@PathVariable Long id, @RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        int position = body.get("position") instanceof Number n ? n.intValue() : 0;
        personalCenterService.moveDirectory(userId, id, position);
        return Result.ok("已排序", null);
    }

    /** 目录移动到新的父目录(拖动跨层级): {parentId: 目标父目录, null/不传=移到顶层} */
    @PutMapping("/directories/{id}/parent")
    @OperationLog(module = "个人Wiki", action = "移动目录")
    public ResponseEntity<?> moveDirParent(@PathVariable Long id, @RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        Long parentId = body.get("parentId") instanceof Number n ? n.longValue() : null;
        WikiDirectory data = personalCenterService.moveDirectoryToParent(userId, id, parentId);
        return Result.ok("目录已移动", data);
    }

    // ==================== 文章 ====================

    @PostMapping("/articles")
    @OperationLog(module = "个人Wiki", action = "新增文章")
    public ResponseEntity<?> createArticle(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        Long directoryId = body.get("directoryId") instanceof Number n ? n.longValue() : null;
        WikiArticle data = personalCenterService.createArticle(userId, directoryId,
            str(body.get("title")), str(body.get("content")), str(body.get("contentType")));
        return Result.created("文章已创建", data);
    }

    /**
     * 导入文档为文章: 支持 Markdown(.md) / Word(.docx) / Excel(.xlsx)。
     * 解析转换由 {@link DocumentImportService} 完成, 标题取文件名;
     * 转换出的富文本在入库时经 HtmlSanitizer 消毒。
     * (不加 @OperationLog: 避免 MultipartFile 参数序列化进日志)
     */
    @PostMapping("/articles/import")
    public ResponseEntity<?> importArticle(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "directoryId", required = false) Long directoryId,
                                           @CurrentUser Long userId) throws IOException {
        DocumentImportService.ImportedDoc doc =
            documentImportService.parse(file.getOriginalFilename(), file.getBytes());
        WikiArticle data = personalCenterService.createArticle(userId, directoryId,
            doc.title(), doc.content(), doc.contentType());
        return Result.created("文档已导入", data);
    }

    @PutMapping("/articles/{id}")
    @OperationLog(module = "个人Wiki", action = "编辑文章")
    public ResponseEntity<?> updateArticle(@PathVariable Long id, @RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        Long directoryId = body.get("directoryId") instanceof Number n ? n.longValue() : null;
        WikiArticle data = personalCenterService.updateArticle(userId, id, directoryId,
            str(body.get("title")), str(body.get("content")), str(body.get("contentType")));
        return Result.ok("已保存", data);
    }

    @DeleteMapping("/articles/{id}")
    @OperationLog(module = "个人Wiki", action = "删除文章")
    public ResponseEntity<?> deleteArticle(@PathVariable Long id, @CurrentUser Long userId) {
        personalCenterService.deleteArticle(userId, id);
        return Result.ok("文章已删除", null);
    }

    /** 文章排序(拖动,目录内): {position: 目标下标} */
    @PutMapping("/articles/{id}/move")
    public ResponseEntity<?> moveArticle(@PathVariable Long id, @RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        int position = body.get("position") instanceof Number n ? n.intValue() : 0;
        personalCenterService.moveArticle(userId, id, position);
        return Result.ok("已排序", null);
    }

    /**
     * 开启/更新分享: 返回分享令牌。
     * body 可选 {requireLogin: true|false} —— 是否要求登录后才能查看(默认 false=任何人凭链接可看)
     */
    @PostMapping("/articles/{id}/share")
    @OperationLog(module = "个人Wiki", action = "分享文章")
    public ResponseEntity<?> share(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   @CurrentUser Long userId) {
        Boolean requireLogin = body != null && Boolean.TRUE.equals(body.get("requireLogin"));
        WikiArticle data = personalCenterService.shareArticle(userId, id, requireLogin);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("shareToken", data.getShareToken());
        res.put("sharePath", "/wiki/share/" + data.getShareToken());
        res.put("requireLogin", data.getShareRequireLogin());
        return Result.ok("已开启分享", res);
    }

    /** 取消分享 */
    @DeleteMapping("/articles/{id}/share")
    @OperationLog(module = "个人Wiki", action = "取消分享")
    public ResponseEntity<?> unshare(@PathVariable Long id, @CurrentUser Long userId) {
        personalCenterService.unshareArticle(userId, id);
        return Result.ok("已取消分享", null);
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
