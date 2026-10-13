<script setup>
// 论坛帖子详情 — 正文(富文本/Markdown)+ 回复列表 + 回复框
// 作者/管理员可编辑、删除帖子与回复
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPost, updatePost, deletePost, pageReplies, createReply, deleteReply, toggleLike, toggleFavorite, sharePost } from '../api/forum'
import { getSubjects } from '../api/learning'
import { renderContent } from '../utils/markdown'
import { useUserStore } from '../store/user'
import { useDictStore } from '../store/dict'
import ContentEditor from '../components/ContentEditor.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const dictStore = useDictStore()
// 版块文案来自数据字典 forum_board
const boardLabel = computed(() => dictStore.label('forum_board', 0, '综合讨论'))
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const loading = ref(false)
const post = ref(null)
const replies = ref([])
const replyTotal = ref(0)
const replyPage = ref(1)
const replySize = ref(20)
const subjects = ref([])

const render = (content, type) => renderContent(content, type)
const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)

// 请求序号: 快速翻页/刷新时旧响应可能后到,只认最后一次请求的响应
let postSeq = 0
const loadPost = async () => {
  const seq = ++postSeq
  loading.value = true
  try {
    const res = await getPost(route.params.id)
    if (seq !== postSeq) return
    post.value = res.data
  } catch (e) { /* 已统一弹错 */ } finally {
    if (seq === postSeq) loading.value = false
  }
}
let replySeq = 0
const loadReplies = async () => {
  const seq = ++replySeq
  try {
    const res = await pageReplies(route.params.id, { page: replyPage.value, size: replySize.value })
    if (seq !== replySeq) return
    replies.value = res.data?.records || []
    replyTotal.value = res.data?.total || 0
  } catch (e) { /* 已统一弹错 */ }
}
// 回复翻页统一由 watch 触发加载(单一入口,去掉 @current-change 与手动 load 双通道,避免同一动作重复请求)
watch(replyPage, loadReplies)

// ---------- 编辑帖子 ----------
const editDialog = ref(false)
const editSaving = ref(false)
const editForm = reactive({ subjectId: null, title: '', content: '', contentType: 'richtext', pinned: false })
const openEdit = () => {
  Object.assign(editForm, {
    subjectId: post.value.subjectId,
    title: post.value.title,
    content: post.value.content,
    contentType: post.value.contentType || 'richtext',
    pinned: post.value.pinned
  })
  editDialog.value = true
}
const saveEdit = async () => {
  if (!editForm.title.trim() || !editForm.content.trim()) { ElMessage.warning(t('forumDetail.emptyTitleContent')); return }
  editSaving.value = true
  try {
    await updatePost(post.value.id, { ...editForm, subjectId: editForm.subjectId || null })
    ElMessage.success(t('forumDetail.updated'))
    editDialog.value = false
    await loadPost()
  } catch (e) { /* 已统一弹错 */ } finally {
    editSaving.value = false
  }
}
const removePost = async () => {
  try {
    await ElMessageBox.confirm(t('forumDetail.deletePostConfirm'), t('forumDetail.deleteConfirmTitle'), { type: 'warning' })
    await deletePost(post.value.id)
    ElMessage.success(t('forumDetail.deleted'))
    router.push('/forum')
  } catch (e) { /* 取消或已统一弹错 */ }
}

// ---------- 回复(支持楼中楼: 回复某条回复) ----------
const replyForm = reactive({ content: '', contentType: 'markdown', parentReplyId: null, replyToUsername: '' })
const replySaving = ref(false)
const editorRef = ref(null)

// 点击某条回复的「回复」: 带上父回复 ID,编辑区显示"回复 @xxx"
const replyTo = (r) => {
  if (!requireLogin()) return
  replyForm.parentReplyId = r.id
  replyForm.replyToUsername = r.username || t('forumDetail.anonymous')
  const el = editorRef.value
  if (el?.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
const cancelReplyTo = () => {
  replyForm.parentReplyId = null
  replyForm.replyToUsername = ''
}
const submitReply = async () => {
  if (!replyForm.content.trim()) { ElMessage.warning(t('forumDetail.needReply')); return }
  replySaving.value = true
  try {
    await createReply(post.value.id, {
      content: replyForm.content,
      contentType: replyForm.contentType,
      parentReplyId: replyForm.parentReplyId
    })
    ElMessage.success(replyForm.parentReplyId ? t('forumDetail.repliedTo', { name: replyForm.replyToUsername }) : t('forumDetail.replySuccess'))
    replyForm.content = ''
    cancelReplyTo()
    // 回到第 1 页并刷新:页码变化时回复由 watch(replyPage) 触发加载;原本就在第 1 页则手动加载(保证只发一次)
    const stayFirstPage = replyPage.value === 1
    replyPage.value = 1
    await loadPost()
    if (stayFirstPage) await loadReplies()
  } catch (e) { /* 已统一弹错 */ } finally {
    replySaving.value = false
  }
}
const removeReply = async (r) => {
  try {
    await ElMessageBox.confirm(
      r.parentReplyId ? t('forumDetail.deleteReplyConfirm') : t('forumDetail.deleteReplyNestedConfirm'),
      t('forumDetail.deleteConfirmTitle'), { type: 'warning' }
    )
    await deleteReply(r.id)
    ElMessage.success(t('forumDetail.deleted'))
    await Promise.all([loadReplies(), loadPost()])
  } catch (e) { /* 取消或已统一弹错 */ }
}

// ---------- 点赞 / 收藏 / 转发 ----------
const requireLogin = () => {
  if (userStore.isLogin) return true
  ElMessageBox.confirm(t('forumDetail.loginRequired'), t('forumDetail.tip'), {
    type: 'info', confirmButtonText: t('forumDetail.goLogin'), cancelButtonText: t('common.cancel')
  }).then(() => router.push({ path: '/login', query: { redirect: `/forum/${route.params.id}` } })).catch(() => {})
  return false
}
const doLike = async () => {
  if (!requireLogin()) return
  try {
    const res = await toggleLike(post.value.id)
    post.value.liked = res.data.liked
    post.value.likeCount = res.data.likeCount
  } catch (e) { /* 已统一弹错 */ }
}
const doFavorite = async () => {
  if (!requireLogin()) return
  try {
    const res = await toggleFavorite(post.value.id)
    post.value.favorited = res.data.favorited
    post.value.favoriteCount = res.data.favoriteCount
  } catch (e) { /* 已统一弹错 */ }
}
const doShare = async () => {
  const url = `${location.origin}/forum/${post.value.id}`
  try {
    await navigator.clipboard.writeText(url)
  } catch (_) {
    const ta = document.createElement('textarea')
    ta.value = url
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy') } catch (_) { /* ignore */ }
    document.body.removeChild(ta)
  }
  try {
    const res = await sharePost(post.value.id)
    post.value.shareCount = res.data.shareCount
    ElMessage.success(t('forumDetail.linkCopied'))
  } catch (e) { /* 已统一弹错 */ }
}

onMounted(async () => {
  try {
    const res = await getSubjects()
    subjects.value = res.data || []
  } catch (e) { /* ignore */ }
  await Promise.all([loadPost(), loadReplies()])
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="back">
      <el-button link @click="router.push('/forum')"><el-icon><ArrowLeft /></el-icon> {{ $t('forumDetail.backToForum') }}</el-button>
    </div>

    <template v-if="post">
      <!-- 帖子 -->
      <div class="post-card">
        <div class="post-head">
          <h1>
            <el-tag v-if="post.pinned" size="small" type="danger" effect="dark">{{ $t('forumDetail.pinned') }}</el-tag>
            <el-tag size="small" effect="plain" :type="post.subjectId ? 'primary' : 'info'">{{ post.subjectName }}</el-tag>
            {{ post.title }}
          </h1>
          <div class="post-meta">
            <span>{{ post.username || $t('forumDetail.anonymous') }}</span>
            <span>· {{ fmtTime(post.createdAt) }}</span>
            <span>· {{ $t('forumDetail.views', { n: post.viewCount }) }}</span>
            <span>· {{ $t('forumDetail.replies', { n: post.replyCount }) }}</span>
            <span class="ops" v-if="post.editable">
              <el-button link type="primary" size="small" @click="openEdit">{{ $t('common.edit') }}</el-button>
              <el-button link type="danger" size="small" @click="removePost">{{ $t('common.delete') }}</el-button>
            </span>
          </div>
        </div>
        <div class="post-body rich-body" v-html="render(post.content, post.contentType)"></div>
        <div class="post-actions">
          <button class="act" :class="{ on: post.liked }" :aria-label="post.liked ? $t('forumDetail.unlike') : $t('forumDetail.like')" @click="doLike">
            <el-icon><Pointer /></el-icon> {{ $t('forumDetail.like') }} <b>{{ post.likeCount }}</b>
          </button>
          <button class="act" :class="{ on: post.favorited }" :aria-label="post.favorited ? $t('forumDetail.unfavorite') : $t('forumDetail.favorite')" @click="doFavorite">
            <el-icon><component :is="post.favorited ? 'StarFilled' : 'Star'" /></el-icon> {{ $t('forumDetail.favorite') }} <b>{{ post.favoriteCount }}</b>
          </button>
          <button class="act" :aria-label="$t('forumDetail.share')" @click="doShare">
            <el-icon><Share /></el-icon> {{ $t('forumDetail.share') }} <b>{{ post.shareCount }}</b>
          </button>
        </div>
      </div>

      <!-- 回复列表 -->
      <div class="reply-card">
        <div class="reply-head">{{ $t('forumDetail.allReplies') }} <small>({{ replyTotal }})</small></div>
        <div v-for="(r, i) in replies" :key="r.id" class="reply-item" :class="{ nested: r.parentReplyId }">
          <div class="reply-meta">
            <span class="avatar">{{ (r.username || '?').charAt(0).toUpperCase() }}</span>
            <b>{{ r.username || $t('forumDetail.anonymous') }}</b>
            <span v-if="r.replyToUsername" class="reply-to">{{ $t('forumDetail.replyWord') }} <b>@{{ r.replyToUsername }}</b></span>
            <span class="time">#{{ (replyPage - 1) * replySize + i + 1 }} · {{ fmtTime(r.createdAt) }}</span>
            <el-button link type="primary" size="small" class="r-reply" @click="replyTo(r)">{{ $t('forumDetail.reply') }}</el-button>
            <el-button v-if="r.editable" link type="danger" size="small" class="del" @click="removeReply(r)">{{ $t('common.delete') }}</el-button>
          </div>
          <div class="reply-body rich-body" v-html="render(r.content, r.contentType)"></div>
        </div>
        <el-empty v-if="!replies.length" :description="$t('forumDetail.noReplies')" :image-size="60" />

        <div class="pager" v-if="replyTotal > replySize">
          <el-pagination
            background layout="prev, pager, next" :total="replyTotal"
            v-model:current-page="replyPage" :page-size="replySize"
          />
        </div>
      </div>

      <!-- 回复框 -->
      <div class="reply-editor" ref="editorRef">
        <h3>{{ replyForm.parentReplyId ? $t('forumDetail.replyTo', { name: replyForm.replyToUsername }) : $t('forumDetail.writeReply') }}</h3>
        <div v-if="replyForm.parentReplyId" class="reply-to-chip">
          {{ $t('forumDetail.replyingTo') }} <b>@{{ replyForm.replyToUsername }}</b>
          <el-button link size="small" @click="cancelReplyTo">{{ $t('common.cancel') }}</el-button>
        </div>
        <ContentEditor v-model="replyForm.content" v-model:content-type="replyForm.contentType" :height="160" :placeholder="$t('forumDetail.replyPlaceholder')" />
        <div class="reply-actions">
          <el-button @click="cancelReplyTo" v-if="replyForm.parentReplyId">{{ $t('forumDetail.cancelReply') }}</el-button>
          <el-button type="primary" :loading="replySaving" @click="submitReply">
            {{ replyForm.parentReplyId ? $t('forumDetail.replyTo', { name: replyForm.replyToUsername }) : $t('forumDetail.submitReply') }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- 编辑帖子 -->
    <el-dialog v-model="editDialog" :title="$t('forumDetail.editTitle')" width="760px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="$t('forumDetail.titleLabel')" required>
          <el-input v-model="editForm.title" maxlength="200" />
        </el-form-item>
        <el-form-item :label="$t('forumDetail.board')">
          <el-select v-model="editForm.subjectId" :placeholder="boardLabel" clearable style="width:200px">
            <el-option :label="boardLabel" value="" />
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-checkbox v-if="isAdmin" v-model="editForm.pinned" style="margin-left:16px">{{ $t('forumDetail.pinned') }}</el-checkbox>
        </el-form-item>
        <el-form-item :label="$t('forumDetail.contentLabel')">
          <ContentEditor v-model="editForm.content" v-model:content-type="editForm.contentType" :height="240" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-actions">
          <el-button @click="editDialog = false">{{ $t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="editSaving" @click="saveEdit">{{ $t('common.save') }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px 40px; }
.back { margin-bottom: 12px; }
.post-card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l); padding: 24px 28px; }
.post-head h1 { margin: 0 0 10px; font-size: var(--fs-xl); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.post-meta { display: flex; align-items: center; gap: 6px; color: #64748b; font-size: var(--fs-xs); }
.post-meta .ops { margin-left: auto; }
.post-body { margin-top: 16px; font-size: var(--fs-base); line-height: 1.8; color: var(--c-text-1); }
.post-actions {
  display: flex; gap: 10px; margin-top: 18px; padding-top: 16px; border-top: 1px solid #f1f3f8;
}
.act {
  display: inline-flex; align-items: center; gap: 6px;
  min-height: 36px; padding: 6px 16px;
  border: 1px solid var(--c-border); border-radius: var(--r-m);
  background: #fff; color: var(--c-text-2); font-size: var(--fs-base); cursor: pointer;
  transition: color .12s, border-color .12s, background .12s;
}
.act:hover { color: var(--c-primary); border-color: var(--c-primary); background: #eff6ff; }
.act.on { color: var(--c-primary); border-color: var(--c-primary); background: #eff6ff; font-weight: 600; }
.act b { font-weight: 600; }
.post-body :deep(p) { margin: 8px 0; }
.post-body :deep(pre), .reply-body :deep(pre) {
  background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow-x: auto; font-size: 13px;
}
.post-body :deep(code), .reply-body :deep(code) { background: rgba(100,116,139,.12); padding: 1px 5px; border-radius: 4px; font-size: .9em; }
.post-body :deep(pre code), .reply-body :deep(pre code) { background: transparent; padding: 0; }
.post-body :deep(img), .reply-body :deep(img) { max-width: 100%; }
.post-body :deep(blockquote), .reply-body :deep(blockquote) {
  border-left: 3px solid var(--c-border-strong); margin: 10px 0; padding: 2px 14px; color: var(--c-text-2);
}

.reply-card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l); margin-top: 16px; padding: 18px 24px; }
.reply-head { font-weight: 600; font-size: var(--fs-md); padding-bottom: 10px; border-bottom: 1px solid #f1f3f8; }
.reply-head small { color: #64748b; font-weight: 400; }
.reply-item { padding: 14px 0; border-bottom: 1px solid #f4f6fa; }
.reply-meta { display: flex; align-items: center; gap: 8px; }
.avatar {
  width: 28px; height: 28px; border-radius: 50%; background: #2563eb; color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: var(--fs-xs); font-weight: 600;
}
.reply-meta .time { color: #64748b; font-size: var(--fs-xs); }
.reply-meta .reply-to { color: var(--c-text-3); font-size: var(--fs-xs); }
.reply-meta .reply-to b { color: var(--c-primary); font-weight: 500; }
.reply-meta .del { margin-left: auto; }
/* 楼中楼: 视觉上轻微缩进,标识层级 */
.reply-item.nested { padding-left: 22px; border-left: 2px solid var(--c-primary-bg); margin-left: 8px; }
.reply-body { margin: 8px 0 0 36px; font-size: var(--fs-base); color: var(--c-text-1); line-height: 1.7; }
.reply-body :deep(p) { margin: 4px 0; }

.reply-editor { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l); margin-top: 16px; padding: 18px 24px; }
.reply-editor h3 { margin: 0 0 12px; font-size: var(--fs-md); }
.reply-to-chip {
  display: flex; align-items: center; gap: 6px; margin-bottom: 10px; padding: 6px 12px;
  background: var(--c-primary-bg); border-radius: var(--r-s); font-size: var(--fs-sm); color: var(--c-text-2);
}
.reply-to-chip b { color: var(--c-primary); }
.reply-actions { display: flex; justify-content: flex-end; margin-top: 12px; }
.pager { display: flex; justify-content: center; margin-top: 12px; }

/* 编辑弹窗: 底部按钮居中 */
.dialog-actions { display: flex; justify-content: center; gap: 10px; }
</style>
