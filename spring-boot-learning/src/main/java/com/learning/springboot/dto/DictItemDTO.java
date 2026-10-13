package com.learning.springboot.dto;

import com.learning.springboot.entity.DictItem;

import java.time.LocalDateTime;

/**
 * 字典项 DTO
 */
public class DictItemDTO {

    private Long id;
    private Long typeId;
    private String typeCode;
    private String itemCode;
    private String itemValue;
    private String itemValueEn;
    private Integer sort;
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static DictItemDTO from(DictItem i) {
        DictItemDTO d = new DictItemDTO();
        d.id = i.getId();
        d.typeId = i.getType() != null ? i.getType().getId() : null;
        d.typeCode = i.getTypeCode();
        d.itemCode = i.getItemCode();
        d.itemValue = i.getItemValue();
        d.itemValueEn = i.getItemValueEn();
        d.sort = i.getSort();
        d.status = i.getStatus();
        d.remark = i.getRemark();
        d.createTime = i.getCreateTime();
        d.updateTime = i.getUpdateTime();
        return d;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }
    public String getTypeCode() { return typeCode; }
    public void setTypeCode(String typeCode) { this.typeCode = typeCode; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public String getItemValue() { return itemValue; }
    public void setItemValue(String itemValue) { this.itemValue = itemValue; }
    public String getItemValueEn() { return itemValueEn; }
    public void setItemValueEn(String itemValueEn) { this.itemValueEn = itemValueEn; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}