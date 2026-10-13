<script setup>
// 意见反馈页: 系统评分 + bug 反馈 + 功能建议 + 我的反馈 + 管理端反馈处理
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteFeedback, getFeedbackPage, getMyFeedback, getRatingSummary,
  handleFeedback, submitFeedback
} from '../api/feedback'
import { useUserStore } from '../store/user'
import { useDictStore } from '../store/dict'

const userStore = useUserStore()
const route = useRoute()
const { t } = useI18n()
const dictStore = useDictStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const activeTab = ref('submit')
const summary = ref({ avgScore: null, ratingCount: 0 })

// 标签文案来自数据字典(feedback_type/feedback_status),tag 颜色固定在代码
const typeMap = computed(() => ({
  RATING: { text: dictStore.label('feedback_type', 'RATING', t('feedback.type.RATING')), type: 'success' },
  BUG: { text: dictStore.label('feedback_type', 'BUG', t('feedback.type.BUG')), type: 'danger' },
  SUGGESTION: { text: dictStore.label('feedback_type', 'SUGGESTION', t('feedback.type.SUGGESTION')), type: 'warning' }
}))
const statusMap = computed(() => ({
  OPEN: { text: dictStore.label('feedback_status', 'OPEN', t('feedback.status.OPEN')), type: 'warning' },
  PROCESSING: { text: dictStore.label('feedback_status', 'PROCESSING', t('feedback.status.PROCESSING')), type: 'primary' },
  DONE: { text: dictStore.label('feedback_status', 'DONE', t('feedback.status.DONE')), type: 'success' }
}))
// 下拉选项: 字典就绪用字典(仅保留代码支持的编码),否则兜底
const typeOptions = computed(() => {
  const d = dictStore.options('feedback_type').filter(o => typeMap.value[o.value])
  return d.length ? d : [
    { value: 'RATING', label: t('feedback.type.RATING') },
    { value: 'BUG', label: t('feedback.type.BUG') },
    { value: 'SUGGESTION', label: t('feedback.type.SUGGESTION') }
  ]
})
const statusOptions = computed(() => {
  const d = dictStore.options('feedback_status').filter(o => statusMap.value[o.value])
  return d.length ? d : [
    { value: 'OPEN', label: t('feedback.status.OPEN') },
    { value: 'PROCESSING', label: t('feedback.status.PROCESSING') },
    { value: 'DONE', label: t('feedback.status.DONE') }
  ]
})

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
  if (form.type !== 'RATING' && !form.title.trim()) {
    ElMessage.warning(form.type === 'BUG' ? t('feedback.needBugTitle') : t('feedback.needSuggestionTitle'))
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning(t('feedback.needContent'))
    return
  }
  submitting.value = true
  try {
    // 自动携带来源页面,管理员可定位反馈出处
    await submitFeedback({ ...form, source: `${t('feedback.sourcePage')} · ${route.path}` })
    ElMessage.success(t('feedback.thanks'))
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
    ElMessage.success(t('feedback.handled'))
    handleVisible.value = false
    loadAdmin()
    loadSummary()
  } catch (e) { /* 已统一弹错 */ }
}

const removeRow = async (row) => {
  try {
    await ElMessageBox.confirm(t('feedback.deleteConfirm'), t('feedback.tip'), {
      confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning'
    })
    await deleteFeedback(row.id)
    ElMessage.success(t('feedback.deleted'))
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
        <h1>{{ $t('feedback.title') }}</h1>
        <p>{{ $t('feedback.subtitle') }}</p>
      </div>
      <div class="score-card">
        <div class="score-num">{{ summary.avgScore ?? '—' }}</div>
        <div class="score-side">
          <el-rate :model-value="summary.avgScore || 0" disabled allow-half size="small" />
          <small>{{ $t('feedback.ratingCount', { n: summary.ratingCount }) }}</small>
        </div>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="fb-tabs" @tab-change="onTab">
      <!-- 提交反馈 -->
      <el-tab-pane :label="$t('feedback.tab.submit')" name="submit">
        <div class="card">
          <el-radio-group v-model="form.type" class="type-group">
            <el-radio-button v-for="o in typeOptions" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
          </el-radio-group>

          <div v-if="form.type === 'RATING'" class="rate-row">
            <span class="label">{{ $t('feedback.rateLabel') }}</span>
            <el-rate v-model="form.score" show-text
              :texts="[$t('feedback.rate.1'), $t('feedback.rate.2'), $t('feedback.rate.3'), $t('feedback.rate.4'), $t('feedback.rate.5')]"
              size="large" />
          </div>

          <el-form-item v-if="form.type !== 'RATING'" :label="form.type === 'BUG' ? $t('feedback.bugTitleLabel') : $t('feedback.suggestionTitleLabel')" class="mt">
            <el-input v-model="form.title" :placeholder="form.type === 'BUG' ? $t('feedback.bugTitlePlaceholder') : $t('feedback.suggestionTitlePlaceholder')" maxlength="100" />
          </el-form-item>

          <el-form-item
            :label="form.type === 'RATING' ? $t('feedback.ratingContentLabel') : (form.type === 'BUG' ? $t('feedback.bugContentLabel') : $t('feedback.suggestionContentLabel'))"
            class="mt">
            <el-input v-model="form.content" type="textarea" :rows="5"
              :placeholder="form.type === 'RATING' ? $t('feedback.ratingContentPlaceholder') : (form.type === 'BUG' ? $t('feedback.bugContentPlaceholder') : $t('feedback.suggestionContentPlaceholder'))" />
          </el-form-item>

          <el-form-item :label="$t('feedback.contactLabel')" class="mt">
            <el-input v-model="form.contact" :placeholder="$t('feedback.contactPlaceholder')" maxlength="100" style="width:320px" />
          </el-form-item>

          <el-button type="primary" :loading="submitting" @click="submit">{{ $t('feedback.submit') }}</el-button>
        </div>
      </el-tab-pane>

      <!-- 我的反馈 -->
      <el-tab-pane :label="$t('feedback.tab.mine')" name="mine">
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
              <small v-if="f.source" class="fb-source">{{ $t('feedback.source', { source: f.source }) }}</small>
              <div v-if="f.adminReply" class="fb-reply">
                <b>{{ $t('feedback.adminReplyLabel') }}</b> {{ f.adminReply }}
              </div>
            </div>
          </div>
          <el-empty v-else :description="$t('feedback.noFeedback')" />
        </div>
      </el-tab-pane>

      <!-- 反馈管理(管理员) -->
      <el-tab-pane v-if="isAdmin" :label="$t('feedback.tab.admin')" name="admin">
        <div class="card" v-loading="adminLoading">
          <div class="filter-row">
            <el-select v-model="query.type" :placeholder="$t('feedback.allTypes')" clearable style="width:140px" @change="query.page = 1; loadAdmin()">
              <el-option v-for="o in typeOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-select v-model="query.status" :placeholder="$t('feedback.allStatus')" clearable style="width:140px" @change="query.page = 1; loadAdmin()">
              <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </div>

          <el-table :data="adminList" stripe>
            <el-table-column prop="username" :label="$t('feedback.col.user')" width="100" />
            <el-table-column :label="$t('feedback.col.type')" width="90">
              <template #default="{ row }">
                <el-tag :type="typeMap[row.type]?.type" size="small" effect="plain">{{ typeMap[row.type]?.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="$t('feedback.col.score')" width="140">
              <template #default="{ row }">
                <el-rate v-if="row.type === 'RATING'" :model-value="row.score" disabled size="small" />
                <span v-else class="t-title">{{ row.title }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="content" :label="$t('feedback.col.content')" min-width="200" show-overflow-tooltip />
            <el-table-column prop="source" :label="$t('feedback.col.source')" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.source" class="src-cell">{{ row.source }}</span>
                <el-tag v-else size="small" type="info" effect="plain">{{ $t('feedback.unknown') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="$t('feedback.col.status')" width="90">
              <template #default="{ row }">
                <el-tag :type="statusMap[row.status]?.type" size="small">{{ statusMap[row.status]?.text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" :label="$t('feedback.col.createTime')" width="160">
              <template #default="{ row }">{{ row.createTime?.replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column :label="$t('common.actions')" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openHandle(row)">{{ $t('feedback.handle') }}</el-button>
                <el-button link type="danger" size="small" @click="removeRow(row)">{{ $t('common.delete') }}</el-button>
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
    <el-dialog v-model="handleVisible" :title="$t('feedback.handleTitle')" width="480px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item :label="$t('feedback.handleStatus')">
          <el-radio-group v-model="handleForm.status">
            <el-radio-button v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="$t('feedback.handleNote')">
          <el-input v-model="handleForm.adminReply" type="textarea" :rows="4" :placeholder="$t('feedback.handleReplyPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveHandle">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding:24px 32px 40px }

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
.fb-source { display:block; margin-top:8px; color:var(--c-text-3); font-size:var(--fs-xs) }

.filter-row { display:flex; gap:10px; margin-bottom:14px }
.t-title { font-size:var(--fs-xs); color:var(--c-text-1) }
.src-cell { font-size:var(--fs-xs); color:var(--c-text-2) }
</style>
