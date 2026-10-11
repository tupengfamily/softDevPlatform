<script setup>
/**
 * 登录页
 * <p>
 * - 居中卡片
 * - 调用 auth.js.login,Pinia store 保存 token
 * - 成功后跳到 redirect 或 /dashboard
 */
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: 'admin123'
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const onSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.login({ username: form.username, password: form.password })
      ElMessage.success('登录成功')
      // 有明确来源页就回来源页;否则交给根路由 "/" 按角色分流
      // (管理员 → 仪表盘, 普通用户 → 学习中心, 由 router 的 redirect 统一决定)
      const from = route.query.redirect
      const target = (from && from !== '/login' && from !== '/register') ? from : '/'
      router.push(target)
    } catch (e) {
      // 拦截器已弹窗
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <div class="auth-header">
        <h2>通用学习平台</h2>
        <p>Spring Boot 3 + Vue 3 + Element Plus + SQLite</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" size="large" @submit.prevent="onSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="onSubmit">登 录</el-button>
        <div class="auth-tip">
          还没有账号? <router-link to="/register">立即注册</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #2563eb 0%, #4f46e5 100%);
}
.auth-card {
  width: 420px;
  border-radius: var(--r-s);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}
.auth-header { text-align: center; margin-bottom: 24px; }
.auth-header h2 { margin: 0 0 6px 0; color: #303133; }
.auth-header p { margin: 0; color: #6b7280; font-size: 13px; }
.auth-tip { text-align: center; margin-top: 16px; font-size: 13px; }
.auth-tip a { color: #2563eb; text-decoration: none; }
</style>