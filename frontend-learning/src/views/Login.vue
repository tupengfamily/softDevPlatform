<script setup>
/**
 * 登录页
 * <p>
 * - 居中卡片
 * - 调用 auth.js.login,Pinia store 保存 token
 * - 成功后跳到 redirect 或 /dashboard
 * - 记住账号密码(本地便利,非远程凭据): 默认仅"记住账号",勾"记住密码"才存密码;
 *   localStorage 内容仅对当前 origin 生效,不跨设备同步
 */
import { ref, reactive, computed, onMounted } from 'vue'
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

// "记住账号" 默认开(只记用户名,不记密码), "记住密码" 默认关(更敏感)
// 切到密码输入型 "记住密码" 时仍由用户主动勾选
const rememberAccount = ref(true)
const rememberPassword = ref(false)

const rules = computed(() => ({
  username: [{ required: true, message: t('login.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.passwordRequired'), trigger: 'blur' }]
}))

const REMEMBER_USER_KEY = 'login.rememberedUsername'
const REMEMBER_PWD_KEY = 'login.rememberedPassword'

// 页面加载时: 若 localStorage 有上次记住的账号, 自动填入
onMounted(() => {
  try {
    const u = localStorage.getItem(REMEMBER_USER_KEY)
    if (u) form.username = u
    // 密码只有"记住账号密码"勾选时才读 + 写; 不主动读
  } catch (_) { /* localStorage 异常时静默 */ }
})

const onSubmit = async () => {
  if (!formRef.value) return
  if (loading.value) return   // 防双击 / 双回车
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.login({ username: form.username, password: form.password })
      // 登录成功: 按勾选状态写/清 localStorage
      try {
        if (rememberAccount.value) {
          localStorage.setItem(REMEMBER_USER_KEY, form.username)
        } else {
          localStorage.removeItem(REMEMBER_USER_KEY)
        }
        if (rememberAccount.value && rememberPassword.value) {
          localStorage.setItem(REMEMBER_PWD_KEY, form.password)
        } else {
          localStorage.removeItem(REMEMBER_PWD_KEY)
        }
      } catch (_) { /* localStorage 不可用时静默 */ }

      ElMessage.success(t('login.success'))
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
          <el-input v-model="form.password" type="password" :placeholder="$t('login.passwordPlaceholder')" prefix-icon="Lock" show-password @keyup.enter="onSubmit" />
        </el-form-item>
        <!-- 记住账号 + 记住密码: 单独 checkbox,默认"记住账号"开, "记住密码"关 -->
        <div class="remember-row">
          <el-checkbox v-model="rememberAccount" size="small">{{ $t('login.rememberAccount') }}</el-checkbox>
          <el-checkbox v-model="rememberPassword" size="small" :disabled="!rememberAccount" @change="rememberPassword && rememberAccount">
            {{ $t('login.rememberPassword') }}
          </el-checkbox>
          <el-tooltip :content="$t('login.rememberTip')" placement="top" effect="light">
            <el-icon class="tip-icon"><InfoFilled /></el-icon>
          </el-tooltip>
        </div>
        <el-button type="primary" :loading="loading" style="width:100%;margin-top:4px" @click="onSubmit">{{ $t('login.submit') }}</el-button>
        <div class="auth-tip">
          {{ $t('login.noAccount') }} <router-link to="/register">{{ $t('login.registerNow') }}</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
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
  background: var(--c-primary-grad, linear-gradient(135deg, #2563eb, #4f46e5));
  box-shadow: 0 8px 20px rgba(37, 99, 235, .32);
}
.auth-header h2 { margin: 0 0 6px 0; color: #1f2937; font-size: 22px; letter-spacing: .3px; }
.auth-header p { margin: 0; color: #6b7280; font-size: 13px; }
.auth-tip { text-align: center; margin-top: 16px; font-size: 13px; }
.auth-tip a { color: var(--c-primary, #2563eb); text-decoration: none; }

/* 记住账号密码行 */
.remember-row {
  display: flex; align-items: center; gap: 14px; margin: 0 0 4px 84px; font-size: 12px; color: #4b5563;
}
.tip-icon { color: #94a3b8; font-size: 14px; cursor: help; }
.tip-icon:hover { color: #64748b; }

@media (max-width: 480px) {
  .auth-card { width: calc(100vw - 32px); }
  .remember-row { margin-left: 0; flex-wrap: wrap; }
}
</style>