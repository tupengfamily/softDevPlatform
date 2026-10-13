<script setup>
// 论坛专区 — 帖子列表 + 发帖
// 用户之间的学习交流;支持按语言(科目)维度筛选、关键字搜索、富文本/Markdown 发帖
// 版块默认选中当前学习语言(头部选择器偏好),用户可自行切换
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pagePosts, createPost, toggleLike, toggleFavorite, sharePost } from '../api/forum'
import { useUserStore } from '../store/user'
import { useSubjectStore } from '../store/subject'
import { useDictStore } from '../store/dict'
import ContentEditor from '../components/ContentEditor.vue'

const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const subjectStore = useSubjectStore()
const dictStore = useDictStore()
// 版块文案来自数据字典 forum_board
const boardLabel = computed(() => dictStore.label('forum_board', 0, '综合讨论'))
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const loading = ref(false)
const posts = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const subjectFilter = ref(null)   // null=全部 0=综合讨论 其余=科目 id
// 跟随 store 的响应式数组(勿拷贝引用:store load() 会整体替换数组,拷贝会停留在旧空数组)
const subjects = computed(() => subjectStore.subjects)
const sort = ref('updated')       // updated/created/hot/likes/favorites/replies/views

const sortOptions = computed(() => [
  { value: 'updated', label: t('forum.sort.updated') },
  { value: 'created', label: t('forum.sort.created') },
  { value: 'hot', label: t('forum.sort.hot') },
  { value: 'likes', label: t('forum.sort.likes') },
  { value: 'favorites', label: t('forum.sort.favorites') },
  { value: 'replies', label: t('forum.sort.replies') },
  { value: 'views', label: t('forum.sort.views') }
])

// 请求序号: 快速切换筛选/翻页时旧响应可能后到,只认最后一次请求的响应
let reqSeq = 0
const load = async () => {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await pagePosts({
      subjectId: subjectFilter.value === '' ? null : subjectFilter.value,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value,
      page: page.value,
      size: size.value
    })
    if (seq !== reqSeq) return
    posts.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) { /* 已统一弹错 */ } finally {
    if (seq === reqSeq) loading.value = false
  }
}
// 翻页/改页大小统一由 watch 触发加载(单一入口,去掉 @current-change/@size-change 与手动 load 双通道,
// 避免同一动作同时发出两个相同请求)
watch([page, size], load)
// 搜索/筛选/排序: 先回到第 1 页;页码本就是 1 时 watch 不触发,手动加载
const search = () => { if (page.value === 1) load(); else page.value = 1 }

const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)
const subjectName = (row) => row.subjectName || boardLabel.value
const openDetail = (row) => router.push(`/forum/${row.id}`)

// ---------- 列表快捷互动: 点赞 / 收藏 / 转发 ----------
const requireLogin = () => {
  if (userStore.isLogin) return true
  ElMessageBox.confirm(t('forum.loginRequired'), t('forum.tip'), {
    type: 'info', confirmButtonText: t('forum.goLogin'), cancelButtonText: t('common.cancel')
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
    ElMessage.success(t('forum.linkCopied'))
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
  if (!form.title.trim() && !form.content.trim()) { ElMessage.warning(t('forum.draftEmpty')); return }
  writeDraft()
  ElMessage.success(t('forum.draftSaved'))
}
/** 打开弹窗时若有草稿,询问恢复 */
const offerRestoreDraft = (defaultSubject) => {
  let d = null
  try { d = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null') } catch (_) { clearDraft(); return }
  if (!d || (!(d.title || '').trim() && !(d.content || '').trim())) { clearDraft(); return }
  ElMessageBox.confirm(t('forum.draftRestoreConfirm'), t('forum.draftTitle'), {
    confirmButtonText: t('forum.draftRestore'),
    cancelButtonText: t('forum.draftDiscard'),
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
  // 版块默认当前学习语言;若筛选已选中具体版块则沿用筛选('' 才算未选;0=综合讨论 是有效版块,需沿用)
  const defaultSubject = (subjectFilter.value === '' || subjectFilter.value === null || subjectFilter.value === undefined)
    ? (subjectStore.preferredSubjectId || null)
    : subjectFilter.value
  Object.assign(form, { subjectId: defaultSubject, title: '', content: '', contentType: 'richtext', pinned: false })
  offerRestoreDraft(defaultSubject)
  dialogVisible.value = true
}
const submit = async () => {
  if (!form.title.trim()) { ElMessage.warning(t('forum.needTitle')); return }
  if (!form.content.trim()) { ElMessage.warning(t('forum.needContent')); return }
  saving.value = true
  try {
    // subjectId 约定: 0=综合讨论 是有效版块不能归一成 null;仅 ''/null/undefined(未选)才传 null
    const res = await createPost({ ...form, subjectId: (form.subjectId === '' || form.subjectId === null || form.subjectId === undefined) ? null : form.subjectId })
    skipDraftOnce = true
    clearDraft()   // 发布成功,草稿失效
    ElMessage.success(t('forum.publishSuccess'))
    dialogVisible.value = false
    router.push(`/forum/${res.data.id}`)
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!subjectStore.loaded) await subjectStore.load()
  // 版块筛选默认选中当前学习语言(''=全部,用户可自行切换)
  subjectFilter.value = subjectStore.preferredSubjectId || ''
  await load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="hero">
      <div>
        <h1>{{ $t('forum.title') }}</h1>
        <p>{{ $t('forum.subtitle') }}</p>
      </div>
      <el-button type="primary" @click="openCreate"><el-icon><EditPen /></el-icon> {{ $t('forum.createPost') }}</el-button>
    </div>

    <div class="filters">
      <el-select v-model="subjectFilter" :placeholder="$t('forum.allBoards')" clearable style="width:170px" @change="search">
        <el-option :label="$t('forum.allBoards')" value="" />
        <el-option :label="boardLabel" :value="0" />
        <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-input
        v-model="keyword"
        :placeholder="$t('forum.searchPlaceholder')"
        clearable
        style="width:240px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button @click="search">{{ $t('common.search') }}</el-button>
      <el-radio-group v-model="sort" size="small" class="sort-group" @change="search">
        <el-radio-button v-for="o in sortOptions" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <div class="post-list">
      <div v-for="p in posts" :key="p.id" class="post-item" role="button" tabindex="0"
           @click="openDetail(p)" @keydown.enter="openDetail(p)">
        <div class="post-main">
          <div class="post-title">
            <el-tag v-if="p.pinned" size="small" type="danger" effect="dark" class="pin">{{ $t('forum.pinned') }}</el-tag>
            <el-tag size="small" effect="plain" :type="p.subjectId ? 'primary' : 'info'">{{ subjectName(p) }}</el-tag>
            <b>{{ p.title }}</b>
          </div>
          <div class="post-meta">
            <span>{{ p.username || $t('forum.anonymous') }}</span>
            <span>· {{ fmtTime(p.updatedAt || p.createdAt) }}</span>
            <span>· {{ $t('forum.views', { n: p.viewCount }) }}</span>
            <span>· {{ $t('forum.replies', { n: p.replyCount }) }}</span>
            <span>· {{ $t('forum.likes', { n: p.likeCount }) }}</span>
            <span>· {{ $t('forum.favorites', { n: p.favoriteCount }) }}</span>
            <span>· {{ $t('forum.shares', { n: p.shareCount }) }}</span>
          </div>
        </div>
        <div class="quick">
          <button class="qbtn" :class="{ on: p.liked }" :aria-label="p.liked ? $t('forum.unlike') : $t('forum.like')"
                  @click.stop="quickLike(p)">
            <el-icon><Pointer /></el-icon> {{ p.likeCount }}
          </button>
          <button class="qbtn" :class="{ on: p.favorited }" :aria-label="p.favorited ? $t('forum.unfavorite') : $t('forum.favorite')"
                  @click.stop="quickFav(p)">
            <el-icon><component :is="p.favorited ? 'StarFilled' : 'Star'" /></el-icon> {{ p.favoriteCount }}
          </button>
          <button class="qbtn" :aria-label="$t('forum.share')" @click.stop="quickShare(p)">
            <el-icon><Share /></el-icon> {{ p.shareCount }}
          </button>
        </div>
        <el-icon class="arrow"><ArrowRight /></el-icon>
      </div>
      <el-empty v-if="!posts.length && !loading" :description="$t('forum.empty')" />
    </div>

    <div class="pager" v-if="total > size">
      <el-pagination
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50]"
      />
    </div>

    <!-- 发帖对话框 -->
    <el-dialog v-model="dialogVisible" :title="$t('forum.createTitle')" width="760px" destroy-on-close class="post-dialog" @close="onDialogClose">
      <el-form label-position="top">
        <el-form-item :label="$t('forum.titleLabel')" required>
          <el-input v-model="form.title" maxlength="200" :placeholder="$t('forum.titlePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('forum.board')">
          <el-select v-model="form.subjectId" :placeholder="boardLabel" clearable style="width:200px">
            <!-- 0=综合讨论 是有效版块(与筛选下拉一致),选中后提交保留 0 -->
            <el-option :label="boardLabel" :value="0" />
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-checkbox v-if="isAdmin" v-model="form.pinned" style="margin-left:16px">{{ $t('forum.pinned') }}</el-checkbox>
        </el-form-item>
        <el-form-item :label="$t('forum.contentLabel')">
          <ContentEditor v-model="form.content" v-model:content-type="form.contentType" :height="260" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-actions">
          <el-button plain @click="saveDraft">{{ $t('forum.saveDraft') }}</el-button>
          <el-button @click="dialogVisible = false">{{ $t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" @click="submit">{{ $t('forum.publish') }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px 40px; }
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
