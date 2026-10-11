package com.learning.springboot.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户补充节点(章节/子节点)的视图对象
 */
public class SupplementVO {

    /** 节点(含子树) */
    public static class NodeVO {
        public Long id;
        public Long subjectId;
        public Long chapterId;
        public Long parentNodeId;
        public String title;
        public String content;
        public String contentType;        // markdown / richtext
        public Long userId;
        public String username;
        public int sortNo;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public boolean editable;          // 当前用户可编辑(作者或管理员)
        public List<NodeVO> children = new ArrayList<>();
    }

    /** 创建/更新请求 */
    public static class NodeUpsertRequest {
        public Long subjectId;
        public Long chapterId;            // 可空: 挂官方章节
        public Long parentNodeId;         // 可空: 挂补充节点(子节点)
        public String title;
        public String content;
        public String contentType;        // markdown / richtext
        public Integer sortNo;
    }
}
