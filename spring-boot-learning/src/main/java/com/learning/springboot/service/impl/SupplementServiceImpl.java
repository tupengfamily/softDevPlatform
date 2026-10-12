package com.learning.springboot.service.impl;

import com.learning.springboot.dto.SupplementVO;
import com.learning.springboot.entity.SupplementNode;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.SupplementNodeRepository;
import com.learning.springboot.security.HtmlSanitizer;
import com.learning.springboot.service.SupplementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 用户补充节点 Service 实现
 */
@Service
public class SupplementServiceImpl implements SupplementService {

    private static final Logger log = LoggerFactory.getLogger(SupplementServiceImpl.class);

    @Autowired private SupplementNodeRepository nodeRepository;

    @Override
    public List<SupplementVO.NodeVO> getTree(Long subjectId, Long chapterId, Long currentUserId, boolean admin) {
        if (chapterId != null) {
            // 官方章节视图: 章节下直接挂载的节点 + 递归收集全部子孙节点
            List<SupplementNode> flat = new ArrayList<>();
            java.util.Deque<SupplementNode> queue = new java.util.ArrayDeque<>(
                nodeRepository.findByChapterIdOrderBySortNoAscIdAsc(chapterId));
            while (!queue.isEmpty()) {
                SupplementNode n = queue.poll();
                flat.add(n);
                for (SupplementNode child : nodeRepository.findByParentNodeIdOrderBySortNoAscIdAsc(n.getId())) {
                    queue.add(child);
                }
            }
            return buildTree(flat, currentUserId, admin, true);
        }
        if (subjectId != null) {
            // 科目视图: 只展示"用户自建章节"(根节点,未挂官方章节)及其子树
            return buildTree(nodeRepository.findBySubjectIdOrderBySortNoAscIdAsc(subjectId),
                currentUserId, admin, false);
        }
        throw new IllegalArgumentException("subjectId 或 chapterId 至少提供一个");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplementVO.NodeVO create(SupplementVO.NodeUpsertRequest req, Long userId, String username) {
        validate(req);
        SupplementNode node = new SupplementNode();
        node.setSubjectId(req.subjectId);
        node.setChapterId(req.chapterId);
        node.setParentNodeId(req.parentNodeId);
        node.setTitle(req.title.trim());
        node.setContentType(normalizeType(req.contentType));
        node.setContent(sanitizeContent(req.content, node.getContentType()));
        node.setUserId(userId);
        node.setUsername(username);
        node.setSortNo(req.sortNo == null ? 0 : req.sortNo);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.save(node);
        log.info("用户 {} 新增补充节点: {}", username, node);
        return toVO(node, userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplementVO.NodeVO update(Long id, SupplementVO.NodeUpsertRequest req, Long userId, boolean admin) {
        validate(req);
        SupplementNode node = requireEditable(id, userId, admin);
        node.setTitle(req.title.trim());
        node.setContentType(normalizeType(req.contentType));
        node.setContent(sanitizeContent(req.content, node.getContentType()));
        if (req.sortNo != null) node.setSortNo(req.sortNo);
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.save(node);
        return toVO(node, userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId, boolean admin) {
        SupplementNode node = requireEditable(id, userId, admin);
        // 级联删除全部子孙节点
        Set<Long> toDelete = new HashSet<>();
        collectDescendants(id, toDelete);
        toDelete.add(id);
        nodeRepository.deleteAllById(toDelete);
        log.info("用户 {} 删除补充节点 {} 及其 {} 个子节点", userId, id, toDelete.size() - 1);
    }

    // ==================== 私有工具 ====================

    /**
     * 组树: 父节点在 nodes 内则挂为 children,否则作为根。
     * 根的判定:
     * - 章节视图(chapterView=true): 挂在官方章节下且无父节点的节点为根
     * - 科目视图: 仅"用户自建章节"(无父节点且未挂官方章节)为根
     */
    private List<SupplementVO.NodeVO> buildTree(List<SupplementNode> nodes, Long currentUserId,
                                                boolean admin, boolean chapterView) {
        Map<Long, SupplementVO.NodeVO> voById = new LinkedHashMap<>();
        for (SupplementNode n : nodes) {
            voById.put(n.getId(), toVO(n, currentUserId, admin));
        }
        List<SupplementVO.NodeVO> roots = new ArrayList<>();
        for (SupplementNode n : nodes) {
            SupplementVO.NodeVO vo = voById.get(n.getId());
            SupplementVO.NodeVO parent = n.getParentNodeId() == null ? null : voById.get(n.getParentNodeId());
            if (parent != null) {
                parent.children.add(vo);
                continue;
            }
            boolean isRoot = chapterView
                ? n.getChapterId() != null
                : n.getChapterId() == null;
            if (isRoot) roots.add(vo);
        }
        // 排序: sortNo 升序,同值按创建时间
        Comparator<SupplementVO.NodeVO> cmp = Comparator
            .comparingInt((SupplementVO.NodeVO v) -> v.sortNo)
            .thenComparing(v -> v.createdAt == null ? LocalDateTime.MIN : v.createdAt);
        roots.sort(cmp);
        for (SupplementVO.NodeVO vo : voById.values()) {
            vo.children.sort(cmp);
        }
        return roots;
    }

    private void collectDescendants(Long nodeId, Set<Long> acc) {
        for (SupplementNode child : nodeRepository.findByParentNodeIdOrderBySortNoAscIdAsc(nodeId)) {
            if (acc.add(child.getId())) {
                collectDescendants(child.getId(), acc);
            }
        }
    }

    private SupplementNode requireEditable(Long id, Long userId, boolean admin) {
        SupplementNode node = nodeRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("补充节点不存在: " + id));
        if (!admin && !node.getUserId().equals(userId)) {
            throw new BusinessException(403, "只能编辑/删除自己的补充内容");
        }
        return node;
    }

    private void validate(SupplementVO.NodeUpsertRequest req) {
        if (req == null || req.title == null || req.title.trim().isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (req.content == null || req.content.trim().isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (req.subjectId == null) {
            throw new IllegalArgumentException("必须指定所属科目");
        }
        // 挂载位置: 子节点 / 官方章节 / 科目根,三者至少其一
        if (req.parentNodeId == null && req.chapterId == null) {
            // 允许: 科目下的自建章节(根节点)
        }
    }

    private String normalizeType(String type) {
        return SupplementNode.TYPE_RICHTEXT.equals(type)
            ? SupplementNode.TYPE_RICHTEXT
            : SupplementNode.TYPE_MARKDOWN;
    }

    /** 富文本入库前服务端消毒(防存储型 XSS) */
    private String sanitizeContent(String content, String contentType) {
        return SupplementNode.TYPE_RICHTEXT.equals(contentType)
            ? HtmlSanitizer.sanitizeRichText(content)
            : content;
    }

    private SupplementVO.NodeVO toVO(SupplementNode n, Long currentUserId, boolean admin) {
        SupplementVO.NodeVO vo = new SupplementVO.NodeVO();
        vo.id = n.getId();
        vo.subjectId = n.getSubjectId();
        vo.chapterId = n.getChapterId();
        vo.parentNodeId = n.getParentNodeId();
        vo.title = n.getTitle();
        vo.content = n.getContent();
        vo.contentType = n.getContentType();
        vo.userId = n.getUserId();
        vo.username = n.getUsername();
        vo.sortNo = n.getSortNo() == null ? 0 : n.getSortNo();
        vo.createdAt = n.getCreatedAt();
        vo.updatedAt = n.getUpdatedAt();
        vo.editable = admin || (currentUserId != null && currentUserId.equals(n.getUserId()));
        return vo;
    }
}
