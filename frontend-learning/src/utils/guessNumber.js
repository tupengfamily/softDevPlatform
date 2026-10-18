// 猜数字 (Bulls and Cows / Mastermind) 核心算法
// 规则: 系统随机生成 N 位 0-9 不重复数字, 玩家逐次猜,
//       反馈 X A Y B (A=位置和数字都对, B=数字对位置错)
// 难度: easy=4位 / normal=5位 / hard=6位

export const DIFFICULTY = [
  { code: 'easy', length: 4, maxAttempts: 10, labelKey: 'guess.diff.easy' },
  { code: 'normal', length: 5, maxAttempts: 12, labelKey: 'guess.diff.normal' },
  { code: 'hard', length: 6, maxAttempts: 14, labelKey: 'guess.diff.hard' }
]

/** 给定长度生成 N 位不重复数字, 首数字也可以为 0 */
export function generateAnswer (length) {
  if (length < 4 || length > 10) length = 4
  const digits = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
  // Fisher-Yates 洗牌
  for (let i = digits.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[digits[i], digits[j]] = [digits[j], digits[i]]
  }
  return digits.slice(0, length).join('')
}

/** 判定 guess 与 answer 的 Bulls and Cows
 * 返回 { A: 位置和数字都对的位数, B: 数字对但位置错的位数 }
 */
export function judge (guess, answer) {
  let a = 0
  let b = 0
  const seen = {}
  for (let i = 0; i < answer.length; i++) {
    const g = guess[i]
    const an = answer[i]
    if (g === an) {
      a++
    } else {
      // 数字对了位置错了: 数字出现在对方未匹配过的位置
      seen[an] = (seen[an] || 0) + 1
    }
  }
  for (let i = 0; i < answer.length; i++) {
    const g = guess[i]
    const an = answer[i]
    if (g !== an && seen[g] > 0) {
      b++
      seen[g]--
    }
  }
  return { A: a, B: b }
}

/** 校验玩家输入: 仅数字, 长度符合, 数字不重复, 不能全相同 */
export function validateGuess (input, length) {
  if (typeof input !== 'string') return '请输入数字'
  const s = input.trim()
  if (s.length !== length) return `需要 ${length} 位数字`
  if (!/^\d+$/.test(s)) return '只能输入数字 0-9'
  const set = new Set(s)
  if (set.size !== s.length) return '数字不能重复'
  return null
}

/** 是否胜利: A 等于总长度 */
export function isWin (result) {
  return result && result.A === (result.length || 0)
}