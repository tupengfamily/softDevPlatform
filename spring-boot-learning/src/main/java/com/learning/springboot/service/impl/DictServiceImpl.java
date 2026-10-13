package com.learning.springboot.service.impl;

import com.learning.springboot.dto.DictItemDTO;
import com.learning.springboot.dto.DictTypeDTO;
import com.learning.springboot.entity.DictItem;
import com.learning.springboot.entity.DictType;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.DictItemRepository;
import com.learning.springboot.repository.DictTypeRepository;
import com.learning.springboot.service.DictService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DictServiceImpl implements DictService {

    @Autowired private DictTypeRepository dictTypeRepository;
    @Autowired private DictItemRepository dictItemRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DictTypeDTO> listTypes() {
        return dictTypeRepository.findAll().stream().map(DictTypeDTO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DictTypeDTO createType(String typeCode, String typeName, String description) {
        if (dictTypeRepository.existsByTypeCode(typeCode)) {
            throw new BusinessException(409, "字典类型编码已存在: " + typeCode);
        }
        DictType t = new DictType(typeCode, typeName, description);
        return DictTypeDTO.from(dictTypeRepository.save(t));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DictTypeDTO updateType(Long id, String typeName, String description, Integer status) {
        DictType t = dictTypeRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "字典类型不存在"));
        if (typeName != null) t.setTypeName(typeName);
        if (description != null) t.setDescription(description);
        if (status != null) t.setStatus(status);
        return DictTypeDTO.from(dictTypeRepository.save(t));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteType(Long id) {
        if (!dictTypeRepository.existsById(id)) {
            throw new BusinessException(404, "字典类型不存在");
        }
        // 先删除该类型下的所有项
        DictType t = dictTypeRepository.findById(id).get();
        dictItemRepository.deleteByTypeCode(t.getTypeCode());
        dictTypeRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DictItemDTO> listItemsByType(String typeCode) {
        return dictItemRepository.findByTypeCodeOrderBySortAsc(typeCode)
            .stream().map(DictItemDTO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DictItemDTO createItem(Long typeId, String itemCode, String itemValue, String itemValueEn, Integer sort, String remark) {
        DictType t = dictTypeRepository.findById(typeId)
            .orElseThrow(() -> new BusinessException(404, "字典类型不存在"));
        DictItem item = new DictItem(t.getTypeCode(), itemCode, itemValue, sort);
        item.setType(t);
        item.setItemValueEn(itemValueEn);
        item.setRemark(remark);
        return DictItemDTO.from(dictItemRepository.save(item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DictItemDTO updateItem(Long id, String itemValue, String itemValueEn, Integer sort, Integer status, String remark) {
        DictItem item = dictItemRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "字典项不存在"));
        if (itemValue != null) item.setItemValue(itemValue);
        item.setItemValueEn(itemValueEn);   // 允许清空(传 null 即回退用中文值)
        if (sort != null) item.setSort(sort);
        if (status != null) item.setStatus(status);
        if (remark != null) item.setRemark(remark);
        return DictItemDTO.from(dictItemRepository.save(item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long id) {
        if (!dictItemRepository.existsById(id)) {
            throw new BusinessException(404, "字典项不存在");
        }
        dictItemRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<Map<String, String>>> mapAllEnabled() {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (DictType t : dictTypeRepository.findAll()) {
            if (t.getStatus() == null || t.getStatus() != 1) continue;
            List<Map<String, String>> items = dictItemRepository
                .findByTypeCodeAndStatusOrderBySortAsc(t.getTypeCode(), 1)
                .stream()
                .map(i -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("itemCode", i.getItemCode());
                    m.put("itemValue", i.getItemValue());
                    m.put("itemValueEn", i.getItemValueEn() == null ? "" : i.getItemValueEn());
                    return m;
                })
                .toList();
            result.put(t.getTypeCode(), items);
        }
        return result;
    }
}