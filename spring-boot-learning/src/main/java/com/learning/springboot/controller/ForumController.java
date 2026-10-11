package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.ForumVO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.ForumService;
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

import java.util.Set;

/**
 * 论坛专区 API — 用户之间的学习交流
 * <p>
 * 浏览/发帖/回帖: 全部登录用户;编辑/删除: 作者或管理员。
 * subjectId 约定: 不传=全部;0=综合讨论;其余=具体科目(语言)。
 */
@RestController
@RequestMapping("/api/forum")
public class ForumController {

    @Autowired private ForumService forumService;

    /** 帖子分页列表 */
    @GetMapping("/posts")
    public ResponseEntity<?> posts(@RequestParam(required = false) Long subjectId,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int size) {
        PageResult<ForumVO.PostItemVO> data = forumService.pagePosts(subjectId, keyword, page, size);
        return Result.ok(data);
    }

    /** 帖子详情(浏览数 +1) */
    @GetMapping("/posts/{id}")
    public ResponseEntity<?> post(@PathVariable Long id,
                                  @CurrentUser Long userId,
                                  @CurrentUser("roles") Set<String> roles) {
        ForumVO.PostDetailVO data = forumService.getPost(id, userId, isAdmin(roles));
        return Result.ok(data);
    }

    @PostMapping("/posts")
    @OperationLog(module = "论坛专区", action = "发布帖子")
    public ResponseEntity<?> createPost(@RequestBody ForumVO.PostUpsertRequest req,
                                        @CurrentUser Long userId,
                                        @CurrentUser("username") String username,
                                        @CurrentUser("roles") Set<String> roles) {
        return Result.ok("发布成功", forumService.createPost(req, userId, username, isAdmin(roles)));
    }

    @PutMapping("/posts/{id}")
    @OperationLog(module = "论坛专区", action = "编辑帖子")
    public ResponseEntity<?> updatePost(@PathVariable Long id,
                                        @RequestBody ForumVO.PostUpsertRequest req,
                                        @CurrentUser Long userId,
                                        @CurrentUser("roles") Set<String> roles) {
        return Result.ok("已更新", forumService.updatePost(id, req, userId, isAdmin(roles)));
    }

    @DeleteMapping("/posts/{id}")
    @OperationLog(module = "论坛专区", action = "删除帖子")
    public ResponseEntity<?> deletePost(@PathVariable Long id,
                                        @CurrentUser Long userId,
                                        @CurrentUser("roles") Set<String> roles) {
        forumService.deletePost(id, userId, isAdmin(roles));
        return Result.ok("帖子已删除");
    }

    /** 帖子回复分页 */
    @GetMapping("/posts/{id}/replies")
    public ResponseEntity<?> replies(@PathVariable Long id,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @CurrentUser Long userId,
                                     @CurrentUser("roles") Set<String> roles) {
        PageResult<ForumVO.ReplyVO> data = forumService.pageReplies(id, page, size, userId, isAdmin(roles));
        return Result.ok(data);
    }

    @PostMapping("/posts/{id}/replies")
    @OperationLog(module = "论坛专区", action = "回复帖子")
    public ResponseEntity<?> createReply(@PathVariable Long id,
                                         @RequestBody ForumVO.ReplyCreateRequest req,
                                         @CurrentUser Long userId,
                                         @CurrentUser("username") String username) {
        return Result.ok("回复成功", forumService.createReply(id, req, userId, username));
    }

    @DeleteMapping("/replies/{id}")
    @OperationLog(module = "论坛专区", action = "删除回复")
    public ResponseEntity<?> deleteReply(@PathVariable Long id,
                                         @CurrentUser Long userId,
                                         @CurrentUser("roles") Set<String> roles) {
        forumService.deleteReply(id, userId, isAdmin(roles));
        return Result.ok("回复已删除");
    }

    private boolean isAdmin(Set<String> roles) {
        return roles != null && roles.contains("ADMIN");
    }
}
