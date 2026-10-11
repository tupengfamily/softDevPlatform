package com.learning.springboot.service;

import com.learning.springboot.dto.SupplementVO;

import java.util.List;

/**
 * 用户补充节点 Service — 章节下用户自建的"补充章节/子节点"
 * <p>
 * 所有登录用户可查看/新增;作者或管理员可编辑/删除。
 */
public interface SupplementService {

    /**
     * 查询补充节点树。
     * chapterId 优先:返回某官方章节下的补充子树;
     * 否则按 subjectId 返回该科目下的全部补充节点(含用户自建章节)。
     */
    List<SupplementVO.NodeVO> getTree(Long subjectId, Long chapterId, Long currentUserId, boolean admin);

    /** 新增节点 */
    SupplementVO.NodeVO create(SupplementVO.NodeUpsertRequest req, Long userId, String username);

    /** 编辑节点(作者或管理员) */
    SupplementVO.NodeVO update(Long id, SupplementVO.NodeUpsertRequest req, Long userId, boolean admin);

    /** 删除节点(作者或管理员),级联删除子节点 */
    void delete(Long id, Long userId, boolean admin);
}
