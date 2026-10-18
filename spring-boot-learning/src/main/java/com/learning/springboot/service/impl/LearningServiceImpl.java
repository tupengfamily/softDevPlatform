package com.learning.springboot.service.impl;

import com.learning.springboot.dto.LearningVO;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.entity.User;
import com.learning.springboot.entity.UserPreference;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.repository.UserPreferenceRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.service.LearningService;
import com.learning.springboot.service.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
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
    @Autowired private ObjectMapper objectMapper;

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

        // 章级 prerequisite -> 是否已学完(同科目内)
        // 缓存: chapterId -> Set<Long> lessonIds
        Map<Long, Set<Long>> lessonsByChapterId = new HashMap<>();
        for (LearningChapter c : chapters) {
            Set<Long> ls = new HashSet<>();
            for (LearningLesson l : lessonRepository.findByChapterIdOrderByLessonNoAsc(c.getId())) {
                ls.add(l.getId());
            }
            lessonsByChapterId.put(c.getId(), ls);
        }

        // 解析每章的 prerequisites JSON (本学科内的其他章 id 列表)
        Map<Long, List<Long>> prereqByChapterId = new HashMap<>();
        for (LearningChapter c : chapters) {
            String raw = c.getPrerequisites();
            if (raw == null || raw.isBlank()) continue;
            try {
                List<Long> ids = objectMapper.readValue(raw, new TypeReference<List<Long>>() {});
                // 过滤掉不存在的章 id(防 schema 漂移)
                Set<Long> validChapterIds = new HashSet<>();
                for (LearningChapter x : chapters) validChapterIds.add(x.getId());
                ids = ids.stream().filter(validChapterIds::contains).toList();
                prereqByChapterId.put(c.getId(), ids);
            } catch (Exception e) {
                log.warn("解析章节 prerequisites JSON 失败, chapterId={}, raw={}", c.getId(), raw);
            }
        }

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

            // 前置 + 锁定态计算
            List<Long> prereqs = prereqByChapterId.getOrDefault(chapter.getId(), List.of());
            cv.prerequisites = prereqs;
            if (prereqs.isEmpty()) {
                cv.locked = false;
                cv.unmetPrerequisites = List.of();
            } else {
                // 计算未学完的前置章(至少还有 1 个 lesson 未学)
                List<Long> unmet = new ArrayList<>();
                for (Long pid : prereqs) {
                    Set<Long> pLessons = lessonsByChapterId.getOrDefault(pid, Set.of());
                    boolean allLearned = !pLessons.isEmpty() && learnedIds.containsAll(pLessons);
                    if (!allLearned) unmet.add(pid);
                }
                cv.unmetPrerequisites = unmet;
                cv.locked = !unmet.isEmpty();
            }

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

        // 服务端强制 locked 校验: 即便前端只展示锁定, 用户拼 id 也能拿到内容时也不能绕过
        // 未登录访问默认放行(游客浏览);登录用户必须满足 prerequisites
        if (userId != null && isChapterLocked(chapter.getId(), userId)) {
            throw new BusinessException(403, "章节未解锁, 请先完成前置: " + chapter.getTitle());
        }

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

        // 前后课时导航:一次 SQL 取该 subject 内全部已排序的课时(替代原 N+1 的逐章拉)
        List<LearningLesson> ordered = lessonRepository.findBySubjectIdOrdered(chapter.getSubjectId());
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

    /**
     * 看板"最近学习动态"(管理员视角,跨用户的最近完成事件)
     * <p>
     * 返回 N 条,按 learned_at 倒序,带 lessonTitle / chapterNo / subjectName
     */
    @Override
    @Cacheable(value = "learningBoard", key = "'recent:' + #limit")
    public List<Map<String, Object>> recentProgress(int limit) {
        List<LessonProgress> all = progressRepository.findTop100ByLearnedAtNotNullOrderByLearnedAtDesc();
        if (all.isEmpty()) return List.of();

        // 批量拉 lesson / user / chapter 减少 N+1
        Set<Long> lessonIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (LessonProgress p : all) {
            lessonIds.add(p.getLessonId());
            userIds.add(p.getUserId());
        }
        Map<Long, LearningLesson> lessons = lessonRepository.findAllById(lessonIds).stream()
            .collect(Collectors.toMap(LearningLesson::getId, l -> l));
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, u -> u));

        // 还需要 chapter 拿 subjectId (subjectName 用于前端展示)
        Set<Long> chapterIds = lessons.values().stream().map(LearningLesson::getChapterId).collect(Collectors.toSet());
        Map<Long, LearningChapter> chapters = chapterRepository.findAllById(chapterIds).stream()
            .collect(Collectors.toMap(LearningChapter::getId, c -> c));
        Set<Long> subjectIds = chapters.values().stream().map(LearningChapter::getSubjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> subjectNames = subjectRepository.findAllById(subjectIds).stream()
            .collect(Collectors.toMap(LearningSubject::getId, LearningSubject::getName));

        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (LessonProgress p : all) {
            if (n >= limit) break;
            if (p.getLearnedAt() == null) continue;
            LearningLesson l = lessons.get(p.getLessonId());
            if (l == null) continue;
            LearningChapter c = chapters.get(l.getChapterId());
            User u = users.get(p.getUserId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", p.getUserId());
            item.put("username", u == null ? null : u.getUsername());
            item.put("lessonId", l.getId());
            item.put("lessonTitle", l.getTitle());
            item.put("chapterNo", c == null ? null : c.getChapterNo());
            item.put("subjectId", c == null ? null : c.getSubjectId());
            item.put("subjectName", c == null ? null : subjectNames.getOrDefault(c.getSubjectId(), null));
            item.put("learnedAt", p.getLearnedAt());
            out.add(item);
            n++;
        }
        return out;
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
                LearningChapter c = chapterMap.get(l.getChapterId());
                if (c == null) return;   // 归档章节/其他科目不在时间线展示
                LearningVO.RecentLearnedVO r = new LearningVO.RecentLearnedVO();
                r.lessonId = l.getId();
                r.lessonTitle = l.getTitle();
                r.chapterTitle = c.getTitle();
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

        // 限定到本次统计范围的进度(避免全表加载);按用户分组
        Map<Long, List<LessonProgress>> progressByUser;
        if (scopeLessonIds.isEmpty()) {
            progressByUser = Map.of();
        } else {
            progressByUser = progressRepository.findByLessonIdIn(scopeLessonIds).stream()
                .collect(Collectors.groupingBy(LessonProgress::getUserId));
        }

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

    @Override
    public String getPreferredLocale(Long userId) {
        return preferenceRepository.findByUserId(userId)
            .map(UserPreference::getLocale)
            .orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePreferredLocale(Long userId, String locale) {
        if (locale != null && !locale.isBlank() && !com.learning.springboot.common.I18nConstants.SUPPORTED_LOCALES.contains(locale)) {
            throw new IllegalArgumentException("不支持的界面语言: " + locale + " (支持: " + com.learning.springboot.common.I18nConstants.SUPPORTED_LOCALES + ")");
        }
        UserPreference pref = preferenceRepository.findByUserId(userId)
            .orElseGet(() -> new UserPreference(userId, null));
        pref.setLocale(locale);
        preferenceRepository.save(pref);
        log.info("用户 {} 界面语言更新为 {}", userId, locale);
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

    /**
     * 章节是否对当前用户锁定(prerequisites 中有未完成的章节)
     * <p>
     * 与 getCurriculum 的 locked 计算语义保持一致:
     *   - 无 prerequisites → 解锁
     *   - prerequisites 是该 chapter 本身的 id → 忽略(防自引用死锁)
     *   - prerequisites 章节下所有课时都已完成 → 解锁
     *   - 否则 → 锁定
     */
    private boolean isChapterLocked(Long chapterId, Long userId) {
        LearningChapter chapter = chapterRepository.findById(chapterId).orElse(null);
        if (chapter == null) return false;
        String raw = chapter.getPrerequisites();
        if (raw == null || raw.isBlank()) return false;

        List<Long> prereqIds;
        try {
            prereqIds = objectMapper.readValue(raw, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("解析 prerequisites 失败, chapterId={}, raw={}", chapterId, raw);
            return false;
        }
        if (prereqIds == null || prereqIds.isEmpty()) return false;

        Set<Long> learnedIds = findLearnedIds(userId);
        // 学完 = prereq 章节下所有课时都已完成
        for (Long prereqId : prereqIds) {
            if (prereqId.equals(chapterId)) continue;  // 自引用忽略
            List<LearningLesson> lessons = lessonRepository.findByChapterIdOrderByLessonNoAsc(prereqId);
            if (lessons.isEmpty()) continue;            // 空章节视为已完成(避免死锁)
            for (LearningLesson l : lessons) {
                if (!learnedIds.contains(l.getId())) return true;
            }
        }
        return false;
    }

    /** 科目筛选下的章节(subjectId 为空=全部);归档章节不进入课程树与统计 */
    private List<LearningChapter> chaptersOf(Long subjectId) {
        List<LearningChapter> list = subjectId == null
            ? chapterRepository.findAllByOrderByChapterNoAsc()
            : chapterRepository.findBySubjectIdOrderByChapterNoAsc(subjectId);
        return list.stream()
            .filter(c -> !Boolean.TRUE.equals(c.getArchived()))
            .collect(Collectors.toList());
    }

    /** 科目 id -> 该科目全部课时 id(不含归档章节) */
    private Map<Long, Set<Long>> lessonIdsBySubject() {
        Map<Long, Set<Long>> map = new HashMap<>();
        for (LearningChapter c : chapterRepository.findAll()) {
            if (c.getSubjectId() == null || Boolean.TRUE.equals(c.getArchived())) continue;
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
