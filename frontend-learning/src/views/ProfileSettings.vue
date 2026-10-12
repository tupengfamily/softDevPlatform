<script setup>
// 个人信息设置:头像/昵称/个性签名等资料 + 页面主题(经典配色预设)
// 资料保存到后端(本人维度);主题为纯前端换肤(CSS 变量 + localStorage 持久化)
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import { useThemeStore, THEME_PRESETS } from '../store/theme'
import { uploadAvatar } from '../api/auth'

const userStore = useUserStore()
const themeStore = useThemeStore()

// ============== 个人信息 ==============
const saving = ref(false)
const formRef = ref(null)

const form = reactive({
  nickname: userStore.userInfo?.nickname || '',
  avatar: userStore.userInfo?.avatar || '',
  signature: userStore.userInfo?.signature || '',
  email: userStore.userInfo?.email || '',
  age: userStore.userInfo?.age ?? 18,
  address: userStore.userInfo?.address || ''
})

// 预设头像(emoji):点选即用;也支持粘贴图片 URL
const EMOJI_AVATARS = ['😀', '😎', '🤓', '🧑‍💻', '👩‍💻', '🐱', '🦊', '🐼', '🐯', '🚀', '🌟', '🎯', '🍀', '🔥', '📚', '☕']

const rules = {
  nickname: [{ max: 50, message: '昵称长度不能超过 50', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式错误', trigger: 'blur' }
  ],
  signature: [{ max: 200, message: '个性签名不能超过 200 字', trigger: 'blur' }]
}

const isAvatarUrl = (v) => /^https?:\/\//.test(v || '') || (v || '').startsWith('data:') || (v || '').startsWith('/uploads/')
const avatarSrc = computed(() => (isAvatarUrl(form.avatar) ? form.avatar : undefined))
const avatarFallback = computed(() => form.avatar && !isAvatarUrl(form.avatar)
  ? form.avatar
  : (form.nickname || userStore.username || '?').charAt(0).toUpperCase())

const pickEmoji = (e) => { form.avatar = e }

// 上传头像:类型/大小前端先校验,成功后 avatar 置为返回的 /uploads/ URL
const uploading = ref(false)
const doUpload = async ({ file }) => {
  const okTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!okTypes.includes(file.type)) {
    ElMessage.error('仅支持 jpg / png / gif / webp 图片')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('头像图片不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    const resp = await uploadAvatar(file)
    form.avatar = resp.data
    ElMessage.success('头像上传成功,记得保存个人信息')
  } catch (e) { /* 拦截器已弹窗 */ } finally {
    uploading.value = false
  }
}

const onSave = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      await userStore.updateProfile({
        nickname: form.nickname,
        avatar: form.avatar,
        signature: form.signature,
        email: form.email,
        age: form.age,
        address: form.address
      })
      ElMessage.success('个人信息已保存')
    } catch (e) { /* 拦截器已弹窗 */ } finally {
      saving.value = false
    }
  })
}

// ============== 页面主题 ==============
const onThemeClick = (id) => {
  themeStore.apply(id)
  ElMessage.success(`已切换为「${themeStore.current.name}」主题`)
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1>个人信息设置</h1>
      <p>完善你的个人资料,并选择喜欢的页面配色。</p>
    </div>

    <div class="grid">
      <!-- 个人信息 -->
      <section class="card">
        <h2 class="card-title"><el-icon><User /></el-icon> 个人信息</h2>

        <div class="avatar-row">
          <el-avatar :size="72" :src="avatarSrc" class="avatar-preview">{{ avatarFallback }}</el-avatar>
          <div class="avatar-choose">
            <div class="avatar-label">
              选择头像
              <el-upload
                :show-file-list="false"
                :http-request="doUpload"
                accept="image/jpeg,image/png,image/gif,image/webp"
                class="avatar-upload"
              >
                <el-button size="small" :loading="uploading" plain>
                  <el-icon><Upload /></el-icon> 上传图片
                </el-button>
              </el-upload>
            </div>
            <div class="emoji-grid">
              <button
                v-for="e in EMOJI_AVATARS"
                :key="e"
                type="button"
                class="emoji-item"
                :class="{ active: form.avatar === e }"
                @click="pickEmoji(e)"
              >{{ e }}</button>
            </div>
            <el-input
              v-model="form.avatar"
              class="avatar-url"
              placeholder="或粘贴头像图片 URL(留空显示首字母)"
              clearable
            />
          </div>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" @submit.prevent="onSave">
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" maxlength="50" placeholder="展示用的名字" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input :model-value="userStore.username" disabled />
            <div class="field-tip">用户名作为登录账号不可修改</div>
          </el-form-item>
          <el-form-item label="个性签名" prop="signature">
            <el-input
              v-model="form.signature"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="介绍一下自己吧"
            />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="用于接收通知" />
          </el-form-item>
          <el-form-item label="年龄">
            <el-input-number v-model="form.age" :min="0" :max="150" style="width:100%" />
          </el-form-item>
          <el-form-item label="地址">
            <el-input v-model="form.address" maxlength="200" placeholder="可选" />
          </el-form-item>
          <el-button type="primary" :loading="saving" style="width:100%" @click="onSave">保存个人信息</el-button>
        </el-form>
      </section>

      <!-- 页面主题 -->
      <section class="card">
        <h2 class="card-title"><el-icon><Brush /></el-icon> 页面主题</h2>
        <p class="theme-desc">预设经典配色,点击即刻生效并自动记住你的选择。</p>

        <div class="theme-grid">
          <button
            v-for="t in THEME_PRESETS"
            :key="t.id"
            type="button"
            class="theme-item"
            :class="{ active: themeStore.themeId === t.id }"
            @click="onThemeClick(t.id)"
          >
            <span class="theme-bar" :style="{ background: `linear-gradient(135deg, ${t.primary}, ${t.hover})` }">
              <el-icon v-if="themeStore.themeId === t.id" class="theme-check"><CircleCheckFilled /></el-icon>
            </span>
            <span class="theme-name">{{ t.name }}</span>
            <span class="theme-dots">
              <i :style="{ background: t.primary }"></i>
              <i :style="{ background: t.hover }"></i>
              <i :style="{ background: t.bg, border: '1px solid #e5e7eb' }"></i>
            </span>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1100px; margin: 0 auto }
.page-head { margin-bottom: 20px }
.page-head h1 { margin: 0 0 6px; font-size: var(--fs-xl); font-weight: 600; color: var(--c-text-1) }
.page-head p { margin: 0; color: var(--c-text-2); font-size: var(--fs-base) }

.grid { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 20px; align-items: start }
@media (max-width: 960px) { .grid { grid-template-columns: 1fr } }

.card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 22px;
}
.card-title {
  display: flex; align-items: center; gap: 8px;
  margin: 0 0 18px; font-size: var(--fs-md); font-weight: 600; color: var(--c-text-1);
}
.card-title .el-icon { color: var(--c-primary) }

/* 头像选择 */
.avatar-row { display: flex; gap: 18px; margin-bottom: 20px; align-items: flex-start }
.avatar-preview {
  flex: none;
  font-size: 28px;
  background: var(--c-primary-grad, linear-gradient(135deg, #2563eb, #4f46e5));
  color: #fff;
}
.avatar-choose { flex: 1; min-width: 0 }
.avatar-label {
  display: flex; align-items: center; gap: 10px;
  font-size: var(--fs-sm); color: var(--c-text-2); margin-bottom: 8px;
}
.avatar-upload { display: inline-flex; }
.emoji-grid { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px }
.emoji-item {
  width: 36px; height: 36px; padding: 0; font-size: 20px; line-height: 1;
  border: 1px solid var(--c-border); border-radius: var(--r-s);
  background: #fff; cursor: pointer; transition: border-color .15s, transform .15s;
}
.emoji-item:hover { border-color: var(--c-primary); transform: translateY(-1px) }
.emoji-item.active { border-color: var(--c-primary); background: var(--c-primary-bg) }
.avatar-url :deep(.el-input__wrapper) { border-radius: var(--r-s) }

.field-tip { margin-top: 4px; font-size: var(--fs-xs); color: var(--c-text-4); line-height: 1.5 }

/* 主题选择 */
.theme-desc { margin: -8px 0 16px; font-size: var(--fs-sm); color: var(--c-text-2) }
.theme-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px }
.theme-item {
  display: flex; flex-direction: column; gap: 8px;
  padding: 12px; border: 1.5px solid var(--c-border); border-radius: var(--r-m);
  background: #fff; cursor: pointer; text-align: left;
  transition: border-color .15s, box-shadow .15s;
}
.theme-item:hover { border-color: var(--c-primary); box-shadow: 0 4px 12px rgba(15, 23, 42, .08) }
.theme-item.active { border-color: var(--c-primary); background: var(--c-primary-bg) }
.theme-bar {
  position: relative; display: block; height: 34px; border-radius: var(--r-s);
}
.theme-check {
  position: absolute; right: 6px; top: 50%; transform: translateY(-50%);
  color: #fff; font-size: 16px;
}
.theme-name { font-size: var(--fs-sm); font-weight: 600; color: var(--c-text-1) }
.theme-dots { display: flex; gap: 5px }
.theme-dots i { width: 12px; height: 12px; border-radius: 50%; display: inline-block }
</style>
