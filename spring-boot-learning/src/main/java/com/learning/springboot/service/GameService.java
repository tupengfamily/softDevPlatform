package com.learning.springboot.service;

import com.learning.springboot.entity.GameProgress;
import com.learning.springboot.entity.GameScore;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.GameProgressRepository;
import com.learning.springboot.repository.GameScoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 娱乐板块 · 小游戏服务
 * <p>
 * 职责:
 * <ul>
 *   <li>单局得分落库(t_game_score) → 支撑「最近十次」与排行榜</li>
 *   <li>进行中局面存档(t_game_progress) → 支撑「下次进入继续」</li>
 *   <li>排行榜按 user 聚合最高分(JdbcTemplate 手写 SQL, 避开 SQLite 下 JPA 多字段聚合的坑)</li>
 * </ul>
 * 所有数据严格按 userId 隔离, 只有排行榜会跨用户读出(且只暴露昵称/头像, 不暴露账号敏感信息)。
 */
@Service
public class GameService {

    /** 已上线的游戏白名单(防止任意 gameCode 往库里灌数据) */
    private static final Set<String> SUPPORTED_GAMES = Set.of("2048", "gomoku", "guess", "sudoku", "one-stroke", "farm");
    /** 单局得分的合理上限(纯防御: 2048 理论上限远低于此, 拦住前端误传/刷分) */
    private static final int MAX_SCORE = 10_000_000;
    /** 最大方块值上限(理论 2^24 ≈ 16M 已足够) */
    private static final int MAX_MAX_TILE = 1 << 24;
    /** 移动步数上限(单局正常人玩 1 万步够多了) */
    private static final int MAX_MOVES = 100_000;
    /** 单局用时上限(24 小时, 异常防御) */
    private static final int MAX_PLAY_SECONDS = 24 * 3600;
    private static final int MAX_BOARD_LEN = 4000;
    /** 单个用户对某游戏的最近对局保留上限(超出按 id 倒序裁剪) */
    private static final int MAX_RECENT_PER_GAME = 100;
    /** 单条 extra_json 长度上限(防御: 万一前端误传巨型 JSON) */
    private static final int MAX_EXTRA_LEN = 4000;
    /**
     * 🔴 2026-10-27：extra_json 超长时<b>按优先级从低到高丢弃</b>的可再生明细字段。
     *
     * <p>只丢「明细数组」，不丢「汇总数值」——{@code pestLoss} / {@code disasterLoss}
     * 这些汇总值是玩家真正要看的数字，丢了就等于把「为什么减产」这条线索也删了。
     */
    private static final List<String> DETAIL_FIELDS_TO_DROP = List.of(
            "pests", "disasters", "weatherSourceData");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /**
     * 丢弃清单的只读视图（供测试断言「字段名与真实归因明细对得上」）。
     *
     * <p>🔴 为什么需要暴露：若清单里的字段名与归因明细实际字段名不一致
     * （改字段名时忘了改这里），「超长时丢弃明细」就会<b>静默失效</b> ——
     * 每次都走到保底分支，丢的却是玩家真正要看的数值字段。
     * 这个公开契约让测试能发现「清单与实际字段脱节」。
     */
    static List<String> detailFieldsToDropForTest() {
        return DETAIL_FIELDS_TO_DROP;
    }

    private final GameScoreRepository scoreRepo;
    private final GameProgressRepository progressRepo;
    private final JdbcTemplate jdbc;

    public GameService(GameScoreRepository scoreRepo,
                       GameProgressRepository progressRepo,
                       JdbcTemplate jdbc) {
        this.scoreRepo = scoreRepo;
        this.progressRepo = progressRepo;
        this.jdbc = jdbc;
    }

    /** 校验 gameCode;非法抛业务异常 */
    private String checkCode(String gameCode) {
        String c = gameCode == null ? "" : gameCode.trim().toLowerCase();
        if (!SUPPORTED_GAMES.contains(c)) {
            throw new BusinessException("不支持的游戏: " + gameCode);
        }
        return c;
    }

    // ==================================================================
    //  统计
    // ==================================================================

    /**
     * 游戏中心总览:一次返回所有游戏的「我的数据」, 供卡片列表渲染
     * <p>
     * 每款游戏:最高分 / 总局数 / 是否有进行中存档 / 存档分数与步数 / 存档更新时间 / 最近一次游玩时间。
     * 游戏名称、简介、封面等展示信息由前端清单维护(属于 UI 关注点), 这里只出数据。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String code : SUPPORTED_GAMES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gameCode", code);
            item.put("bestScore", scoreRepo.findBestScore(userId, code));
            item.put("playedCount", scoreRepo.countByUserIdAndGameCode(userId, code));

            GameProgress p = progressRepo.findByUserIdAndGameCode(userId, code).orElse(null);
            // 算「进行中」需同时满足: 未结束 且 有实际进展(0 分 0 步只是刚开局, 提示继续没意义)
            boolean ongoing = p != null
                    && (p.getFinished() == null || p.getFinished() == 0)
                    && ((p.getScore() != null && p.getScore() > 0) || (p.getMoves() != null && p.getMoves() > 0));
            item.put("hasProgress", ongoing);
            item.put("progressScore", ongoing ? p.getScore() : null);
            item.put("progressMoves", ongoing ? p.getMoves() : null);
            item.put("progressUpdatedAt", ongoing ? p.getUpdateTime() : null);

            // 最近一次游玩时间 = 最新一局得分记录时间(没有则 null)
            List<GameScore> latest = scoreRepo.findTop10ByUserIdAndGameCodeOrderByIdDesc(userId, code);
            item.put("lastPlayedAt", latest.isEmpty() ? null : latest.get(0).getCreateTime());
            out.put(code, item);
        }
        return out;
    }

    /**
     * 我的游戏数据:最高分 + 总局数 + 最近 N 局 + 进行中存档
     * <p>
     * 前端进入游戏页时一次拿全, 避免三四个请求串行。limit 默认 10, 上限 100 (与单局保留条数对齐)。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(Long userId, String gameCode, int limit) {
        String code = checkCode(gameCode);
        int best = scoreRepo.findBestScore(userId, code);
        int n = Math.max(1, Math.min(limit, MAX_RECENT_PER_GAME));
        List<GameScore> recent;
        if (n <= 10) {
            recent = scoreRepo.findTop10ByUserIdAndGameCodeOrderByIdDesc(userId, code);
            if (recent.size() > n) recent = recent.subList(0, n);
        } else {
            recent = scoreRepo.findTop100ByUserIdAndGameCodeOrderByIdDesc(userId, code);
            if (recent.size() > n) recent = recent.subList(0, n);
        }
        long played = scoreRepo.countByUserIdAndGameCode(userId, code);

        List<Map<String, Object>> recentList = new ArrayList<>(recent.size());
        for (GameScore s : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("score", s.getScore());
            m.put("maxTile", s.getMaxTile());
            m.put("moves", s.getMoves());
            m.put("playSeconds", s.getPlaySeconds());
            m.put("finished", s.getFinished());
            m.put("extra", s.getExtraJson());
            m.put("createTime", s.getCreateTime());
            recentList.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameCode", code);
        out.put("bestScore", best);
        out.put("playedCount", played);
        out.put("recent", recentList);
        out.put("recentLimit", n);
        out.put("progress", progressToMap(progressRepo.findByUserIdAndGameCode(userId, code).orElse(null)));
        return out;
    }

    // ==================================================================
    //  单局得分
    // ==================================================================

    /**
     * 保存一局结束的得分
     * <p>
     * 侧效应:
     *   1. 清掉进行中的存档(该局已结束, 下次进入应该是新局)
     *   2. 裁剪历史: 单个用户对某游戏的记录保留最近 MAX_RECENT_PER_GAME 条, 超出按 id 倒序删
     * <p>
     * finished: 1=死局 2=达成 2048 后结束 3=主动新开一局 4=正常胜利(非 2048) 5=放弃/未完成
     */
    @Transactional
    public Map<String, Object> saveScore(Long userId, String gameCode, Integer score,
                                         Integer maxTile, Integer moves, Integer playSeconds,
                                         Integer finished, String extraJson) {
        String code = checkCode(gameCode);
        int sc = clampInt(score, 0, MAX_SCORE);
        int tile = clampInt(maxTile, 0, MAX_MAX_TILE);
        int mv = clampInt(moves, 0, MAX_MOVES);
        int secs = clampInt(playSeconds, 0, MAX_PLAY_SECONDS);

        // 取写入前的历史最高分(判断 isNewBest 用)
        int oldBest = scoreRepo.findBestScore(userId, code);

        GameScore s = new GameScore();
        s.setUserId(userId);
        s.setGameCode(code);
        s.setScore(sc);
        s.setMaxTile(tile);
        s.setMoves(mv);
        s.setPlaySeconds(secs);
        s.setFinished(finished == null ? 1 : finished);
        if (extraJson != null) {
            s.setExtraJson(fitExtraJson(extraJson));
        }
        GameScore saved = scoreRepo.save(s);

        // 一局结束 → 进度存档作废
        progressRepo.findByUserIdAndGameCode(userId, code).ifPresent(progressRepo::delete);

        // 裁剪超出 100 的旧记录 (按 id 倒序, 删 tail) — 只在确实超出时才删
        List<GameScore> all = scoreRepo.findTop100ByUserIdAndGameCodeOrderByIdDesc(userId, code);
        if (all.size() > MAX_RECENT_PER_GAME) {
            List<GameScore> toDelete = all.subList(MAX_RECENT_PER_GAME, all.size());
            scoreRepo.deleteAll(toDelete);
        }

        int best = scoreRepo.findBestScore(userId, code);
        // isNewBest: 严格 > 历史最高才算新纪录(平局不算, 0 分不算)
        boolean isNewBest = sc > 0 && sc > oldBest;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("score", saved.getScore());
        out.put("bestScore", best);
        out.put("isNewBest", isNewBest);
        return out;
    }

    /** 整数钳制到 [min, max]; null 视为 min */
    private static int clampInt(Integer v, int min, int max) {
        if (v == null) return min;
        return Math.max(min, Math.min(v, max));
    }

    // ==================================================================
    //  extra_json 长度收敛（🔴 2026-10-27）
    // ==================================================================

    /**
     * 🔴 <b>把 extra_json 收敛到长度上限内，但保证它始终是<b>合法 JSON</b>。</b>
     *
     * <p><b>原实现（错在哪）</b>：
     * <pre>
     *   String trimmed = extraJson.length() &gt; MAX_EXTRA_LEN
     *           ? extraJson.substring(0, MAX_EXTRA_LEN) : extraJson;
     * </pre>
     * 直接按字符数硬切 —— 切点落在对象/数组中间时，存进去的是<b>半个 JSON</b>。
     * 后果不是「字段少几个」，而是<b>整条记录读不出来</b>：
     * 前端 {@code JSON.parse(extra)} 直接抛异常 → 归因面板整块崩掉。
     *
     * <p><b>为什么农场会真的触发</b>：归因明细里带 {@code pests[]} 与 {@code disasters[]}
     * 两个<b>不定长数组</b> —— 病虫害按作物类型最多十几项（{@code PestEngine} 每类作物
     * 配 4~6 种病虫害），灾害按发生次数累积。两项一叠加就可能超过 4000 字符。
     * 也就是说：<b>「病虫害多的那一季」恰好是「查不出减产原因的那一季」</b>——
     * 而那正是玩家最需要看归因的时候。
     *
     * <p><b>修法</b>：不按字符硬切，而是<b>按字段语义</b>收敛：
     * <ol>
     *   <li>先原样写入（没超长就不动，零行为变化）；</li>
     *   <li>超长时按 <b>优先级从低到高</b> 丢弃可再生的明细项，
     *       每次丢完重新序列化，直到塞得下；</li>
     *   <li>丢到极限仍超长，才做<b>保底截断</b> —— 但此时截断的是
     *       <b>已经解析成 Map 的结构</b>，不是字符串，
     *       所以结果仍是<b>合法 JSON</b>（最坏情况丢几个末尾的数值字段）。</li>
     * </ol>
     *
     * <p><b>丢弃优先级（先丢哪个）</b>：
     * <ol>
     *   <li>{@code pests} —— 病虫害明细（{@code pestLoss} 汇总值仍保留，
     *       玩家至少知道「损失了 23%」，只是看不到逐项）；</li>
     *   <li>{@code disasters} —— 灾害明细（同上，{@code disasterLoss} 保留）；</li>
     *   <li>{@code weatherSourceData} —— 天气来源的结构化描述
     *       （{@code weatherSource} 文字标签仍保留）。</li>
     * </ol>
     * 汇总值（{@code pestLoss} / {@code disasterLoss} / {@code inputCost} /
     * {@code revenue} / {@code net} / 产量四要素）<b>永远不丢</b> ——
     * 那些是玩家真正要看的数字。
     *
     * <p><b>为什么不直接调大上限</b>：{@code MAX_EXTRA_LEN} 是防御
     * 「前端误传巨型 JSON」的护栏，不是给业务数据用的额度。
     * 放宽上限 = 护栏失效。正确做法是<b>让超长内容优雅降级</b>。
     */
    private static String fitExtraJson(String extraJson) {
        if (extraJson == null || extraJson.length() <= MAX_EXTRA_LEN) {
            return extraJson;
        }
        // 解析失败 → 原文入库（不是本方法的职责范围，也不该在这里抛异常）
        JsonNode root;
        try {
            root = JSON.readTree(extraJson);
        } catch (Exception e) {
            log.warn("[游戏中心] extra_json 非法 JSON，原样入库（长度 {}）: {}", extraJson.length(), e.getMessage());
            return extraJson;
        }
        if (!root.isObject()) {
            // 不是对象（理论上不会发生）→ 原样入库
            return extraJson;
        }

        ObjectNode obj = (ObjectNode) root;
        // 按优先级从低到高逐个丢弃明细数组
        for (String field : DETAIL_FIELDS_TO_DROP) {
            if (obj.has(field)) {
                obj.remove(field);
                String candidate = writeJson(obj);
                if (candidate.length() <= MAX_EXTRA_LEN) {
                    log.info("[游戏中心] extra_json 超长（{}→{}），已丢弃明细字段 {}",
                            extraJson.length(), candidate.length(), field);
                    return candidate;
                }
            }
        }

        // 到这里仍然超长 → 保底：逐个丢末尾的数值/字符串字段（保底是合法 JSON）
        List<String> droppable = new ArrayList<>();
        obj.fieldNames().forEachRemaining(droppable::add);
        Collections.reverse(droppable);
        for (String field : droppable) {
            obj.remove(field);
            String candidate = writeJson(obj);
            if (candidate.length() <= MAX_EXTRA_LEN) {
                log.info("[游戏中心] extra_json 超长（{}→{}），保底丢弃字段 {}",
                        extraJson.length(), candidate.length(), field);
                return candidate;
            }
        }
        // 连空对象都超长（不可能，除非 MAX_EXTRA_LEN 被改成 0）
        return "{}";
    }

    /** 序列化，失败时返回一个必定合法的空对象（宁可丢内容也不能写坏 JSON）。 */
    private static String writeJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ==================================================================
    //  进度存档
    // ==================================================================

    /** 读取进行中存档(没有则 progress=null) */
    @Transactional(readOnly = true)
    public Map<String, Object> readProgress(Long userId, String gameCode) {
        String code = checkCode(gameCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("progress", progressToMap(progressRepo.findByUserIdAndGameCode(userId, code).orElse(null)));
        return out;
    }

    /**
     * 保存/更新进度存档(upsert)
     * <p>
     * 前端每次有效移动后防抖调用, 因此这里要足够轻: 一次查询 + 一次 save。
     */
    @Transactional
    public Map<String, Object> saveProgress(Long userId, String gameCode, String boardJson,
                                            Integer score, Integer maxTile, Integer moves,
                                            Integer playSeconds, Integer finished) {
        String code = checkCode(gameCode);
        if (boardJson == null || boardJson.isBlank()) {
            throw new BusinessException("棋盘数据为空");
        }
        if (boardJson.length() > MAX_BOARD_LEN) {
            throw new BusinessException("棋盘数据异常(过长)");
        }

        GameProgress p = progressRepo.findByUserIdAndGameCode(userId, code).orElseGet(() -> {
            GameProgress np = new GameProgress();
            np.setUserId(userId);
            np.setGameCode(code);
            return np;
        });
        p.setBoardJson(boardJson);
        p.setScore(clampInt(score, 0, MAX_SCORE));
        p.setMaxTile(clampInt(maxTile, 0, MAX_MAX_TILE));
        p.setMoves(clampInt(moves, 0, MAX_MOVES));
        p.setPlaySeconds(clampInt(playSeconds, 0, MAX_PLAY_SECONDS));
        p.setFinished(finished == null ? 0 : finished);
        progressRepo.save(p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", true);
        out.put("updateTime", p.getUpdateTime());
        return out;
    }

    /** 清空进度(前端「新游戏」时调用) */
    @Transactional
    public Map<String, Object> clearProgress(Long userId, String gameCode) {
        String code = checkCode(gameCode);
        progressRepo.findByUserIdAndGameCode(userId, code).ifPresent(progressRepo::delete);
        return Map.of("cleared", true);
    }

    // ==================================================================
    //  排行榜
    // ==================================================================

    /**
     * 排行榜: 每人取最高分, 按分数倒序
     * <p>
     * 用 JdbcTemplate 手写 SQL 的原因: 需要 GROUP BY user_id + MAX(score) 再 join t_user,
     * JPA 的多字段聚合返回 Object[] 在 SQLite + Hibernate 6 下容易出错(项目里踩过)。
     * 分两步查避免 N+1: 先拿聚合榜, 再一次性取用户信息。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaderboard(Long currentUserId, String gameCode, int limit) {
        String code = checkCode(gameCode);
        int n = Math.max(1, Math.min(limit, 100));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, MAX(score) AS best, MAX(max_tile) AS best_tile, COUNT(*) AS plays "
                        + "FROM t_game_score WHERE game_code = ? GROUP BY user_id "
                        + "ORDER BY best DESC, best_tile DESC LIMIT ?",
                code, n);

        // 一次性取用户资料(昵称/头像), 避免逐行查
        Map<Long, Map<String, Object>> users = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> r : rows) ids.add(num(r.get("user_id")));
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) in.append(i == 0 ? "?" : ",?");
            for (Map<String, Object> u : jdbc.queryForList(
                    "SELECT id, username, nickname, avatar FROM t_user WHERE id IN (" + in + ")",
                    ids.toArray())) {
                users.put(num(u.get("id")), u);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        int rank = 1;
        for (Map<String, Object> r : rows) {
            Long uid = num(r.get("user_id"));
            Map<String, Object> u = users.get(uid);
            String nickname = u == null ? null : str(u.get("nickname"));
            String username = u == null ? "已注销用户" : str(u.get("username"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", rank++);
            item.put("userId", uid);
            item.put("username", username);
            // 展示名优先昵称, 没有昵称回落用户名;不返回 email 等敏感字段
            item.put("displayName", nickname == null || nickname.isBlank() ? username : nickname);
            item.put("avatar", u == null ? null : str(u.get("avatar")));
            item.put("bestScore", num(r.get("best")));
            item.put("bestTile", num(r.get("best_tile")));
            item.put("plays", num(r.get("plays")));
            item.put("isMe", uid != null && uid.equals(currentUserId));
            out.add(item);
        }
        return out;
    }

    // ==================================================================
    //  工具
    // ==================================================================

    private Map<String, Object> progressToMap(GameProgress p) {
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("board", p.getBoardJson());
        m.put("score", p.getScore());
        m.put("maxTile", p.getMaxTile());
        m.put("moves", p.getMoves());
        m.put("playSeconds", p.getPlaySeconds());
        m.put("finished", p.getFinished());
        m.put("updateTime", p.getUpdateTime());
        return m;
    }

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number nb) return nb.longValue();
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
