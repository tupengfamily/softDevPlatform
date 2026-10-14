<script setup>
/**
 * 注册页
 */
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../store/user'

const router = useRouter()
const userStore = useUserStore()
const { t } = useI18n()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  email: '',
  password: '',
  confirm: '',
  age: 18,
  address: ''
})

const validateConfirm = (rule, value, cb) => {
  if (value !== form.password) cb(new Error(t('register.confirmMismatch')))
  else cb()
}

const rules = computed(() => ({
  username: [
    { required: true, message: t('register.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 50, message: t('register.usernameLength'), trigger: 'blur' }
  ],
  email: [
    { required: true, message: t('register.emailRequired'), trigger: 'blur' },
    { type: 'email', message: t('register.emailInvalid'), trigger: 'blur' }
  ],
  password: [
    { required: true, message: t('register.passwordRequired'), trigger: 'blur' },
    { min: 6, max: 50, message: t('register.passwordLength'), trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: t('register.confirmRequired'), trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}))

const onSubmit = async () => {
  if (!formRef.value) return
  if (loading.value) return   // 防双击 / 双回车
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.register({
        username: form.username,
        email: form.email,
        password: form.password,
        age: form.age,
        address: form.address
      })
      ElMessage.success(t('register.success'))
      router.push('/dashboard')
    } catch (e) {
      // 拦截器处理
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
        <h2>{{ $t('register.title') }}</h2>
        <p>{{ $t('register.subtitle') }}</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" size="default" @submit.prevent="onSubmit">
        <el-form-item :label="$t('register.username')" prop="username">
          <el-input v-model="form.username" prefix-icon="User" />
        </el-form-item>
        <el-form-item :label="$t('register.email')" prop="email">
          <el-input v-model="form.email" prefix-icon="Message" />
        </el-form-item>
        <el-form-item :label="$t('register.password')" prop="password">
          <el-input v-model="form.password" type="password" show-password prefix-icon="Lock" />
        </el-form-item>
        <el-form-item :label="$t('register.confirm')" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password prefix-icon="Lock" @keyup.enter="onSubmit" />
        </el-form-item>
        <el-form-item :label="$t('register.age')">
          <el-input-number v-model="form.age" :min="0" :max="150" style="width:100%" />
        </el-form-item>
        <el-form-item :label="$t('register.address')">
          <el-input v-model="form.address" :placeholder="$t('register.optional')" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="onSubmit">{{ $t('register.submit') }}</el-button>
        <div class="auth-tip">
          {{ $t('register.hasAccount') }} <router-link to="/login">{{ $t('register.toLogin') }}</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 与登录页统一的浅色柔和渐变 + 柔光晕染背景 */
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
  width: 460px;
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