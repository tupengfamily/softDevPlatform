package com.learning.springboot.service.impl;

import com.learning.springboot.dto.ForumVO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.ForumPost;
import com.learning.springboot.entity.ForumPostFavorite;
import com.learning.springboot.entity.ForumPostLike;
import com.learning.springboot.entity.ForumReply;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.security.HtmlSanitizer;
import com.learning.springboot.repository.ForumPostFavoriteRepository;
import com.learning.springboot.repository.ForumPostLikeRepository;
import com.learning.springboot.repository.ForumPostRepository;
import com.learning.springboot.repository.ForumReplyRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
import com.learning.springboot.service.ForumService;
import com.learning.springboot.service.NotificationService;
import jakarta.persistence.criteria.Expression;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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
    @Autowired private ForumPostLikeRepository likeRepository;
    @Autowired private ForumPostFavoriteRepository favoriteRepository;
    @Autowired private LearningSubjectRepository subjectRepository;
    @Autowired private NotificationService notificationService;

    // ==================== 帖子 ====================

    @Override
    public PageResult<ForumVO.PostItemVO> pagePosts(Long subjectId, String keyword, String sort,
                                                   int page, int size, Long currentUserId) {
        String sortKey = normalizeSort(sort);
        Pageable pageable = "hot".equals(sortKey)
            // 热度为复合表达式,排序在 Specification 内完成,Pageable 不带 Sort
            ? PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1))
            : PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1), Sort.by(sortOrders(sortKey)));
        Page<ForumPost> posts = postRepository.findAll(buildSpec(subjectId, keyword, sortKey), pageable);
        Map<Long, String> subjectNames = subjectNameMap();
        Page<ForumVO.PostItemVO> vos = posts.map(p -> toItemVO(p, subjectNames));
        fillInteractFlags(vos.getContent(), currentUserId);
        return PageResult.of(vos);
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
        post.setContentType(normalizeType(req.contentType));
        post.setContent(sanitizeContent(req.content, post.getContentType()));
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
        post.setContentType(normalizeType(req.contentType));
        post.setContent(sanitizeContent(req.content, post.getContentType()));
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
        likeRepository.deleteByPostId(id);
        favoriteRepository.deleteByPostId(id);
        postRepository.delete(post);
        log.info("用户 {} 删除帖子 {} 及其回复/点赞/收藏", userId, id);
    }

    // ==================== 点赞 / 收藏 / 转发 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.InteractVO toggleLike(Long postId, Long userId) {
        ForumPost post = requirePost(postId);
        ForumPostLike existing = likeRepository.findByPostIdAndUserId(postId, userId).orElse(null);
        if (existing != null) {
            likeRepository.delete(existing);
        } else {
            likeRepository.save(new ForumPostLike(postId, userId));
        }
        post.setLikeCount((int) likeRepository.countByPostId(postId));
        postRepository.save(post);
        log.info("用户 {} {} 帖子 {}", userId, existing != null ? "取消点赞" : "点赞", postId);
        return interactState(post, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.InteractVO toggleFavorite(Long postId, Long userId) {
        ForumPost post = requirePost(postId);
        ForumPostFavorite existing = favoriteRepository.findByPostIdAndUserId(postId, userId).orElse(null);
        if (existing != null) {
            favoriteRepository.delete(existing);
        } else {
            favoriteRepository.save(new ForumPostFavorite(postId, userId));
        }
        post.setFavoriteCount((int) favoriteRepository.countByPostId(postId));
        postRepository.save(post);
        log.info("用户 {} {} 帖子 {}", userId, existing != null ? "取消收藏" : "收藏", postId);
        return interactState(post, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ForumVO.InteractVO sharePost(Long postId, Long currentUserId) {
        ForumPost post = requirePost(postId);
        post.setShareCount((post.getShareCount() == null ? 0 : post.getShareCount()) + 1);
        postRepository.save(post);
        return interactState(post, currentUserId);
    }

    /** 最新互动状态(计数 + 当前用户标记) */
    private ForumVO.InteractVO interactState(ForumPost post, Long currentUserId) {
        ForumVO.InteractVO vo = new ForumVO.InteractVO();
        vo.likeCount = post.getLikeCount() == null ? 0 : post.getLikeCount();
        vo.favoriteCount = post.getFavoriteCount() == null ? 0 : post.getFavoriteCount();
        vo.shareCount = post.getShareCount() == null ? 0 : post.getShareCount();
        vo.liked = currentUserId != null && likeRepository.existsByPostIdAndUserId(post.getId(), currentUserId);
        vo.favorited = currentUserId != null && favoriteRepository.existsByPostIdAndUserId(post.getId(), currentUserId);
        return vo;
    }

    /** 列表批量回显当前用户的点赞/收藏状态 */
    private void fillInteractFlags(List<ForumVO.PostItemVO> items, Long currentUserId) {
        if (currentUserId == null || items.isEmpty()) return;
        List<Long> ids = items.stream().map(v -> v.id).collect(Collectors.toList());
        Set<Long> likedIds = likeRepository.findByUserIdAndPostIdIn(currentUserId, ids)
            .stream().map(com.learning.springboot.entity.ForumPostLike::getPostId).collect(Collectors.toSet());
        Set<Long> favoritedIds = favoriteRepository.findByUserIdAndPostIdIn(currentUserId, ids)
            .stream().map(com.learning.springboot.entity.ForumPostFavorite::getPostId).collect(Collectors.toSet());
        for (ForumVO.PostItemVO v : items) {
            v.liked = likedIds.contains(v.id);
            v.favorited = favoritedIds.contains(v.id);
        }
    }

    private ForumPost requirePost(Long id) {
        return postRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("帖子不存在: " + id));
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
        reply.setContentType(normalizeType(req.contentType));
        reply.setContent(sanitizeContent(req.content.trim(), reply.getContentType()));
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
            throw new BusinessException(403, "只能删除自己的回复");
        }
        replyRepository.delete(reply);
        postRepository.recalcReplyCount(reply.getPostId());
        log.info("用户 {} 删除回复 {}", userId, id);
    }

    // ==================== 私有工具 ====================

    private Specification<ForumPost> buildSpec(Long subjectId, String keyword, String sortKey) {
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
            if ("hot".equals(sortKey)) {
                // 热度 = 点赞*2 + 收藏*2 + 回复*3 + 浏览(置顶仍优先)
                Expression<Number> hot = cb.sum(
                    cb.sum(cb.prod(root.get("likeCount"), 2), cb.prod(root.get("favoriteCount"), 2)),
                    cb.sum(cb.prod(root.get("replyCount"), 3), root.get("viewCount")));
                query.orderBy(cb.desc(root.get("pinned")), cb.desc(hot), cb.desc(root.get("updatedAt")));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /** 排序键归一化: updated(默认)/created/hot/likes/favorites/replies/views */
    private String normalizeSort(String sort) {
        if (sort == null) return "updated";
        switch (sort.trim().toLowerCase()) {
            case "created": return "created";
            case "hot": return "hot";
            case "likes": return "likes";
            case "favorites": return "favorites";
            case "replies": return "replies";
            case "views": return "views";
            default: return "updated";
        }
    }

    /** 非复合排序的字段映射(均置顶优先) */
    private Sort.Order[] sortOrders(String sortKey) {
        String field;
        switch (sortKey) {
            case "created": field = "createdAt"; break;
            case "likes": field = "likeCount"; break;
            case "favorites": field = "favoriteCount"; break;
            case "replies": field = "replyCount"; break;
            case "views": field = "viewCount"; break;
            default: field = "updatedAt";
        }
        return new Sort.Order[]{
            Sort.Order.desc("pinned"),
            Sort.Order.desc(field),
            Sort.Order.desc("updatedAt")
        };
    }

    private ForumPost requireEditable(Long id, Long userId, boolean admin) {
        ForumPost post = postRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("帖子不存在: " + id));
        if (!admin && !post.getUserId().equals(userId)) {
            throw new BusinessException(403, "只能编辑/删除自己的帖子");
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

    /** 富文本入库前服务端消毒(防存储型 XSS;markdown 保留原文,渲染端 DOMPurify 兜底) */
    private String sanitizeContent(String content, String contentType) {
        return ForumPost.TYPE_RICHTEXT.equals(contentType)
            ? HtmlSanitizer.sanitizeRichText(content)
            : content;
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
        vo.likeCount = p.getLikeCount() == null ? 0 : p.getLikeCount();
        vo.favoriteCount = p.getFavoriteCount() == null ? 0 : p.getFavoriteCount();
        vo.shareCount = p.getShareCount() == null ? 0 : p.getShareCount();
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
        vo.likeCount = p.getLikeCount() == null ? 0 : p.getLikeCount();
        vo.favoriteCount = p.getFavoriteCount() == null ? 0 : p.getFavoriteCount();
        vo.shareCount = p.getShareCount() == null ? 0 : p.getShareCount();
        vo.liked = currentUserId != null && likeRepository.existsByPostIdAndUserId(p.getId(), currentUserId);
        vo.favorited = currentUserId != null && favoriteRepository.existsByPostIdAndUserId(p.getId(), currentUserId);
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
