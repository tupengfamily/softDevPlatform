package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.WikiCollaborator;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.CollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 协作申请 + 审批 API
 * <p>
 * - /api/wiki/collab/apply         申请人发起
 * - /api/wiki/collab/{id}/cancel   申请人撤销
 * - /api/wiki/collab/{id}/approve  所有者通过
 * - /api/wiki/collab/{id}/reject   所有者拒绝
 * - /api/wiki/collab/inbox         资源所有者的审批中心(待办 + 历史)
 * - /api/wiki/collab/mine          申请人视角: 我提交的申请
 * - /api/wiki/collab/shared-to-me  协作者视角: 共享给我的资源
 * - /api/wiki/collab/resource      资源上的协作者列表
 */
@RestController
@RequestMapping("/api/wiki/collab")
public class WikiCollaboratorController {

    @Autowired private CollaborationService collaborationService;

    /** 发起申请 */
    @PostMapping("/apply")
    @OperationLog(module = "个人Wiki", action = "提交协作申请")
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        String resourceType = str(body.get("resourceType"));
        Long resourceId = body.get("resourceId") instanceof Number n ? n.longValue() : null;
        String permission = str(body.get("permission"));
        String reason = str(body.get("reason"));
        WikiCollaborator c = collaborationService.submitApplication(userId, resourceType, resourceId, permission, reason);
        return Result.created("申请已提交, 等待资源所有者审批", collaborationService.listMineForApplicant(userId).stream()
            .filter(m -> m.get("id").equals(c.getId())).findFirst().orElse(null));
    }

    /** 撤销申请 */
    @PostMapping("/{id}/cancel")
    @OperationLog(module = "个人Wiki", action = "撤销协作申请")
    public ResponseEntity<?> cancel(@PathVariable Long id, @CurrentUser Long userId) {
        collaborationService.cancelApplication(userId, id);
        return Result.ok("已撤销", null);
    }

    /** 通过 */
    @PostMapping("/{id}/approve")
    @OperationLog(module = "个人Wiki", action = "批准协作申请")
    public ResponseEntity<?> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                     @CurrentUser Long userId) {
        String comment = body == null ? null : str(body.get("comment"));
        collaborationService.approveApplication(userId, id, comment);
        return Result.ok("已批准, 已通知申请人", null);
    }

    /** 拒绝 */
    @PostMapping("/{id}/reject")
    @OperationLog(module = "个人Wiki", action = "拒绝协作申请")
    public ResponseEntity<?> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                    @CurrentUser Long userId) {
        String comment = body == null ? null : str(body.get("comment"));
        collaborationService.rejectApplication(userId, id, comment);
        return Result.ok("已拒绝, 已通知申请人", null);
    }

    /** 资源所有者的审批中心(PENDING 置顶 + 已处理历史) */
    @GetMapping("/inbox")
    public ResponseEntity<?> inbox(@CurrentUser Long userId) {
        List<Map<String, Object>> list = collaborationService.listOwnerInbox(userId);
        return Result.ok(list);
    }

    /** 我提交的申请 */
    @GetMapping("/mine")
    public ResponseEntity<?> mine(@CurrentUser Long userId) {
        return Result.ok(collaborationService.listMineForApplicant(userId));
    }

    /** 共享给我的资源(树中显示) */
    @GetMapping("/shared-to-me")
    public ResponseEntity<?> sharedToMe(@CurrentUser Long userId) {
        return Result.ok(collaborationService.listSharedToMe(userId));
    }

    /** 资源上的协作者列表(所有者管理) */
    @GetMapping("/resource")
    public ResponseEntity<?> ofResource(@RequestParam String resourceType, @RequestParam Long resourceId) {
        return Result.ok(collaborationService.listCollaboratorsOfResource(resourceType, resourceId));
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
