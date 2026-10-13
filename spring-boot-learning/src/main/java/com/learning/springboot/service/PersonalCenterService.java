package com.learning.springboot.service;

import com.learning.springboot.entity.LessonProgress;
import com.learning.springboot.entity.Todo;
import com.learning.springboot.entity.UserDataRecord;
import com.learning.springboot.entity.UserFavorite;
import com.learning.springboot.entity.WikiArticle;
import com.learning.springboot.entity.WikiDirectory;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.LessonProgressRepository;
import com.learning.springboot.repository.TodoRepository;
import com.learning.springboot.repository.UserDataRecordRepository;
import com.learning.springboot.repository.UserFavoriteRepository;
import com.learning.springboot.repository.WikiArticleRepository;
import com.learning.springboot.repository.WikiDirectoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 个人中心服务: 个人收藏 / 我的待办 / Wiki(目录+文章+排序+分享) / 个人数据导入导出
 */
@Service
public class PersonalCenterService {

    @Autowired private UserFavoriteRepository favoriteRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private WikiDirectoryRepository directoryRepository;
    @Autowired private WikiArticleRepository articleRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private UserDataRecordRepository recordRepository;

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ==================== 个人收藏 ====================

    public List<UserFavorite> listFavorites(Long userId) {
        return favoriteRepository.findByUserIdOrderBySortNoAscIdAsc(userId);
    }

    @Transactional
    public UserFavorite createFavorite(Long userId, String title, String url, String category, String remark) {
        if (title == null || title.trim().isEmpty()) throw new BusinessException("收藏名称不能为空");
        if (url == null || url.trim().isEmpty()) throw new BusinessException("收藏网址不能为空");
        String norm = url.trim();
        if (!norm.startsWith("http://") && !norm.startsWith("https://")) norm = "https://" + norm;
        UserFavorite f = new UserFavorite();
        f.setUserId(userId);
        f.setTitle(title.trim());
        f.setUrl(norm);
        f.setCategory(trimTo(category, 50));
        f.setRemark(trimTo(remark, 200));
        f.setSortNo(nextSortNo(favoriteRepository.findByUserIdOrderBySortNoAscIdAsc(userId).stream()
            .map(UserFavorite::getSortNo).max(Integer::compareTo).orElse(0)));
        return favoriteRepository.save(f);
    }

    @Transactional
    public UserFavorite updateFavorite(Long userId, Long id, String title, String url, String category, String remark) {
        UserFavorite f = favoriteRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("收藏不存在"));
        if (title != null && !title.trim().isEmpty()) f.setTitle(title.trim());
        if (url != null && !url.trim().isEmpty()) {
            String norm = url.trim();
            if (!norm.startsWith("http://") && !norm.startsWith("https://")) norm = "https://" + norm;
            f.setUrl(norm);
        }
        f.setCategory(trimTo(category, 50));
        f.setRemark(trimTo(remark, 200));
        return favoriteRepository.save(f);
    }

    @Transactional
    public void deleteFavorite(Long userId, Long id) {
        UserFavorite f = favoriteRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("收藏不存在"));
        favoriteRepository.delete(f);
    }

    /** 收藏上移/下移(与相邻收藏交换 sortNo) */
    @Transactional
    public void moveFavorite(Long userId, Long id, String direction) {
        List<UserFavorite> list = favoriteRepository.findByUserIdOrderBySortNoAscIdAsc(userId);
        swapSort(list, id, direction, UserFavorite::getSortNo, UserFavorite::setSortNo);
        favoriteRepository.saveAll(list);
    }

    // ==================== 我的待办 ====================

    /**
     * 待办列表(仅本人): status 为空查全部,0=待办,1=已完成。
     * 排序: 待办在前 → 优先级降序 → 截止日期升序(无截止排后) → 创建时间倒序。
     */
    public List<Todo> listTodos(Long userId, Integer status) {
        List<Todo> out = new ArrayList<>();
        for (Todo t : todoRepository.findByUserIdOrderByCreateTimeDesc(userId)) {
            if (status != null && !status.equals(t.getStatus())) continue;
            out.add(t);
        }
        out.sort(Comparator
            .comparing(Todo::getStatus)
            .thenComparing(Comparator.comparing((Todo t) -> t.getPriority() == null ? 0 : t.getPriority()).reversed())
            .thenComparing(Comparator.comparing((Todo t) -> t.getDueDate() == null ? "9999-99-99" : t.getDueDate()))
            .thenComparing(Comparator.comparing(Todo::getCreateTime).reversed()));
        return out;
    }

    @Transactional
    public Todo createTodo(Long userId, String title, Integer priority, String dueDate) {
        if (title == null || title.trim().isEmpty()) throw new BusinessException("待办内容不能为空");
        Todo t = new Todo();
        t.setUserId(userId);
        t.setTitle(trimTo(title, 200));
        t.setPriority(priority == null ? 1 : (priority < 0 ? 0 : Math.min(priority, 2)));
        t.setStatus(0);
        t.setDueDate(normalizeDueDate(dueDate));
        return todoRepository.save(t);
    }

    @Transactional
    public Todo updateTodo(Long userId, Long id, String title, Integer priority, String dueDate, Integer status) {
        Todo t = todoRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("待办不存在"));
        if (title != null && !title.trim().isEmpty()) t.setTitle(trimTo(title, 200));
        if (priority != null) t.setPriority(priority < 0 ? 0 : Math.min(priority, 2));
        if (dueDate != null) t.setDueDate(normalizeDueDate(dueDate));   // 传空串可清除截止日期
        if (status != null && !status.equals(t.getStatus())) {
            t.setStatus(status == 0 ? 0 : 1);
            t.setDoneTime(t.getStatus() == 1 ? LocalDateTime.now() : null);
        }
        return todoRepository.save(t);
    }

    @Transactional
    public void deleteTodo(Long userId, Long id) {
        Todo t = todoRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("待办不存在"));
        todoRepository.delete(t);
    }

    /** 截止日期: 校验 yyyy-MM-dd;空串/null 归一为 null(清除) */
    private String normalizeDueDate(String dueDate) {
        String d = trimTo(dueDate, 10);
        if (d == null) return null;
        try {
            java.time.LocalDate.parse(d);
        } catch (Exception e) {
            throw new BusinessException("截止日期格式应为 yyyy-MM-dd");
        }
        return d;
    }

    // ==================== Wiki 目录 ====================

    /** 目录 + 目录内文章(均按 sortNo 排序;含 parentId 供前端组树) */
    public List<Map<String, Object>> wikiTree(Long userId) {
        List<Map<String, Object>> tree = new ArrayList<>();
        for (WikiDirectory d : directoryRepository.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", d.getId());
            node.put("name", d.getName());
            node.put("parentId", d.getParentId());
            node.put("sortNo", d.getSortNo());
            node.put("createTime", d.getCreateTime());
            node.put("articles", articleRepository.findByDirectoryIdOrderBySortNoAscIdAsc(d.getId()));
            tree.add(node);
        }
        return tree;
    }

    @Transactional
    public WikiDirectory createDirectory(Long userId, String name, Long parentId) {
        if (name == null || name.trim().isEmpty()) throw new BusinessException("目录名称不能为空");
        WikiDirectory d = new WikiDirectory();
        d.setUserId(userId);
        d.setName(trimTo(name, 100));
        if (parentId != null) {
            WikiDirectory parent = directoryRepository.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new BusinessException("父目录不存在"));
            d.setParentId(parent.getId());
        }
        d.setSortNo(nextSortNo(siblingDirs(userId, d.getParentId()).stream()
            .map(WikiDirectory::getSortNo).max(Integer::compareTo).orElse(0)));
        return directoryRepository.save(d);
    }

    @Transactional
    public WikiDirectory updateDirectory(Long userId, Long id, String name) {
        WikiDirectory d = directoryRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("目录不存在"));
        if (name == null || name.trim().isEmpty()) throw new BusinessException("目录名称不能为空");
        d.setName(trimTo(name, 100));
        return directoryRepository.save(d);
    }

    /** 删除目录: 递归删除子目录,级联删除目录下文章 */
    @Transactional
    public void deleteDirectory(Long userId, Long id) {
        WikiDirectory d = directoryRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("目录不存在"));
        for (WikiDirectory child : siblingDirs(userId, id)) {
            deleteDirectory(userId, child.getId());
        }
        articleRepository.deleteByDirectoryId(id);
        directoryRepository.delete(d);
    }

    /** 目录移动到指定位置(同级内,拖动排序) */
    @Transactional
    public void moveDirectory(Long userId, Long id, int position) {
        WikiDirectory d = directoryRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("目录不存在"));
        List<WikiDirectory> siblings = siblingDirs(userId, d.getParentId());
        reorder(siblings, id, position, WikiDirectory::getSortNo, WikiDirectory::setSortNo);
        directoryRepository.saveAll(siblings);
    }

    // ==================== Wiki 文章 ====================

    @Transactional
    public WikiArticle createArticle(Long userId, Long directoryId, String title, String content, String contentType) {
        WikiDirectory d = directoryRepository.findByIdAndUserId(directoryId, userId)
            .orElseThrow(() -> new BusinessException("目录不存在"));
        if (title == null || title.trim().isEmpty()) throw new BusinessException("文章标题不能为空");
        if (content == null || content.trim().isEmpty()) throw new BusinessException("文章内容不能为空");
        WikiArticle a = new WikiArticle();
        a.setUserId(userId);
        a.setDirectoryId(d.getId());
        a.setTitle(trimTo(title, 200));
        a.setContent(content);
        a.setContentType("richtext".equals(contentType) ? "richtext" : "markdown");
        a.setSortNo(nextSortNo(articleRepository.findByDirectoryIdOrderBySortNoAscIdAsc(d.getId()).stream()
            .map(WikiArticle::getSortNo).max(Integer::compareTo).orElse(0)));
        return articleRepository.save(a);
    }

    @Transactional
    public WikiArticle updateArticle(Long userId, Long id, Long directoryId, String title, String content, String contentType) {
        WikiArticle a = articleRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("文章不存在"));
        if (directoryId != null && !directoryId.equals(a.getDirectoryId())) {
            WikiDirectory d = directoryRepository.findByIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new BusinessException("目录不存在"));
            a.setDirectoryId(d.getId());
        }
        if (title != null && !title.trim().isEmpty()) a.setTitle(trimTo(title, 200));
        if (content != null && !content.trim().isEmpty()) a.setContent(content);
        if (contentType != null) a.setContentType("richtext".equals(contentType) ? "richtext" : "markdown");
        return articleRepository.save(a);
    }

    @Transactional
    public void deleteArticle(Long userId, Long id) {
        WikiArticle a = articleRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("文章不存在"));
        articleRepository.delete(a);
    }

    /** 文章移动到指定位置(目录内,拖动排序) */
    @Transactional
    public void moveArticle(Long userId, Long id, int position) {
        WikiArticle a = articleRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("文章不存在"));
        List<WikiArticle> list = articleRepository.findByDirectoryIdOrderBySortNoAscIdAsc(a.getDirectoryId());
        reorder(list, id, position, WikiArticle::getSortNo, WikiArticle::setSortNo);
        articleRepository.saveAll(list);
    }

    /** 开启分享: 生成/复用分享令牌 */
    @Transactional
    public WikiArticle shareArticle(Long userId, Long id) {
        WikiArticle a = articleRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("文章不存在"));
        if (a.getShareToken() == null || a.getShareToken().isEmpty()) {
            a.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        }
        return articleRepository.save(a);
    }

    /** 取消分享: 清除令牌 */
    @Transactional
    public WikiArticle unshareArticle(Long userId, Long id) {
        WikiArticle a = articleRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("文章不存在"));
        a.setShareToken(null);
        return articleRepository.save(a);
    }

    /** 分享页公开只读访问(游客),计一次浏览 */
    @Transactional
    public WikiArticle viewShared(String token) {
        WikiArticle a = articleRepository.findByShareToken(token)
            .orElseThrow(() -> new BusinessException(404, "分享不存在或已取消"));
        a.setViewCount((a.getViewCount() == null ? 0L : a.getViewCount()) + 1);
        return articleRepository.save(a);
    }

    // ==================== 个人数据导入导出 ====================

    /** 导出当前用户个人数据(收藏 + Wiki + 学习进度) */
    public Map<String, Object> exportUserData(Long userId, String username) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", LocalDateTime.now().toString());
        data.put("username", username);
        data.put("favorites", favoriteRepository.findByUserIdOrderBySortNoAscIdAsc(userId));
        data.put("wikiDirectories", directoryRepository.findByUserIdOrderBySortNoAscIdAsc(userId));
        data.put("wikiArticles", articleRepository.findByUserIdOrderBySortNoAscIdAsc(userId));
        List<Map<String, Object>> progress = new ArrayList<>();
        for (LessonProgress p : progressRepository.findByUserId(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lessonId", p.getLessonId());
            m.put("status", p.getStatus());
            m.put("learnedAt", p.getLearnedAt());
            progress.add(m);
        }
        data.put("lessonProgress", progress);
        return data;
    }

    /**
     * 导入个人数据(合并模式,跳过重复项):
     * - 收藏按 url 去重;目录按名称去重;文章按标题去重;进度按课时去重
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importUserData(Long userId, Map<String, Object> payload) {
        if (payload == null) throw new BusinessException("导入数据为空");
        int fAdd = 0, fSkip = 0, dAdd = 0, dSkip = 0, aAdd = 0, aSkip = 0, pAdd = 0, pSkip = 0;

        // 收藏
        List<Map<String, Object>> favorites = (List<Map<String, Object>>) payload.getOrDefault("favorites", List.of());
        for (Map<String, Object> row : favorites) {
            String url = str(row.get("url"));
            if (url == null || url.isEmpty()) { fSkip++; continue; }
            String norm = url.startsWith("http") ? url : "https://" + url;
            boolean dup = favoriteRepository.findByUserIdOrderBySortNoAscIdAsc(userId).stream()
                .anyMatch(x -> x.getUrl().equals(norm));
            if (dup) { fSkip++; continue; }
            UserFavorite f = new UserFavorite();
            f.setUserId(userId);
            f.setTitle(trimTo(str(row.get("title")), 200));
            if (f.getTitle() == null || f.getTitle().isEmpty()) f.setTitle(norm);
            f.setUrl(norm);
            f.setCategory(trimTo(str(row.get("category")), 50));
            f.setRemark(trimTo(str(row.get("remark")), 200));
            f.setSortNo(row.get("sortNo") instanceof Number n ? n.intValue() : fAdd);
            favoriteRepository.save(f);
            fAdd++;
        }

        // Wiki 目录(两级处理: 先建目录,再按导出文件里的 parentId 回填父子关系)
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) payload.getOrDefault("wikiDirectories", List.of());
        Map<String, Long> dirNameToId = new LinkedHashMap<>();
        for (WikiDirectory d : directoryRepository.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            dirNameToId.put(d.getName(), d.getId());
        }
        Map<Long, Long> oldIdToNewId = new LinkedHashMap<>();   // 导出文件目录 id -> 本地目录 id
        List<WikiDirectory> created = new ArrayList<>();
        for (Map<String, Object> row : dirs) {
            String name = trimTo(str(row.get("name")), 100);
            if (name == null || name.isEmpty()) { dSkip++; continue; }
            if (dirNameToId.containsKey(name)) {
                if (row.get("id") instanceof Number n) oldIdToNewId.put(n.longValue(), dirNameToId.get(name));
                dSkip++;
                continue;
            }
            WikiDirectory d = new WikiDirectory();
            d.setUserId(userId);
            d.setName(name);
            d.setSortNo(row.get("sortNo") instanceof Number n ? n.intValue() : dAdd);
            directoryRepository.save(d);
            dirNameToId.put(name, d.getId());
            if (row.get("id") instanceof Number n) oldIdToNewId.put(n.longValue(), d.getId());
            created.add(d);
            dAdd++;
        }
        // 回填父子关系
        for (Map<String, Object> row : dirs) {
            if (!(row.get("id") instanceof Number n)) continue;
            Long newId = oldIdToNewId.get(n.longValue());
            if (newId == null) continue;
            Long parentOld = row.get("parentId") instanceof Number p ? p.longValue() : null;
            Long parentNew = parentOld != null ? oldIdToNewId.get(parentOld) : null;
            WikiDirectory d = created.stream().filter(x -> x.getId().equals(newId)).findFirst().orElse(null);
            if (d != null && !Objects.equals(d.getParentId(), parentNew)) {
                d.setParentId(parentNew);
                directoryRepository.save(d);
            }
        }

        // Wiki 文章(目录按 id 映射到同名目录;目录不存在则跳过)
        List<Map<String, Object>> articles = (List<Map<String, Object>>) payload.getOrDefault("wikiArticles", List.of());
        Map<Long, Long> dirIdMap = new LinkedHashMap<>();   // 导出文件里的目录 id -> 本地目录 id
        for (Map<String, Object> row : dirs) {
            // 建立同名映射(导入文件自带 id)
            if (row.get("id") instanceof Number n && row.get("name") != null) {
                Long local = dirNameToId.get(trimTo(str(row.get("name")), 100));
                if (local != null) dirIdMap.put(n.longValue(), local);
            }
        }
        final List<String> existingTitles = new ArrayList<>();
        for (WikiArticle a : articleRepository.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            existingTitles.add(a.getTitle());
        }
        for (Map<String, Object> row : articles) {
            String title = trimTo(str(row.get("title")), 200);
            if (title == null || title.isEmpty()) { aSkip++; continue; }
            if (existingTitles.contains(title)) { aSkip++; continue; }
            Long dirId = null;
            if (row.get("directoryId") instanceof Number n) dirId = dirIdMap.get(n.longValue());
            if (dirId == null) { aSkip++; continue; }
            WikiArticle a = new WikiArticle();
            a.setUserId(userId);
            a.setDirectoryId(dirId);
            a.setTitle(title);
            a.setContent(str(row.get("content")) != null ? str(row.get("content")) : " ");
            a.setContentType("richtext".equals(str(row.get("contentType"))) ? "richtext" : "markdown");
            a.setSortNo(row.get("sortNo") instanceof Number n ? n.intValue() : aAdd);
            articleRepository.save(a);
            existingTitles.add(title);
            aAdd++;
        }

        // 学习进度
        List<Map<String, Object>> progress = (List<Map<String, Object>>) payload.getOrDefault("lessonProgress", List.of());
        for (Map<String, Object> row : progress) {
            if (!(row.get("lessonId") instanceof Number n)) { pSkip++; continue; }
            Long lessonId = n.longValue();
            if (progressRepository.existsByUserIdAndLessonId(userId, lessonId)) { pSkip++; continue; }
            progressRepository.save(new LessonProgress(userId, lessonId));
            pAdd++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("favoritesAdded", fAdd); result.put("favoritesSkipped", fSkip);
        result.put("directoriesAdded", dAdd); result.put("directoriesSkipped", dSkip);
        result.put("articlesAdded", aAdd); result.put("articlesSkipped", aSkip);
        result.put("progressAdded", pAdd); result.put("progressSkipped", pSkip);
        return result;
    }

    // ==================== 导入导出记录 ====================

    /** 记录列表(不含内容快照,轻量) */
    public List<Map<String, Object>> listRecords(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserDataRecord r : recordRepository.findByUserIdOrderByCreateTimeDescIdDesc(userId)) {
            out.add(recordSummary(r));
        }
        return out;
    }

    /** 记录详情(含内容,查看/下载用) */
    public UserDataRecord getRecord(Long userId, Long id) {
        return recordRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException("记录不存在"));
    }

    /** 导出并落一条导出记录 */
    @Transactional
    public Map<String, Object> createExportRecord(Long userId, String username) {
        Map<String, Object> data = exportUserData(userId, username);
        String json = toJson(data);
        UserDataRecord r = new UserDataRecord();
        r.setUserId(userId);
        r.setUsername(username);
        r.setType(UserDataRecord.TYPE_EXPORT);
        r.setContent(json);
        r.setSizeBytes(bytes(json));
        r.setSummary(String.format("收藏 %d · Wiki 目录 %d/文章 %d · 学习进度 %d",
            sizeOf(data.get("favorites")), sizeOf(data.get("wikiDirectories")),
            sizeOf(data.get("wikiArticles")), sizeOf(data.get("lessonProgress"))));
        recordRepository.save(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("fileName", "learning-backup-" + (username != null ? username : "user")
            + "-" + LocalDateTime.now().toLocalDate() + ".json");
        out.put("content", json);
        out.put("sizeBytes", r.getSizeBytes());
        out.put("createTime", r.getCreateTime());
        return out;
    }

    /** 导入并落一条导入记录 */
    @Transactional
    public Map<String, Object> createImportRecord(Long userId, String username, Map<String, Object> payload) {
        Map<String, Object> result = importUserData(userId, payload);
        String json = toJson(payload);
        UserDataRecord r = new UserDataRecord();
        r.setUserId(userId);
        r.setUsername(username);
        r.setType(UserDataRecord.TYPE_IMPORT);
        r.setContent(json);
        r.setSizeBytes(bytes(json));
        r.setSummary(String.format("新增 收藏%d/目录%d/文章%d/进度%d,跳过 %d",
            result.get("favoritesAdded"), result.get("directoriesAdded"),
            result.get("articlesAdded"), result.get("progressAdded"),
            (int) result.get("favoritesSkipped") + (int) result.get("directoriesSkipped")
                + (int) result.get("articlesSkipped") + (int) result.get("progressSkipped")));
        recordRepository.save(r);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("recordId", r.getId());
        return out;
    }

    private Map<String, Object> recordSummary(UserDataRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("type", r.getType());
        m.put("summary", r.getSummary());
        m.put("sizeBytes", r.getSizeBytes());
        m.put("createTime", r.getCreateTime());
        return m;
    }

    private String toJson(Object o) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException("数据序列化失败: " + e.getMessage());
        }
    }

    private long bytes(String s) {
        return s == null ? 0L : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private int sizeOf(Object list) {
        return list instanceof java.util.Collection<?> c ? c.size() : 0;
    }

    // ==================== 私有工具 ====================

    private int nextSortNo(int max) { return max + 1; }

    /** 同级目录(按 parentId 分组) */
    private List<WikiDirectory> siblingDirs(Long userId, Long parentId) {
        List<WikiDirectory> out = new ArrayList<>();
        for (WikiDirectory d : directoryRepository.findByUserIdOrderBySortNoAscIdAsc(userId)) {
            if (Objects.equals(d.getParentId(), parentId)) out.add(d);
        }
        return out;
    }

    /** 把 id 对应元素移动到 position 并整列重排 sortNo(0..n) */
    private <T> void reorder(List<T> list, Long id, int position,
                             java.util.function.Function<T, Integer> getter,
                             java.util.function.BiConsumer<T, Integer> setter) {
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            Long itemId = item instanceof UserFavorite f ? f.getId()
                : item instanceof WikiDirectory d ? d.getId()
                : item instanceof WikiArticle a ? a.getId() : null;
            if (id.equals(itemId)) { idx = i; break; }
        }
        if (idx < 0) throw new BusinessException("记录不存在");
        T moved = list.remove(idx);
        int target = Math.max(0, Math.min(position, list.size()));
        list.add(target, moved);
        for (int i = 0; i < list.size(); i++) setter.accept(list.get(i), i);
    }

    private String trimTo(String s, int len) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > len ? t.substring(0, len) : t);
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** 在有序列表中把 id 对应元素与相邻元素交换 sortNo */
    private <T> void swapSort(List<T> list, Long id, String direction,
                              java.util.function.Function<T, Integer> getter,
                              java.util.function.BiConsumer<T, Integer> setter) {
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            Long itemId = item instanceof UserFavorite f ? f.getId()
                : item instanceof WikiDirectory d ? d.getId()
                : item instanceof WikiArticle a ? a.getId() : null;
            if (id.equals(itemId)) { idx = i; break; }
        }
        if (idx < 0) throw new BusinessException("记录不存在");
        int target = "up".equals(direction) ? idx - 1 : idx + 1;
        if (target < 0 || target >= list.size()) return;   // 已到边界,无操作
        T cur = list.get(idx);
        T other = list.get(target);
        Integer tmp = getter.apply(cur);
        setter.accept(cur, getter.apply(other));
        setter.accept(other, tmp);
    }
}
