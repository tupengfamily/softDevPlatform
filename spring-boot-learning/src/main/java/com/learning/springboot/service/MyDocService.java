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
 * 个人文档服务: 目录树 + 文件(Office/Markdown/RichText/Image/PDF/Text/Code)增删改查 + 在线编辑模型读写 + 导入导出。
 * <p>
 * 设计要点:
 * <ul>
 *   <li>二进制落盘、元数据入库(SQLite 不吃大 BLOB)</li>
 *   <li>所有查询强制带 userId, 天然的用户隔离</li>
 *   <li>文件名只入库不落盘(UUID 落盘), 名称做长度/非法字符/同名去重处理</li>
 *   <li>配额/单文件上限/文件数上限 走 {@link SystemSettingService}, 管理员可调</li>
 *   <li>Office 重建式保存前自动留历史版本</li>
 * </ul>
 */
@Service
public class MyDocService {

    /** 名称长度上限 */
    private static final int MAX_NAME_LEN = 100;

    private final MyDocDirectoryRepository dirRepo;
    private final MyDocFileRepository fileRepo;
    private final MyDocStorage storage;
    private final OfficeDocService office;
    private final SystemSettingService settings;

    public MyDocService(MyDocDirectoryRepository dirRepo, MyDocFileRepository fileRepo,
                        MyDocStorage storage, OfficeDocService office, SystemSettingService settings) {
        this.dirRepo = dirRepo;
        this.fileRepo = fileRepo;
        this.storage = storage;
        this.office = office;
        this.settings = settings;
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
        // 配额 / 上限 (用系统设置)
        long quotaBytes = settings.getMyDocQuotaBytes();
        m.put("maxQuotaBytes", quotaBytes);
        m.put("maxQuotaText", humanSize(quotaBytes));
        m.put("remainingBytes", Math.max(0, quotaBytes - bytes));
        m.put("remainingText", humanSize(Math.max(0, quotaBytes - bytes)));
        m.put("wordCount", byType.getOrDefault(MyDocType.WORD.code(), 0));
        m.put("excelCount", byType.getOrDefault(MyDocType.EXCEL.code(), 0));
        m.put("pptCount", byType.getOrDefault(MyDocType.PPT.code(), 0));
        m.put("markdownCount", byType.getOrDefault(MyDocType.MARKDOWN.code(), 0));
        m.put("richtextCount", byType.getOrDefault(MyDocType.RICHTEXT.code(), 0));
        m.put("imageCount", byType.getOrDefault(MyDocType.IMAGE.code(), 0));
        m.put("pdfCount", byType.getOrDefault(MyDocType.PDF.code(), 0));
        m.put("textCount", byType.getOrDefault(MyDocType.TEXT.code(), 0));
        m.put("codeCount", byType.getOrDefault(MyDocType.CODE.code(), 0));
        m.put("maxNameLen", MAX_NAME_LEN);
        m.put("maxFileBytes", settings.getMyDocMaxFileBytes());
        m.put("maxFileCount", settings.getMyDocMaxFileCount());
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

    /**
     * 新建空白文档: name + docType + directoryId
     * <ul>
     *   <li>Office(word/excel/ppt): 后端生成空白模板</li>
     *   <li>Markdown / RichText: 后端写入 "Untitled" 占位文本</li>
     *   <li>其它类型: 抛业务异常(应该走导入, 不要走"新建空白")</li>
     * </ul>
     */
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

        byte[] bytes;
        switch (type.mode()) {
            case OFFICE -> bytes = office.createBlank(type);
            case MD_RT -> bytes = defaultMdOrRt(type).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> throw new BusinessException("该类型不支持新建空白文档, 请用「导入」上传文件");
        }
        storage.write(userId, f.getStorageKey(), bytes);
        f.setSizeBytes((long) bytes.length);
        return fileRepo.save(f);
    }

    /**
     * 导入上传文件: 支持 Office 三件套 + 图片 + PDF + Markdown + HTML + 纯文本 + 代码片段
     * <p>
     * 规则:
     * <ul>
     *   <li>扩展名 → 推断 docType</li>
     *   <li>Office 必须能解析(否则抛业务异常)</li>
     *   <li>其它类型: 不做内容校验, 落盘即可(图片/PDF 等二进制无法轻量校验)</li>
     * </ul>
     */
    @Transactional
    public MyDocFile importFile(Long userId, String originalFilename, byte[] bytes, Long directoryId) {
        if (bytes == null || bytes.length == 0) throw new BusinessException("文件内容为空");
        long maxFile = settings.getMyDocMaxFileBytes();
        if (bytes.length > maxFile) {
            throw new BusinessException("文件过大, 单个文件最大 " + humanSize(maxFile));
        }
        String filename = originalFilename == null ? "" : originalFilename.trim();
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        MyDocType type = MyDocType.ofExt(ext);

        // 仅对 Office 三件套做内容校验(其它二进制无法轻量校验)
        if (type.mode() == MyDocType.Mode.OFFICE) {
            office.validate(type, bytes);
        }
        checkQuota(userId, bytes.length);

        String base = stripExt(filename);
        if (base.isEmpty()) base = defaultName(type);
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
        Map<String, Object> model = new LinkedHashMap<>();
        switch (type.mode()) {
            case OFFICE -> {
                if (type == MyDocType.WORD) {
                    model = office.parse(type, bytes, storage.imageDir(f.getId()), storage.imageUrlBase(f.getId()));
                } else {
                    model = office.parse(type, bytes, null, null);
                }
            }
            case MD_RT -> {
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                model.put("content", text);
            }
            case PREVIEW_ONLY -> {
                // 预览类文件不提供编辑模型(只读)
                model.put("readonly", true);
                model.put("size", bytes.length);
            }
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
        // 仅 Office 三件套重建式保存才需要留历史版本(其它类型原字节回写, 无损)
        if (type.mode() == MyDocType.Mode.OFFICE) {
            storage.snapshot(userId, f, current);
        }

        byte[] bytes;
        if (type.mode() == MyDocType.Mode.MD_RT) {
            // Markdown / RichText: model.content 是纯文本 / HTML
            Object content = model == null ? null : model.get("content");
            String text = content == null ? "" : String.valueOf(content);
            // 限制大小: 单文件 20MB(留余量)
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > settings.getMyDocMaxFileBytes()) {
                throw new BusinessException("保存后内容过大, 超出单文件上限 " + humanSize(settings.getMyDocMaxFileBytes()));
            }
            bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            bytes = office.save(type, current == null ? new byte[0] : current, model, storage.uploadRoot());
        }

        if (bytes == null || bytes.length == 0) throw new BusinessException("保存失败: 生成的文件为空");
        long maxFile = settings.getMyDocMaxFileBytes();
        if (bytes.length > maxFile) {
            throw new BusinessException("保存后文件过大(" + humanSize(bytes.length) + "), 超出单文件上限 " + humanSize(maxFile));
        }
        // 配额检查(替换后是否会超)
        long currentBytes = f.getSizeBytes() == null ? 0 : f.getSizeBytes();
        long newBytes = bytes.length;
        long quota = settings.getMyDocQuotaBytes();
        long otherBytes = 0;
        for (MyDocFile other : fileRepo.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            if (other.getId().equals(f.getId())) continue;
            otherBytes += other.getSizeBytes() == null ? 0 : other.getSizeBytes();
        }
        if (otherBytes + newBytes > quota) {
            throw new BusinessException("保存后占用空间将超出个人配额(已用 " + humanSize(otherBytes + newBytes) + ", 上限 " + humanSize(quota) + ")");
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
    //  历史版本(只对 Office 有意义)
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
        // 仅剥离已知扩展名, 避免误剥(例如 "1.0" 中的点)
        Set<String> known = new HashSet<>(Set.of("docx", "xlsx", "pptx", "md", "html", "htm", "pdf",
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
                "txt", "log", "csv", "json", "xml", "yml", "yaml",
                "java", "py", "js", "ts", "jsx", "tsx", "vue", "css", "scss", "less",
                "sh", "bash", "sql", "go", "rs", "c", "cpp", "h", "hpp", "kt", "rb", "php", "swift", "dart",
                "ini", "conf", "properties"));
        if (!known.contains(ext)) return name;
        return name.substring(0, dot).trim();
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

    /**
     * 配额/文件数上限校验
     * <p>
     * 配置来自 {@link SystemSettingService}: 管理员可在 t_system_setting 中调整
     */
    private void checkQuota(Long userId, long extraBytes) {
        long count = fileRepo.countByUserId(userId);
        int maxCount = settings.getMyDocMaxFileCount();
        if (count >= maxCount) {
            throw new BusinessException("文档数量已达上限(" + maxCount + " 个), 请先清理");
        }
        long used = 0;
        for (MyDocFile f : fileRepo.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            used += f.getSizeBytes() == null ? 0 : f.getSizeBytes();
        }
        long quota = settings.getMyDocQuotaBytes();
        if (used + extraBytes > quota) {
            throw new BusinessException("个人文档空间不足(已用 " + humanSize(used)
                    + ", 拟添加 " + humanSize(extraBytes)
                    + ", 上限 " + humanSize(quota) + ")。如需更多空间请联系管理员。");
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

    private static String defaultMdOrRt(MyDocType type) {
        if (type == MyDocType.MARKDOWN) {
            return "# 新建 Markdown\n\n开始书写…\n";
        }
        if (type == MyDocType.RICHTEXT) {
            return "<h1>新建富文本</h1>\n<p>开始书写…</p>\n";
        }
        return "";
    }

    private static String defaultName(MyDocType type) {
        return switch (type) {
            case IMAGE -> "图片";
            case PDF -> "PDF 文档";
            case MARKDOWN -> "Markdown";
            case RICHTEXT -> "富文本";
            case HTML -> "HTML";
            case TEXT -> "文本";
            case CODE -> "代码片段";
            default -> "导入文档";
        };
    }
}
