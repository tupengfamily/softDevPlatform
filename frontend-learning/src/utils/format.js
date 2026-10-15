/**
 * 通用格式化工具
 */

/** 把毫秒格式化为「X 分 Y 秒 / X.X 秒」 */
export function fmtMs(ms) {
  if (ms == null || isNaN(ms)) return '-'
  if (ms < 1000) return Math.round(ms) + ' ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + ' 秒'
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms - m * 60000) / 1000)
  return s > 0 ? `${m} 分 ${s} 秒` : `${m} 分`
}

/** 百分比(数值 -> 字符串带 %, 保留 1 位小数) */
export function fmtPct(value) {
  if (value == null || isNaN(value)) return '-'
  return Number(value).toFixed(1) + '%'
}

/** 大数加千分位 */
export function fmtNum(n) {
  if (n == null || isNaN(n)) return '-'
  return Number(n).toLocaleString('zh-CN')
}