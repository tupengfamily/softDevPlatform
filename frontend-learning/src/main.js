import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { useThemeStore } from './store/theme'
import './assets/styles/main.css'

const app = createApp(App)

// Pinia 必须在 router 之前注册,这样路由守卫才能读取 store
app.use(createPinia())
// 应用已保存的页面主题(经典配色换肤,详见 store/theme.js)
useThemeStore().apply()
app.use(ElementPlus, {
  locale: zhCn
})

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(router)

app.mount('#app')