package com.learning.springboot.service.impl;

import com.learning.springboot.dto.FeedbackDTO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.Feedback;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.FeedbackRepository;
import com.learning.springboot.service.FeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户反馈服务实现
 */
@Service
public class FeedbackServiceImpl implements FeedbackService {

    private static final Set<String> VALID_TYPES = Set.of(Feedback.TYPE_RATING, Feedback.TYPE_BUG);
    private static final Set<String> VALID_STATUS = Set.of(Feedback.STATUS_OPEN, Feedback.STATUS_PROCESSING, Feedback.STATUS_DONE);

    @Autowired private FeedbackRepository feedbackRepository;

    @Override
    @Transactional
    public Feedback submit(Long userId, String username, FeedbackDTO dto) {
        if (dto.getType() == null || !VALID_TYPES.contains(dto.getType())) {
            throw new BusinessException("反馈类型无效");
        }
        if (Feedback.TYPE_RATING.equals(dto.getType())) {
            if (dto.getScore() == null || dto.getScore() < 1 || dto.getScore() > 5) {
                throw new BusinessException("评分必须是 1~5 星");
            }
        } else if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BusinessException("请填写问题标题");
        }
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BusinessException("反馈内容不能为空");
        }

        Feedback f = new Feedback();
        f.setUserId(userId);
        f.setUsername(username);
        f.setType(dto.getType());
        f.setScore(Feedback.TYPE_RATING.equals(dto.getType()) ? dto.getScore() : null);
        f.setTitle(dto.getTitle() != null ? dto.getTitle().trim() : null);
        f.setContent(dto.getContent().trim());
        f.setContact(dto.getContact() != null ? dto.getContact().trim() : null);
        return feedbackRepository.save(f);
    }

    @Override
    public List<Feedback> myFeedback(Long userId) {
        return feedbackRepository.findByUserIdOrderByCreateTimeDescIdDesc(userId);
    }

    @Override
    public PageResult<Feedback> pageQuery(String type, String status, int page, int size) {
        if (type != null && !type.isBlank() && !VALID_TYPES.contains(type)) throw new BusinessException("反馈类型无效");
        if (status != null && !status.isBlank() && !VALID_STATUS.contains(status)) throw new BusinessException("状态无效");
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1),
            Sort.by(Sort.Direction.DESC, "createTime", "id"));
        Page<Feedback> p = feedbackRepository.pageQuery(
            (type == null || type.isBlank()) ? null : type,
            (status == null || status.isBlank()) ? null : status,
            pageable);
        return PageResult.of(p);
    }

    @Override
    @Transactional
    public Feedback handle(Long id, String status, String adminReply) {
        Feedback f = feedbackRepository.findById(id)
            .orElseThrow(() -> new BusinessException("反馈不存在"));
        if (status != null) {
            if (!VALID_STATUS.contains(status)) throw new BusinessException("状态无效");
            f.setStatus(status);
        }
        f.setAdminReply(adminReply != null ? adminReply.trim() : null);
        return feedbackRepository.save(f);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        feedbackRepository.deleteById(id);
    }

    @Override
    public Map<String, Object> ratingSummary() {
        Double avg = feedbackRepository.averageScore();
        long count = feedbackRepository.countByType(Feedback.TYPE_RATING);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgScore", avg != null ? Math.round(avg * 10) / 10.0 : null);
        m.put("ratingCount", count);
        return m;
    }
}
