package com.learning.springboot.service;

import com.learning.springboot.dto.LearningAdminVO;
import com.learning.springboot.dto.LearningVO;

import java.util.List;

/**
 * 学习配置 Service(管理员)— 科目(语言)与课程结构(阶段/章节)的维护
 * <p>
 * 通用学习平台的学习菜单由管理员在此配置:
 * - 科目(语言): 增删改、排序、启停(如 Java / Python / JavaScript)
 * - 章节: 增删改、调整阶段归属与序号(学习中心目录随之变化)
 */
public interface SubjectAdminService {

    // ==================== 科目(语言) ====================

    /** 全部科目(含停用),按排序号 */
    List<LearningVO.SubjectVO> listSubjects();

    /** 新增科目 */
    LearningVO.SubjectVO createSubject(LearningAdminVO.SubjectUpsertRequest req);

    /** 编辑科目 */
    LearningVO.SubjectVO updateSubject(Long id, LearningAdminVO.SubjectUpsertRequest req);

    /** 删除科目(内置科目/仍有章节的科目不允许删除) */
    void deleteSubject(Long id);

    // ==================== 章节(学习菜单结构) ====================

    /** 某科目下的全部章节(subjectId 可空=全部),含课时数 */
    List<LearningAdminVO.ChapterAdminVO> listChapters(Long subjectId);

    /** 新增章节 */
    LearningAdminVO.ChapterAdminVO createChapter(LearningAdminVO.ChapterUpsertRequest req);

    /** 编辑章节(含阶段调整/重排序) */
    LearningAdminVO.ChapterAdminVO updateChapter(Long id, LearningAdminVO.ChapterUpsertRequest req);

    /** 删除章节(级联删除课时与学习进度) */
    void deleteChapter(Long id);
}
