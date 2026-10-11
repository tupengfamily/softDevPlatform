package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.FeedbackDTO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.Feedback;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.FeedbackService;
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
import java.util.Map;

/**
 * 用户反馈 API
 * <p>
 * 用户侧: 提交反馈(评分/bug)、查看自己的反馈、评分统计。
 * 管理侧: 分页查看全部反馈、处理(改状态+备注)、删除。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired private FeedbackService feedbackService;

    /** 提交反馈(评分或 bug) */
    @PostMapping
    @OperationLog(module = "意见反馈", action = "提交反馈")
    public ResponseEntity<?> submit(@RequestBody FeedbackDTO dto, @CurrentUser Long userId, @CurrentUser("username") String username) {
        Feedback data = feedbackService.submit(userId, username, dto);
        return Result.created("感谢你的反馈!", data);
    }

    /** 我的反馈 */
    @GetMapping("/mine")
    public ResponseEntity<?> mine(@CurrentUser Long userId) {
        List<Feedback> data = feedbackService.myFeedback(userId);
        return Result.ok(data);
    }

    /** 评分统计(平均分 + 评分数,展示在反馈页) */
    @GetMapping("/rating-summary")
    public ResponseEntity<?> ratingSummary() {
        Map<String, Object> data = feedbackService.ratingSummary();
        return Result.ok(data);
    }

    /** 管理端: 分页查询全部反馈 */
    @GetMapping("/page")
    @RequireAdmin
    public ResponseEntity<?> page(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int size) {
        PageResult<Feedback> data = feedbackService.pageQuery(type, status, page, size);
        return Result.ok(data);
    }

    /** 管理端: 处理反馈(改状态 + 处理备注) */
    @PutMapping("/{id}")
    @RequireAdmin
    @OperationLog(module = "意见反馈", action = "处理反馈")
    public ResponseEntity<?> handle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Feedback data = feedbackService.handle(id, body.get("status"), body.get("adminReply"));
        return Result.ok("反馈已处理", data);
    }

    /** 管理端: 删除反馈 */
    @DeleteMapping("/{id}")
    @RequireAdmin
    @OperationLog(module = "意见反馈", action = "删除反馈")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        feedbackService.delete(id);
        return Result.ok("反馈已删除", null);
    }
}
