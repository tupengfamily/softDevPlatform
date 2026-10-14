package com.learning.springboot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.entity.LearningChapter;
import com.learning.springboot.entity.LearningLesson;
import com.learning.springboot.entity.LearningSubject;
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
 * 学习课程种子 Service(增量 upsert 版)
 * <p>
 * 启动时从 classpath:data/lessons.json 同步课程体系(阶段 -> 章节 -> 课时):
 * - 章节按 (subjectId, chapterNo) 匹配:已存在则更新元信息(含 stageNo/stageName),不存在则插入;
 *   章节序号在科目内独立编号,每门课程的章节都从 1 开始;
 * - 课时按 (chapterId, lessonNo) 匹配:已存在则更新内容,不存在则插入;
 * - 不删除 JSON 中已移除的章节/课时,保证用户学习进度(LessonProgress 关联 lessonId)不丢失。
 * <p>
 * 科目(语言)维度: 内置科目 java / python / javascript / sql 由本 Service 保证存在;
 * lessons.json 中的章节按 "subject" 字段(科目 code)归属,缺省 java;"archived": true 的章节下架。
 */
@Service
public class LearningDataSeedService {

    private static final Logger log = LoggerFactory.getLogger(LearningDataSeedService.class);

    private static final String SEED_FILE = "data/lessons.json";

    @Autowired private LearningChapterRepository chapterRepository;
    @Autowired private LearningLessonRepository lessonRepository;
    @Autowired private LearningSubjectRepository subjectRepository;

    @Transactional(rollbackFor = Exception.class)
    public void seedLearningData() {
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode chapters = mapper.readTree(in).get("chapters");

            // 保证内置科目存在;章节按 JSON 的 subject 字段归属科目(缺省 java)
            ensureBuiltinSubjects();

            // 已有章节按 (subjectId, chapterNo) 建索引:章节序号在科目内独立编号(每门课程从 1 开始)
            Map<String, LearningChapter> existingByKey = new HashMap<>();
            for (LearningChapter c : chapterRepository.findAll()) {
                existingByKey.put(c.getSubjectId() + ":" + c.getChapterNo(), c);
            }

            int chaptersInserted = 0, chaptersUpdated = 0;
            int lessonsInserted = 0, lessonsUpdated = 0;

            for (JsonNode ch : chapters) {
                int chapterNo = ch.get("chapterNo").asInt();
                // 章节可指定科目(subject 字段为科目 code),缺省归属 java
                Long subjectId = ensureSubjectForCode(ch.path("subject").asText("java")).getId();
                LearningChapter chapter = existingByKey.get(subjectId + ":" + chapterNo);
                boolean isNewChapter = (chapter == null);
                if (isNewChapter) {
                    chapter = new LearningChapter();
                }
                chapter.setSubjectId(subjectId);
                chapter.setStageNo(ch.get("stageNo").asInt());
                chapter.setStageName(ch.get("stageName").asText());
                chapter.setChapterNo(chapterNo);
                chapter.setTitle(ch.get("title").asText());
                chapter.setDescription(ch.path("description").asText(null));
                chapter.setCodePath(ch.path("codePath").asText(null));
                chapter.setEstimatedHours(ch.path("estimatedHours").asInt(0));
                chapter.setArchived(ch.path("archived").asBoolean(false));
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
                    lesson.setCodeType(ls.path("codeType").asText("java"));
                    lesson.setDifficulty(ls.path("difficulty").asInt(1));
                    lesson.setDurationMinutes(ls.path("durationMinutes").asInt(15));
                    lessonRepository.save(lesson);
                    if (isNewLesson) lessonsInserted++; else lessonsUpdated++;
                }
            }
            // 有课程内容的科目自动启用(避免「课程建设中」的历史禁用状态挡住已有内容的科目)
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (JsonNode ch : chapters) {
                codes.add(ch.path("subject").asText("java"));
            }
            for (String code : codes) {
                subjectRepository.findByCode(code).ifPresent(s -> {
                    if (!Boolean.TRUE.equals(s.getEnabled())) {
                        s.setEnabled(true);
                        subjectRepository.save(s);
                        log.info("科目已启用(检测到课程内容): {} ({})", s.getName(), code);
                    }
                });
            }
            log.info("✅ 学习课程种子同步完成: 章节 +{}/更新{}, 课时 +{}/更新{}",
                chaptersInserted, chaptersUpdated, lessonsInserted, lessonsUpdated);
        } catch (Exception e) {
            log.error("学习课程种子同步失败", e);
            throw new IllegalStateException("课程种子同步失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保证内置科目(语言)存在:java / python / javascript。
     * 平台定位为通用学习平台,Python/JS 等科目的课程内容由管理员后续在学习配置中补充。
     *
     * @return java 科目的 id(课程数据的默认归属)
     */
    private Long ensureBuiltinSubjects() {
        LearningSubject java = ensureSubject("java", "Java", "Java 全栈开发:基础语法 → 进阶 → 并发 → 数据库 → 框架生态", 1);
        ensureSubject("python", "Python", "Python 语言开发:基础语法 → 数据结构 → 函数式 → 面向对象 → 并发网络", 2);
        ensureSubject("javascript", "JavaScript", "JavaScript / 前端开发(课程建设中)", 3, false);
        ensureSubject("nodejs", "Node.js", "Node.js 服务端开发:入门 → 核心模块 → Web 开发 → 工程化实战", 5);
        ensureSubject("git", "Git", "Git 版本控制:基础 → 分支与远程 → 团队协作实战", 6);
        ensureSubject("web", "Web 开发",
            "Web 全栈开发:HTML/CSS → JavaScript → Vue/React → Node 服务端 → 工程化构建 → 项目实战 → 部署上线 → 前沿热点", 7);
        return java.getId();
    }

    private LearningSubject ensureSubject(String code, String name, String description, int sortNo) {
        return ensureSubject(code, name, description, sortNo, true);
    }

    private LearningSubject ensureSubject(String code, String name, String description, int sortNo, boolean enabled) {
        return subjectRepository.findByCode(code).orElseGet(() -> {
            LearningSubject s = new LearningSubject();
            s.setCode(code);
            s.setName(name);
            s.setDescription(description);
            s.setSortNo(sortNo);
            s.setEnabled(enabled);
            s.setBuiltin(true);
            LearningSubject saved = subjectRepository.save(s);
            log.info("内置学习科目初始化: {} ({}{})", name, code, enabled ? "" : ", disabled");
            return saved;
        });
    }

    /** 按 code 取科目,不存在则创建(lessons.json 中章节 subject 字段的归属入口) */
    private LearningSubject ensureSubjectForCode(String code) {
        return switch (code) {
            case "sql" -> ensureSubject("sql", "SQL",
                "SQL 数据库:关系模型 → 查询 → 修改 → 事务 → 索引 → 管理", 4);
            case "python" -> ensureSubject("python", "Python", "Python 语言开发:基础语法 → 数据结构 → 函数式 → 面向对象 → 并发网络", 2);
            case "javascript" -> ensureSubject("javascript", "JavaScript", "JavaScript / 前端开发(课程建设中)", 3, false);
            case "jsadv" -> ensureSubject("jsadv", "JavaScript 进阶",
                "JavaScript 进阶 + TypeScript:现代 JS 核心 → 异步并发 → 函数式 → TS 入门 → TS 进阶 → TS 框架实战", 8);
            case "nodejs" -> ensureSubject("nodejs", "Node.js", "Node.js 服务端开发:入门 → 核心模块 → Web 开发 → 工程化实战", 5);
            case "git" -> ensureSubject("git", "Git", "Git 版本控制:基础 → 分支与远程 → 团队协作实战", 6);
            case "web" -> ensureSubject("web", "Web 开发",
                "Web 全栈开发:HTML/CSS → JavaScript → Vue/React → Node 服务端 → 工程化构建 → 项目实战 → 部署上线 → 前沿热点", 7);
            default -> ensureSubject("java", "Java", "Java 全栈开发:基础语法 → 进阶 → 并发 → 数据库 → 框架生态", 1);
        };
    }
}
