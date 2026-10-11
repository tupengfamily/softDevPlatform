package com.learning.springboot.service.impl;

import com.learning.springboot.dto.LearningAdminVO;
import com.learning.springboot.dto.LearningVO;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.service.SubjectAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 学习配置 Service 实现(管理员)
 */
@Service
public class SubjectAdminServiceImpl implements SubjectAdminService {

    private static final Logger log = LoggerFactory.getLogger(SubjectAdminServiceImpl.class);

    @Autowired private LearningSubjectRepository subjectRepository;
    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;

    // ==================== 科目(语言) ====================

    @Override
    public List<LearningVO.SubjectVO> listSubjects() {
        List<LearningVO.SubjectVO> list = new ArrayList<>();
        for (LearningSubject s : subjectRepository.findAllByOrderBySortNoAsc()) {
            LearningVO.SubjectVO vo = new LearningVO.SubjectVO();
            vo.id = s.getId();
            vo.code = s.getCode();
            vo.name = s.getName();
            vo.description = s.getDescription();
            vo.sortNo = s.getSortNo() == null ? 0 : s.getSortNo();
            vo.enabled = Boolean.TRUE.equals(s.getEnabled());
            vo.totalLessons = countLessons(s.getId());
            list.add(vo);
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningVO.SubjectVO createSubject(LearningAdminVO.SubjectUpsertRequest req) {
        requireSubjectInput(req);
        if (subjectRepository.existsByCode(req.code.trim())) {
            throw new IllegalArgumentException("科目编码已存在: " + req.code);
        }
        LearningSubject s = new LearningSubject();
        s.setCode(req.code.trim());
        s.setName(req.name.trim());
        s.setDescription(req.description);
        s.setSortNo(req.sortNo == null ? 0 : req.sortNo);
        s.setEnabled(req.enabled == null || req.enabled);
        s.setBuiltin(false);
        subjectRepository.save(s);
        log.info("新增学习科目: {}", s);
        return toVO(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningVO.SubjectVO updateSubject(Long id, LearningAdminVO.SubjectUpsertRequest req) {
        requireSubjectInput(req);
        LearningSubject s = subjectRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("科目不存在: " + id));
        if (subjectRepository.existsByCodeAndIdNot(req.code.trim(), id)) {
            throw new IllegalArgumentException("科目编码已被占用: " + req.code);
        }
        s.setCode(req.code.trim());
        s.setName(req.name.trim());
        s.setDescription(req.description);
        if (req.sortNo != null) s.setSortNo(req.sortNo);
        if (req.enabled != null) s.setEnabled(req.enabled);
        subjectRepository.save(s);
        log.info("更新学习科目: {}", s);
        return toVO(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSubject(Long id) {
        LearningSubject s = subjectRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("科目不存在: " + id));
        if (Boolean.TRUE.equals(s.getBuiltin())) {
            throw new IllegalArgumentException("内置科目不允许删除,可改为「停用」");
        }
        long chapters = chapterRepository.countBySubjectId(id);
        if (chapters > 0) {
            throw new IllegalStateException("该科目下还有 " + chapters + " 个章节,请先迁移或删除章节");
        }
        subjectRepository.delete(s);
        log.info("删除学习科目: {}", s);
    }

    // ==================== 章节(学习菜单结构) ====================

    @Override
    public List<LearningAdminVO.ChapterAdminVO> listChapters(Long subjectId) {
        List<LearningChapter> chapters = subjectId == null
            ? chapterRepository.findAllByOrderByChapterNoAsc()
            : chapterRepository.findBySubjectIdOrderByChapterNoAsc(subjectId);
        List<LearningAdminVO.ChapterAdminVO> list = new ArrayList<>();
        for (LearningChapter c : chapters) {
            list.add(toChapterVO(c));
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningAdminVO.ChapterAdminVO createChapter(LearningAdminVO.ChapterUpsertRequest req) {
        requireChapterInput(req);
        requireSubjectExists(req.subjectId);
        LearningChapter c = new LearningChapter();
        applyChapter(c, req);
        LearningChapter saved = chapterRepository.save(c);
        log.info("新增章节: {}", saved);
        return toChapterVO(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningAdminVO.ChapterAdminVO updateChapter(Long id, LearningAdminVO.ChapterUpsertRequest req) {
        requireChapterInput(req);
        requireSubjectExists(req.subjectId);
        LearningChapter c = chapterRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("章节不存在: " + id));
        applyChapter(c, req);
        LearningChapter saved = chapterRepository.save(c);
        log.info("更新章节: {}", saved);
        return toChapterVO(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChapter(Long id) {
        LearningChapter c = chapterRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("章节不存在: " + id));
        // 级联: 删除课时 + 学习进度(避免孤儿数据)
        List<LearningLesson> lessons = lessonRepository.findByChapterIdOrderByLessonNoAsc(id);
        if (!lessons.isEmpty()) {
            progressRepository.deleteByLessonIdIn(lessons.stream().map(LearningLesson::getId).toList());
            lessonRepository.deleteByChapterId(id);
        }
        chapterRepository.delete(c);
        log.info("删除章节(含 {} 课时): {}", lessons.size(), c);
    }

    // ==================== 私有工具 ====================

    private void requireSubjectInput(LearningAdminVO.SubjectUpsertRequest req) {
        if (req == null || isBlank(req.code) || isBlank(req.name)) {
            throw new IllegalArgumentException("科目编码与名称不能为空");
        }
    }

    private void requireChapterInput(LearningAdminVO.ChapterUpsertRequest req) {
        if (req == null || isBlank(req.title)) {
            throw new IllegalArgumentException("章节标题不能为空");
        }
        if (req.subjectId == null) {
            throw new IllegalArgumentException("必须指定所属科目(语言)");
        }
        if (req.stageNo == null || isBlank(req.stageName)) {
            throw new IllegalArgumentException("阶段编号与阶段名称不能为空");
        }
    }

    private void requireSubjectExists(Long subjectId) {
        if (subjectId == null || !subjectRepository.existsById(subjectId)) {
            throw new IllegalArgumentException("所属科目不存在: " + subjectId);
        }
    }

    private void applyChapter(LearningChapter c, LearningAdminVO.ChapterUpsertRequest req) {
        c.setSubjectId(req.subjectId);
        c.setStageNo(req.stageNo);
        c.setStageName(req.stageName.trim());
        c.setChapterNo(req.chapterNo == null ? 0 : req.chapterNo);
        c.setTitle(req.title.trim());
        c.setDescription(req.description);
        c.setCodePath(req.codePath);
        c.setEstimatedHours(req.estimatedHours == null ? 0 : req.estimatedHours);
    }

    private LearningAdminVO.ChapterAdminVO toChapterVO(LearningChapter c) {
        LearningAdminVO.ChapterAdminVO vo = new LearningAdminVO.ChapterAdminVO();
        vo.id = c.getId();
        vo.subjectId = c.getSubjectId();
        vo.subjectName = c.getSubjectId() == null ? ""
            : subjectRepository.findById(c.getSubjectId()).map(LearningSubject::getName).orElse("");
        vo.stageNo = c.getStageNo();
        vo.stageName = c.getStageName();
        vo.chapterNo = c.getChapterNo() == null ? 0 : c.getChapterNo();
        vo.title = c.getTitle();
        vo.description = c.getDescription();
        vo.codePath = c.getCodePath();
        vo.estimatedHours = c.getEstimatedHours() == null ? 0 : c.getEstimatedHours();
        vo.lessonCount = lessonRepository.countByChapterId(c.getId());
        return vo;
    }

    private LearningVO.SubjectVO toVO(LearningSubject s) {
        LearningVO.SubjectVO vo = new LearningVO.SubjectVO();
        vo.id = s.getId();
        vo.code = s.getCode();
        vo.name = s.getName();
        vo.description = s.getDescription();
        vo.sortNo = s.getSortNo() == null ? 0 : s.getSortNo();
        vo.enabled = Boolean.TRUE.equals(s.getEnabled());
        vo.totalLessons = countLessons(s.getId());
        return vo;
    }

    private long countLessons(Long subjectId) {
        long n = 0;
        for (LearningChapter c : chapterRepository.findBySubjectIdOrderByChapterNoAsc(subjectId)) {
            n += lessonRepository.countByChapterId(c.getId());
        }
        return n;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
