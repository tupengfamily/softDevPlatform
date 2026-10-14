import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import i18n from './i18n'
import { useThemeStore } from './store/theme'
import './assets/styles/main.css'

const app = createApp(App)

// Pinia 必须在 router 之前注册,这样路由守卫才能读取 store
app.use(createPinia())
// 应用已保存的页面主题(经典配色换肤,详见 store/theme.js)
useThemeStore().apply()
// Element Plus 内置文案(分页/日期等)由 App.vue 的 el-config-provider 按当前语言联动
app.use(ElementPlus, {
  locale: zhCn
})
app.use(i18n)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(router)

// 全局错误处理: 把 setup/render 阶段异常打到 console(开发期必备, 避免被 Vue dev 模式静默吞掉)
app.config.errorHandler = (err, instance, info) => {
  console.error('[Vue] errorHandler:', err && (err.stack || err.message), '| info:', info)
}
app.config.warnHandler = (msg, instance, trace) => {
  // 过滤大量噪音警告, 留关键
  if (msg && /(Invalid prop|missing required|Missing required|invalid prop)/i.test(msg)) {
    console.warn('[Vue] warnHandler:', msg, '| trace:', trace)
  }
}
window.addEventListener('error', e => console.error('[window.onerror]', e.message, e.error && e.error.stack))
window.addEventListener('unhandledrejection', e => console.error('[unhandledrejection]', e.reason))

app.mount('#app')