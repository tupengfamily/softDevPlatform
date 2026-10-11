package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.SupplementVO;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.SupplementService;
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

import java.util.List;

/**
 * 用户补充节点 API — 章节下用户自建的"补充章节/子节点"
 * <p>
 * 所有登录用户可查看与新增(支持富文本/Markdown);
 * 编辑与删除限作者或管理员。
 */
@RestController
@RequestMapping("/api/learning/supplements")
public class SupplementController {

    @Autowired private SupplementService supplementService;

    /**
     * 补充节点树。
     * chapterId 优先(某官方章节下的补充子树),否则按 subjectId 返回科目下全部节点。
     */
    @GetMapping
    public ResponseEntity<?> tree(@RequestParam(required = false) Long subjectId,
                                  @RequestParam(required = false) Long chapterId,
                                  @CurrentUser Long userId,
                                  @CurrentUser("roles") java.util.Set<String> roles) {
        boolean admin = roles != null && roles.contains("ADMIN");
        List<SupplementVO.NodeVO> data = supplementService.getTree(subjectId, chapterId, userId, admin);
        return Result.ok(data);
    }

    @PostMapping
    @OperationLog(module = "学习补充", action = "新增补充节点")
    public ResponseEntity<?> create(@RequestBody SupplementVO.NodeUpsertRequest req,
                                    @CurrentUser Long userId,
                                    @CurrentUser("username") String username) {
        return Result.ok("已添加", supplementService.create(req, userId, username));
    }

    @PutMapping("/{id}")
    @OperationLog(module = "学习补充", action = "编辑补充节点")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody SupplementVO.NodeUpsertRequest req,
                                    @CurrentUser Long userId,
                                    @CurrentUser("roles") java.util.Set<String> roles) {
        boolean admin = roles != null && roles.contains("ADMIN");
        return Result.ok("已更新", supplementService.update(id, req, userId, admin));
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "学习补充", action = "删除补充节点")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                    @CurrentUser Long userId,
                                    @CurrentUser("roles") java.util.Set<String> roles) {
        boolean admin = roles != null && roles.contains("ADMIN");
        supplementService.delete(id, userId, admin);
        return Result.ok("已删除");
    }
}
