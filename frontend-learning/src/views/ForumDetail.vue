<script setup>
// 论坛帖子详情 — 正文(富文本/Markdown)+ 回复列表 + 回复框
// 作者/管理员可编辑、删除帖子与回复
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPost, updatePost, deletePost, pageReplies, createReply, deleteReply } from '../api/forum'
import { getSubjects } from '../api/learning'
import { renderContent } from '../utils/markdown'
import { useUserStore } from '../store/user'
import ContentEditor from '../components/ContentEditor.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
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

const loadPost = async () => {
  loading.value = true
  try {
    const res = await getPost(route.params.id)
    post.value = res.data
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}
const loadReplies = async () => {
  try {
    const res = await pageReplies(route.params.id, { page: replyPage.value, size: replySize.value })
    replies.value = res.data?.records || []
    replyTotal.value = res.data?.total || 0
  } catch (e) { /* 已统一弹错 */ }
}

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
  if (!editForm.title.trim() || !editForm.content.trim()) { ElMessage.warning('标题与内容不能为空'); return }
  editSaving.value = true
  try {
    await updatePost(post.value.id, { ...editForm, subjectId: editForm.subjectId || null })
    ElMessage.success('已更新')
    editDialog.value = false
    await loadPost()
  } catch (e) { /* 已统一弹错 */ } finally {
    editSaving.value = false
  }
}
const removePost = async () => {
  try {
    await ElMessageBox.confirm('删除帖子将同时删除全部回复,确定?', '删除确认', { type: 'warning' })
    await deletePost(post.value.id)
    ElMessage.success('已删除')
    router.push('/forum')
  } catch (e) { /* 取消或已统一弹错 */ }
}

// ---------- 回复 ----------
const replyForm = reactive({ content: '', contentType: 'markdown' })
const replySaving = ref(false)
const submitReply = async () => {
  if (!replyForm.content.trim()) { ElMessage.warning('请输入回复内容'); return }
  replySaving.value = true
  try {
    await createReply(post.value.id, { ...replyForm })
    ElMessage.success('回复成功')
    replyForm.content = ''
    replyPage.value = 1
    await Promise.all([loadReplies(), loadPost()])
  } catch (e) { /* 已统一弹错 */ } finally {
    replySaving.value = false
  }
}
const removeReply = async (r) => {
  try {
    await ElMessageBox.confirm('确定删除该回复?', '删除确认', { type: 'warning' })
    await deleteReply(r.id)
    ElMessage.success('已删除')
    await Promise.all([loadReplies(), loadPost()])
  } catch (e) { /* 取消或已统一弹错 */ }
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
      <el-button link @click="router.push('/forum')"><el-icon><ArrowLeft /></el-icon> 返回论坛</el-button>
    </div>

    <template v-if="post">
      <!-- 帖子 -->
      <div class="post-card">
        <div class="post-head">
          <h1>
            <el-tag v-if="post.pinned" size="small" type="danger" effect="dark">置顶</el-tag>
            <el-tag size="small" effect="plain" :type="post.subjectId ? 'primary' : 'info'">{{ post.subjectName }}</el-tag>
            {{ post.title }}
          </h1>
          <div class="post-meta">
            <span>{{ post.username || '匿名' }}</span>
            <span>· {{ fmtTime(post.createdAt) }}</span>
            <span>· {{ post.viewCount }} 浏览</span>
            <span>· {{ post.replyCount }} 回复</span>
            <span class="ops" v-if="post.editable">
              <el-button link type="primary" size="small" @click="openEdit">编辑</el-button>
              <el-button link type="danger" size="small" @click="removePost">删除</el-button>
            </span>
          </div>
        </div>
        <div class="post-body rich-body" v-html="render(post.content, post.contentType)"></div>
      </div>

      <!-- 回复列表 -->
      <div class="reply-card">
        <div class="reply-head">全部回复 <small>({{ replyTotal }})</small></div>
        <div v-for="(r, i) in replies" :key="r.id" class="reply-item">
          <div class="reply-meta">
            <span class="avatar">{{ (r.username || '?').charAt(0).toUpperCase() }}</span>
            <b>{{ r.username || '匿名' }}</b>
            <span class="time">#{{ i + 1 }} · {{ fmtTime(r.createdAt) }}</span>
            <el-button v-if="r.editable" link type="danger" size="small" class="del" @click="removeReply(r)">删除</el-button>
          </div>
          <div class="reply-body rich-body" v-html="render(r.content, r.contentType)"></div>
        </div>
        <el-empty v-if="!replies.length" description="还没有回复,来抢沙发" :image-size="60" />

        <div class="pager" v-if="replyTotal > replySize">
          <el-pagination
            background layout="prev, pager, next" :total="replyTotal"
            v-model:current-page="replyPage" :page-size="replySize" @current-change="loadReplies"
          />
        </div>
      </div>

      <!-- 回复框 -->
      <div class="reply-editor">
        <h3>写回复</h3>
        <ContentEditor v-model="replyForm.content" v-model:content-type="replyForm.contentType" :height="160" placeholder="写下你的想法…" />
        <div class="reply-actions">
          <el-button type="primary" :loading="replySaving" @click="submitReply">发表回复</el-button>
        </div>
      </div>
    </template>

    <!-- 编辑帖子 -->
    <el-dialog v-model="editDialog" title="编辑帖子" width="760px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="editForm.title" maxlength="200" />
        </el-form-item>
        <el-form-item label="版块">
          <el-select v-model="editForm.subjectId" placeholder="综合讨论" clearable style="width:200px">
            <el-option label="综合讨论" value="" />
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-checkbox v-if="isAdmin" v-model="editForm.pinned" style="margin-left:16px">置顶</el-checkbox>
        </el-form-item>
        <el-form-item label="内容">
          <ContentEditor v-model="editForm.content" v-model:content-type="editForm.contentType" :height="240" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-actions">
          <el-button @click="editDialog = false">取消</el-button>
          <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px 26px 40px; max-width: 900px; margin: 0 auto; }
.back { margin-bottom: 12px; }
.post-card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l); padding: 24px 28px; }
.post-head h1 { margin: 0 0 10px; font-size: var(--fs-xl); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.post-meta { display: flex; align-items: center; gap: 6px; color: #94a3b8; font-size: var(--fs-xs); }
.post-meta .ops { margin-left: auto; }
.post-body { margin-top: 16px; font-size: var(--fs-base); line-height: 1.8; color: var(--c-text-1); }
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
.reply-head small { color: #94a3b8; font-weight: 400; }
.reply-item { padding: 14px 0; border-bottom: 1px solid #f4f6fa; }
.reply-meta { display: flex; align-items: center; gap: 8px; }
.avatar {
  width: 28px; height: 28px; border-radius: 50%; background: #2563eb; color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: var(--fs-xs); font-weight: 600;
}
.reply-meta .time { color: #94a3b8; font-size: var(--fs-xs); }
.reply-meta .del { margin-left: auto; }
.reply-body { margin: 8px 0 0 36px; font-size: var(--fs-base); color: var(--c-text-1); line-height: 1.7; }
.reply-body :deep(p) { margin: 4px 0; }

.reply-editor { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l); margin-top: 16px; padding: 18px 24px; }
.reply-editor h3 { margin: 0 0 12px; font-size: var(--fs-md); }
.reply-actions { display: flex; justify-content: flex-end; margin-top: 12px; }
.pager { display: flex; justify-content: center; margin-top: 12px; }

/* 编辑弹窗: 底部按钮居中 */
.dialog-actions { display: flex; justify-content: center; gap: 10px; }
</style>
