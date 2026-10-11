package com.learning.springboot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习课程种子 Service(增量 upsert 版)
 * <p>
 * 启动时从 classpath:data/lessons.json 同步课程体系(阶段 -> 章节 -> 课时):
 * - 章节按 chapterNo 匹配:已存在则更新元信息(含 stageNo/stageName),不存在则插入;
 * - 课时按 (chapterId, lessonNo) 匹配:已存在则更新内容,不存在则插入;
 * - 不删除 JSON 中已移除的章节/课时,保证用户学习进度(LessonProgress 关联 lessonId)不丢失。
 */
@Service
public class LearningDataSeedService {

    private static final Logger log = LoggerFactory.getLogger(LearningDataSeedService.class);

    private static final String SEED_FILE = "data/lessons.json";

    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;

    @Transactional(rollbackFor = Exception.class)
    public void seedLearningData() {
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode chapters = mapper.readTree(in).get("chapters");

            // 已有章节按 chapterNo 建索引
            Map<Integer, LearningChapter> existingByNo = new HashMap<>();
            for (LearningChapter c : chapterRepository.findAll()) {
                existingByNo.put(c.getChapterNo(), c);
            }

            int chaptersInserted = 0, chaptersUpdated = 0;
            int lessonsInserted = 0, lessonsUpdated = 0;

            for (JsonNode ch : chapters) {
                int chapterNo = ch.get("chapterNo").asInt();
                LearningChapter chapter = existingByNo.get(chapterNo);
                boolean isNewChapter = (chapter == null);
                if (isNewChapter) {
                    chapter = new LearningChapter();
                }
                chapter.setStageNo(ch.get("stageNo").asInt());
                chapter.setStageName(ch.get("stageName").asText());
                chapter.setChapterNo(chapterNo);
                chapter.setTitle(ch.get("title").asText());
                chapter.setDescription(ch.path("description").asText(null));
                chapter.setCodePath(ch.path("codePath").asText(null));
                chapter.setEstimatedHours(ch.path("estimatedHours").asInt(0));
                LearningChapter saved = chapterRepository.save(chapter);
                if (isNewChapter) chaptersInserted++; else chaptersUpdated++;

                // 该章已有课时按 lessonNo 建索引
                Map<Integer, LearningLesson> existingLessons = new HashMap<>();
                List<LearningLesson> chapterLessons =
                    lessonRepository.findByChapterIdOrderByLessonNoAsc(saved.getId());
                for (LearningLesson l : chapterLessons) {
                    existingLessons.put(l.getLessonNo(), l);
                }

                for (JsonNode ls : ch.get("lessons")) {
                    int lessonNo = ls.get("lessonNo").asInt();
                    LearningLesson lesson = existingLessons.get(lessonNo);
                    boolean isNewLesson = (lesson == null);
                    if (isNewLesson) {
                        lesson = new LearningLesson();
                        lesson.setChapterId(saved.getId());
                        lesson.setLessonNo(lessonNo);
                    }
                    lesson.setTitle(ls.get("title").asText());
                    lesson.setSummary(ls.path("summary").asText(null));
                    lesson.setContent(ls.get("content").asText());
                    lesson.setCodeExample(ls.path("codeExample").asText(null));
                    lesson.setDifficulty(ls.path("difficulty").asInt(1));
                    lesson.setDurationMinutes(ls.path("durationMinutes").asInt(15));
                    lessonRepository.save(lesson);
                    if (isNewLesson) lessonsInserted++; else lessonsUpdated++;
                }
            }
            log.info("✅ 学习课程种子同步完成: 章节 +{}/更新{}, 课时 +{}/更新{}",
                chaptersInserted, chaptersUpdated, lessonsInserted, lessonsUpdated);
        } catch (Exception e) {
            log.error("学习课程种子同步失败", e);
            throw new IllegalStateException("课程种子同步失败: " + e.getMessage(), e);
        }
    }
}
