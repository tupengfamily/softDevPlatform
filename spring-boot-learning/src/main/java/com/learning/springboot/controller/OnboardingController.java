package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.entity.User;
import com.learning.springboot.entity.UserPreference;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
import com.learning.springboot.repository.UserPreferenceRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新手指引 API(3 步向导: 目标 → 水平 → 语言)
 * <p>
 * - 扩展 t_user_preference 字段 (goal / level / onboardedAt / onboardingSkipped)
 * - 首次登录前端检测 onboarded_at = null → 推 /onboarding
 * - 用户可在 ProfileSettings「重做新手指引」调 /reset 重开
 * <p>
 * 推荐路径: 根据 goal + level + 偏好语言, 给出 3-5 个起步课程
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    @Autowired private UserRepository userRepository;
    @Autowired private UserPreferenceRepository preferenceRepository;
    @Autowired private LearningSubjectRepository subjectRepository;
    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;

    /** 1. 获取当前用户的引导状态 */
    @GetMapping("/me")
    public ResponseEntity<?> me(@CurrentUser Long userId) {
        UserPreference pref = loadOrCreate(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal", pref.getGoal());
        data.put("level", pref.getLevel());
        data.put("preferredSubjectId", pref.getPreferredSubjectId());
        data.put("onboardedAt", pref.getOnboardedAt());
        data.put("skipped", Boolean.TRUE.equals(pref.getOnboardingSkipped()));
        // 派生字段: 是否首次
        data.put("needsOnboarding", pref.getOnboardedAt() == null && !Boolean.TRUE.equals(pref.getOnboardingSkipped()));
        return Result.ok(data);
    }

    /** 2. 提交引导结果(完成) */
    @PostMapping("/finish")
    @Transactional
    public ResponseEntity<?> finish(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        String goal = str(body.get("goal"));
        String level = str(body.get("level"));
        Long preferredSubjectId = body.get("preferredSubjectId") instanceof Number n ? n.longValue() : null;
        validateGoal(goal);
        validateLevel(level);

        UserPreference pref = loadOrCreate(userId);
        pref.setGoal(goal);
        pref.setLevel(level);
        if (preferredSubjectId != null) {
            // 校验学科存在 + enabled
            LearningSubject sub = subjectRepository.findById(preferredSubjectId)
                .orElseThrow(() -> new BusinessException(400, "所选语言不存在"));
            if (!Boolean.TRUE.equals(sub.getEnabled())) {
                throw new BusinessException(400, "所选语言已停用, 请重新选择");
            }
            pref.setPreferredSubjectId(preferredSubjectId);
        }
        pref.setOnboardedAt(LocalDateTime.now());
        pref.setOnboardingSkipped(false);
        preferenceRepository.save(pref);

        // 返回推荐路径
        return Result.ok("引导完成", buildRecommendation(userId, pref));
    }

    /** 3. 跳过引导(标记为已处理, 之后不再弹) */
    @PostMapping("/skip")
    @Transactional
    public ResponseEntity<?> skip(@CurrentUser Long userId) {
        UserPreference pref = loadOrCreate(userId);
        pref.setOnboardingSkipped(true);
        pref.setOnboardedAt(LocalDateTime.now());
        preferenceRepository.save(pref);
        return Result.ok("已跳过引导", null);
    }

    /** 4. 重置引导(让用户重做 3 步) */
    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<?> reset(@CurrentUser Long userId) {
        UserPreference pref = loadOrCreate(userId);
        pref.setOnboardedAt(null);
        pref.setOnboardingSkipped(false);
        pref.setGoal(null);
        pref.setLevel(null);
        // 不清 preferredSubjectId, 用户可能想保留之前的语言偏好
        preferenceRepository.save(pref);
        return Result.ok("已重置, 重新进入引导", null);
    }

    /** 5. 单独获取推荐路径(用于 Dashboard "接下来学什么" 卡片) */
    @GetMapping("/recommend")
    public ResponseEntity<?> recommend(@CurrentUser Long userId) {
        UserPreference pref = loadOrCreate(userId);
        return Result.ok(buildRecommendation(userId, pref));
    }

    // ==================== 私有 ====================

    private UserPreference loadOrCreate(Long userId) {
        return preferenceRepository.findByUserId(userId).orElseGet(() -> {
            UserPreference p = new UserPreference();
            p.setUserId(userId);
            return preferenceRepository.save(p);
        });
    }

    private void validateGoal(String goal) {
        if (goal == null || !goal.matches("JOB|INTEREST|EXAM|PROJECT")) {
            throw new BusinessException(400, "goal 必须是 JOB/INTEREST/EXAM/PROJECT 之一");
        }
    }

    private void validateLevel(String level) {
        if (level == null || !level.matches("BEGINNER|HAS_OTHER|HAS_JS|INTERMEDIATE")) {
            throw new BusinessException(400, "level 必须是 BEGINNER/HAS_OTHER/HAS_JS/INTERMEDIATE 之一");
        }
    }

    /**
     * 推荐路径:
     * - BEGINNER + JOB: java 或 python (就业热门)
     * - HAS_OTHER + JOB: web 或 nodejs (已有语言基础, 拓展后端)
     * - HAS_JS + INTEREST: jsadv (进阶)
     * - INTERMEDIATE: 偏好语言
     * - 其他: 偏好语言的第 1 阶段前 3 课
     */
    private Map<String, Object> buildRecommendation(Long userId, UserPreference pref) {
        Map<String, Object> out = new LinkedHashMap<>();
        String goal = pref.getGoal();
        String level = pref.getLevel();
        Long subjectId = pref.getPreferredSubjectId();

        // 1. 推荐学科
        LearningSubject subject = null;
        if (subjectId != null) {
            subject = subjectRepository.findById(subjectId).orElse(null);
        }
        if (subject == null) {
            // fallback: 根据 goal + level 选默认学科
            String fallbackCode = pickFallbackSubject(goal, level);
            if (fallbackCode != null) {
                subject = subjectRepository.findAll().stream()
                    .filter(s -> fallbackCode.equals(s.getCode()))
                    .findFirst().orElse(null);
            }
        }
        if (subject == null) {
            subject = subjectRepository.findAll().stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .findFirst().orElse(null);
        }
        out.put("recommendedSubject", subject == null ? null : Map.of(
            "id", subject.getId(), "code", subject.getCode(), "name", subject.getName()
        ));

        // 2. 推荐课程: 该学科 stage 1 前 3 课
        List<Map<String, Object>> recommended = new ArrayList<>();
        if (subject != null) {
            List<LearningChapter> chapters = chapterRepository.findBySubjectIdOrderByChapterNoAsc(subject.getId());
            int cnt = 0;
            for (LearningChapter ch : chapters) {
                if (cnt >= 3) break;
                List<LearningLesson> lessons = lessonRepository.findByChapterIdOrderByLessonNoAsc(ch.getId());
                if (!lessons.isEmpty()) {
                    LearningLesson first = lessons.get(0);
                    recommended.add(Map.of(
                        "chapterId", ch.getId(),
                        "chapterNo", ch.getChapterNo(),
                        "chapterTitle", ch.getTitle(),
                        "lessonId", first.getId(),
                        "lessonNo", first.getLessonNo(),
                        "lessonTitle", first.getTitle(),
                        "summary", first.getSummary() == null ? "" : first.getSummary(),
                        "durationMinutes", first.getDurationMinutes() == null ? 0 : first.getDurationMinutes()
                    ));
                    cnt++;
                }
            }
        }
        out.put("recommendedLessons", recommended);
        out.put("hint", buildHint(goal, level));
        return out;
    }

    private String pickFallbackSubject(String goal, String level) {
        if (level == null || "BEGINNER".equals(level)) {
            if ("JOB".equals(goal) || "EXAM".equals(goal)) return "java";
            if ("INTEREST".equals(goal)) return "python";
            if ("PROJECT".equals(goal)) return "web";
        }
        if ("HAS_OTHER".equals(level)) {
            if ("JOB".equals(goal)) return "web";
            return "sql";
        }
        if ("HAS_JS".equals(level) || "INTERMEDIATE".equals(level)) {
            return "jsadv";
        }
        return "java";
    }

    private String buildHint(String goal, String level) {
        if (goal == null) return null;
        StringBuilder sb = new StringBuilder();
        if ("JOB".equals(goal)) sb.append("求职导向: 建议从基础到项目实战, 关注每一章的练习。");
        else if ("INTEREST".equals(goal)) sb.append("兴趣驱动: 自由探索, 推荐先选一门感兴趣的语言入门。");
        else if ("EXAM".equals(goal)) sb.append("备考模式: 建议按章节顺序学习, 完成每章练习。");
        else if ("PROJECT".equals(goal)) sb.append("项目实战: 学完基础后, 直接进入项目驱动学习。");
        if ("BEGINNER".equals(level)) sb.append(" 零基础友好, 不必担心, 慢慢来。");
        else if ("HAS_OTHER".equals(level)) sb.append(" 有其他语言基础, 重点关注语法差异。");
        else if ("HAS_JS".equals(level)) sb.append(" 已掌握 JavaScript, 建议直接进入 TypeScript 与进阶。");
        else if ("INTERMEDIATE".equals(level)) sb.append(" 中级水平, 可跳读熟悉的部分, 聚焦实战。");
        return sb.toString();
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
