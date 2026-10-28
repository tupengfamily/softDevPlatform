package com.learning.springboot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LearningSubject;
import com.learning.springboot.farm.insight.AgriConcept;
import com.learning.springboot.repository.LearningChapterRepository;
import com.learning.springboot.repository.LearningLessonRepository;
import com.learning.springboot.repository.LearningSubjectRepository;
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
 * 「农业科学」科目课程种子 Service（2026-10-28 P2）。
 *
 * <p><b>为什么单独一个 Service，而不是并进 {@link LearningDataSeedService}</b>：
 * <ul>
 *   <li><b>数据源不同</b>：编程课程在 {@code data/lessons.json}（148 章、98 万字符），
 *       农业课程在 {@code data/agri-lessons.json}（8 章）。合并会让那个文件更难维护。</li>
 *   <li><b>结构不同</b>：编程章节按 {@code (subjectId, chapterNo)} upsert，
 *       农业章节按 <b>概念标识</b> upsert —— 章节号会随概念增删而变，
 *       概念 ID 不会。</li>
 *   <li><b>失败隔离</b>：农业内容写错了，不该连累 148 章编程课程同步失败。</li>
 * </ul>
 *
 * <p><b>🔴 章节按概念 ID upsert 的原因</b>：章节序号（{@code chapterNo}）
 * 在科目内是「1..N」的连续编号 —— 概念增删会让后面的章号全部平移。
 * 如果按章号 upsert，插入一个新概念到中间，后面所有章的内容都会被
 * <b>错位覆盖</b>（第 2 章的标题写到第 3 章上）。
 * 而 {@code t_learning_lesson.concept_id} 是稳定标识，
 * 按它 upsert 天然免疫这种平移。
 *
 * <p><b>🔴 幂等</b>：重复启动不产生重复章节/课时 —— 按概念 ID 建索引 upsert，
 * 与 {@code LearningDataSeedService} 的行为一致。
 */
@Service
public class AgriDataSeedService {

    private static final Logger log = LoggerFactory.getLogger(AgriDataSeedService.class);

    private static final String SEED_FILE = "data/agri-lessons.json";

    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LearningSubjectRepository subjectRepository;

    /**
     * 同步农业科学科目及其 8 章课程。
     *
     * <p>🔴 <b>整个方法体包在一个 try 里</b>：失败时抛 {@code IllegalStateException}，
     * 让启动期就<b>响亮失败</b>而不是静默跳过。
     * 这正是本项目反复踩过的「静默 {@code continue}」陷阱 ——
     * 药剂 id 拼错被悄悄跳过，表现为「功能坏了但没人知道为什么」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void seedAgriData() {
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);

            // ---- ① 科目 ----
            JsonNode subjNode = root.path("subject");
            String code = subjNode.path("code").asText("agri");
            String name = subjNode.path("name").asText("农业科学");
            String desc = subjNode.path("description").asText("");
            int sortNo = subjNode.path("sortNo").asInt(9);
            int stageNo = subjNode.path("stageNo").asInt(1);
            String stageName = subjNode.path("stageName").asText(AgriConcept.STAGE_NAME);

            LearningSubject subject = subjectRepository.findByCode(code).orElseGet(() -> {
                LearningSubject s = new LearningSubject();
                s.setCode(code);
                s.setName(name);
                s.setDescription(desc);
                s.setSortNo(sortNo);
                s.setEnabled(true);
                s.setBuiltin(true);
                log.info("内置学习科目初始化: {} ({})", name, code);
                return s;
            });
            // 元信息每次启动都对齐（改了 JSON 描述能生效）
            subject.setName(name);
            subject.setDescription(desc);
            subject.setSortNo(sortNo);
            // 🔴 有课程内容的科目必须启用 —— 否则前端科目下拉里根本看不到「农业科学」，
            // 而这正是 P2 的验收点之一（「学习看板出现农业科学科目」）。
            subject.setEnabled(true);
            subject.setBuiltin(true);
            LearningSubject savedSubject = subjectRepository.save(subject);
            Long subjectId = savedSubject.getId();

            // ---- ② 章节索引（按概念 ID，不按章号）----
            Map<String, LearningChapter> byConcept = new HashMap<>();
            for (LearningChapter c : chapterRepository.findBySubjectIdOrderByChapterNoAsc(subjectId)) {
                // 概念 ID 存在 codePath 里（章节没有独立的 concept_id 列）
                String conceptId = conceptOfChapter(c);
                if (conceptId != null) {
                    byConcept.put(conceptId, c);
                }
            }

            int chaptersInserted = 0, chaptersUpdated = 0;
            int lessonsInserted = 0, lessonsUpdated = 0;

            for (JsonNode ch : root.path("chapters")) {
                String conceptId = ch.path("conceptId").asText(null);
                // 🔴 启动期响亮失败：概念 ID 拼错或漏写，这里必须炸，
                // 不能悄悄跳过 —— 跳过的后果是「玩家点了农事却学不到东西」，
                // 而且要等到玩家投诉才会被发现。
                if (conceptId == null || conceptId.isBlank()) {
                    throw new IllegalStateException("农业课程章节缺少 conceptId: " + ch.path("title").asText());
                }
                if (AgriConcept.of(conceptId) == null) {
                    throw new IllegalStateException(
                            "农业课程引用了未定义的概念 ID: " + conceptId
                                    + "（章节: " + ch.path("title").asText() + "）");
                }

                LearningChapter chapter = byConcept.get(conceptId);
                boolean isNewChapter = (chapter == null);
                if (isNewChapter) {
                    chapter = new LearningChapter();
                    chapter.setSubjectId(subjectId);
                }
                chapter.setSubjectId(subjectId);
                chapter.setStageNo(stageNo);
                chapter.setStageName(stageName);
                chapter.setChapterNo(ch.path("chapterNo").asInt(0));
                chapter.setTitle(ch.path("title").asText());
                chapter.setDescription(ch.path("description").asText(null));
                // 🔴 概念 ID 存在 codePath 里 —— 章节表没有独立的 concept_id 列，
                // 而 codePath 在前端只对「有值」的章节显示「复制练习代码路径」，
                // 这里存的是概念标识而不是代码路径，所以前端要特判（见 LearningVO 注释）。
                chapter.setCodePath(conceptId);
                chapter.setEstimatedHours(ch.path("estimatedHours").asInt(1));
                chapter.setArchived(false);
                LearningChapter savedChapter = chapterRepository.save(chapter);
                if (isNewChapter) chaptersInserted++; else chaptersUpdated++;

                // 该概念下的已有课时按 lessonNo 建索引
                Map<Integer, LearningLesson> existingLessons = new HashMap<>();
                for (LearningLesson l : lessonRepository
                        .findByChapterIdOrderByLessonNoAsc(savedChapter.getId())) {
                    existingLessons.put(l.getLessonNo(), l);
                }

                for (JsonNode ls : ch.path("lessons")) {
                    int lessonNo = ls.path("lessonNo").asInt(0);
                    LearningLesson lesson = existingLessons.get(lessonNo);
                    boolean isNewLesson = (lesson == null);
                    if (isNewLesson) {
                        lesson = new LearningLesson();
                        lesson.setChapterId(savedChapter.getId());
                        lesson.setLessonNo(lessonNo);
                    }
                    lesson.setTitle(ls.path("title").asText());
                    lesson.setSummary(ls.path("summary").asText(null));
                    lesson.setContent(ls.path("content").asText(""));
                    lesson.setCodeExample(ls.path("codeExample").asText(null));
                    lesson.setCodeType(ls.path("codeType").asText("python"));
                    lesson.setDifficulty(ls.path("difficulty").asInt(1));
                    lesson.setDurationMinutes(ls.path("durationMinutes").asInt(15));
                    // 🔴 关键：概念标识写进课时 —— 这是「实操即课时」的绑定键
                    lesson.setConceptId(conceptId);
                    lessonRepository.save(lesson);
                    if (isNewLesson) lessonsInserted++; else lessonsUpdated++;
                }
            }

            log.info("✅ 农业科学课程同步完成: 科目 {} ({}), 章节 +{}/更新{}, 课时 +{}/更新{}",
                    name, code, chaptersInserted, chaptersUpdated, lessonsInserted, lessonsUpdated);
        } catch (Exception e) {
            log.error("农业科学课程种子同步失败", e);
            throw new IllegalStateException("农业科学课程种子同步失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从章节的 {@code codePath} 里取概念 ID。
     *
     * <p>🔴 <b>为什么概念 ID 寄存在 codePath 而不是给章节表加一列</b>：
     * 加列需要改 {@code LearningChapter} 实体 + 迁移脚本，而章节本来就有
     * 一个「稳定标识」字段且前端对它只有「有值才显示」的判断。
     * 复用它的代价是前端要特判「这是概念 ID 不是代码路径」——
     * 这个特判必须写在<b>唯一出口</b>（{@code LearningVO} 的组装处），
     * 不能散落到多个界面。
     *
     * <p>返回 null 表示「这不是农业章节」或「没有概念标识」。
     */
    private static String conceptOfChapter(LearningChapter c) {
        if (c == null || c.getCodePath() == null || c.getCodePath().isBlank()) {
            return null;
        }
        String v = c.getCodePath().trim();
        // 只有「恰好是已定义概念 ID」的才认 —— 防止把真正的代码路径误判成概念
        return AgriConcept.of(v) != null ? v : null;
    }
}
