<script setup>
// 论坛专区 — 帖子列表 + 发帖
// 用户之间的学习交流;支持按语言(科目)维度筛选、关键字搜索、富文本/Markdown 发帖
// 版块默认选中当前学习语言(头部选择器偏好),用户可自行切换
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pagePosts, createPost, toggleLike, toggleFavorite, sharePost } from '../api/forum'
import { useUserStore } from '../store/user'
import { useSubjectStore } from '../store/subject'
import ContentEditor from '../components/ContentEditor.vue'

const router = useRouter()
const userStore = useUserStore()
const subjectStore = useSubjectStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const loading = ref(false)
const posts = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const subjectFilter = ref(null)   // null=全部 0=综合讨论 其余=科目 id
const subjects = ref([])
const sort = ref('updated')       // updated/created/hot/likes/favorites/replies/views

const sortOptions = [
  { value: 'updated', label: '最近更新' },
  { value: 'created', label: '最新发布' },
  { value: 'hot', label: '最热门' },
  { value: 'likes', label: '点赞最多' },
  { value: 'favorites', label: '收藏最多' },
  { value: 'replies', label: '回复最多' },
  { value: 'views', label: '浏览最多' }
]

const load = async () => {
  loading.value = true
  try {
    const res = await pagePosts({
      subjectId: subjectFilter.value === '' ? null : subjectFilter.value,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value,
      page: page.value,
      size: size.value
    })
    posts.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}
const search = () => { page.value = 1; load() }
const onSizeChange = (s) => { size.value = s; page.value = 1; load() }

const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)
const subjectName = (row) => row.subjectName || '综合讨论'
const openDetail = (row) => router.push(`/forum/${row.id}`)

// ---------- 列表快捷互动: 点赞 / 收藏 / 转发 ----------
const requireLogin = () => {
  if (userStore.isLogin) return true
  ElMessageBox.confirm('登录后才能点赞/收藏,去登录?', '提示', {
    type: 'info', confirmButtonText: '去登录', cancelButtonText: '取消'
  }).then(() => router.push({ path: '/login', query: { redirect: '/forum' } })).catch(() => {})
  return false
}
const copyLink = async (id) => {
  const url = `${location.origin}/forum/${id}`
  try {
    await navigator.clipboard.writeText(url)
    return true
  } catch (_) {
    // 剪贴板不可用时退回 execCommand
    const ta = document.createElement('textarea')
    ta.value = url
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy') } catch (_) { /* ignore */ }
    document.body.removeChild(ta)
    return true
  }
}
const quickLike = async (p) => {
  if (!requireLogin()) return
  try {
    const res = await toggleLike(p.id)
    p.liked = res.data.liked
    p.likeCount = res.data.likeCount
  } catch (e) { /* 已统一弹错 */ }
}
const quickFav = async (p) => {
  if (!requireLogin()) return
  try {
    const res = await toggleFavorite(p.id)
    p.favorited = res.data.favorited
    p.favoriteCount = res.data.favoriteCount
  } catch (e) { /* 已统一弹错 */ }
}
const quickShare = async (p) => {
  await copyLink(p.id)
  try {
    const res = await sharePost(p.id)
    p.shareCount = res.data.shareCount
    ElMessage.success('链接已复制,快去分享吧')
  } catch (e) { /* 已统一弹错 */ }
}

// ---------- 发帖 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ subjectId: null, title: '', content: '', contentType: 'richtext', pinned: false })

// ============== 草稿(localStorage,防误关丢失) ==============
const DRAFT_KEY = 'forumPostDraft'
const clearDraft = () => localStorage.removeItem(DRAFT_KEY)
const writeDraft = () => {
  if (!form.title.trim() && !form.content.trim()) return   // 空内容不留草稿
  localStorage.setItem(DRAFT_KEY, JSON.stringify({ ...form, savedAt: new Date().toISOString() }))
}
/** 显式保存草稿 */
const saveDraft = () => {
  if (!form.title.trim() && !form.content.trim()) { ElMessage.warning('内容为空,无需保存草稿'); return }
  writeDraft()
  ElMessage.success('草稿已保存,下次打开发帖可恢复')
}
/** 打开弹窗时若有草稿,询问恢复 */
const offerRestoreDraft = (defaultSubject) => {
  let d = null
  try { d = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null') } catch (_) { clearDraft(); return }
  if (!d || (!(d.title || '').trim() && !(d.content || '').trim())) { clearDraft(); return }
  ElMessageBox.confirm('检测到未发布的草稿,是否恢复?', '草稿', {
    confirmButtonText: '恢复草稿',
    cancelButtonText: '丢弃草稿',
    type: 'info'
  }).then(() => {
    Object.assign(form, {
      subjectId: d.subjectId ?? defaultSubject,
      title: d.title || '',
      content: d.content || '',
      contentType: d.contentType || 'richtext',
      pinned: !!d.pinned
    })
  }).catch(() => clearDraft())
}
/** 关闭弹窗时自动留草稿(静默,防误关丢失);发布成功那次跳过 */
let skipDraftOnce = false
const onDialogClose = () => {
  if (skipDraftOnce) { skipDraftOnce = false; return }
  writeDraft()
}

const openCreate = () => {
  // 版块默认当前学习语言;若筛选已选中具体版块则沿用筛选
  const defaultSubject = (subjectFilter.value && subjectFilter.value > 0)
    ? subjectFilter.value
    : (subjectStore.preferredSubjectId || null)
  Object.assign(form, { subjectId: defaultSubject, title: '', content: '', contentType: 'richtext', pinned: false })
  offerRestoreDraft(defaultSubject)
  dialogVisible.value = true
}
const submit = async () => {
  if (!form.title.trim()) { ElMessage.warning('请填写标题'); return }
  if (!form.content.trim()) { ElMessage.warning('请填写内容'); return }
  saving.value = true
  try {
    const res = await createPost({ ...form, subjectId: form.subjectId || null })
    skipDraftOnce = true
    clearDraft()   // 发布成功,草稿失效
    ElMessage.success('发布成功')
    dialogVisible.value = false
    router.push(`/forum/${res.data.id}`)
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!subjectStore.loaded) await subjectStore.load()
  subjects.value = subjectStore.subjects
  // 版块筛选默认选中当前学习语言(''=全部,用户可自行切换)
  subjectFilter.value = subjectStore.preferredSubjectId || ''
  await load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="hero">
      <div>
        <h1>论坛专区</h1>
        <p>学习交流、提问答疑、经验分享 — 与同学们一起成长。</p>
      </div>
      <el-button type="primary" @click="openCreate"><el-icon><EditPen /></el-icon> 发帖</el-button>
    </div>

    <div class="filters">
      <el-select v-model="subjectFilter" placeholder="全部版块" clearable style="width:170px" @change="search">
        <el-option label="全部版块" value="" />
        <el-option label="综合讨论" :value="0" />
        <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索标题 / 内容"
        clearable
        style="width:240px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button @click="search">搜索</el-button>
      <el-radio-group v-model="sort" size="small" class="sort-group" @change="search">
        <el-radio-button v-for="o in sortOptions" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <div class="post-list">
      <div v-for="p in posts" :key="p.id" class="post-item" role="button" tabindex="0"
           @click="openDetail(p)" @keydown.enter="openDetail(p)">
        <div class="post-main">
          <div class="post-title">
            <el-tag v-if="p.pinned" size="small" type="danger" effect="dark" class="pin">置顶</el-tag>
            <el-tag size="small" effect="plain" :type="p.subjectId ? 'primary' : 'info'">{{ subjectName(p) }}</el-tag>
            <b>{{ p.title }}</b>
          </div>
          <div class="post-meta">
            <span>{{ p.username || '匿名' }}</span>
            <span>· {{ fmtTime(p.updatedAt || p.createdAt) }}</span>
            <span>· {{ p.viewCount }} 浏览</span>
            <span>· {{ p.replyCount }} 回复</span>
            <span>· {{ p.likeCount }} 赞</span>
            <span>· {{ p.favoriteCount }} 收藏</span>
            <span>· {{ p.shareCount }} 转发</span>
          </div>
        </div>
        <div class="quick">
          <button class="qbtn" :class="{ on: p.liked }" :aria-label="p.liked ? '取消点赞' : '点赞'"
                  @click.stop="quickLike(p)">
            <el-icon><Pointer /></el-icon> {{ p.likeCount }}
          </button>
          <button class="qbtn" :class="{ on: p.favorited }" :aria-label="p.favorited ? '取消收藏' : '收藏'"
                  @click.stop="quickFav(p)">
            <el-icon><component :is="p.favorited ? 'StarFilled' : 'Star'" /></el-icon> {{ p.favoriteCount }}
          </button>
          <button class="qbtn" aria-label="转发" @click.stop="quickShare(p)">
            <el-icon><Share /></el-icon> {{ p.shareCount }}
          </button>
        </div>
        <el-icon class="arrow"><ArrowRight /></el-icon>
      </div>
      <el-empty v-if="!posts.length && !loading" description="暂无帖子,来发第一帖吧" />
    </div>

    <div class="pager" v-if="total > size">
      <el-pagination
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50]"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 发帖对话框 -->
    <el-dialog v-model="dialogVisible" title="发布帖子" width="760px" destroy-on-close class="post-dialog" @close="onDialogClose">
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="200" placeholder="帖子标题" />
        </el-form-item>
        <el-form-item label="版块">
          <el-select v-model="form.subjectId" placeholder="综合讨论" clearable style="width:200px">
            <el-option label="综合讨论" value="" />
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-checkbox v-if="isAdmin" v-model="form.pinned" style="margin-left:16px">置顶</el-checkbox>
        </el-form-item>
        <el-form-item label="内容(支持 Markdown / 富文本)">
          <ContentEditor v-model="form.content" v-model:content-type="form.contentType" :height="260" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-actions">
          <el-button plain @click="saveDraft">保存草稿</el-button>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submit">发布</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 26px 40px; max-width: 1080px; margin: 0 auto; }
.hero {
  display: flex; align-items: center; justify-content: space-between;
  background: linear-gradient(120deg, #eef4ff 0%, #f6f1ff 60%, #fff 100%);
  border: 1px solid #e8edf9; border-radius: var(--r-l); padding: 22px 26px; margin-bottom: 16px;
}
.hero h1 { margin: 0 0 6px; font-size: var(--fs-xl); }
.hero p { margin: 0; color: #6b7280; font-size: var(--fs-base); }
.filters { display: flex; gap: 10px; margin-bottom: 14px; align-items: center; flex-wrap: wrap; }
.sort-group { flex: none; }
.post-list { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); overflow: hidden; }
.post-item {
  display: flex; align-items: center; gap: 12px; padding: 15px 20px;
  border-bottom: 1px solid #f1f3f8; cursor: pointer; transition: background .12s;
}
.post-item:hover { background: #f8fafd; }
.post-title { display: flex; align-items: center; gap: 8px; font-size: var(--fs-base); color: var(--c-text-1); }
.post-title b { font-weight: 600; }
.pin { flex: none; }
.post-meta { margin-top: 5px; color: #64748b; font-size: var(--fs-xs); display: flex; gap: 6px; }
.quick { display: flex; gap: 6px; flex: none; }
.qbtn {
  display: inline-flex; align-items: center; gap: 4px;
  min-width: 44px; min-height: 32px; padding: 4px 10px;
  border: 1px solid var(--c-border); border-radius: var(--r-s);
  background: #fff; color: var(--c-text-2); font-size: var(--fs-xs); cursor: pointer;
  transition: color .12s, border-color .12s, background .12s;
}
.qbtn:hover { color: var(--c-primary); border-color: var(--c-primary); background: #eff6ff; }
.qbtn.on { color: var(--c-primary); border-color: var(--c-primary); background: #eff6ff; font-weight: 600; }
.arrow { margin-left: auto; color: #cbd5e1; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }

/* 发帖弹窗: 底部按钮居中 */
.dialog-actions { display: flex; justify-content: center; gap: 10px; }
.post-dialog :deep(.el-dialog__footer) { padding: 10px 20px 20px; }
</style>
