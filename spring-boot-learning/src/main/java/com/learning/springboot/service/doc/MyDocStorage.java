package com.learning.springboot.service.doc;

import com.learning.springboot.entity.MyDocFile;
import com.learning.springboot.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 个人文档磁盘存储
 * <p>
 * 目录结构(全部在 data/ 下, 随 Docker 的 ./data 卷持久化):
 * <pre>
 *   data/my-docs/{userId}/{storageKey}             当前文件本体
 *   data/my-docs/{userId}/.versions/{fileId}/…     保存前的历史快照(最近 N 个)
 *   data/uploads/my-doc/{fileId}/…                 Word 文档内嵌图片(复用 /uploads/** 静态映射)
 * </pre>
 * <p>
 * 安全: 文件名一律用服务端生成的 UUID(storageKey), 用户提供的名称只入库、不落盘,
 * 从根上避免路径注入 / 覆盖 / 中文名乱码。
 */
@Component
public class MyDocStorage {

    /** 单文件体积上限 20MB(与管理端 multipart 上限保持一致) */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** 每个文件保留的历史版本数 */
    public static final int MAX_VERSIONS = 5;

    private static final Path ROOT = Paths.get("data", "my-docs");
    private static final Path UPLOAD_ROOT = Paths.get("data", "uploads");
    private static final String IMAGE_SCOPE = "my-doc";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Pattern STORAGE_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,80}\\.[a-z0-9]{2,6}$");
    private static final Pattern VERSION_NAME = Pattern.compile("^\\d{17}\\.[a-z0-9]{2,6}$");

    /** 当前工作目录(应用启动目录), 用于把 /uploads/xx 还原成绝对路径 */
    public Path projectRoot() {
        return Paths.get("").toAbsolutePath();
    }

    /** /uploads 的磁盘根目录 */
    public Path uploadRoot() {
        return UPLOAD_ROOT;
    }

    private Path userDir(Long userId) {
        return ROOT.resolve(String.valueOf(userId));
    }

    public Path filePath(Long userId, String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new BusinessException("文件定位非法");
        }
        return userDir(userId).resolve(storageKey);
    }

    public byte[] read(Long userId, String storageKey) {
        Path p = filePath(userId, storageKey);
        if (!Files.isReadable(p)) throw new BusinessException("文件不存在或已被清理");
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage());
        }
    }

    public void write(Long userId, String storageKey, byte[] bytes) {
        Path p = filePath(userId, storageKey);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            throw new BusinessException("文件写入失败: " + e.getMessage());
        }
    }

    public void delete(Long userId, String storageKey) {
        try {
            Files.deleteIfExists(filePath(userId, storageKey));
        } catch (IOException ignore) {
            // 删文件失败不阻断业务: 元数据已删, 残留文件不影响用户
        }
    }

    /** 删除某文件的全部分支(本体 + 历史版本 + 内嵌图片目录) */
    public void purge(Long userId, MyDocFile f) {
        delete(userId, f.getStorageKey());
        rmDir(versionDir(userId, f.getId()));
        rmDir(imageDir(f.getId()));
    }

    // ==================== 历史版本 ====================

    private Path versionDir(Long userId, Long fileId) {
        return userDir(userId).resolve(".versions").resolve(String.valueOf(fileId));
    }

    /** 保存前留档(重建式保存会丢失复杂排版, 这里保一份原文供回退) */
    public void snapshot(Long userId, MyDocFile f, byte[] current) {
        if (current == null || current.length == 0) return;
        try {
            Path dir = versionDir(userId, f.getId());
            Files.createDirectories(dir);
            String name = LocalDateTime.now().format(STAMP) + "." + f.getExt();
            Files.write(dir.resolve(name), current);
            List<String> names = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                 .map(p -> p.getFileName().toString())
                 .filter(n -> VERSION_NAME.matcher(n).matches())
                 .forEach(names::add);
            }
            names.sort(Comparator.naturalOrder());
            while (names.size() > MAX_VERSIONS) {
                Files.deleteIfExists(dir.resolve(names.remove(0)));
            }
        } catch (IOException ignore) {
            // 留档失败不影响本次保存
        }
    }

    /** 版本列表(新 -> 旧) */
    public List<Map<String, Object>> listVersions(Long userId, Long fileId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = versionDir(userId, fileId);
        if (!Files.isDirectory(dir)) return out;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> VERSION_NAME.matcher(p.getFileName().toString()).matches())
             .forEach(files::add);
        } catch (IOException e) {
            return out;
        }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path p : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            String n = p.getFileName().toString();
            m.put("name", n);
            try {
                m.put("size", Files.size(p));
                m.put("time", Files.getLastModifiedTime(p).toString());
            } catch (IOException ignore) {
                m.put("size", 0);
                m.put("time", "");
            }
            m.put("label", n.substring(0, 4) + "-" + n.substring(4, 6) + "-" + n.substring(6, 8)
                    + " " + n.substring(8, 10) + ":" + n.substring(10, 12) + ":" + n.substring(12, 14));
            out.add(m);
        }
        return out;
    }

    public byte[] readVersion(Long userId, Long fileId, String name) {
        if (name == null || !VERSION_NAME.matcher(name).matches()) {
            throw new BusinessException("版本标识非法");
        }
        Path p = versionDir(userId, fileId).resolve(name);
        if (!Files.isReadable(p)) throw new BusinessException("该版本不存在");
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new BusinessException("版本读取失败: " + e.getMessage());
        }
    }

    /** 把某个历史版本恢复为当前文件(恢复前会先把当前文件留档) */
    public void restore(Long userId, MyDocFile f, String versionName) {
        byte[] bytes = readVersion(userId, f.getId(), versionName);
        byte[] current = null;
        try {
            current = read(userId, f.getStorageKey());
        } catch (BusinessException ignore) {
            // 当前文件缺失时直接以该版本重建
        }
        snapshot(userId, f, current);
        write(userId, f.getStorageKey(), bytes);
    }

    // ==================== 内嵌图片 ====================

    /** Word 内嵌图片目录: data/uploads/my-doc/{fileId} */
    public Path imageDir(Long fileId) {
        return UPLOAD_ROOT.resolve(IMAGE_SCOPE).resolve(String.valueOf(fileId));
    }

    /** 图片对外 URL 前缀: /uploads/my-doc/{fileId} */
    public String imageUrlBase(Long fileId) {
        return "/uploads/" + IMAGE_SCOPE + "/" + fileId;
    }

    private void rmDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 尽力而为
                }
            });
        } catch (IOException ignore) {
            // 尽力而为
        }
    }
}
