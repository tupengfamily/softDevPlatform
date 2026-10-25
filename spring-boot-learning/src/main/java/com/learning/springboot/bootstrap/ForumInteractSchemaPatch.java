package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 论坛互动表（点赞 / 收藏）唯一索引补建（🔴 2026-10-26 修复 M12）。
 *
 * <p><b>为什么必须手写 DDL</b>：
 * 实体 {@code ForumPostLike} / {@code ForumPostFavorite} 上已经声明了
 * {@code @UniqueConstraint(columnNames = {"post_id", "user_id"})}，
 * 但 SQLite 下 {@code hibernate.ddl-auto: update} <b>不会</b>为已存在的表补建唯一索引
 * （2026-10-26 用 {@code PRAGMA index_list} 实测确认：两个表的索引列表都是空的）。
 * 结果就是「查是否存在 → 再插入」的 check-then-act 在并发下会插出多条重复记录，
 * 计数从此和真实记录数永久对不上。
 *
 * <p><b>建索引前的清理</b>：老库里可能已经有重复的 (post_id, user_id) 记录，
 * 直接建唯一索引会失败。处理顺序：
 * <ol>
 *   <li>删掉重复行（保留 id 最小的那条）；</li>
 *   <li>建唯一索引；</li>
 *   <li>用真实记录数回填帖子的 like_count / favorite_count（索引前的计数本来就不可信）。</li>
 * </ol>
 *
 * <p><b>为什么加 {@code @Order(LOWEST_PRECEDENCE)}</b>：
 * 多个 patch 都监听 {@code ApplicationReadyEvent}，Spring 不保证顺序。
 * 本 patch 依赖帖子表已存在，故排到最后。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ForumInteractSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(ForumInteractSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public ForumInteractSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        patchLikeTable();
        patchFavoriteTable();
    }

    private void patchLikeTable() {
        if (!tableExists("t_forum_post_like")) return;
        dedupe("t_forum_post_like", "post_id", "user_id");
        createUniqueIndex("t_forum_post_like", "uk_forum_post_like_post_user");
        resyncCounter("t_forum_post_like", "t_forum_post", "like_count");
    }

    private void patchFavoriteTable() {
        if (!tableExists("t_forum_post_favorite")) return;
        dedupe("t_forum_post_favorite", "post_id", "user_id");
        createUniqueIndex("t_forum_post_favorite", "uk_forum_post_favorite_post_user");
        resyncCounter("t_forum_post_favorite", "t_forum_post", "favorite_count");
    }

    /**
     * 删除重复的 (post_id, user_id) 行，保留 id 最小的一条。
     */
    private void dedupe(String table, String colA, String colB) {
        try {
            List<Map<String, Object>> dups = jdbc.queryForList(
                    "SELECT " + colA + ", " + colB + ", COUNT(*) AS c, MIN(id) AS keep_id FROM " + table
                            + " GROUP BY " + colA + ", " + colB + " HAVING COUNT(*) > 1");
            if (dups.isEmpty()) return;
            int removed = 0;
            for (Map<String, Object> d : dups) {
                Object a = d.get(colA);
                Object b = d.get(colB);
                Object keep = d.get("keep_id");
                int n = jdbc.update("DELETE FROM " + table + " WHERE " + colA + " = ? AND " + colB
                        + " = ? AND id <> ?", a, b, keep);
                removed += n;
            }
            log.info("[schema-patch] {} 清理重复互动记录 {} 条", table, removed);
        } catch (Exception ex) {
            log.warn("[schema-patch] 清理 {} 重复记录失败: {}", table, ex.getMessage());
        }
    }

    private void createUniqueIndex(String table, String indexName) {
        try {
            if (hasIndex(table, indexName)) return;
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + indexName + " ON " + table + " (post_id, user_id)");
            log.info("[schema-patch] {} 已建唯一索引 {}", table, indexName);
        } catch (Exception ex) {
            log.warn("[schema-patch] 建唯一索引 {} 失败: {}", indexName, ex.getMessage());
        }
    }

    /**
     * 用互动表的真实记录数回填帖子的计数字段。
     *
     * <p>建唯一索引之前的计数是「覆盖写」时代留下的，可能与真实记录数不符
     * （尤其是重复记录被删掉之后）。这里做一次校准。
     */
    private void resyncCounter(String likeTable, String postTable, String column) {
        try {
            if (!tableExists(postTable)) return;
            jdbc.update("UPDATE " + postTable + " SET " + column + " = ("
                    + " SELECT COUNT(*) FROM " + likeTable + " l WHERE l.post_id = " + postTable + ".id)");
            log.info("[schema-patch] {} 已按真实记录数校准", column);
        } catch (Exception ex) {
            log.warn("[schema-patch] 校准 {} 失败: {}", column, ex.getMessage());
        }
    }

    private boolean tableExists(String table) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", table);
            return !rows.isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasIndex(String table, String indexName) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA index_list(" + table + ")");
            for (Map<String, Object> r : rows) {
                Object n = r.get("name");
                if (n != null && indexName.equalsIgnoreCase(String.valueOf(n))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
}
