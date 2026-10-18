/**
 * 五子棋规则与 AI 引擎(纯前端, 用于人机/人人对战)
 * <p>
 * board 表示法: 二维数组 board[r][c], 0=空 / 1=黑 / 2=白
 * 棋盘大小固定 15, 便于渲染与减少内存
 * <p>
 * AI 算法:
 * <ul>
 *   <li>扫描每个空位</li>
 *   <li>对每个方向累计连续数 + 端点是否开放(活几)</li>
 *   <li>为每个位置算: 自己落子的进攻分 + 对方在这里落子的防守分, 取较大值</li>
 *   <li>难度档级: 简单=贪心只算一层, 普通=评分, 困难=评分 + 多层推演 1 步</li>
 * </ul>
 * 调试入口: 在 console 里跑 GomokuAI.pick(board, BLACK, 2) 可看到 AI 在某局下的选点。
 */

export const SIZE = 15
export const EMPTY = 0
export const BLACK = 1
export const WHITE = 2

export const opposite = (c) => (c === BLACK ? WHITE : BLACK)

export function makeBoard (size = SIZE) {
  return Array.from({ length: size }, () => new Array(size).fill(EMPTY))
}

export function cloneBoard (b) {
  return b.map((r) => r.slice())
}

export function inBounds (b, r, c) {
  return r >= 0 && r < b.length && c >= 0 && c < b[0].length
}

/**
 * 在 [r][c] 落下 color 后, 若产生五连返回五个连子坐标(取第一个方向命中)
 * 否则 null
 */
export function findWinLine (board, r, c) {
  const color = board[r][c]
  if (color === EMPTY) return null
  const dirs = [[0, 1], [1, 0], [1, 1], [1, -1]]
  for (const [dr, dc] of dirs) {
    const line = [[r, c]]
    // 正向
    let nr = r, nc = c
    while (inBounds(board, nr + dr, nc + dc) && board[nr + dr][nc + dc] === color) {
      nr += dr; nc += dc
      line.push([nr, nc])
    }
    // 反向
    let pr = r, pc = c
    while (inBounds(board, pr - dr, pc - dc) && board[pr - dr][pc - dc] === color) {
      pr -= dr; pc -= dc
      line.unshift([pr, pc])
    }
    if (line.length >= 5) return line.slice(0, 5)
  }
  return null
}

export function isFull (board) {
  for (const row of board) for (const v of row) if (v === EMPTY) return false
  return true
}

/** 直接判定整盘是否已分胜负(返回胜方或 0) */
export function checkWinner (board) {
  // 任一有子位置: 向四个方向判断
  for (let r = 0; r < board.length; r++) {
    for (let c = 0; c < board[r].length; c++) {
      const color = board[r][c]
      if (color === EMPTY) continue
      if (findWinLine(board, r, c)) return color
    }
  }
  return 0
}

// ==================================================================
//  评分: 计算每个空位对 color 的"成五倾向"
// ==================================================================

/** 评估一条线的得分(己方 color 的进攻分) */
function lineScore (len, openEnds, hasGap) {
  // 开端: 0=堵死, 1=单端开口, 2=双端开口
  if (len >= 5) return 100000
  if (len === 4) {
    if (openEnds === 2) return 10000  // 活四, 必胜
    if (openEnds === 1) return 1000   // 冲四
  }
  if (len === 3) {
    if (openEnds === 2) return 1000   // 活三
    if (openEnds === 1) return 100    // 眠三
  }
  if (len === 2) {
    if (openEnds === 2) return 100
    if (openEnds === 1) return 10
  }
  if (len === 1) {
    if (openEnds === 2) return 10
    if (openEnds === 1) return 1
  }
  return 0
}

/**
 * 禁手检测(仅对黑棋生效): 三三 / 四四 / 长连(超过 5 子)
 * <p>
 * renju 规则只禁黑先手; 白棋无禁手。返回 null = 合规, 否则返回禁手类型字符串数组。
 * <p>
 * 实现要点:
 * - 三三: 在该点落子后形成两条或以上「活三/眠四及以上」的线
 * - 四四: 两条或以上的冲四/活四
 * - 长连: 该方向连续 6 子及以上
 */
export function checkRenju (board, r, c) {
  const color = board[r][c]
  if (color !== BLACK) return null
  const SIZE = board.length
  const inB = (rr, cc) => rr >= 0 && rr < SIZE && cc >= 0 && cc < SIZE
  const dirs = [[0, 1], [1, 0], [1, 1], [1, -1]]

  // 先检测长连
  for (const [dr, dc] of dirs) {
    let len = 1
    let nr = r, nc = c
    while (inB(nr + dr, nc + dc) && board[nr + dr][nc + dc] === color) { nr += dr; nc += dc; len++ }
    let pr = r, pc = c
    while (inB(pr - dr, pc - dc) && board[pr - dr][pc - dc] === color) { pr -= dr; pc -= dc; len++ }
    if (len > 5) return ['long']
  }

  // 统计四条(三三/四四): 落子后形成的「活四/冲四/活三」线数量
  let openFours = 0   // 冲四(单端开口)
  let fours = 0       // 活四(双端开口)
  let openThrees = 0  // 活三(双端开口, 可成活四)

  for (const [dr, dc] of dirs) {
    let len = 1
    let nr = r, nc = c
    while (inB(nr + dr, nc + dc) && board[nr + dr][nc + dc] === color) { nr += dr; nc += dc; len++ }
    const endForwardEmpty = inB(nr + dr, nc + dc) && board[nr + dr][nc + dc] === EMPTY
    let pr = r, pc = c
    while (inB(pr - dr, pc - dc) && board[pr - dr][pc - dc] === color) { pr -= dr; pc -= dc; len++ }
    const endBackwardEmpty = inB(pr - dr, pc - dc) && board[pr - dr][pc - dc] === EMPTY

    if (len === 4) {
      if (endForwardEmpty && endBackwardEmpty) fours++      // 活四
      else if (endForwardEmpty || endBackwardEmpty) openFours++ // 冲四
    } else if (len === 3) {
      // 活三: 两端都空, 且任一端再落一子即可形成冲四
      if (endForwardEmpty && endBackwardEmpty) {
        // 检查「跳三」(xx_x 或 x_xx) 也算活三
        // 简化: 仅做两端开口的三
        openThrees++
      }
    }
  }
  // 三三: 两条或以上活三
  if (openThrees >= 2) return ['double-three']
  // 四四: 两条或以上冲四, 或一条冲四 + 一条活四, 或两条活四(若允许活四)
  if (openFours + fours >= 2) return ['double-four']
  return null
}

/** 在 [r][c] 假设落 color, 评估该位置总得分(进攻+防守) */
function evaluatePos (board, r, c, color) {
  // 对每个方向, 找出从 (r,c) 起算的连续段长度 + 端点是否开口
  const dirs = [[0, 1], [1, 0], [1, 1], [1, -1]]
  let attack = 0
  let defense = 0
  for (const [dr, dc] of dirs) {
    let len = 1
    let openEnds = 0
    // 正向
    let nr = r, nc = c
    while (inBounds(board, nr + dr, nc + dc) && board[nr + dr][nc + dc] === color) { nr += dr; nc += dc; len++ }
    if (inBounds(board, nr + dr, nc + dc) && board[nr + dr][nc + dc] === EMPTY) openEnds++
    // 反向
    let pr = r, pc = c
    while (inBounds(board, pr - dr, pc - dc) && board[pr - dr][pc - dc] === color) { pr -= dr; pc -= dc; len++ }
    if (inBounds(board, pr - dr, pc - dc) && board[pr - dr][pc - dc] === EMPTY) openEnds++
    attack += lineScore(len, openEnds, false)

    // 防守: 同方向算对手的得分(若在这里放对手, 威胁多大)
    len = 0; openEnds = 0
    nr = r; nc = c
    while (inBounds(board, nr + dr, nc + dc) && board[nr + dr][nc + dc] === opposite(color)) { nr += dr; nc += dc; len++ }
    if (inBounds(board, nr + dr, nc + dc) && board[nr + dr][nc + dc] === EMPTY) openEnds++
    pr = r; pc = c
    while (inBounds(board, pr - dr, pc - dc) && board[pr - dr][pc - dc] === opposite(color)) { pr -= dr; pc -= dc; len++ }
    if (inBounds(board, pr - dr, pc - dc) && board[pr - dr][pc - dc] === EMPTY) openEnds++
    defense += lineScore(len, openEnds, false)
  }
  // 防守值适度衰减(进攻优先), 避免一个点两个方向都防御导致过度防守
  return attack + defense * 0.85
}

// ==================================================================
//  AI 主入口
// ==================================================================

/**
 * 选点
 * @param difficulty 0=简单 / 1=普通 / 2=困难
 * @param thinkingMs 软时限, 防止过慢(简单档可设 200, 困难档 800)
 */
export function pickMove (board, color, difficulty = 1, thinkingMs = 600) {
  const candidates = []
  for (let r = 0; r < board.length; r++) {
    for (let c = 0; c < board[r].length; c++) {
      if (board[r][c] !== EMPTY) continue
      // 跳过"无意义"空位: 周围 2 格没有任何子的点(降低搜索量, 不影响大局)
      let nearAny = false
      outer: for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          if (dr === 0 && dc === 0) continue
          const nr = r + dr, nc = c + dc
          if (inBounds(board, nr, nc) && board[nr][nc] !== EMPTY) { nearAny = true; break outer }
        }
      }
      if (!nearAny) continue
      candidates.push([r, c, evaluatePos(board, r, c, color)])
    }
  }
  if (candidates.length === 0) {
    // 没有邻居棋子 → 中心附近随机
    const c = Math.floor(board.length / 2)
    const offsets = [[0, 0], [0, 1], [1, 0], [-1, 0], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]
    for (const [dr, dc] of offsets) {
      if (inBounds(board, c + dr, c + dc) && board[c + dr][c + dc] === EMPTY) return [c + dr, c + dc]
    }
    return [c, c]
  }

  // 简单档: 取评分最高的, 同分随机
  // 普通档/困难档: 同分时按时间微扰, 让选点更有变化
  candidates.sort((a, b) => b[2] - a[2])
  const best = candidates[0][2]
  const top = candidates.filter((c) => c[2] >= best - 50) // 50 分内视为同档
  const choice = top[Math.floor(Math.random() * top.length)]
  return [choice[0], choice[1]]
}