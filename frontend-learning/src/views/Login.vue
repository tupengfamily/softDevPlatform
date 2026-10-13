<script setup>
/**
 * 登录页
 * <p>
 * - 居中卡片
 * - 调用 auth.js.login,Pinia store 保存 token
 * - 成功后跳到 redirect 或 /dashboard
 */
import { ref, reactive, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { t } = useI18n()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = computed(() => ({
  username: [{ required: true, message: t('login.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.passwordRequired'), trigger: 'blur' }]
}))

const onSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.login({ username: form.username, password: form.password })
      ElMessage.success(t('login.success'))
      // 有明确来源页就回来源页;否则交给根路由 "/" 按角色分流
      // (管理员 → 仪表盘, 普通用户 → 学习中心, 由 router 的 redirect 统一决定)
      // redirect 校验: 仅接受站内相对路径(以 / 开头且非 // 协议相对),防开放跳转
      const from = route.query.redirect
      const isSafePath = typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')
      const target = (isSafePath && from !== '/login' && from !== '/register') ? from : '/'
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
        <img class="brand-mark" src="/favicon.svg" alt="" aria-hidden="true">
        <h2>{{ $t('login.title') }}</h2>
        <p>{{ $t('login.subtitle') }}</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" size="large" @submit.prevent="onSubmit">
        <el-form-item :label="$t('login.username')" prop="username">
          <el-input v-model="form.username" :placeholder="$t('login.usernamePlaceholder')" prefix-icon="User" />
        </el-form-item>
        <el-form-item :label="$t('login.password')" prop="password">
          <el-input v-model="form.password" type="password" :placeholder="$t('login.passwordPlaceholder')" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="onSubmit">{{ $t('login.submit') }}</el-button>
        <div class="auth-tip">
          {{ $t('login.noAccount') }} <router-link to="/register">{{ $t('login.registerNow') }}</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 浅色柔和渐变 + 柔光晕染(业界通用的现代登录页背景) */
.auth-page {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  position: relative; overflow: hidden;
  background:
    radial-gradient(900px 420px at 12% -8%, rgba(79, 70, 229, .12), transparent 62%),
    radial-gradient(820px 420px at 108% 112%, rgba(37, 99, 235, .10), transparent 58%),
    linear-gradient(160deg, #eef2f9 0%, #f7f9fc 48%, #edf1f8 100%);
}
.auth-page::before, .auth-page::after {
  content: ''; position: absolute; border-radius: 50%;
  filter: blur(60px); opacity: .55; pointer-events: none;
}
.auth-page::before {
  width: 420px; height: 420px; left: -120px; top: -120px;
  background: radial-gradient(circle, rgba(99, 102, 241, .28), transparent 70%);
}
.auth-page::after {
  width: 480px; height: 480px; right: -160px; bottom: -180px;
  background: radial-gradient(circle, rgba(56, 189, 248, .22), transparent 70%);
}
.auth-card {
  width: 420px;
  position: relative; z-index: 1;
  border: 1px solid rgba(226, 232, 240, .9);
  border-radius: 14px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .04), 0 16px 40px rgba(15, 23, 42, .10);
}
.auth-header { text-align: center; margin-bottom: 24px; }
.brand-mark {
  width: 52px; height: 52px; margin: 0 auto 14px;
  display: block;
  border-radius: 14px;
  background: var(--c-primary-grad, linear-gradient(135deg, #2563eb, #4f46e5)); /* 图标加载失败的兜底 */
  box-shadow: 0 8px 20px rgba(37, 99, 235, .32);
}
.auth-header h2 { margin: 0 0 6px 0; color: #1f2937; font-size: 22px; letter-spacing: .3px; }
.auth-header p { margin: 0; color: #6b7280; font-size: 13px; }
.auth-tip { text-align: center; margin-top: 16px; font-size: 13px; }
.auth-tip a { color: var(--c-primary, #2563eb); text-decoration: none; }

/* 移动端: 卡片贴边全宽 */
@media (max-width: 480px) {
  .auth-card { width: calc(100vw - 32px); }
}
</style>