package com.learning.springboot.repository;

import com.learning.springboot.entity.SupplementNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 用户补充节点 Repository
 */
public interface SupplementNodeRepository extends JpaRepository<SupplementNode, Long> {

    /** 某科目下全部补充节点(前端/服务层组树) */
    List<SupplementNode> findBySubjectIdOrderBySortNoAscIdAsc(Long subjectId);

    /** 某官方章节下直接挂载的补充节点 */
    List<SupplementNode> findByChapterIdOrderBySortNoAscIdAsc(Long chapterId);

    /** 某节点的子节点 */
    List<SupplementNode> findByParentNodeIdOrderBySortNoAscIdAsc(Long parentNodeId);

    /** 某作者的全部节点(管理用) */
    List<SupplementNode> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
