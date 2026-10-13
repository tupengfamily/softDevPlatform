<script setup>
// 更新日志页: 版本公告时间线(最新在前),内容为富文本(Quill 编辑, HTML 存储)
// 管理员可发布公告;已到发布日期的公告即锁定,不允许再编辑/删除(后端强制)
// 发布后老用户会收到新版本公告弹窗
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { QuillEditor } from '@vueup/vue-quill'
import '@vueup/vue-quill/dist/vue-quill.snow.css'
import { createRelease, deleteRelease, getReleases, updateRelease } from '../api/release'
import { sanitizeHtml } from '../utils/markdown'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
const { t } = useI18n()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const loading = ref(false)
const releases = ref([])

const loadData = async () => {
  loading.value = true
  try {
    const res = await getReleases()
    releases.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

// 富文本工具栏(常用项,不含图片上传)
const editorOptions = computed(() => ({
  placeholder: t('releaseNotes.editorPlaceholder'),
  modules: {
    toolbar: [
      [{ header: [1, 2, 3, false] }],
      ['bold', 'italic', 'underline', 'strike'],
      [{ color: [] }, { background: [] }],
      [{ list: 'ordered' }, { list: 'bullet' }],
      ['blockquote', 'code-block', 'link'],
      ['clean']
    ]
  }
}))

// 去掉 HTML 标签后判断内容是否为空
const stripHtml = (html) => (html || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim()

// ============== 管理员: 发布 / 编辑 / 删除 ==============
const dialogVisible = ref(false)
const editingId = ref(null)
const form = ref({ version: '', title: '', content: '', releaseDate: '' })
const saving = ref(false)
const dialogTitle = computed(() => (editingId.value
  ? t('releaseNotes.editTitle', { v: form.value.version })
  : t('releaseNotes.createTitle')))

// 已到发布日期的公告锁定(不允许编辑/删除);未来的预发公告可调整
const todayStr = () => new Date().toISOString().slice(0, 10)
const canManage = (r) => (r.releaseDate || '') > todayStr()

const openCreate = () => {
  editingId.value = null
  form.value = { version: '', title: '', content: '', releaseDate: new Date().toISOString().slice(0, 10) }
  dialogVisible.value = true
}

const openEdit = (r) => {
  editingId.value = r.id
  form.value = {
    version: r.version,
    title: r.title,
    content: r.content,
    releaseDate: (r.releaseDate || '').slice(0, 10)
  }
  dialogVisible.value = true
}

const save = async () => {
  if (!form.value.version?.trim() || !form.value.title?.trim() || !stripHtml(form.value.content)) {
    ElMessage.warning(t('releaseNotes.fillRequired'))
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value, releaseDate: form.value.releaseDate || undefined }
    const isEdit = !!editingId.value
    if (isEdit) await updateRelease(editingId.value, payload)
    else await createRelease(payload)
    ElMessage.success(isEdit ? t('releaseNotes.saved') : t('releaseNotes.published'))
    dialogVisible.value = false
    loadData()
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

const remove = async (r) => {
  try {
    await ElMessageBox.confirm(t('releaseNotes.removeTip', { v: r.version }), t('releaseNotes.tipTitle'), {
      confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning'
    })
    await deleteRelease(r.id)
    ElMessage.success(t('releaseNotes.deleted'))
    loadData()
  } catch (e) { /* 取消或已弹错 */ }
}

onMounted(loadData)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="hero">
      <div>
        <h1>{{ $t('releaseNotes.title') }}</h1>
        <p>{{ $t('releaseNotes.subtitle') }}</p>
      </div>
      <button v-if="isAdmin" class="btn primary" @click="openCreate">
        <el-icon><Plus /></el-icon> {{ $t('releaseNotes.create') }}
      </button>
    </div>

    <div v-if="releases.length" class="timeline-card">
      <el-timeline class="timeline">
        <el-timeline-item
          v-for="r in releases"
          :key="r.id"
          :timestamp="r.releaseDate"
          placement="top"
          :hollow="r !== releases[0]"
          :type="r === releases[0] ? 'primary' : ''"
        >
          <div class="rel">
            <div class="rel-head">
              <el-tag :type="r === releases[0] ? 'primary' : 'info'" effect="dark" round size="small">
                v{{ r.version }}
              </el-tag>
              <span class="rel-title">{{ r.title }}</span>
              <span v-if="r === releases[0]" class="rel-latest">{{ $t('releaseNotes.latest') }}</span>
              <span v-if="isAdmin && canManage(r)" class="rel-ops">
                <el-button link type="primary" size="small" @click="openEdit(r)">{{ $t('common.edit') }}</el-button>
                <el-button link type="danger" size="small" @click="remove(r)">{{ $t('common.delete') }}</el-button>
              </span>
            </div>
            <div class="rel-body rich-text" v-html="sanitizeHtml(r.content)"></div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </div>
    <el-empty v-else :description="$t('releaseNotes.empty')" />

    <!-- 发布 / 编辑公告 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item :label="$t('releaseNotes.version')" required>
          <el-input v-model="form.version" :placeholder="$t('releaseNotes.versionPlaceholder')" style="width:180px" />
        </el-form-item>
        <el-form-item :label="$t('releaseNotes.fieldTitle')" required>
          <el-input v-model="form.title" :placeholder="$t('releaseNotes.titlePlaceholder')" maxlength="100" />
        </el-form-item>
        <el-form-item :label="$t('releaseNotes.releaseDate')">
          <el-date-picker v-model="form.releaseDate" type="date" value-format="YYYY-MM-DD" :placeholder="$t('releaseNotes.datePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('releaseNotes.contentLabel')" required>
          <QuillEditor
            v-model:content="form.content"
            content-type="html"
            theme="snow"
            :options="editorOptions"
            style="min-height:220px; width:100%"
          />
        </el-form-item>
        <div class="lock-tip">
          <el-icon><Lock /></el-icon>
          {{ $t('releaseNotes.lockTip') }}
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding:24px 32px; }

.hero { display:flex; align-items:center; justify-content:space-between; margin-bottom:24px }
.hero h1 { margin:0 0 4px; font-size:var(--fs-xl); font-weight:600; color:var(--c-text-1) }
.hero p { margin:0; color:var(--c-text-2); font-size:var(--fs-base) }
.btn { padding:9px 16px; border-radius:var(--r-s); font-size:var(--fs-sm); cursor:pointer; border:1px solid var(--c-border-strong); background:#fff; transition:all .15s; display:inline-flex; align-items:center; gap:4px }
.btn.primary { background:var(--c-primary); color:#fff; border-color:transparent; box-shadow:0 4px 12px rgba(37,99,235,.25) }
.btn.primary:hover { background:var(--c-primary-hover); transform:translateY(-1px) }

.timeline-card { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-l); padding:28px 32px; box-shadow:0 2px 6px rgba(20,30,60,.04) }
.timeline { padding-left:4px }

.rel { padding-bottom:6px }
.rel-head { display:flex; align-items:center; gap:10px; flex-wrap:wrap }
.rel-title { font-size:var(--fs-md); font-weight:600; color:var(--c-text-1) }
.rel-latest { font-size:var(--fs-xs); color:var(--c-success); background:var(--c-success-bg); padding:2px 8px; border-radius:var(--r-full) }
.rel-ops { margin-left:auto }

/* 发布对话框锁定提示 */
.lock-tip {
  display:flex; align-items:center; gap:6px; margin:-4px 0 4px 80px;
  font-size:var(--fs-xs); color:var(--c-text-3);
}

.rel-body { margin-top:10px }
/* 富文本内容排版 */
.rich-text { font-size:var(--fs-sm); line-height:1.85; color:var(--c-text-1); word-break:break-word }
.rich-text :deep(p) { margin:0 0 8px }
.rich-text :deep(ul), .rich-text :deep(ol) { margin:0 0 8px; padding-left:22px }
.rich-text :deep(li) { margin-bottom:4px }
.rich-text :deep(h1), .rich-text :deep(h2), .rich-text :deep(h3) { margin:12px 0 8px; font-size:var(--fs-md); color:var(--c-text-1) }
.rich-text :deep(strong) { color:var(--c-text-1) }
.rich-text :deep(a) { color:var(--c-primary); text-decoration:underline }
.rich-text :deep(blockquote) { margin:8px 0; padding:6px 12px; border-left:3px solid var(--c-primary); background:var(--c-primary-bg); color:var(--c-text-2) }
.rich-text :deep(pre.ql-syntax) { background:#0f172a; color:#e2e8f0; padding:12px 14px; border-radius:var(--r-s); overflow-x:auto; font-size:var(--fs-xs) }

/* 编辑器容器宽度适配 */
:deep(.ql-toolbar.ql-snow), :deep(.ql-container.ql-snow) { border-color:var(--c-border) }
:deep(.ql-editor) { min-height:180px; font-size:var(--fs-sm) }
</style>
