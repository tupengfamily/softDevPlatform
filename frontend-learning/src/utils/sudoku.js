// 数独核心算法
// 规则: 9x9 网格分成 9 个 3x3 宫, 每行/每列/每宫 1-9 各出现一次

export const SIZE = 9
export const BOX_SIZE = 3

/** 难度配置: 已知格数(剩余空格 = 81 - 已知数) */
export const DIFFICULTY = {
  easy: { code: 'easy', given: 46, maxHints: 8 },
  normal: { code: 'normal', given: 36, maxHints: 5 },
  hard: { code: 'hard', given: 28, maxHints: 3 }
}

// ==================== 内部工具 ====================

/** 创建 9x9 空棋盘 */
function makeEmpty () {
  return Array.from({ length: SIZE }, () => new Array(SIZE).fill(0))
}

/** 深拷贝 */
function copy (board) {
  return board.map(row => row.slice())
}

/** 数字 1-9 在 (r, c) 位置是否可填 */
function isValid (board, r, c, n) {
  // 行
  for (let j = 0; j < SIZE; j++) if (board[r][j] === n) return false
  // 列
  for (let i = 0; i < SIZE; i++) if (board[i][c] === n) return false
  // 宫
  const br = Math.floor(r / BOX_SIZE) * BOX_SIZE
  const bc = Math.floor(c / BOX_SIZE) * BOX_SIZE
  for (let i = br; i < br + BOX_SIZE; i++) {
    for (let j = bc; j < bc + BOX_SIZE; j++) {
      if (board[i][j] === n) return false
    }
  }
  return true
}

/** 找下一个空格的位置(从上到下从左到右); 没有则返回 null */
function findEmpty (board) {
  for (let i = 0; i < SIZE; i++) {
    for (let j = 0; j < SIZE; j++) {
      if (board[i][j] === 0) return [i, j]
    }
  }
  return null
}

/** 1-9 洗牌后的数组 */
function shuffledDigits () {
  const a = [1, 2, 3, 4, 5, 6, 7, 8, 9]
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[a[i], a[j]] = [a[j], a[i]]
  }
  return a
}

/** 回溯求解, 默认最多返回 2 个解(用于唯一性判定) */
function solveInner (board, limit = 1) {
  const empty = findEmpty(board)
  if (!empty) return 1 // 已填满 → 找到一个解
  const [r, c] = empty
  let count = 0
  for (const n of shuffledDigits()) {
    if (!isValid(board, r, c, n)) continue
    board[r][c] = n
    count += solveInner(board, limit - count)
    if (count >= limit) {
      board[r][c] = 0
      return count
    }
    board[r][c] = 0
  }
  return count
}

/** 解数独, 返回解后的棋盘(第一个解), 无解返回 null */
export function solve (board) {
  const b = copy(board)
  const count = solveInner(b, 1)
  return count >= 1 ? b : null
}

/** 计算当前棋盘有 N 个解, 用于验证题目唯一性 */
export function countSolutions (board, limit = 2) {
  const b = copy(board)
  return solveInner(b, limit)
}

// ==================== 出题 ====================

/** 生成一个完整合法数独 */
export function generateFull () {
  const board = makeEmpty()
  // 用回溯填满整个棋盘
  function fill () {
    const empty = findEmpty(board)
    if (!empty) return true
    const [r, c] = empty
    for (const n of shuffledDigits()) {
      if (isValid(board, r, c, n)) {
        board[r][c] = n
        if (fill()) return true
        board[r][c] = 0
      }
    }
    return false
  }
  fill()
  return board
}

/** 随机挖洞生成题目
 * @param {'easy'|'normal'|'hard'} level
 * @returns {{ puzzle:number[][], solution:number[][] }}
 */
export function generatePuzzle (level = 'normal') {
  const cfg = DIFFICULTY[level] || DIFFICULTY.normal
  const solution = generateFull()
  const puzzle = copy(solution)

  // 所有格子的随机顺序
  const cells = []
  for (let i = 0; i < SIZE; i++) for (let j = 0; j < SIZE; j++) cells.push([i, j])
  for (let i = cells.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[cells[i], cells[j]] = [cells[j], cells[i]]
  }

  const targetEmpty = SIZE * SIZE - cfg.given
  let holes = 0

  for (const [r, c] of cells) {
    if (holes >= targetEmpty) break
    if (puzzle[r][c] === 0) continue
    const backup = puzzle[r][c]
    puzzle[r][c] = 0
    // 验证唯一性: 多解则回滚
    const temp = copy(puzzle)
    const cnt = countSolutions(temp, 2)
    if (cnt === 1) {
      holes++
    } else {
      // 多解, 还原
      puzzle[r][c] = backup
    }
  }

  return { puzzle, solution }
}

// ==================== 候选数 ====================

/** 计算每个空格的候选数字集合 1..9 (返回稀疏数组, 已填格返回 []) */
export function computeCandidates (board) {
  const result = Array.from({ length: SIZE }, () => new Array(SIZE).fill(0))
  for (let i = 0; i < SIZE; i++) {
    for (let j = 0; j < SIZE; j++) {
      if (board[i][j] !== 0) continue
      let mask = 0
      for (const n of [1, 2, 3, 4, 5, 6, 7, 8, 9]) {
        if (isValid(board, i, j, n)) mask |= (1 << n)
      }
      result[i][j] = mask
    }
  }
  return result
}

/** mask (位 1-9) 转数字数组 */
export function maskToDigits (mask) {
  const out = []
  for (let n = 1; n <= 9; n++) {
    if (mask & (1 << n)) out.push(n)
  }
  return out
}

/** 添加数字到候选 mask */
export function maskAdd (mask, n) {
  return mask | (1 << n)
}

/** 从候选 mask 移除数字 */
export function maskRemove (mask, n) {
  return mask & ~(1 << n)
}

// ==================== 校验 ====================

/** 检查当前填入是否正确(与 solution 比对) */
export function checkMistakes (board, solution) {
  const mistakes = []
  for (let i = 0; i < SIZE; i++) {
    for (let j = 0; j < SIZE; j++) {
      const v = board[i][j]
      if (v !== 0 && v !== solution[i][j]) mistakes.push([i, j])
    }
  }
  return mistakes
}

/** 当前棋盘是否已完整填满 */
export function isComplete (board) {
  for (let i = 0; i < SIZE; i++) {
    for (let j = 0; j < SIZE; j++) {
      if (board[i][j] === 0) return false
    }
  }
  return true
}

/** 数独是否正确填满(无空无错) */
export function isSolved (board, solution) {
  if (!isComplete(board)) return false
  for (let i = 0; i < SIZE; i++) {
    for (let j = 0; j < SIZE; j++) {
      if (board[i][j] !== solution[i][j]) return false
    }
  }
  return true
}

/** 给一个提示: 找第一个空格并返回答案 */
export function getHint (board, solution) {
  for (let i = 0; i < SIZE; i++) {
    for (let j = 0; j < SIZE; j++) {
      if (board[i][j] === 0) return [i, j, solution[i][j]]
    }
  }
  return null
}

// ==================== 坐标工具 ====================

export const cellKey = (r, c) => `${r},${c}`
export function parseKey (k) {
  const [r, c] = k.split(',').map(Number)
  return [r, c]
}