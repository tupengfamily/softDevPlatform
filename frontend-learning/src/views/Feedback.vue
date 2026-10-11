<script setup>
// 意见反馈页: 系统评分 + bug 反馈 + 我的反馈 + 管理端反馈处理
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteFeedback, getFeedbackPage, getMyFeedback, getRatingSummary,
  handleFeedback, submitFeedback
} from '../api/feedback'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const activeTab = ref('submit')
const summary = ref({ avgScore: null, ratingCount: 0 })

const typeMap = { RATING: { text: '评分', type: 'success' }, BUG: { text: '问题反馈', type: 'danger' } }
const statusMap = {
  OPEN: { text: '待处理', type: 'warning' },
  PROCESSING: { text: '处理中', type: 'primary' },
  DONE: { text: '已处理', type: 'success' }
}

const loadSummary = async () => {
  try {
    const res = await getRatingSummary()
    summary.value = res.data || summary.value
  } catch (e) {}
}

// ============== 提交反馈 ==============
const form = reactive({ type: 'RATING', score: 5, title: '', content: '', contact: '' })
const submitting = ref(false)

const submit = async () => {
  if (form.type === 'BUG' && !form.title.trim()) {
    ElMessage.warning('请填写问题标题')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请填写反馈内容')
    return
  }
  submitting.value = true
  try {
    await submitFeedback({ ...form })
    ElMessage.success('感谢你的反馈!')
    form.title = ''
    form.content = ''
    form.contact = ''
    form.score = 5
    loadSummary()
    loadMine()
  } catch (e) { /* 已统一弹错 */ } finally {
    submitting.value = false
  }
}

// ============== 我的反馈 ==============
const mine = ref([])
const mineLoading = ref(false)
const loadMine = async () => {
  mineLoading.value = true
  try {
    const res = await getMyFeedback()
    mine.value = res.data || []
  } catch (e) {} finally {
    mineLoading.value = false
  }
}

// ============== 管理端 ==============
const query = reactive({ type: '', status: '', page: 1, size: 10 })
const adminList = ref([])
const adminTotal = ref(0)
const adminLoading = ref(false)

const loadAdmin = async () => {
  adminLoading.value = true
  try {
    const res = await getFeedbackPage({
      type: query.type || undefined,
      status: query.status || undefined,
      page: query.page,
      size: query.size
    })
    adminList.value = res.data?.records || []
    adminTotal.value = res.data?.total || 0
  } catch (e) {} finally {
    adminLoading.value = false
  }
}

const handleVisible = ref(false)
const handleForm = reactive({ id: null, status: 'DONE', adminReply: '' })

const openHandle = (row) => {
  handleForm.id = row.id
  handleForm.status = row.status === 'OPEN' ? 'PROCESSING' : row.status
  handleForm.adminReply = row.adminReply || ''
  handleVisible.value = true
}

const saveHandle = async () => {
  try {
    await handleFeedback(handleForm.id, { status: handleForm.status, adminReply: handleForm.adminReply })
    ElMessage.success('已处理')
    handleVisible.value = false
    loadAdmin()
    loadSummary()
  } catch (e) { /* 已统一弹错 */ }
}

const removeRow = async (row) => {
  try {
    await ElMessageBox.confirm('确定删除这条反馈吗?', '提示', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await deleteFeedback(row.id)
    ElMessage.success('已删除')
    loadAdmin()
  } catch (e) { /* 取消 */ }
}

const onTab = (name) => {
  if (name === 'mine') loadMine()
  if (name === 'admin') loadAdmin()
}

onMounted(() => {
  loadSummary()
  loadMine()
  if (isAdmin.value) loadAdmin()
})
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>意见反馈</h1>
        <p>你的评分与建议是我们改进的方向,欢迎反馈问题。</p>
      </div>
      <div class="score-card">
        <div class="score-num">{{ summary.avgScore ?? '—' }}</div>
        <div class="score-side">
          <el-rate :model-value="summary.avgScore || 0" disabled allow-half size="small" />
          <small>共 {{ summary.ratingCount }} 条评分</small>
        </div>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="fb-tabs" @tab-change="onTab">
      <!-- 提交反馈 -->
      <el-tab-pane label="提交反馈" name="submit">
        <div class="card">
          <el-radio-group v-model="form.type" class="type-group">
            <el-radio-button value="RATING">系统评分</el-radio-button>
            <el-radio-button value="BUG">问题反馈</el-radio-button>
          </el-radio-group>

          <div v-if="form.type === 'RATING'" class="rate-row">
            <span class="label">给系统打个分:</span>
            <el-rate v-model="form.score" show-text :texts="['很差', '较差', '一般', '不错', '很棒']" size="large" />
          </div>

          <el-form-item v-if="form.type === 'BUG'" label="问题标题" class="mt">
            <el-input v-model="form.title" placeholder="一句话描述问题" maxlength="100" />
          </el-form-item>

          <el-form-item :label="form.type === 'RATING' ? '评价内容' : '问题描述'" class="mt">
            <el-input v-model="form.content" type="textarea" :rows="5"
              :placeholder="form.type === 'RATING' ? '说说你的使用体验, 哪里做得好, 哪里可以更好?' : '请描述复现步骤、期望与实际结果, 便于我们定位问题'" />
          </el-form-item>

          <el-form-item label="联系方式" class="mt">
            <el-input v-model="form.contact" placeholder="选填, 留下邮箱/微信便于回复" maxlength="100" style="width:320px" />
          </el-form-item>

          <el-button type="primary" :loading="submitting" @click="submit">提交反馈</el-button>
        </div>
      </el-tab-pane>

      <!-- 我的反馈 -->
      <el-tab-pane label="我的反馈" name="mine">
        <div v-loading="mineLoading">
          <div v-if="mine.length" class="fb-list">
            <div v-for="f in mine" :key="f.id" class="fb-item">
              <div class="fb-head">
                <el-tag :type="typeMap[f.type]?.type" size="small" effect="plain">{{ typeMap[f.type]?.text }}</el-tag>
                <el-rate v-if="f.type === 'RATING'" :model-value="f.score" disabled size="small" />
                <span v-else class="fb-title">{{ f.title }}</span>
                <el-tag :type="statusMap[f.status]?.type" size="small" class="st">{{ statusMap[f.status]?.text }}</el-tag>
                <small class="time">{{ f.createTime?.replace('T', ' ') }}</small>
              </div>
              <p class="fb-content">{{ f.content }}</p>
              <div v-if="f.adminReply" class="fb-reply">
                <b>管理员回复:</b> {{ f.adminReply }}
              </div>
            </div>
          </div>
          <el-empty v-else description="你还没有提交过反馈" />
        </div>
      </el-tab-pane>

      <!-- 反馈管理(管理员) -->
      <el-tab-pane v-if="isAdmin" label="反馈管理" name="admin">
        <div class="card" v-loading="adminLoading">
          <div class="filter-row">
            <el-select v-model="query.type" placeholder="全部类型" clearable style="width:140px" @change="query.page = 1; loadAdmin()">
              <el-option label="评分" value="RATING" />
              <el-option label="问题反馈" value="BUG" />
            </el-select>
            <el-select v-model="query.status" placeholder="全部状态" clearable style="width:140px" @change="query.page = 1; loadAdmin()">
              <el-option label="待处理" value="OPEN" />
              <el-option label="处理中" value="PROCESSING" />
              <el-option label="已处理" value="DONE" />
            </el-select>
          </div>

          <el-table :data="adminList" stripe>
            <el-table-column prop="username" label="用户" width="100" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="typeMap[row.type]?.type" size="small" effect="plain">{{ typeMap[row.type]?.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="评分" width="140">
              <template #default="{ row }">
                <el-rate v-if="row.type === 'RATING'" :model-value="row.score" disabled size="small" />
                <span v-else class="t-title">{{ row.title }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusMap[row.status]?.type" size="small">{{ statusMap[row.status]?.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="提交时间" width="160">
              <template #default="{ row }">{{ row.createTime?.replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openHandle(row)">处理</el-button>
                <el-button link type="danger" size="small" @click="removeRow(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="query.page"
            :page-size="query.size"
            :total="adminTotal"
            layout="total, prev, pager, next"
            style="margin-top:14px; justify-content:flex-end"
            @current-change="loadAdmin"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 处理反馈 -->
    <el-dialog v-model="handleVisible" title="处理反馈" width="480px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="处理状态">
          <el-radio-group v-model="handleForm.status">
            <el-radio-button value="OPEN">待处理</el-radio-button>
            <el-radio-button value="PROCESSING">处理中</el-radio-button>
            <el-radio-button value="DONE">已处理</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input v-model="handleForm.adminReply" type="textarea" :rows="4" placeholder="回复用户 / 处理说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="saveHandle">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding:24px 32px; max-width:1100px; margin:0 auto }

.hero { display:flex; align-items:center; justify-content:space-between; margin-bottom:20px }
.hero h1 { margin:0 0 4px; font-size:var(--fs-xl); font-weight:600; color:var(--c-text-1) }
.hero p { margin:0; color:var(--c-text-2); font-size:var(--fs-base) }

.score-card { display:flex; align-items:center; gap:12px; background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:12px 18px; box-shadow:0 2px 6px rgba(20,30,60,.04) }
.score-num { font-size:32px; font-weight:600; color:#f59e0b; line-height:1 }
.score-side small { display:block; color:var(--c-text-3); font-size:var(--fs-xs); margin-top:2px }

.fb-tabs :deep(.el-tabs__header) { margin-bottom:16px }

.card { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:22px 24px; box-shadow:0 2px 6px rgba(20,30,60,.04) }
.type-group { margin-bottom:18px }
.rate-row { display:flex; align-items:center; gap:12px; margin:6px 0 4px }
.rate-row .label { font-size:var(--fs-sm); color:var(--c-text-1) }
.mt { margin-top:14px }

.fb-list { display:flex; flex-direction:column; gap:12px }
.fb-item { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:16px 18px }
.fb-head { display:flex; align-items:center; gap:10px; flex-wrap:wrap }
.fb-title { font-weight:600; color:var(--c-text-1); font-size:var(--fs-sm) }
.fb-head .st { margin-left:auto }
.fb-head .time { color:var(--c-text-3); font-size:var(--fs-xs) }
.fb-content { margin:10px 0 0; color:var(--c-text-2); font-size:var(--fs-sm); line-height:1.8; white-space:pre-wrap }
.fb-reply { margin-top:10px; background:var(--c-primary-bg); border-radius:var(--r-s); padding:10px 12px; font-size:var(--fs-sm); color:var(--c-text-2) }
.fb-reply b { color:var(--c-primary) }

.filter-row { display:flex; gap:10px; margin-bottom:14px }
.t-title { font-size:var(--fs-xs); color:var(--c-text-1) }
</style>
