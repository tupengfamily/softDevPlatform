import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import router from '../router'

/**
 * Axios 封装
 * <p>
 * - 请求拦截器: 自动注入 Bearer Token
 * - 响应拦截器:
 *   - 后端 {code, message, data} 统一响应,code !== 200 弹窗报错
 *   - HTTP 401 → 清 token + 跳登录页
 *   - HTTP 403 → 提示无权限
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  (response) => {
    const data = response.data
    // 后端统一返回 { code, message, data }
    if (data && data.code !== undefined && data.code !== 200) {
      ElMessage.error(data.message || '请求失败')
      return Promise.reject(new Error(data.message || '请求失败'))
    }
    return data
  },
  (error) => {
    if (error.response) {
      const status = error.response.status
      const message = error.response.data?.message || error.message

      if (status === 401) {
        const userStore = useUserStore()
        const wasGuest = !userStore.token
        userStore.clear()
        if (wasGuest) {
          // 游客访问了需要登录的功能: 温和引导登录,不做"过期"误导
          ElMessage.info('该功能需要登录后使用')
        } else {
          // 优先用后端返回的具体原因(如"Token 无效或已过期"),否则用默认提示
          ElMessage.error(message || '登录已过期,请重新登录')
        }
        // 仅在不是登录页时跳转,避免循环
        if (router.currentRoute.value.path !== '/login') {
          router.push({
            path: '/login',
            query: { redirect: router.currentRoute.value.fullPath }
          })
        }
      } else if (status === 403) {
        ElMessage.error(message || '无权限访问')
      } else if (status === 404) {
        ElMessage.error(message || '资源不存在')
      } else if (status === 409) {
        ElMessage.error(message || '数据冲突')
      } else if (status === 500) {
        ElMessage.error('服务器错误: ' + message)
      } else {
        ElMessage.error(`请求失败 (${status}): ${message}`)
      }
    } else if (error.request) {
      ElMessage.error('网络异常,请检查后端服务是否启动')
    } else {
      ElMessage.error(error.message || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request