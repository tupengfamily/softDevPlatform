package com.learning.springboot.service;

import com.learning.springboot.dto.DictItemDTO;
import com.learning.springboot.dto.DictTypeDTO;

import java.util.List;
import java.util.Map;

/**
 * 字典服务接口
 */
public interface DictService {

    // 类型
    List<DictTypeDTO> listTypes();
    DictTypeDTO createType(String typeCode, String typeName, String description);
    DictTypeDTO updateType(Long id, String typeName, String description, Integer status);
    void deleteType(Long id);

    // 项
    List<DictItemDTO> listItemsByType(String typeCode);
    DictItemDTO createItem(Long typeId, String itemCode, String itemValue, String itemValueEn, Integer sort, String remark);
    DictItemDTO updateItem(Long id, String itemValue, String itemValueEn, Integer sort, Integer status, String remark);
    void deleteItem(Long id);

    /**
     * 全量启用字典: typeCode -> [{itemCode, itemValue, itemValueEn}...]
     * 供业务页一次性取回标签映射(前端缓存,避免逐类型请求);英文值为空时前端回退中文值
     */
    Map<String, List<Map<String, String>>> mapAllEnabled();
}