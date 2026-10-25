/**
 * 「稼穑」游戏画面上下文持久化（2026-10-23）。
 *
 * <p><b>需求</b>：在游戏内刷新页面时，应该还保持在当前游戏画面
 * （同一个农场、同一块地），而不是跳回中国地图概览。
 *
 * <p><b>为什么用 sessionStorage 而不是 localStorage</b>：
 * localStorage 是**长期**的 —— 玩家上周关掉浏览器，今天打开还是上次的农场，
 * 会觉得「怎么一进来就在那个破农场里」。sessionStorage 的生命周期是**标签页**，
 * 正好匹配「刷新保持、关掉标签页就复位」这个语义。
 *
 * <p><b>为什么存 slot + farmId 两个</b>：
 * slot 在用户范围内全局唯一，但 3D 场景里相邻的田可能属于**别的农场**
 * （玩家在地图上建了多个农场，各自的地块在全局网格上挨着）。
 * 只存 slot 恢复不出正确的农场上下文 → 切换条会显示错误的农场名。
 */

const KEY = 'farm:ctx'

/**
 * @typedef {object} FarmCtx
 * @property {number} slot      当前地块槽位
 * @property {number|null} farmId 当前农场 id（null = 未归属的老田）
 * @property {boolean} mapOpen  中国地图浮层是否打开
 */

/**
 * 读取上次退出时的画面上下文。
 * @returns {FarmCtx|null} 无记录或数据损坏时返回 null
 */
export function readFarmContext() {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const obj = JSON.parse(raw)
    if (!obj || typeof obj.slot !== 'number') return null
    return {
      slot: obj.slot,
      farmId: obj.farmId ?? null,
      mapOpen: !!obj.mapOpen
    }
  } catch (e) {
    // JSON 损坏 / sessionStorage 不可用（隐私模式）→ 当作没有记录
    return null
  }
}

/**
 * 保存当前画面上下文。
 * @param {FarmCtx} ctx
 */
export function writeFarmContext(ctx) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify({
      slot: ctx.slot,
      farmId: ctx.farmId ?? null,
      mapOpen: !!ctx.mapOpen
    }))
  } catch (e) {
    // 存储不可用不影响主流程，忽略
  }
}

/**
 * 清除画面上下文（玩家主动「返回农场列表 / 退出游戏」时调用）。
 */
export function clearFarmContext() {
  try {
    sessionStorage.removeItem(KEY)
  } catch (e) {
    // 忽略
  }
}
