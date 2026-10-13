package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 数据字典项实体
 * <p>
 * 一个 DictType 下的具体键值对,如 user_status: 1=启用, 0=禁用。
 * 通过 ManyToOne 关联到 DictType。
 */
@Entity
@Table(name = "t_dict_item")
public class DictItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 字典类型编码冗余字段(便于查询) */
    @Column(name = "type_code", nullable = false, length = 50)
    private String typeCode;

    /** 项编码(在同一 typeCode 下唯一): 1、0、M、F */
    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    /** 项值(显示值): 启用、禁用、男、女 */
    @Column(name = "item_value", nullable = false, length = 100)
    private String itemValue;

    /** 项值(英文,国际化用): Enabled、Disabled;为空时前端回退中文值 */
    @Column(name = "item_value_en", length = 100)
    private String itemValueEn;

    @Column(nullable = false)
    private Integer sort = 0;

    /** 1=启用,0=禁用 */
    @Column(nullable = false)
    private Integer status = 1;

    @Column(length = 200)
    private String remark;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id")
    private DictType type;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public DictItem() {}

    public DictItem(String typeCode, String itemCode, String itemValue, Integer sort) {
        this.typeCode = typeCode;
        this.itemCode = itemCode;
        this.itemValue = itemValue;
        this.sort = sort;
    }

    @jakarta.persistence.PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
        if (this.status == null) this.status = 1;
        if (this.sort == null) this.sort = 0;
    }

    @jakarta.persistence.PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public DictType getType() { return type; }
    public void setType(DictType type) { this.type = type; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}