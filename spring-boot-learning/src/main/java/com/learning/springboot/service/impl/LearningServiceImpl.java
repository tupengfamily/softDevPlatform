package com.learning.springboot.service.impl;

import com.learning.springboot.dto.LearningVO;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.entity.User;
import com.learning.springboot.entity.UserPreference;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.repository.UserPreferenceRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.service.LearningService;
import com.learning.springboot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 学习中心 Service 实现
 * <p>
 * 实现要点:
 * - 课程树一次查询内存组装(课时总量小,无需分页),支持 subjectId(语言维度)筛选
 * - 进度通过 Set&lt;Long&gt; learnedIds 判定,避免 N+1 查询
 * - 标记/取消操作幂等:重复调用不报错、不产生脏数据
 */
@Service
public class LearningServiceImpl implements LearningService {

    private static final Logger log = LoggerFactory.getLogger(LearningServiceImpl.class);

    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LearningSubjectRepository subjectRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserPreferenceRepository preferenceRepository;
    @Autowired private NotificationService notificationService;

    // ==================== 科目(语言)维度 ====================

    @Override
    public List<LearningVO.SubjectVO> getSubjects() {
        Map<Long, Long> lessonCountBySubject = countLessonsBySubject();
        List<LearningVO.SubjectVO> list = new ArrayList<>();
        for (LearningSubject s : subjectRepository.findByEnabledTrueOrderBySortNoAsc()) {
            list.add(toSubjectVO(s, lessonCountBySubject.getOrDefault(s.getId(), 0L)));
        }
        return list;
    }

    // ==================== 课程树 ====================

    @Override
    public List<LearningVO.StageVO> getCurriculum(Long userId, Long subjectId) {
        Set<Long> learnedIds = findLearnedIds(userId);
        List<LearningChapter> chapters = chaptersOf(subjectId);

        Map<Integer, LearningVO.StageVO> stageMap = new LinkedHashMap<>();
        for (LearningChapter chapter : chapters) {
            LearningVO.StageVO stage = stageMap.computeIfAbsent(
                chapter.getStageNo(),
                no -> {
                    LearningVO.StageVO s = new LearningVO.StageVO();
                    s.stageNo = no;
                    s.stageName = chapter.getStageName();
                    return s;
                });

            LearningVO.ChapterVO cv = new LearningVO.ChapterVO();
            cv.id = chapter.getId();
            cv.subjectId = chapter.getSubjectId();
            cv.stageNo = chapter.getStageNo();
            cv.stageName = chapter.getStageName();
            cv.chapterNo = chapter.getChapterNo();
            cv.title = chapter.getTitle();
            cv.description = chapter.getDescription();
            cv.codePath = chapter.getCodePath();
            cv.estimatedHours = chapter.getEstimatedHours() == null ? 0 : chapter.getEstimatedHours();

            List<LearningLesson> lessons =
                lessonRepository.findByChapterIdOrderByLessonNoAsc(chapter.getId());
            for (LearningLesson lesson : lessons) {
                LearningVO.LessonItemVO lv = new LearningVO.LessonItemVO();
                lv.id = lesson.getId();
                lv.lessonNo = lesson.getLessonNo();
                lv.title = lesson.getTitle();
                lv.summary = lesson.getSummary();
                lv.difficulty = lesson.getDifficulty();
                lv.durationMinutes = lesson.getDurationMinutes();
                lv.learned = learnedIds.contains(lesson.getId());
                cv.lessons.add(lv);
            }
            cv.totalLessons = cv.lessons.size();
            cv.learnedLessons = cv.lessons.stream().filter(l -> l.learned).count();
            stage.chapters.add(cv);
        }
        List<LearningVO.StageVO> stages = new ArrayList<>(stageMap.values());
        stages.sort(Comparator.comparingInt(s -> s.stageNo));
        return stages;
    }

    // ==================== 课时详情 ====================

    @Override
    public LearningVO.LessonDetailVO getLessonDetail(Long lessonId, Long userId) {
        LearningLesson lesson = lessonRepository.findById(lessonId)
            .orElseThrow(() -> new NoSuchElementException("课时不存在: " + lessonId));
        LearningChapter chapter = chapterRepository.findById(lesson.getChapterId())
            .orElseThrow(() -> new NoSuchElementException("章节不存在: " + lesson.getChapterId()));

        LearningVO.LessonDetailVO vo = new LearningVO.LessonDetailVO();
        vo.id = lesson.getId();
        vo.chapterId = lesson.getChapterId();
        vo.subjectId = chapter.getSubjectId();
        vo.stageNo = chapter.getStageNo();
        vo.stageName = chapter.getStageName();
        vo.chapterNo = chapter.getChapterNo();
        vo.chapterTitle = chapter.getTitle();
        vo.lessonNo = lesson.getLessonNo();
        vo.title = lesson.getTitle();
        vo.summary = lesson.getSummary();
        vo.content = lesson.getContent();
        vo.codeExample = lesson.getCodeExample();
        vo.codeType = lesson.getCodeType() == null ? "java" : lesson.getCodeType();
        vo.difficulty = lesson.getDifficulty();
        vo.durationMinutes = lesson.getDurationMinutes();
        vo.learned = userId != null && progressRepository.existsByUserIdAndLessonId(userId, lessonId);

        // 前后课时导航:按 "章节 chapterNo + 课时 lessonNo" 的全局学习序列定位
        // (同一科目内导航,避免跳到其他语言的课程)
        Map<Long, Integer> chapterNoById = new HashMap<>();
        List<LearningChapter> navChapters = chaptersOf(chapter.getSubjectId());
        for (LearningChapter c : navChapters) {
            chapterNoById.put(c.getId(), c.getChapterNo());
        }
        List<LearningLesson> ordered = new ArrayList<>();
        for (LearningChapter c : navChapters) {
            ordered.addAll(lessonRepository.findByChapterIdOrderByLessonNoAsc(c.getId()));
        }
        ordered.sort(Comparator
            .comparingInt((LearningLesson l) -> chapterNoById.getOrDefault(l.getChapterId(), 0))
            .thenComparingInt(LearningLesson::getLessonNo));
        int idx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(lessonId)) { idx = i; break; }
        }
        if (idx > 0) vo.prevLessonId = ordered.get(idx - 1).getId();
        if (idx >= 0 && idx < ordered.size() - 1) vo.nextLessonId = ordered.get(idx + 1).getId();
        return vo;
    }

    // ==================== 进度标记 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markLearned(Long lessonId, Long userId) {
        LearningLesson lesson = lessonRepository.findById(lessonId)
            .orElseThrow(() -> new NoSuchElementException("课时不存在: " + lessonId));
        if (progressRepository.existsByUserIdAndLessonId(userId, lessonId)) {
            log.debug("课时 {} 已被用户 {} 标记过,幂等返回", lessonId, userId);
            return;
        }
        progressRepository.save(new LessonProgress(userId, lessonId));
        log.info("用户 {} 完成课时 {}", userId, lessonId);
        // 任务完成通知(静默,不影响标记结果)
        try {
            notificationService.notifyTaskDone(userId, lesson.getTitle(), lessonId);
        } catch (Exception e) {
            log.warn("任务完成通知发送失败: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unmarkLearned(Long lessonId, Long userId) {
        progressRepository.deleteByUserIdAndLessonId(userId, lessonId);
        log.info("用户 {} 取消课时 {} 的学习标记", userId, lessonId);
    }

    // ==================== 进度统计 ====================

    @Override
    public LearningVO.ProgressStatsVO getProgressStats(Long userId, Long subjectId) {
        LearningVO.ProgressStatsVO stats = new LearningVO.ProgressStatsVO();

        List<LearningChapter> allChapters = chaptersOf(subjectId);
        Map<Long, LearningChapter> chapterMap = new LinkedHashMap<>();
        Map<Integer, LearningVO.StageProgressVO> stageMap = new LinkedHashMap<>();
        for (LearningChapter c : allChapters) {
            chapterMap.put(c.getId(), c);
            stageMap.computeIfAbsent(c.getStageNo(), no -> {
                LearningVO.StageProgressVO s = new LearningVO.StageProgressVO();
                s.stageNo = no;
                s.stageName = c.getStageName();
                return s;
            });
        }
        List<LearningLesson> allLessons = new ArrayList<>();
        for (LearningChapter c : allChapters) {
            allLessons.addAll(lessonRepository.findByChapterIdOrderByLessonNoAsc(c.getId()));
        }
        stats.totalLessons = allLessons.size();

        Set<Long> learnedIds = findLearnedIds(userId);
        for (LearningLesson lesson : allLessons) {
            LearningChapter ch = chapterMap.get(lesson.getChapterId());
            LearningVO.StageProgressVO stage = ch == null ? null : stageMap.get(ch.getStageNo());
            if (stage != null) {
                stage.total++;
                if (learnedIds.contains(lesson.getId())) stage.learned++;
            }
        }
        stats.learnedLessons = allLessons.stream()
            .filter(l -> learnedIds.contains(l.getId()))
            .count();
        stats.percent = percent(stats.learnedLessons, stats.totalLessons);
        for (LearningVO.StageProgressVO s : stageMap.values()) {
            s.percent = percent(s.learned, s.total);
            stats.stages.add(s);
        }

        // 分科目(语言)进度
        Map<Long, Set<Long>> lessonIdsBySubject = lessonIdsBySubject();
        for (LearningSubject sub : subjectRepository.findByEnabledTrueOrderBySortNoAsc()) {
            Set<Long> ids = lessonIdsBySubject.getOrDefault(sub.getId(), Set.of());
            LearningVO.SubjectProgressVO sv = new LearningVO.SubjectProgressVO();
            sv.subjectId = sub.getId();
            sv.subjectName = sub.getName();
            sv.total = ids.size();
            sv.learned = ids.stream().filter(learnedIds::contains).count();
            sv.percent = percent(sv.learned, sv.total);
            stats.subjects.add(sv);
        }

        // 最近学习时间线(最近 5 条)
        Map<Long, LearningLesson> lessonMap = new LinkedHashMap<>();
        for (LearningChapter c : chapterRepository.findAll()) {
            for (LearningLesson l : lessonRepository.findByChapterIdOrderByLessonNoAsc(c.getId())) {
                lessonMap.put(l.getId(), l);
            }
        }
        progressRepository.findByUserIdOrderByLearnedAtDesc(userId).stream()
            .limit(5)
            .forEach(p -> {
                LearningLesson l = lessonMap.get(p.getLessonId());
                if (l == null) return;
                LearningVO.RecentLearnedVO r = new LearningVO.RecentLearnedVO();
                r.lessonId = l.getId();
                r.lessonTitle = l.getTitle();
                LearningChapter c = chapterMap.get(l.getChapterId());
                r.chapterTitle = c == null ? "" : c.getTitle();
                r.learnedAt = p.getLearnedAt();
                stats.recent.add(r);
            });
        return stats;
    }

    // ==================== 学习看板(管理员/个人,语言维度) ====================

    /**
     * 数据范围在服务端收口:
     * - ADMIN: 全部用户(含零进度),用于教学管理看板
     * - 其他角色: 仅当前用户一条,保证"普通用户只能看到自己相关的内容"
     * subjectId: 语言维度筛选,并返回分科目(语言)概览。
     */
    @Override
    @Transactional(readOnly = true)
    public LearningVO.BoardOverviewVO getBoardOverview(Long userId, Set<String> roles, Long subjectId) {
        boolean admin = roles != null && roles.contains("ADMIN");

        // 科目 -> 课时 id 集合
        Map<Long, Set<Long>> lessonIdsBySubject = lessonIdsBySubject();
        Set<Long> scopeLessonIds;   // 当前统计维度内的课时
        if (subjectId != null) {
            scopeLessonIds = lessonIdsBySubject.getOrDefault(subjectId, Set.of());
        } else {
            scopeLessonIds = new HashSet<>();
            lessonIdsBySubject.values().forEach(scopeLessonIds::addAll);
        }
        long totalLessons = scopeLessonIds.size();

        // 一次取全部进度并按用户分组,避免逐个用户查库(N+1)
        Map<Long, List<LessonProgress>> progressByUser = progressRepository.findAll().stream()
            .collect(Collectors.groupingBy(LessonProgress::getUserId));

        List<User> users;
        if (admin) {
            users = userRepository.findAll();
        } else {
            users = userRepository.findById(userId).map(List::of).orElse(List.of());
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        // 学习偏好:userId -> 偏好科目 id;科目 id -> 名称
        Map<Long, Long> prefByUser = new HashMap<>();
        for (UserPreference p : preferenceRepository.findAll()) {
            prefByUser.put(p.getUserId(), p.getPreferredSubjectId());
        }
        Map<Long, String> subjectNameById = new HashMap<>();
        for (LearningSubject s : subjectRepository.findAll()) {
            subjectNameById.put(s.getId(), s.getName());
        }

        List<LearningVO.BoardRowVO> rows = new ArrayList<>();
        for (User u : users) {
            List<LessonProgress> list = progressByUser.getOrDefault(u.getId(), List.of());
            long learned = list.stream()
                .filter(p -> scopeLessonIds.contains(p.getLessonId()))
                .count();
            LearningVO.BoardRowVO row = new LearningVO.BoardRowVO();
            row.userId = u.getId();
            row.username = u.getUsername();
            row.email = u.getEmail();
            row.roles = String.join(",", u.getRoleCodes());
            row.learnedLessons = learned;
            row.totalLessons = totalLessons;
            row.percent = percent(learned, totalLessons);
            row.lastLearnedAt = list.stream()
                .filter(p -> scopeLessonIds.contains(p.getLessonId()))
                .map(LessonProgress::getLearnedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(fmt::format)
                .orElse(null);
            row.self = u.getId().equals(userId);
            Long prefSubjectId = prefByUser.get(u.getId());
            row.preferredSubject = prefSubjectId == null ? null
                : subjectNameById.getOrDefault(prefSubjectId, null);
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt((LearningVO.BoardRowVO r) -> r.percent).reversed()
            .thenComparing(r -> r.username == null ? "" : r.username));

        LearningVO.BoardOverviewVO vo = new LearningVO.BoardOverviewVO();
        vo.scope = admin ? "ALL" : "SELF";
        vo.subjectId = subjectId;
        vo.totalUsers = rows.size();
        vo.activeUsers = (int) rows.stream().filter(r -> r.learnedLessons > 0).count();
        vo.totalLessons = totalLessons;
        vo.avgPercent = rows.isEmpty() ? 0
            : (int) Math.round(rows.stream().mapToInt(r -> r.percent).average().orElse(0));
        vo.maxPercent = rows.stream().mapToInt(r -> r.percent).max().orElse(0);
        vo.rows = rows;

        // 分科目(语言)概览:每科目的课时数 + 全员平均完成度
        for (LearningSubject sub : subjectRepository.findByEnabledTrueOrderBySortNoAsc()) {
            Set<Long> ids = lessonIdsBySubject.getOrDefault(sub.getId(), Set.of());
            LearningVO.SubjectStatVO sv = new LearningVO.SubjectStatVO();
            sv.subjectId = sub.getId();
            sv.subjectName = sub.getName();
            sv.totalLessons = ids.size();
            if (ids.isEmpty() || rows.isEmpty()) {
                sv.avgPercent = 0;
            } else {
                double sum = 0;
                for (User u : users) {
                    long learned = progressByUser.getOrDefault(u.getId(), List.of()).stream()
                        .filter(p -> ids.contains(p.getLessonId()))
                        .count();
                    sum += percent(learned, ids.size());
                }
                sv.avgPercent = (int) Math.round(sum / users.size());
            }
            vo.subjects.add(sv);
        }

        // 学习偏好分布(管理员视角):各语言的偏好用户数(未设置偏好的单独计数不展示)
        if (admin) {
            Map<Long, Integer> prefCount = new HashMap<>();
            for (Long sid : prefByUser.values()) {
                if (sid != null) prefCount.merge(sid, 1, Integer::sum);
            }
            for (LearningSubject sub : subjectRepository.findByEnabledTrueOrderBySortNoAsc()) {
                LearningVO.PreferenceStatVO pv = new LearningVO.PreferenceStatVO();
                pv.subjectId = sub.getId();
                pv.subjectName = sub.getName();
                pv.userCount = prefCount.getOrDefault(sub.getId(), 0);
                vo.preferenceStats.add(pv);
            }
        }
        log.debug("学习看板查询: userId={}, scope={}, subjectId={}, rows={}", userId, vo.scope, subjectId, rows.size());
        return vo;
    }

    // ==================== 学习偏好(正在学习的语言) ====================

    @Override
    public Long getPreferredSubject(Long userId) {
        return preferenceRepository.findByUserId(userId)
            .map(UserPreference::getPreferredSubjectId)
            .orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePreferredSubject(Long userId, Long subjectId) {
        if (subjectId != null) {
            LearningSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NoSuchElementException("科目不存在: " + subjectId));
            if (!Boolean.TRUE.equals(subject.getEnabled())) {
                throw new IllegalStateException("科目已停用: " + subjectId);
            }
        }
        UserPreference pref = preferenceRepository.findByUserId(userId)
            .orElseGet(() -> new UserPreference(userId, null));
        pref.setPreferredSubjectId(subjectId);
        preferenceRepository.save(pref);
        log.info("用户 {} 学习偏好更新为科目 {}", userId, subjectId);
    }

    // ==================== 私有工具 ====================

    /** 当前用户已学课时 id 集合(游客 userId 为空时返回空集) */
    private Set<Long> findLearnedIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        if (userId == null) return ids;
        for (LessonProgress p : progressRepository.findByUserId(userId)) {
            ids.add(p.getLessonId());
        }
        return ids;
    }

    /** 科目筛选下的章节(subjectId 为空=全部) */
    private List<LearningChapter> chaptersOf(Long subjectId) {
        return subjectId == null
            ? chapterRepository.findAllByOrderByChapterNoAsc()
            : chapterRepository.findBySubjectIdOrderByChapterNoAsc(subjectId);
    }

    /** 科目 id -> 该科目全部课时 id */
    private Map<Long, Set<Long>> lessonIdsBySubject() {
        Map<Long, Set<Long>> map = new HashMap<>();
        for (LearningChapter c : chapterRepository.findAll()) {
            if (c.getSubjectId() == null) continue;
            Set<Long> ids = map.computeIfAbsent(c.getSubjectId(), k -> new HashSet<>());
            for (LearningLesson l : lessonRepository.findByChapterIdOrderByLessonNoAsc(c.getId())) {
                ids.add(l.getId());
            }
        }
        return map;
    }

    /** 科目 -> 课时总数 */
    private Map<Long, Long> countLessonsBySubject() {
        Map<Long, Long> map = new HashMap<>();
        lessonIdsBySubject().forEach((k, v) -> map.put(k, (long) v.size()));
        return map;
    }

    private LearningVO.SubjectVO toSubjectVO(LearningSubject s, long totalLessons) {
        LearningVO.SubjectVO vo = new LearningVO.SubjectVO();
        vo.id = s.getId();
        vo.code = s.getCode();
        vo.name = s.getName();
        vo.description = s.getDescription();
        vo.sortNo = s.getSortNo() == null ? 0 : s.getSortNo();
        vo.enabled = Boolean.TRUE.equals(s.getEnabled());
        vo.totalLessons = totalLessons;
        return vo;
    }

    /** 安全计算百分比 */
    private int percent(long part, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(part * 100.0 / total);
    }
}
