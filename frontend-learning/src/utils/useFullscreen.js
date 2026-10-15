// 统一全屏控制: 用浏览器原生 Fullscreen API (requestFullscreen)
// 不是 "弹窗/遮罩" 模拟, 是真正的无 chrome 全屏 (没有地址栏 / 没有 Layout 头部)
//
// 用法:
//   const { isFullscreen, enter, exit, toggle, target } = useFullscreen()
//   <div ref="target" :class="{ 'is-fullscreen': isFullscreen }">
//     <button @click="toggle">全屏</button>
//     ...
//   </div>
//
// 兼容性:
//   - 现代浏览器 (Chrome/Edge/Safari/Firefox) 都支持
//   - 需要用户手势 (click 事件触发)
//   - 仅在 HTTPS 或 localhost 可用

import { ref, onBeforeUnmount } from 'vue'

export function useFullscreen() {
  const isFullscreen = ref(false)
  const target = ref(null)            // 模板里挂到要全屏的元素
  const onChange = () => {
    // 监听浏览器原生的 fullscreenchange, 同步 isFullscreen
    isFullscreen.value = !!document.fullscreenElement
  }
  if (typeof window !== 'undefined') {
    document.addEventListener('fullscreenchange', onChange)
    document.addEventListener('webkitfullscreenchange', onChange)
  }
  onBeforeUnmount(() => {
    if (typeof document !== 'undefined') {
      document.removeEventListener('fullscreenchange', onChange)
      document.removeEventListener('webkitfullscreenchange', onChange)
    }
  })

  const enter = async () => {
    if (!target.value) return
    if (document.fullscreenElement) return  // 已在全屏
    try {
      await target.value.requestFullscreen()
    } catch (e) {
      // 用户拒绝 / 不支持时静默 (避免在 F11 / 移动 Safari 等环境弹错)
      console.warn('[useFullscreen] enter failed:', e?.message)
    }
  }
  const exit = async () => {
    if (!document.fullscreenElement) return
    try { await document.exitFullscreen() } catch (e) { /* 静默 */ }
  }
  const toggle = () => (isFullscreen.value ? exit() : enter())

  return { isFullscreen, target, enter, exit, toggle }
}
