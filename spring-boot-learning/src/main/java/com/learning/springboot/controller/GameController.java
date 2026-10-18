package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.GameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 娱乐板块 · 小游戏 API
 * <p>
 * 全部接口需登录(个人分数/进度按 userId 隔离);排行榜只暴露昵称/头像, 不含邮箱等敏感字段。
 */
@RestController
@RequestMapping("/api/games")
public class GameController {

    @Autowired private GameService gameService;

    /** 游戏中心总览:所有游戏的「我的数据」(最高分/局数/进行中存档), 供卡片列表用 */
    @GetMapping("/overview")
    public ResponseEntity<?> overview(@CurrentUser Long userId) {
        return Result.ok(gameService.overview(userId));
    }

    /** 我的统计: 最高分 + 总局数 + 最近 N 局(默认 10, 上限 100) + 进行中存档 */
    @GetMapping("/{gameCode}/stats")
    public ResponseEntity<?> stats(@PathVariable String gameCode,
                                   @RequestParam(defaultValue = "10") int limit,
                                   @CurrentUser Long userId) {
        return Result.ok(gameService.stats(userId, gameCode, limit));
    }

    /** 保存一局结束的得分(该局结束后进度存档同时清空; 单游戏最多保留最近 100 条) */
    @PostMapping("/{gameCode}/scores")
    @OperationLog(module = "娱乐", action = "保存游戏得分")
    public ResponseEntity<?> saveScore(@PathVariable String gameCode,
                                       @RequestBody Map<String, Object> body,
                                       @CurrentUser Long userId) {
        Map<String, Object> data = gameService.saveScore(userId, gameCode,
                intOrNull(body.get("score")),
                intOrNull(body.get("maxTile")),
                intOrNull(body.get("moves")),
                intOrNull(body.get("playSeconds")),
                intOrNull(body.get("finished")),
                str(body.get("extra")));
        return Result.created("已记录本局成绩", data);
    }

    /** 读取进行中存档 */
    @GetMapping("/{gameCode}/progress")
    public ResponseEntity<?> readProgress(@PathVariable String gameCode, @CurrentUser Long userId) {
        return Result.ok(gameService.readProgress(userId, gameCode));
    }

    /** 保存进行中存档(前端每次有效移动后防抖调用) */
    @PutMapping("/{gameCode}/progress")
    public ResponseEntity<?> saveProgress(@PathVariable String gameCode,
                                          @RequestBody Map<String, Object> body,
                                          @CurrentUser Long userId) {
        Map<String, Object> data = gameService.saveProgress(userId, gameCode,
                str(body.get("board")),
                intOrNull(body.get("score")),
                intOrNull(body.get("maxTile")),
                intOrNull(body.get("moves")),
                intOrNull(body.get("playSeconds")),
                intOrNull(body.get("finished")));
        return Result.ok("进度已保存", data);
    }

    /** 清除进度(新开一局时调用) */
    @DeleteMapping("/{gameCode}/progress")
    public ResponseEntity<?> clearProgress(@PathVariable String gameCode, @CurrentUser Long userId) {
        return Result.ok("已清除存档", gameService.clearProgress(userId, gameCode));
    }

    /** 排行榜(每人最高分) */
    @GetMapping("/{gameCode}/leaderboard")
    public ResponseEntity<?> leaderboard(@PathVariable String gameCode,
                                         @RequestParam(defaultValue = "20") int limit,
                                         @CurrentUser Long userId) {
        return Result.ok(gameService.leaderboard(userId, gameCode, limit));
    }

    private Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
