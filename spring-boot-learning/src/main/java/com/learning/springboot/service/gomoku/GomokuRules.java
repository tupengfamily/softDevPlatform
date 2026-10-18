package com.learning.springboot.service.gomoku;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 五子棋规则模块
 * <p>
 * 用 0/1/2 表示空/黑/白(服务端与前端约定一致)。
 * <p>
 * 判定胜负: 在落子点位向四个方向(横/竖/主斜/反斜)扫描, 任一方向有连续 5 个或以上同色即胜出。
 * 平局判定: 棋盘下满且无胜负 → 0。
 * <p>
 * 禁手(renju, 仅黑棋生效, 防止专业玩家先手必胜): 三三/四四/长连(超过 5 子)。本项目版本默认
 * 关闭禁手(主流五子棋线上对局大部分用标准规则), 开关保留用于将来开关 UI。
 */
public final class GomokuRules {

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /** 连续数 = 5 时判胜 */
    public static final int WIN_LEN = 5;

    private GomokuRules() {}

    /** 在 [r][c] 落 color 后, 是否产生胜出(返回五个连子坐标) */
    public static int[][] findWinLine(int[][] board, int r, int c) {
        int color = board[r][c];
        if (color == EMPTY) return null;
        int[][][] dirs = {
                {{0, 1}, {0, -1}},
                {{1, 0}, {-1, 0}},
                {{1, 1}, {-1, -1}},
                {{1, -1}, {-1, 1}}
        };
        for (int[][] pair : dirs) {
            List<int[]> line = new ArrayList<>();
            line.add(new int[]{r, c});
            for (int[] d : pair) {
                int nr = r, nc = c;
                while (inBounds(board, nr + d[0], nc + d[1]) && board[nr + d[0]][nc + d[1]] == color) {
                    nr += d[0]; nc += d[1];
                    line.add(new int[]{nr, nc});
                }
            }
            if (line.size() >= WIN_LEN) {
                return line.subList(0, WIN_LEN).toArray(new int[0][]);
            }
        }
        return null;
    }

    /** 是否平局: 棋盘满且无胜 */
    public static boolean isFull(int[][] board) {
        for (int[] row : board) for (int v : row) if (v == EMPTY) return false;
        return true;
    }

    /** 落子是否合法: 棋子在范围内 + 该位为空(落子方/胜负等其它业务校验交给上层) */
    public static boolean isLegalMove(int[][] board, int r, int c) {
        return inBounds(board, r, c) && board[r][c] == EMPTY;
    }

    /** 把 board 深拷贝一份(避免共享二维数组) */
    public static int[][] clone(int[][] board) {
        int[][] out = new int[board.length][];
        for (int i = 0; i < board.length; i++) out[i] = Arrays.copyOf(board[i], board[i].length);
        return out;
    }

    public static boolean inBounds(int[][] board, int r, int c) {
        return r >= 0 && r < board.length && c >= 0 && c < board[0].length;
    }

    /** 创建 size×size 的空棋盘 */
    public static int[][] emptyBoard(int size) {
        return new int[size][size];
    }
}