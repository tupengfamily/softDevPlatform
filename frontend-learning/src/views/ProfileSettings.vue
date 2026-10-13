<script setup>
// 个人信息设置:头像/昵称/个性签名等资料 + 页面主题(经典配色预设)
// 资料保存到后端(本人维度);主题为纯前端换肤(CSS 变量 + localStorage 持久化)
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../store/user'
import { useThemeStore, THEME_PRESETS } from '../store/theme'
import { uploadAvatar } from '../api/auth'

const userStore = useUserStore()
const themeStore = useThemeStore()
const { t } = useI18n()

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

const rules = computed(() => ({
  nickname: [{ max: 50, message: t('profile.nicknameMax'), trigger: 'blur' }],
  email: [
    { required: true, message: t('profile.emailRequired'), trigger: 'blur' },
    { type: 'email', message: t('profile.emailInvalid'), trigger: 'blur' }
  ],
  signature: [{ max: 200, message: t('profile.signatureMax'), trigger: 'blur' }]
}))

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
    ElMessage.error(t('profile.uploadTypeTip'))
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error(t('profile.uploadSizeTip'))
    return
  }
  uploading.value = true
  try {
    const resp = await uploadAvatar(file)
    form.avatar = resp.data
    ElMessage.success(t('profile.uploadSuccess'))
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
      ElMessage.success(t('profile.saveSuccess'))
    } catch (e) { /* 拦截器已弹窗 */ } finally {
      saving.value = false
    }
  })
}

// ============== 页面主题 ==============
// 主题名映射(store/theme.js 的 name 保持原样,这里按 id 取译文)
const themeNames = computed(() => ({
  'classic-blue': t('profile.theme.classicBlue'),
  indigo: t('profile.theme.indigo'),
  emerald: t('profile.theme.emerald'),
  amber: t('profile.theme.amber'),
  rose: t('profile.theme.rose'),
  violet: t('profile.theme.violet'),
  teal: t('profile.theme.teal'),
  graphite: t('profile.theme.graphite')
}))

const onThemeClick = (id) => {
  themeStore.apply(id)
  ElMessage.success(t('profile.themeSwitched', { name: themeNames.value[id] }))
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1>{{ $t('profile.title') }}</h1>
      <p>{{ $t('profile.subtitle') }}</p>
    </div>

    <div class="grid">
      <!-- 个人信息 -->
      <section class="card">
        <h2 class="card-title"><el-icon><User /></el-icon> {{ $t('profile.infoTitle') }}</h2>

        <div class="avatar-row">
          <el-avatar :size="72" :src="avatarSrc" class="avatar-preview">{{ avatarFallback }}</el-avatar>
          <div class="avatar-choose">
            <div class="avatar-label">
              {{ $t('profile.chooseAvatar') }}
              <el-upload
                :show-file-list="false"
                :http-request="doUpload"
                accept="image/jpeg,image/png,image/gif,image/webp"
                class="avatar-upload"
              >
                <el-button size="small" :loading="uploading" plain>
                  <el-icon><Upload /></el-icon> {{ $t('profile.uploadImage') }}
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
              :placeholder="$t('profile.avatarUrlPlaceholder')"
              clearable
            />
          </div>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" @submit.prevent="onSave">
          <el-form-item :label="$t('profile.nickname')" prop="nickname">
            <el-input v-model="form.nickname" maxlength="50" :placeholder="$t('profile.nicknamePlaceholder')" />
          </el-form-item>
          <el-form-item :label="$t('profile.username')">
            <el-input :model-value="userStore.username" disabled />
            <div class="field-tip">{{ $t('profile.usernameTip') }}</div>
          </el-form-item>
          <el-form-item :label="$t('profile.signature')" prop="signature">
            <el-input
              v-model="form.signature"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              :placeholder="$t('profile.signaturePlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="$t('profile.email')" prop="email">
            <el-input v-model="form.email" :placeholder="$t('profile.emailPlaceholder')" />
          </el-form-item>
          <el-form-item :label="$t('profile.age')">
            <el-input-number v-model="form.age" :min="0" :max="150" style="width:100%" />
          </el-form-item>
          <el-form-item :label="$t('profile.address')">
            <el-input v-model="form.address" maxlength="200" :placeholder="$t('profile.optional')" />
          </el-form-item>
          <el-button type="primary" :loading="saving" style="width:100%" @click="onSave">{{ $t('profile.saveBtn') }}</el-button>
        </el-form>
      </section>

      <!-- 页面主题 -->
      <section class="card">
        <h2 class="card-title"><el-icon><Brush /></el-icon> {{ $t('profile.themeTitle') }}</h2>
        <p class="theme-desc">{{ $t('profile.themeDesc') }}</p>

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
            <span class="theme-name">{{ themeNames[t.id] }}</span>
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
.page { padding: 24px 32px 40px }
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

/* ============ 移动端(<=768px) ============ */
@media (max-width: 768px) {
  .theme-grid { grid-template-columns: 1fr 1fr; gap: 10px; }
}
@media (max-width: 420px) {
  .theme-grid { grid-template-columns: 1fr; }
}
</style>
