import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 构建配置
// 重点: 配置前端开发服务器代理(/api 请求转发到后端 localhost:8080)
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 监听所有 v4 接口(0.0.0.0): 既保留 localhost / 127.0.0.1,又允许内网穿透(127.0.0.1)和同网段访问
    // 默认 vite 在 macOS 上只听 [::1](IPv6),hsk-cli tunnel 用 v4 连会 502
    host: '0.0.0.0',
    // Vite 默认 allowedHosts 只放 ['localhost', '127.0.0.1'];hsk-cli 给的公网域名不在内
    // 会 403: "Blocked request. This host is not allowed." -> 设为 true 放行所有 host
    // 公开访问只在你愿意暴露时打开,生产部署别用 dev server
    allowedHosts: true,
    open: true,
    // 代理配置: 解决前端开发时的跨域问题
    // 浏览器访问 http://localhost:5173/api/users
    // 实际请求转发到 http://localhost:8080/api/users
    // 优先用 BACKEND_PORT 环境变量,否则默认 8080
    proxy: {
      '/api': {
        target: process.env.BACKEND_PORT
          ? `http://localhost:${process.env.BACKEND_PORT}`
          : 'http://localhost:8080',
        changeOrigin: true
      },
      // 用户上传文件(头像等)由后端提供静态访问
      '/uploads': {
        target: process.env.BACKEND_PORT
          ? `http://localhost:${process.env.BACKEND_PORT}`
          : 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
})