<script setup>
/**
 * 注册页
 */
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'

const router = useRouter()
const userStore = useUserStore()

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
  if (value !== form.password) cb(new Error('两次输入密码不一致'))
  else cb()
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '长度 3~50', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式错误', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 50, message: '长度 6~50', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

const onSubmit = async () => {
  if (!formRef.value) return
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
      ElMessage.success('注册成功,已自动登录')
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
        <div class="brand-mark"><el-icon :size="26" color="#fff"><Promotion /></el-icon></div>
        <h2>注册账号</h2>
        <p>注册成功自动登录,默认角色为 USER</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" size="default" @submit.prevent="onSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" prefix-icon="User" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" prefix-icon="Message" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password prefix-icon="Lock" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password prefix-icon="Lock" />
        </el-form-item>
        <el-form-item label="年龄">
          <el-input-number v-model="form.age" :min="0" :max="150" style="width:100%" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" placeholder="可选" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="onSubmit">注 册</el-button>
        <div class="auth-tip">
          已有账号? <router-link to="/login">去登录</router-link>
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
  display: flex; align-items: center; justify-content: center;
  border-radius: 14px;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  box-shadow: 0 8px 20px rgba(37, 99, 235, .32);
}
.auth-header h2 { margin: 0 0 6px 0; color: #1f2937; font-size: 22px; letter-spacing: .3px; }
.auth-header p { margin: 0; color: #6b7280; font-size: 13px; }
.auth-tip { text-align: center; margin-top: 16px; font-size: 13px; }
.auth-tip a { color: #2563eb; text-decoration: none; }
</style>