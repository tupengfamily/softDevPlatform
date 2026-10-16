package com.learning.springboot.service;

import com.learning.springboot.entity.SystemSetting;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统 KV 配置读写
 * <p>
 * 默认值在 {@link #DEFAULTS} 集中维护, 启动时如果库里没记录则自动落盘;
 * 这样管理员第一次调之前系统也有合理兜底, 不会 NPE。
 */
@Service
public class SystemSettingService {

    /** 默认值: key → { defaultValue, description } */
    public static final Map<String, Object[]> DEFAULTS = new LinkedHashMap<>();
    static {
        // 个人文档空间上限(字节)。默认 500 MB。管理员可改(区间 50 MB ~ 5 GB)。
        DEFAULTS.put("myDoc.maxQuotaBytes", new Object[]{
                500L * 1024 * 1024,
                "个人文档空间上限(字节), 默认 500MB; 管理员可改(范围 50MB~5GB)"
        });
        // 单文件上限(字节)。默认 20 MB。
        DEFAULTS.put("myDoc.maxFileBytes", new Object[]{
                20L * 1024 * 1024,
                "个人文档单文件上限(字节), 默认 20MB"
        });
        // 个人文档文件数上限。默认 300。
        DEFAULTS.put("myDoc.maxFileCount", new Object[]{
                300,
                "个人文档文件数上限"
        });
    }

    /** 配额合法区间: 50MB ~ 5GB */
    public static final long MIN_QUOTA = 50L * 1024 * 1024;
    public static final long MAX_QUOTA = 5L * 1024L * 1024 * 1024;

    private final SystemSettingRepository repo;

    public SystemSettingService(SystemSettingRepository repo) {
        this.repo = repo;
    }

    /** 启动时把缺失的默认项写入 DB */
    @PostConstruct
    public void seedDefaults() {
        for (Map.Entry<String, Object[]> e : DEFAULTS.entrySet()) {
            if (!repo.existsById(e.getKey())) {
                repo.save(new SystemSetting(e.getKey(), String.valueOf(e.getValue()[0]), (String) e.getValue()[1]));
            }
        }
    }

    /** 取 long, 缺值或解析失败走默认 */
    public long getLong(String key, long fallback) {
        SystemSetting s = repo.findById(key).orElse(null);
        if (s == null || s.getValue() == null) return fallback;
        try {
            return Long.parseLong(s.getValue().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 配额(默认 500MB) */
    public long getMyDocQuotaBytes() {
        return getLong("myDoc.maxQuotaBytes", (Long) DEFAULTS.get("myDoc.maxQuotaBytes")[0]);
    }

    /** 单文件上限(默认 20MB) */
    public long getMyDocMaxFileBytes() {
        return getLong("myDoc.maxFileBytes", (Long) DEFAULTS.get("myDoc.maxFileBytes")[0]);
    }

    /** 文件数上限(默认 300) */
    public int getMyDocMaxFileCount() {
        return (int) getLong("myDoc.maxFileCount", (Integer) DEFAULTS.get("myDoc.maxFileCount")[0]);
    }

    /** 列出所有设置(供管理员面板) */
    public List<SystemSetting> listAll() {
        // 顺序保证: 默认值在先, 库中其它键在末尾
        LinkedHashMap<String, SystemSetting> map = new LinkedHashMap<>();
        for (SystemSetting s : repo.findAll()) map.put(s.getKey(), s);
        // 用 LinkedHashMap 保留 DEFAULTS 顺序
        LinkedHashMap<String, SystemSetting> ordered = new LinkedHashMap<>();
        for (String key : DEFAULTS.keySet()) {
            ordered.put(key, map.get(key));
        }
        // 把可能存在但未在 DEFAULTS 里的项补到末尾
        for (SystemSetting s : map.values()) {
            ordered.putIfAbsent(s.getKey(), s);
        }
        return new java.util.ArrayList<>(ordered.values());
    }

    /** 管理员写入 */
    @Transactional
    public SystemSetting upsert(String key, String value) {
        if (key == null || key.isEmpty()) throw new BusinessException("key 不能为空");
        if (value == null) value = "";
        SystemSetting s = repo.findById(key).orElse(new SystemSetting(key, value, null));
        s.setValue(value);
        // 校验(只校验已知配额键)
        if ("myDoc.maxQuotaBytes".equals(key)) {
            long v;
            try { v = Long.parseLong(value.trim()); } catch (NumberFormatException ex) {
                throw new BusinessException("myDoc.maxQuotaBytes 必须是整数");
            }
            if (v < MIN_QUOTA || v > MAX_QUOTA) {
                throw new BusinessException("个人文档配额需在 50MB ~ 5GB 之间(当前 " + v + ")");
            }
            s.setValue(String.valueOf(v));
        } else if ("myDoc.maxFileBytes".equals(key)) {
            long v;
            try { v = Long.parseLong(value.trim()); } catch (NumberFormatException ex) {
                throw new BusinessException("myDoc.maxFileBytes 必须是整数");
            }
            if (v < 1024 * 1024 || v > 100L * 1024 * 1024) {
                throw new BusinessException("单文件上限需在 1MB ~ 100MB 之间");
            }
            s.setValue(String.valueOf(v));
        } else if ("myDoc.maxFileCount".equals(key)) {
            int v;
            try { v = Integer.parseInt(value.trim()); } catch (NumberFormatException ex) {
                throw new BusinessException("myDoc.maxFileCount 必须是整数");
            }
            if (v < 1 || v > 5000) {
                throw new BusinessException("文件数上限需在 1 ~ 5000 之间");
            }
            s.setValue(String.valueOf(v));
        }
        return repo.save(s);
    }

    /** 普通用户读: 仅返回公开的几个键 + 不含敏感描述(目前没有敏感, 全量返) */
    public Map<String, Object> publicView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("myDoc.maxQuotaBytes", getMyDocQuotaBytes());
        m.put("myDoc.maxFileBytes", getMyDocMaxFileBytes());
        m.put("myDoc.maxFileCount", getMyDocMaxFileCount());
        return m;
    }
}
