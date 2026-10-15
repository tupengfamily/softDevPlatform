package com.learning.springboot.service;

import com.learning.springboot.entity.MyDocDirectory;
import com.learning.springboot.entity.MyDocFile;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.MyDocDirectoryRepository;
import com.learning.springboot.repository.MyDocFileRepository;
import com.learning.springboot.service.doc.MyDocStorage;
import com.learning.springboot.service.doc.MyDocType;
import com.learning.springboot.service.doc.OfficeDocService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 个人文档服务: 目录树 + 文件(Word/Excel/PPT)的增删改查 + 在线编辑模型读写 + 导入导出。
 * <p>
 * 设计要点:
 * <ul>
 *   <li>二进制落盘、元数据入库(SQLite 不吃大 BLOB)</li>
 *   <li>所有查询强制带 userId, 天然的用户隔离</li>
 *   <li>文件名只入库不落盘(UUID 落盘), 名称做长度/非法字符/同名去重处理</li>
 *   <li>保存前自动留历史版本(Word 是重建式保存, 需要可回退)</li>
 * </ul>
 */
@Service
public class MyDocService {

    /** 单用户文件数上限 */
    private static final int MAX_FILES_PER_USER = 300;
    /** 单用户占用空间上限 200MB */
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    /** 名称长度上限 */
    private static final int MAX_NAME_LEN = 100;

    private final MyDocDirectoryRepository dirRepo;
    private final MyDocFileRepository fileRepo;
    private final MyDocStorage storage;
    private final OfficeDocService office;

    public MyDocService(MyDocDirectoryRepository dirRepo, MyDocFileRepository fileRepo,
                        MyDocStorage storage, OfficeDocService office) {
        this.dirRepo = dirRepo;
        this.fileRepo = fileRepo;
        this.storage = storage;
        this.office = office;
    }

    // ======================================================================
    //  目录树
    // ======================================================================

    /** 目录树(平铺 + parentId 供前端组树, 目录内文件一并返回) */
    public List<Map<String, Object>> tree(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MyDocDirectory d : dirRepo.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", d.getId());
            node.put("name", d.getName());
            node.put("parentId", d.getParentId());
            node.put("sortNo", d.getSortNo());
            node.put("createTime", d.getCreateTime());
            node.put("files", fileVOList(userId, d.getId()));
            out.add(node);
        }
        return out;
    }

    /** 根目录文件(directoryId 为空) */
    public List<Map<String, Object>> rootFiles(Long userId) {
        return fileVOList(userId, null);
    }

    /** 统计(供页面顶部展示) */
    public Map<String, Object> stats(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<MyDocFile> all = fileRepo.findByUserIdOrderBySortNoAscIdAsc(userId);
        long bytes = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (MyDocFile f : all) {
            bytes += f.getSizeBytes() == null ? 0 : f.getSizeBytes();
            byType.merge(f.getDocType(), 1, Integer::sum);
        }
        m.put("dirCount", dirRepo.countByUserId(userId));
        m.put("fileCount", all.size());
        m.put("totalBytes", bytes);
        m.put("totalText", humanSize(bytes));
        m.put("wordCount", byType.getOrDefault(MyDocType.WORD.code(), 0));
        m.put("excelCount", byType.getOrDefault(MyDocType.EXCEL.code(), 0));
        m.put("pptCount", byType.getOrDefault(MyDocType.PPT.code(), 0));
        m.put("maxNameLen", MAX_NAME_LEN);
        m.put("maxFileBytes", MyDocStorage.MAX_FILE_BYTES);
        return m;
    }

    @Transactional
    public MyDocDirectory createDirectory(Long userId, String name, Long parentId) {
        String clean = cleanName(name, "目录名称");
        MyDocDirectory d = new MyDocDirectory();
        d.setUserId(userId);
        d.setName(uniqueDirName(userId, parentId, clean, null));
        if (parentId != null) {
            MyDocDirectory parent = dirRepo.findByIdAndUserId(parentId, userId)
                    .orElseThrow(() -> new BusinessException("父目录不存在"));
            d.setParentId(parent.getId());
        }
        d.setSortNo(nextSort(userId, d.getParentId()));
        return dirRepo.save(d);
    }

    @Transactional
    public MyDocDirectory renameDirectory(Long userId, Long id, String name) {
        MyDocDirectory d = dirRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException("目录不存在"));
        String clean = cleanName(name, "目录名称");
        d.setName(uniqueDirName(userId, d.getParentId(), clean, d.getId()));
        return dirRepo.save(d);
    }

    /** 删除目录: 非空拒绝(与个人 Wiki 的语义保持一致, 避免误删) */
    @Transactional
    public void deleteDirectory(Long userId, Long id) {
        MyDocDirectory d = dirRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException("目录不存在"));
        Deque<Long> stack = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        stack.push(d.getId());
        visited.add(d.getId());
        int subDirs = 0;
        int files = 0;
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            List<MyDocDirectory> children = childDirs(userId, cur);
            subDirs += children.size();
            for (MyDocDirectory c : children) {
                if (visited.add(c.getId())) stack.push(c.getId());
            }
            files += (int) fileRepo.countByDirectoryId(cur);
        }
        if (subDirs > 0 || files > 0) {
            throw new BusinessException("目录非空, 无法删除(子目录 " + subDirs + " 个, 文件 " + files + " 个), 请先清空");
        }
        dirRepo.delete(d);
    }

    /** 目录移动到新父目录(parentId 为空 = 顶层), 带成环检测 */
    @Transactional
    public MyDocDirectory moveDirectory(Long userId, Long id, Long parentId) {
        MyDocDirectory d = dirRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException("目录不存在"));
        if (parentId != null) {
            if (parentId.equals(id)) throw new BusinessException("不能把目录移动到自身下");
            MyDocDirectory parent = dirRepo.findByIdAndUserId(parentId, userId)
                    .orElseThrow(() -> new BusinessException("目标目录不存在"));
            Set<Long> visited = new HashSet<>();
            MyDocDirectory cur = parent;
            while (cur != null) {
                if (cur.getId().equals(id)) throw new BusinessException("不能把目录移动到自己的子目录下");
                if (!visited.add(cur.getId())) break;
                Long pid = cur.getParentId();
                cur = pid == null ? null : dirRepo.findByIdAndUserId(pid, userId).orElse(null);
            }
            d.setParentId(parent.getId());
        } else {
            d.setParentId(null);
        }
        d.setName(uniqueDirName(userId, d.getParentId(), d.getName(), d.getId()));
        d.setSortNo(nextSort(userId, d.getParentId()));
        return dirRepo.save(d);
    }

    // ======================================================================
    //  文件
    // ======================================================================

    public List<Map<String, Object>> listFiles(Long userId, Long directoryId) {
        return fileVOList(userId, directoryId);
    }

    /** 新建空白文档(由后端生成模板文件, 保证是合法 Office 文件) */
    @Transactional
    public MyDocFile createFile(Long userId, String name, String docTypeCode, Long directoryId) {
        MyDocType type = MyDocType.ofCode(docTypeCode);
        checkQuota(userId, 0);
        String clean = name == null || name.trim().isEmpty()
                ? "新建" + type.label()
                : stripExt(cleanName(name, "文档名称"));
        MyDocFile f = new MyDocFile();
        f.setUserId(userId);
        f.setDirectoryId(resolveDir(userId, directoryId));
        f.setDocType(type.code());
        f.setExt(type.ext());
        f.setName(uniqueFileName(userId, f.getDirectoryId(), clean, type.ext(), null));
        f.setStorageKey(newKey(type.ext()));
        f.setSortNo(nextSortFile(userId, f.getDirectoryId()));
        f.setSizeBytes(0L);
        f.setVersion(1);

        byte[] bytes = office.createBlank(type);
        storage.write(userId, f.getStorageKey(), bytes);
        f.setSizeBytes((long) bytes.length);
        return fileRepo.save(f);
    }

    /** 导入(上传)Office 文件 */
    @Transactional
    public MyDocFile importFile(Long userId, String originalFilename, byte[] bytes, Long directoryId) {
        if (bytes == null || bytes.length == 0) throw new BusinessException("文件内容为空");
        if (bytes.length > MyDocStorage.MAX_FILE_BYTES) {
            throw new BusinessException("文件过大, 单个文件最大 " + humanSize(MyDocStorage.MAX_FILE_BYTES));
        }
        String filename = originalFilename == null ? "" : originalFilename.trim();
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        MyDocType type = MyDocType.ofExt(ext);
        // 内容校验: 必须是能解析的真实 Office 文件
        office.validate(type, bytes);
        checkQuota(userId, bytes.length);

        String base = stripExt(filename);
        if (base.isEmpty()) base = "导入文档";
        MyDocFile f = new MyDocFile();
        f.setUserId(userId);
        f.setDirectoryId(resolveDir(userId, directoryId));
        f.setDocType(type.code());
        f.setExt(type.ext());
        f.setName(uniqueFileName(userId, f.getDirectoryId(), cleanName(base, "文档名称"), type.ext(), null));
        f.setStorageKey(newKey(type.ext()));
        f.setSortNo(nextSortFile(userId, f.getDirectoryId()));
        f.setSizeBytes((long) bytes.length);
        f.setVersion(1);
        storage.write(userId, f.getStorageKey(), bytes);
        return fileRepo.save(f);
    }

    @Transactional
    public MyDocFile renameFile(Long userId, Long id, String name) {
        MyDocFile f = own(userId, id);
        String clean = stripExt(cleanName(name, "文档名称"));
        f.setName(uniqueFileName(userId, f.getDirectoryId(), clean, f.getExt(), f.getId()));
        return fileRepo.save(f);
    }

    @Transactional
    public MyDocFile moveFile(Long userId, Long id, Long directoryId) {
        MyDocFile f = own(userId, id);
        f.setDirectoryId(resolveDir(userId, directoryId));
        f.setName(uniqueFileName(userId, f.getDirectoryId(), f.getName(), f.getExt(), f.getId()));
        f.setSortNo(nextSortFile(userId, f.getDirectoryId()));
        return fileRepo.save(f);
    }

    @Transactional
    public void deleteFile(Long userId, Long id) {
        MyDocFile f = own(userId, id);
        fileRepo.delete(f);
        storage.purge(userId, f);
    }

    /** 复制一份(含文件本体) */
    @Transactional
    public MyDocFile copyFile(Long userId, Long id, Long directoryId) {
        MyDocFile src = own(userId, id);
        byte[] bytes = storage.read(userId, src.getStorageKey());
        checkQuota(userId, bytes.length);
        MyDocFile f = new MyDocFile();
        f.setUserId(userId);
        f.setDirectoryId(resolveDir(userId, directoryId));
        f.setDocType(src.getDocType());
        f.setExt(src.getExt());
        f.setName(uniqueFileName(userId, f.getDirectoryId(), src.getName() + " 副本", src.getExt(), null));
        f.setStorageKey(newKey(src.getExt()));
        f.setSortNo(nextSortFile(userId, f.getDirectoryId()));
        f.setSizeBytes((long) bytes.length);
        f.setVersion(1);
        storage.write(userId, f.getStorageKey(), bytes);
        return fileRepo.save(f);
    }

    /** 导出(下载)用字节 */
    public byte[] download(Long userId, Long id) {
        MyDocFile f = own(userId, id);
        return storage.read(userId, f.getStorageKey());
    }

    public MyDocFile own(Long userId, Long id) {
        return fileRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "文档不存在或无权访问"));
    }

    // ======================================================================
    //  在线编辑: 读 / 写 模型
    // ======================================================================

    /** 读取编辑器模型 */
    public Map<String, Object> readModel(Long userId, Long id) {
        MyDocFile f = own(userId, id);
        MyDocType type = MyDocType.ofCode(f.getDocType());
        byte[] bytes = storage.read(userId, f.getStorageKey());
        Map<String, Object> model;
        if (type == MyDocType.WORD) {
            model = office.parse(type, bytes, storage.imageDir(f.getId()), storage.imageUrlBase(f.getId()));
        } else {
            model = office.parse(type, bytes, null, null);
        }
        model.put("fileId", f.getId());
        model.put("name", f.getName());
        model.put("version", f.getVersion());
        model.put("updateTime", f.getUpdateTime());
        return model;
    }

    /** 保存编辑器模型: 先留历史版本, 再回写文件 */
    @Transactional
    public Map<String, String> saveModel(Long userId, Long id, Map<String, Object> model) {
        MyDocFile f = own(userId, id);
        MyDocType type = MyDocType.ofCode(f.getDocType());
        byte[] current = null;
        try {
            current = storage.read(userId, f.getStorageKey());
        } catch (BusinessException ignore) {
            // 文件缺失(磁盘被清理)时按模型直接重建
        }
        storage.snapshot(userId, f, current);

        byte[] bytes = office.save(type, current == null ? new byte[0] : current, model, storage.uploadRoot());
        if (bytes == null || bytes.length == 0) throw new BusinessException("保存失败: 生成的文件为空");
        if (bytes.length > MyDocStorage.MAX_FILE_BYTES) {
            // 超限时回滚本次留档写入的空文件风险: 直接抛错, 原文件未被改动
            throw new BusinessException("保存后文件过大(" + humanSize(bytes.length) + "), 超出单文件上限");
        }
        storage.write(userId, f.getStorageKey(), bytes);
        f.setSizeBytes((long) bytes.length);
        f.setVersion((f.getVersion() == null ? 1 : f.getVersion()) + 1);
        fileRepo.save(f);

        Map<String, String> res = new LinkedHashMap<>();
        res.put("savedAt", f.getUpdateTime() == null ? "" : f.getUpdateTime().toString());
        res.put("version", String.valueOf(f.getVersion()));
        res.put("sizeText", humanSize(bytes.length));
        return res;
    }

    // ======================================================================
    //  历史版本
    // ======================================================================

    public List<Map<String, Object>> versions(Long userId, Long id) {
        own(userId, id);
        return storage.listVersions(userId, id);
    }

    public byte[] downloadVersion(Long userId, Long id, String name) {
        own(userId, id);
        return storage.readVersion(userId, id, name);
    }

    @Transactional
    public void restoreVersion(Long userId, Long id, String name) {
        MyDocFile f = own(userId, id);
        storage.restore(userId, f, name);
        try {
            f.setSizeBytes((long) storage.read(userId, f.getStorageKey()).length);
        } catch (BusinessException ignore) {
            // 忽略
        }
        f.setVersion((f.getVersion() == null ? 1 : f.getVersion()) + 1);
        fileRepo.save(f);
    }

    // ======================================================================
    //  内部工具
    // ======================================================================

    private List<Map<String, Object>> fileVOList(Long userId, Long directoryId) {
        List<MyDocFile> list = directoryId == null
                ? fileRepo.findByUserIdAndDirectoryIdIsNullOrderBySortNoAscIdAsc(userId)
                : fileRepo.findByUserIdAndDirectoryIdOrderBySortNoAscIdAsc(userId, directoryId);
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (MyDocFile f : list) out.add(fileVO(f));
        return out;
    }

    private Map<String, Object> fileVO(MyDocFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("fullName", f.getName() + "." + f.getExt());
        m.put("ext", f.getExt());
        m.put("docType", f.getDocType());
        m.put("directoryId", f.getDirectoryId());
        m.put("sizeBytes", f.getSizeBytes());
        m.put("sizeText", humanSize(f.getSizeBytes() == null ? 0 : f.getSizeBytes()));
        m.put("version", f.getVersion());
        m.put("sortNo", f.getSortNo());
        m.put("createTime", f.getCreateTime());
        m.put("updateTime", f.getUpdateTime());
        return m;
    }

    private List<MyDocDirectory> childDirs(Long userId, Long parentId) {
        return parentId == null
                ? dirRepo.findByUserIdAndParentIdIsNullOrderBySortNoAscIdAsc(userId)
                : dirRepo.findByUserIdAndParentIdOrderBySortNoAscIdAsc(userId, parentId);
    }

    /** 校验并归一化目录(不存在则报错; null 表示根目录) */
    private Long resolveDir(Long userId, Long directoryId) {
        if (directoryId == null) return null;
        MyDocDirectory d = dirRepo.findByIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new BusinessException("目标目录不存在"));
        return d.getId();
    }

    private int nextSort(Long userId, Long parentId) {
        return childDirs(userId, parentId).stream()
                .map(MyDocDirectory::getSortNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 10;
    }

    private int nextSortFile(Long userId, Long directoryId) {
        List<MyDocFile> list = directoryId == null
                ? fileRepo.findByUserIdAndDirectoryIdIsNullOrderBySortNoAscIdAsc(userId)
                : fileRepo.findByUserIdAndDirectoryIdOrderBySortNoAscIdAsc(userId, directoryId);
        return list.stream()
                .map(MyDocFile::getSortNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 10;
    }

    /** 名称清洗: 去首尾空白 + 去非法字符 + 限长 */
    private String cleanName(String name, String what) {
        if (name == null) throw new BusinessException(what + "不能为空");
        String n = name.trim()
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (n.isEmpty()) throw new BusinessException(what + "不能为空");
        if (n.length() > MAX_NAME_LEN) n = n.substring(0, MAX_NAME_LEN);
        return n;
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (MyDocType t : MyDocType.values()) {
            if (t.ext().equals(ext)) return name.substring(0, dot).trim();
        }
        return name;
    }

    /** 同目录目录重名 -> "名称 (2)" */
    private String uniqueDirName(Long userId, Long parentId, String name, Long selfId) {
        List<MyDocDirectory> siblings = childDirs(userId, parentId);
        Set<String> taken = new HashSet<>();
        for (MyDocDirectory d : siblings) {
            if (selfId != null && selfId.equals(d.getId())) continue;
            taken.add(d.getName().toLowerCase(Locale.ROOT));
        }
        return unique(name, taken);
    }

    /** 同目录文件重名 -> "名称 (2)" */
    private String uniqueFileName(Long userId, Long directoryId, String name, String ext, Long selfId) {
        List<MyDocFile> siblings = directoryId == null
                ? fileRepo.findByUserIdAndDirectoryIdIsNullOrderBySortNoAscIdAsc(userId)
                : fileRepo.findByUserIdAndDirectoryIdOrderBySortNoAscIdAsc(userId, directoryId);
        Set<String> taken = new HashSet<>();
        for (MyDocFile f : siblings) {
            if (selfId != null && selfId.equals(f.getId())) continue;
            taken.add(f.getName().toLowerCase(Locale.ROOT));
        }
        return unique(name, taken);
    }

    private String unique(String name, Set<String> taken) {
        String base = name;
        if (!taken.contains(base.toLowerCase(Locale.ROOT))) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return base + " " + System.currentTimeMillis();
    }

    private void checkQuota(Long userId, long extraBytes) {
        long count = fileRepo.countByUserId(userId);
        if (count >= MAX_FILES_PER_USER) {
            throw new BusinessException("文档数量已达上限(" + MAX_FILES_PER_USER + " 个), 请先清理");
        }
        long used = 0;
        for (MyDocFile f : fileRepo.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            used += f.getSizeBytes() == null ? 0 : f.getSizeBytes();
        }
        if (used + extraBytes > MAX_TOTAL_BYTES) {
            throw new BusinessException("个人文档空间不足(已用 " + humanSize(used)
                    + ", 上限 " + humanSize(MAX_TOTAL_BYTES) + ")");
        }
    }

    private String newKey(String ext) {
        return UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
