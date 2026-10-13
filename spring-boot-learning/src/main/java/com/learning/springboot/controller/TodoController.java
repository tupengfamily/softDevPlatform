package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.Todo;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.PersonalCenterService;
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
import java.util.Map;

/**
 * 我的待办 API(个人任务/学习计划提醒,仅本人可见)
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    @Autowired private PersonalCenterService personalCenterService;

    /** 待办列表: status 为空查全部,0=待办,1=已完成 */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Long userId,
                                  @RequestParam(required = false) Integer status) {
        List<Todo> data = personalCenterService.listTodos(userId, status);
        return Result.ok(data);
    }

    /** 新增待办 */
    @PostMapping
    @OperationLog(module = "个人中心", action = "新增待办")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        Todo data = personalCenterService.createTodo(userId,
            str(body.get("title")), intOrNull(body.get("priority")), str(body.get("dueDate")));
        return Result.created("待办已添加", data);
    }

    /** 编辑待办(可同时改状态: 0=待办 1=完成) */
    @PutMapping("/{id}")
    @OperationLog(module = "个人中心", action = "编辑待办")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @CurrentUser Long userId) {
        Todo data = personalCenterService.updateTodo(userId, id,
            str(body.get("title")), intOrNull(body.get("priority")), str(body.get("dueDate")), intOrNull(body.get("status")));
        return Result.ok("已更新", data);
    }

    /** 删除待办 */
    @DeleteMapping("/{id}")
    @OperationLog(module = "个人中心", action = "删除待办")
    public ResponseEntity<?> delete(@PathVariable Long id, @CurrentUser Long userId) {
        personalCenterService.deleteTodo(userId, id);
        return Result.ok("已删除", null);
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
