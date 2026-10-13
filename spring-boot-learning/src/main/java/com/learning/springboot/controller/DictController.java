package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.DictItemDTO;
import com.learning.springboot.dto.DictTypeDTO;
import com.learning.springboot.service.DictService;
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
 * 数据字典 API
 * <p>
 * 读接口(/types、/items、/map)开放给所有已登录用户与游客——业务页消费标签文案;
 * 写接口仍限管理员({@link RequireAdmin} 标在方法上)。
 */
@RestController
@RequestMapping("/api/dicts")
public class DictController {

    @Autowired private DictService dictService;

    // ========== 类型 ==========

    @GetMapping("/types")
    public ResponseEntity<?> listTypes() {
        return Result.ok(dictService.listTypes());
    }

    /** 全量启用字典: typeCode -> [{itemCode,itemValue}],业务页一次性取回并本地缓存 */
    @GetMapping("/map")
    public ResponseEntity<?> mapAll() {
        return Result.ok(dictService.mapAllEnabled());
    }

    @RequireAdmin("数据字典仅管理员可维护")
    @PostMapping("/types")
    public ResponseEntity<?> createType(@RequestBody Map<String, String> body) {
        DictTypeDTO dto = dictService.createType(
            body.get("typeCode"), body.get("typeName"), body.get("description"));
        return Result.created("创建成功", dto);
    }

    @RequireAdmin("数据字典仅管理员可维护")
    @PutMapping("/types/{id}")
    public ResponseEntity<?> updateType(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok("更新成功", dictService.updateType(
            id,
            (String) body.get("typeName"),
            (String) body.get("description"),
            body.get("status") == null ? null : ((Number) body.get("status")).intValue()));
    }

    @RequireAdmin("数据字典仅管理员可维护")
    @DeleteMapping("/types/{id}")
    public ResponseEntity<?> deleteType(@PathVariable Long id) {
        dictService.deleteType(id);
        return Result.ok("删除成功", null);
    }

    // ========== 项 ==========

    @GetMapping("/items")
    public ResponseEntity<?> listItems(@RequestParam String typeCode) {
        List<DictItemDTO> items = dictService.listItemsByType(typeCode);
        return Result.ok(items);
    }

    @RequireAdmin("数据字典仅管理员可维护")
    @PostMapping("/items")
    public ResponseEntity<?> createItem(@RequestBody Map<String, Object> body) {
        Long typeId = ((Number) body.get("typeId")).longValue();
        String itemCode = (String) body.get("itemCode");
        String itemValue = (String) body.get("itemValue");
        String itemValueEn = (String) body.get("itemValueEn");
        Integer sort = body.get("sort") == null ? 0 : ((Number) body.get("sort")).intValue();
        String remark = (String) body.get("remark");
        return Result.created("创建成功", dictService.createItem(typeId, itemCode, itemValue, itemValueEn, sort, remark));
    }

    @RequireAdmin("数据字典仅管理员可维护")
    @PutMapping("/items/{id}")
    public ResponseEntity<?> updateItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok("更新成功", dictService.updateItem(
            id,
            (String) body.get("itemValue"),
            (String) body.get("itemValueEn"),
            body.get("sort") == null ? null : ((Number) body.get("sort")).intValue(),
            body.get("status") == null ? null : ((Number) body.get("status")).intValue(),
            (String) body.get("remark")));
    }

    @RequireAdmin("数据字典仅管理员可维护")
    @DeleteMapping("/items/{id}")
    public ResponseEntity<?> deleteItem(@PathVariable Long id) {
        dictService.deleteItem(id);
        return Result.ok("删除成功", null);
    }
}