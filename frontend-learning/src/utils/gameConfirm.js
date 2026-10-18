import { ElMessageBox } from 'element-plus'

/**
 * 检测"是否真的有进行中的局"
 * <p>
 * 判定条件: 局面未结束 且 (moves >= 1 或 score > 0)
 * <p>
 * 为什么不是「棋盘非空」: 刚进入游戏时 spawn 会自动放 2 颗, 这是系统行为不是用户操作,
 * 此时点「新游戏」属于正常操作, 不该弹确认。至少走了一步或得过分才算「进行中」。
 * <p>
 * 已死局(over=true)不弹: 死局本来就要"再来一局"。
 *
 * @param {{board?:any[][], score?:number, moves?:number, over?:boolean, won?:boolean}} state
 * @returns {boolean}
 */
export function hasOngoingGame (state) {
  if (!state) return false
  if (state.over) return false
  const moves = state.moves || 0
  const score = state.score || 0
  return moves >= 1 || score > 0
}

/**
 * 弹出"检测到进行中的对局"确认框: 用户可选择 继续 / 重开 / 取消
 * <p>
 * 用 Element Plus 的 ElMessageBox 实现, 三个按钮, 危险操作 confirmButtonClass 红色.
 * 返回用户选择: 'continue' | 'restart' | 'cancel'
 *
 * @param {object} ctx
 * @param {string} ctx.title          弹窗标题(如 "进行中的 2048")
 * @param {string} ctx.message        主文案(说明当前局面)
 * @param {string} ctx.detail         副文案(如 "分数: 1024 · 步数: 28")
 * @returns {Promise<'continue'|'restart'|'cancel'>} cancel 包含取消和关闭弹窗
 */
export async function confirmOngoingGame ({ title, message, detail }) {
  // title 用纯文本(无 HTML 风险); message 含 <br>/<span> 必须打开 dangerouslyUseHTMLString
  // 安全: 这里的 title/message/detail 都是 i18n key + 数字(score/moves),不含用户输入
  const html = detail
    ? `<div style="font-size:13px;color:#374151;line-height:1.6">${message}<br><span style="color:#6b7280;font-size:12px">${detail}</span></div>`
    : `<div style="font-size:13px;color:#374151;line-height:1.6">${message}</div>`
  try {
    const r = await ElMessageBox({
      title,
      message: html,
      dangerouslyUseHTMLString: true,
      showCancelButton: true,
      showClose: true,
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      confirmButtonText: '重开新局',
      cancelButtonText: '继续对局',
      confirmButtonClass: 'el-button--danger'
    })
    // 点了「重开新局」
    return r === 'confirm' || r === 'close' ? 'restart' : 'continue'
  } catch (e) {
    // 点了「继续对局」或关闭 → 取消重开
    return 'cancel'
  }
}