package com.learning.springboot.service.impl;

import com.learning.springboot.dto.ForumVO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.ForumPost;
import com.learning.springboot.entity.ForumReply;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.repository.ForumPostRepository;
import com.learning.springboot.repository.ForumReplyRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
import com.learning.springboot.service.ForumService;
import com.learning.springboot.service.NotificationService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 论坛 Service 实现
 * <p>
 * subjectId 约定: null=不过滤(全部);0=综合讨论(无科目);其余=具体科目。
 */
@Service
public class ForumServiceImpl implements ForumService {

    private static final Logger log = LoggerFactory.getLogger(ForumServiceImpl.class);

    @Autowired private ForumPostRepository postRepository;
    @Autowired private ForumReplyRepository replyRepository;
    @Autowired private LearningSubjectRepository subjectRepository;
    @Autowired private NotificationService notificationService;

    // ==================== 帖子 ====================

    @Override
    public PageResult<ForumVO.PostItemVO> pagePosts(Long subjectId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1),
            Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("updatedAt")));
        Page<ForumPost> posts = postRepository.findAll(buildSpec(subjectId, keyword), pageable);
        Map<Long, String> subjectNames = subjectNameMap();
        return PageResult.of(posts.map(p -> toItemVO(p, subjectNames)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.PostDetailVO getPost(Long id, Long currentUserId, boolean admin) {
        ForumPost post = postRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("帖子不存在: " + id));
        postRepository.incrementViewCount(id);
        post.setViewCount(post.getViewCount() + 1);
        return toDetailVO(post, subjectNameMap(), currentUserId, admin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.PostDetailVO createPost(ForumVO.PostUpsertRequest req, Long userId, String username, boolean admin) {
        validatePost(req);
        ForumPost post = new ForumPost();
        post.setSubjectId(req.subjectId == null || req.subjectId <= 0 ? null : req.subjectId);
        post.setTitle(req.title.trim());
        post.setContent(req.content);
        post.setContentType(normalizeType(req.contentType));
        post.setUserId(userId);
        post.setUsername(username);
        post.setPinned(admin && Boolean.TRUE.equals(req.pinned));
        post.setCreatedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        postRepository.save(post);
        log.info("用户 {} 发布帖子: {}", username, post);
        return toDetailVO(post, subjectNameMap(), userId, admin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.PostDetailVO updatePost(Long id, ForumVO.PostUpsertRequest req, Long userId, boolean admin) {
        validatePost(req);
        ForumPost post = requireEditable(id, userId, admin);
        post.setSubjectId(req.subjectId == null || req.subjectId <= 0 ? null : req.subjectId);
        post.setTitle(req.title.trim());
        post.setContent(req.content);
        post.setContentType(normalizeType(req.contentType));
        if (admin && req.pinned != null) post.setPinned(req.pinned);
        post.setUpdatedAt(LocalDateTime.now());
        postRepository.save(post);
        return toDetailVO(post, subjectNameMap(), userId, admin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long id, Long userId, boolean admin) {
        ForumPost post = requireEditable(id, userId, admin);
        replyRepository.deleteByPostId(id);
        postRepository.delete(post);
        log.info("用户 {} 删除帖子 {} 及其回复", userId, id);
    }

    // ==================== 回复 ====================

    @Override
    public PageResult<ForumVO.ReplyVO> pageReplies(Long postId, int page, int size, Long currentUserId, boolean admin) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1));
        Page<ForumReply> replies = replyRepository.findByPostIdOrderByCreatedAtAsc(postId, pageable);
        return PageResult.of(replies.map(r -> toReplyVO(r, currentUserId, admin)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.ReplyVO createReply(Long postId, ForumVO.ReplyCreateRequest req, Long userId, String username) {
        if (req == null || req.content == null || req.content.trim().isEmpty()) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        ForumPost post = postRepository.findById(postId)
            .orElseThrow(() -> new NoSuchElementException("帖子不存在: " + postId));
        ForumReply reply = new ForumReply();
        reply.setPostId(postId);
        reply.setUserId(userId);
        reply.setUsername(username);
        reply.setContent(req.content.trim());
        reply.setContentType(normalizeType(req.contentType));
        reply.setCreatedAt(LocalDateTime.now());
        replyRepository.save(reply);
        postRepository.recalcReplyCount(postId);
        // 通知楼主(自己回自己不发)
        notificationService.notifyReply(post.getUserId(), userId, username, post.getTitle(), postId);
        return toReplyVO(reply, userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteReply(Long id, Long userId, boolean admin) {
        ForumReply reply = replyRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("回复不存在: " + id));
        if (!admin && !reply.getUserId().equals(userId)) {
            throw new IllegalArgumentException("只能删除自己的回复");
        }
        replyRepository.delete(reply);
        postRepository.recalcReplyCount(reply.getPostId());
        log.info("用户 {} 删除回复 {}", userId, id);
    }

    // ==================== 私有工具 ====================

    private Specification<ForumPost> buildSpec(Long subjectId, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (subjectId != null) {
                if (subjectId == 0) {
                    preds.add(cb.isNull(root.get("subjectId")));
                } else {
                    preds.add(cb.equal(root.get("subjectId"), subjectId));
                }
            }
            if (keyword != null && !keyword.trim().isEmpty()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(root.get("content")), like)));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    private ForumPost requireEditable(Long id, Long userId, boolean admin) {
        ForumPost post = postRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("帖子不存在: " + id));
        if (!admin && !post.getUserId().equals(userId)) {
            throw new IllegalArgumentException("只能编辑/删除自己的帖子");
        }
        return post;
    }

    private void validatePost(ForumVO.PostUpsertRequest req) {
        if (req == null || req.title == null || req.title.trim().isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (req.content == null || req.content.trim().isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
    }

    private String normalizeType(String type) {
        return ForumPost.TYPE_RICHTEXT.equals(type)
            ? ForumPost.TYPE_RICHTEXT
            : ForumPost.TYPE_MARKDOWN;
    }

    private Map<Long, String> subjectNameMap() {
        return subjectRepository.findAll().stream()
            .collect(Collectors.toMap(LearningSubject::getId, LearningSubject::getName));
    }

    private ForumVO.PostItemVO toItemVO(ForumPost p, Map<Long, String> subjectNames) {
        ForumVO.PostItemVO vo = new ForumVO.PostItemVO();
        vo.id = p.getId();
        vo.subjectId = p.getSubjectId();
        vo.subjectName = p.getSubjectId() == null ? "综合讨论"
            : subjectNames.getOrDefault(p.getSubjectId(), "");
        vo.title = p.getTitle();
        vo.contentType = p.getContentType();
        vo.userId = p.getUserId();
        vo.username = p.getUsername();
        vo.viewCount = p.getViewCount() == null ? 0 : p.getViewCount();
        vo.replyCount = p.getReplyCount() == null ? 0 : p.getReplyCount();
        vo.pinned = Boolean.TRUE.equals(p.getPinned());
        vo.createdAt = p.getCreatedAt();
        vo.updatedAt = p.getUpdatedAt();
        return vo;
    }

    private ForumVO.PostDetailVO toDetailVO(ForumPost p, Map<Long, String> subjectNames,
                                            Long currentUserId, boolean admin) {
        ForumVO.PostDetailVO vo = new ForumVO.PostDetailVO();
        vo.id = p.getId();
        vo.subjectId = p.getSubjectId();
        vo.subjectName = p.getSubjectId() == null ? "综合讨论"
            : subjectNames.getOrDefault(p.getSubjectId(), "");
        vo.title = p.getTitle();
        vo.content = p.getContent();
        vo.contentType = p.getContentType();
        vo.userId = p.getUserId();
        vo.username = p.getUsername();
        vo.viewCount = p.getViewCount() == null ? 0 : p.getViewCount();
        vo.replyCount = p.getReplyCount() == null ? 0 : p.getReplyCount();
        vo.pinned = Boolean.TRUE.equals(p.getPinned());
        vo.createdAt = p.getCreatedAt();
        vo.updatedAt = p.getUpdatedAt();
        vo.editable = admin || (currentUserId != null && currentUserId.equals(p.getUserId()));
        return vo;
    }

    private ForumVO.ReplyVO toReplyVO(ForumReply r, Long currentUserId, boolean admin) {
        ForumVO.ReplyVO vo = new ForumVO.ReplyVO();
        vo.id = r.getId();
        vo.postId = r.getPostId();
        vo.userId = r.getUserId();
        vo.username = r.getUsername();
        vo.content = r.getContent();
        vo.contentType = r.getContentType();
        vo.createdAt = r.getCreatedAt();
        vo.editable = admin || (currentUserId != null && currentUserId.equals(r.getUserId()));
        return vo;
    }
}
