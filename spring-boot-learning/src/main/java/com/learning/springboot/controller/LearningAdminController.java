package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.LearningAdminVO;
import com.learning.springboot.dto.LearningVO;
import com.learning.springboot.service.SubjectAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学习配置 API(仅管理员)
 * <p>
 * 通用学习平台的学习菜单由管理员在此配置:
 * - 科目(语言): Java / Python / JavaScript ... 增删改、排序、启停
 * - 课程结构: 章节与阶段的增删改、调序(学习中心目录随之变化)
 */
@RestController
@RequestMapping("/api/learning/admin")
@RequireAdmin
public class LearningAdminController {

    @Autowired private SubjectAdminService subjectAdminService;

    // ==================== 科目(语言) ====================

    /** 全部科目(含停用) */
    @GetMapping("/subjects")
    public ResponseEntity<?> subjects() {
        List<LearningVO.SubjectVO> data = subjectAdminService.listSubjects();
        return Result.ok(data);
    }

    @PostMapping("/subjects")
    @OperationLog(module = "学习配置", action = "新增科目")
    public ResponseEntity<?> createSubject(@RequestBody LearningAdminVO.SubjectUpsertRequest req) {
        return Result.ok("科目已创建", subjectAdminService.createSubject(req));
    }

    @PutMapping("/subjects/{id}")
    @OperationLog(module = "学习配置", action = "编辑科目")
    public ResponseEntity<?> updateSubject(@PathVariable Long id,
                                           @RequestBody LearningAdminVO.SubjectUpsertRequest req) {
        return Result.ok("科目已更新", subjectAdminService.updateSubject(id, req));
    }

    @DeleteMapping("/subjects/{id}")
    @OperationLog(module = "学习配置", action = "删除科目")
    public ResponseEntity<?> deleteSubject(@PathVariable Long id) {
        subjectAdminService.deleteSubject(id);
        return Result.ok("科目已删除");
    }

    // ==================== 章节(学习菜单结构) ====================

    /** 某科目下的章节列表(subjectId 可选=全部) */
    @GetMapping("/chapters")
    public ResponseEntity<?> chapters(@RequestParam(required = false) Long subjectId) {
        List<LearningAdminVO.ChapterAdminVO> data = subjectAdminService.listChapters(subjectId);
        return Result.ok(data);
    }

    @PostMapping("/chapters")
    @OperationLog(module = "学习配置", action = "新增章节")
    public ResponseEntity<?> createChapter(@RequestBody LearningAdminVO.ChapterUpsertRequest req) {
        return Result.ok("章节已创建", subjectAdminService.createChapter(req));
    }

    @PutMapping("/chapters/{id}")
    @OperationLog(module = "学习配置", action = "编辑章节")
    public ResponseEntity<?> updateChapter(@PathVariable Long id,
                                           @RequestBody LearningAdminVO.ChapterUpsertRequest req) {
        return Result.ok("章节已更新", subjectAdminService.updateChapter(id, req));
    }

    @DeleteMapping("/chapters/{id}")
    @OperationLog(module = "学习配置", action = "删除章节")
    public ResponseEntity<?> deleteChapter(@PathVariable Long id) {
        subjectAdminService.deleteChapter(id);
        return Result.ok("章节已删除");
    }
}
