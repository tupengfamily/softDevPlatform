package com.learning.springboot.service.impl;

import com.learning.springboot.dto.LearningVO;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.entity.User;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.service.LearningService;
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
 * - 课程树一次查询内存组装(课时总量小,无需分页)
 * - 进度通过 Set<Long> learnedIds 判定,避免 N+1 查询
 * - 标记/取消操作幂等:重复调用不报错、不产生脏数据
 */
@Service
public class LearningServiceImpl implements LearningService {

    private static final Logger log = LoggerFactory.getLogger(LearningServiceImpl.class);

    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private UserRepository userRepository;

    // ==================== 课程树 ====================

    @Override
    public List<LearningVO.StageVO> getCurriculum(Long userId) {
        // 1. 查出当前用户已学课时 id 集合
        Set<Long> learnedIds = findLearnedIds(userId);

        // 2. 组装 阶段 -> 章节 -> 课时 树
        Map<Integer, LearningVO.StageVO> stageMap = new LinkedHashMap<>();
        for (LearningChapter chapter : chapterRepository.findAllByOrderByChapterNoAsc()) {
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
        // 阶段按 stageNo 排序返回(chapterNo 与 stageNo 解耦后,章节追加不影响阶段顺序)
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
        vo.stageNo = chapter.getStageNo();
        vo.stageName = chapter.getStageName();
        vo.chapterNo = chapter.getChapterNo();
        vo.chapterTitle = chapter.getTitle();
        vo.lessonNo = lesson.getLessonNo();
        vo.title = lesson.getTitle();
        vo.summary = lesson.getSummary();
        vo.content = lesson.getContent();
        vo.codeExample = lesson.getCodeExample();
        vo.difficulty = lesson.getDifficulty();
        vo.durationMinutes = lesson.getDurationMinutes();
        vo.learned = progressRepository.existsByUserIdAndLessonId(userId, lessonId);

        // 前后课时导航:按 "章节 chapterNo + 课时 lessonNo" 的全局学习序列定位
        // (不能按 chapterId:增量导入的新章 chapterId 靠后,但学习顺序应按 chapterNo)
        List<LearningLesson> ordered = lessonRepository.findAll();
        List<LearningChapter> navChapters = chapterRepository.findAll();
        Map<Long, Integer> chapterNoById = new HashMap<>();
        for (LearningChapter c : navChapters) {
            chapterNoById.put(c.getId(), c.getChapterNo());
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
        if (!lessonRepository.existsById(lessonId)) {
            throw new NoSuchElementException("课时不存在: " + lessonId);
        }
        if (progressRepository.existsByUserIdAndLessonId(userId, lessonId)) {
            log.debug("课时 {} 已被用户 {} 标记过,幂等返回", lessonId, userId);
            return;
        }
        progressRepository.save(new LessonProgress(userId, lessonId));
        log.info("用户 {} 完成课时 {}", userId, lessonId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unmarkLearned(Long lessonId, Long userId) {
        progressRepository.deleteByUserIdAndLessonId(userId, lessonId);
        log.info("用户 {} 取消课时 {} 的学习标记", userId, lessonId);
    }

    // ==================== 进度统计 ====================

    @Override
    public LearningVO.ProgressStatsVO getProgressStats(Long userId) {
        LearningVO.ProgressStatsVO stats = new LearningVO.ProgressStatsVO();

        // 总体
        List<LearningLesson> allLessons = lessonRepository.findAllByOrderByChapterIdAscLessonNoAsc();
        List<LearningChapter> allChapters = chapterRepository.findAllByOrderByChapterNoAsc();
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
        stats.totalLessons = allLessons.size();

        // 已学课时 + 分阶段统计
        Set<Long> learnedIds = findLearnedIds(userId);
        for (LearningLesson lesson : allLessons) {
            LearningVO.StageProgressVO stage =
                stageMap.get(chapterMap.get(lesson.getChapterId()).getStageNo());
            stage.total++;
            if (learnedIds.contains(lesson.getId())) stage.learned++;
        }
        stats.learnedLessons = allLessons.stream()
            .filter(l -> learnedIds.contains(l.getId()))
            .count();
        stats.percent = percent(stats.learnedLessons, stats.totalLessons);
        for (LearningVO.StageProgressVO s : stageMap.values()) {
            s.percent = percent(s.learned, s.total);
            stats.stages.add(s);
        }

        // 最近学习时间线(最近 5 条)
        Map<Long, LearningLesson> lessonMap = new LinkedHashMap<>();
        for (LearningLesson l : allLessons) lessonMap.put(l.getId(), l);
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

    // ==================== 学习看板(管理员/个人) ====================

    /**
     * 数据范围在服务端收口:
     * - ADMIN: 全部用户(含零进度),用于教学管理看板
     * - 其他角色: 仅当前用户一条,保证"普通用户只能看到自己相关的内容"
     */
    @Override
    @Transactional(readOnly = true)
    public LearningVO.BoardOverviewVO getBoardOverview(Long userId, Set<String> roles) {
        boolean admin = roles != null && roles.contains("ADMIN");
        long totalLessons = lessonRepository.count();

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
        List<LearningVO.BoardRowVO> rows = new ArrayList<>();
        for (User u : users) {
            List<LessonProgress> list = progressByUser.getOrDefault(u.getId(), List.of());
            LearningVO.BoardRowVO row = new LearningVO.BoardRowVO();
            row.userId = u.getId();
            row.username = u.getUsername();
            row.email = u.getEmail();
            row.roles = String.join(",", u.getRoleCodes());
            row.learnedLessons = list.size();
            row.totalLessons = totalLessons;
            row.percent = percent(list.size(), totalLessons);
            row.lastLearnedAt = list.stream()
                .map(LessonProgress::getLearnedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(fmt::format)
                .orElse(null);
            row.self = u.getId().equals(userId);
            rows.add(row);
        }
        // 完成度降序,同分按用户名
        rows.sort(Comparator.comparingInt((LearningVO.BoardRowVO r) -> r.percent).reversed()
            .thenComparing(r -> r.username == null ? "" : r.username));

        LearningVO.BoardOverviewVO vo = new LearningVO.BoardOverviewVO();
        vo.scope = admin ? "ALL" : "SELF";
        vo.totalUsers = rows.size();
        vo.activeUsers = (int) rows.stream().filter(r -> r.learnedLessons > 0).count();
        vo.totalLessons = totalLessons;
        vo.avgPercent = rows.isEmpty() ? 0
            : (int) Math.round(rows.stream().mapToInt(r -> r.percent).average().orElse(0));
        vo.maxPercent = rows.stream().mapToInt(r -> r.percent).max().orElse(0);
        vo.rows = rows;
        log.debug("学习看板查询: userId={}, scope={}, rows={}", userId, vo.scope, rows.size());
        return vo;
    }

    // ==================== 私有工具 ====================

    /** 当前用户已学课时 id 集合 */
    private Set<Long> findLearnedIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        for (LessonProgress p : progressRepository.findByUserId(userId)) {
            ids.add(p.getLessonId());
        }
        return ids;
    }

    /** 安全计算百分比 */
    private int percent(long part, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(part * 100.0 / total);
    }
}
