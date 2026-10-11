package com.learning.springboot.service;

import com.learning.springboot.dto.FeedbackDTO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.Feedback;

import java.util.List;
import java.util.Map;

/**
 * 用户反馈服务接口
 */
public interface FeedbackService {

    /** 用户提交反馈(评分 / bug) */
    Feedback submit(Long userId, String username, FeedbackDTO dto);

    /** 当前用户的反馈列表 */
    List<Feedback> myFeedback(Long userId);

    /** 管理端分页查询(可选类型/状态过滤) */
    PageResult<Feedback> pageQuery(String type, String status, int page, int size);

    /** 管理员处理反馈(改状态 + 处理备注) */
    Feedback handle(Long id, String status, String adminReply);

    /** 删除反馈 */
    void delete(Long id);

    /** 评分统计: 平均分 + 评分数 */
    Map<String, Object> ratingSummary();
}
