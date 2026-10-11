package com.learning.springboot.service.impl;

import com.learning.springboot.dto.ReleaseNoteDTO;
import com.learning.springboot.entity.ReleaseNote;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.ReleaseNoteRepository;
import com.learning.springboot.service.NotificationService;
import com.learning.springboot.service.ReleaseNoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 版本更新公告服务实现
 */
@Service
public class ReleaseNoteServiceImpl implements ReleaseNoteService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNoteServiceImpl.class);

    @Autowired private ReleaseNoteRepository releaseNoteRepository;
    @Autowired private NotificationService notificationService;

    @Override
    public List<ReleaseNote> list() {
        return releaseNoteRepository.findAllByOrderByReleaseDateDescIdDesc();
    }

    @Override
    public ReleaseNote latest() {
        return releaseNoteRepository.findTopByOrderByReleaseDateDescIdDesc().orElse(null);
    }

    @Override
    @Transactional
    public ReleaseNote create(ReleaseNoteDTO dto) {
        validate(dto);
        if (dto.getVersion() != null && releaseNoteRepository.findByVersion(dto.getVersion()).isPresent()) {
            throw new BusinessException("版本号 " + dto.getVersion() + " 已存在");
        }
        ReleaseNote note = new ReleaseNote();
        apply(note, dto);
        ReleaseNote saved = releaseNoteRepository.save(note);
        // 新版本公告同时作为系统通知推送给全体用户(失败不影响发版)
        try {
            notificationService.sendSystemNotice("新版本 v" + saved.getVersion() + " 发布", saved.getTitle());
        } catch (Exception e) {
            log.warn("新版本系统通知推送失败: {}", e.getMessage());
        }
        return saved;
    }

    @Override
    @Transactional
    public ReleaseNote update(Long id, ReleaseNoteDTO dto) {
        ReleaseNote note = releaseNoteRepository.findById(id)
            .orElseThrow(() -> new BusinessException("公告不存在"));
        requireNotPublished(note);
        validate(dto);
        // 版本号可改,但不能与其他公告冲突
        if (dto.getVersion() != null) {
            releaseNoteRepository.findByVersion(dto.getVersion())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new BusinessException("版本号 " + dto.getVersion() + " 已存在"); });
        }
        apply(note, dto);
        return releaseNoteRepository.save(note);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ReleaseNote note = releaseNoteRepository.findById(id)
            .orElseThrow(() -> new BusinessException("公告不存在"));
        requireNotPublished(note);
        releaseNoteRepository.delete(note);
    }

    /** 已到发布日期的公告一经发布即锁定,不允许再编辑/删除 */
    private void requireNotPublished(ReleaseNote note) {
        LocalDate date = note.getReleaseDate() != null ? note.getReleaseDate() : LocalDate.now();
        if (!date.isAfter(LocalDate.now())) {
            throw new BusinessException("已发布的公告不允许编辑/删除");
        }
    }

    private void validate(ReleaseNoteDTO dto) {
        if (dto.getVersion() == null || dto.getVersion().isBlank()) throw new BusinessException("版本号不能为空");
        if (dto.getTitle() == null || dto.getTitle().isBlank()) throw new BusinessException("公告标题不能为空");
        if (dto.getContent() == null || dto.getContent().isBlank()) throw new BusinessException("更新内容不能为空");
    }

    private void apply(ReleaseNote note, ReleaseNoteDTO dto) {
        note.setVersion(dto.getVersion().trim());
        note.setTitle(dto.getTitle().trim());
        note.setContent(dto.getContent().trim());
        note.setReleaseDate(dto.getReleaseDate() != null ? dto.getReleaseDate() : LocalDate.now());
    }
}
