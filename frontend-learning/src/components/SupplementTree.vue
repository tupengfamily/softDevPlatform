<script setup>
// 用户补充节点树: 章节/科目下用户自建的"补充章节/子节点"
// - 支持无限级子节点、富文本/Markdown 双格式
// - 所有登录用户可添加;作者/管理员可编辑、删除
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { getSupplements, createSupplement, updateSupplement, deleteSupplement } from '../api/learning'
import ContentEditor from './ContentEditor.vue'
import SupplementNodeItem from './SupplementNodeItem.vue'

const props = defineProps({
  subjectId: { type: [Number, String], default: null },
  chapterId: { type: [Number, String], default: null },
  /** 顶层按钮文案,如"添加补充章节"/"添加补充内容"(可传原文或 i18n key) */
  rootLabel: { type: String, default: '' },
  /** 标题,如"补充内容"/"社区补充章节"(可传原文或 i18n key) */
  title: { type: String, default: '' },
  /** 隐藏组件内"添加补充内容"按钮(由外部按钮组触发 openCreate) */
  hideRootButton: { type: Boolean, default: false },
  /** 没有任何补充内容时隐藏整块卡片(对话框仍可由外部 openCreate 唤出) */
  hideWhenEmpty: { type: Boolean, default: false }
})

const { t } = useI18n()
// 外部传入文案兜底: 原文/key 均可解析,缺省用组件内文案
const rootLabelDisplay = computed(() => t(props.rootLabel || 'supplement.addContent'))
const titleDisplay = computed(() => t(props.title || 'supplement.defaultTitle'))

const loading = ref(false)
const nodes = ref([])
const expanded = ref({})          // id -> bool

const load = async () => {
  if (!props.subjectId && !props.chapterId) return
  loading.value = true
  try {
    const res = await getSupplements({ subjectId: props.subjectId, chapterId: props.chapterId })
    nodes.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}
watch(() => [props.subjectId, props.chapterId], load, { immediate: true })

// ---------- 编辑对话框 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ id: null, parentNodeId: null, title: '', content: '', contentType: 'markdown' })
const dialogTitle = computed(() => {
  if (form.value.id) return t('supplement.editContent')
  return form.value.parentNodeId ? t('supplement.addChild') : rootLabelDisplay.value
})

const openCreate = (parentNode = null) => {
  form.value = {
    id: null,
    parentNodeId: parentNode ? parentNode.id : null,
    title: '',
    content: '',
    contentType: 'markdown'
  }
  dialogVisible.value = true
}
const openEdit = (node) => {
  form.value = {
    id: node.id,
    parentNodeId: node.parentNodeId,
    title: node.title,
    content: node.content,
    contentType: node.contentType || 'markdown'
  }
  dialogVisible.value = true
}

const save = async () => {
  if (!form.value.title.trim()) { ElMessage.warning(t('supplement.titleRequired')); return }
  const content = form.value.content || ''
  if (!content.trim()) { ElMessage.warning(t('supplement.contentRequired')); return }
  const payload = {
    subjectId: props.subjectId || null,
    chapterId: form.value.parentNodeId ? null : (props.chapterId || null),
    parentNodeId: form.value.parentNodeId || null,
    title: form.value.title,
    content,
    contentType: form.value.contentType
  }
  saving.value = true
  try {
    if (form.value.id) await updateSupplement(form.value.id, payload)
    else await createSupplement(payload)
    ElMessage.success(form.value.id ? t('supplement.updated') : t('supplement.added'))
    dialogVisible.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

const remove = async (node) => {
  try {
    await ElMessageBox.confirm(
      node.children?.length
        ? t('supplement.removeWithChildren', { title: node.title, n: node.children.length })
        : t('supplement.removeTip', { title: node.title }),
      t('supplement.deleteConfirm'), { type: 'warning' }
    )
    await deleteSupplement(node.id)
    ElMessage.success(t('supplement.deleted'))
    await load()
  } catch (e) { /* 取消或已统一弹错 */ }
}

const toggle = (id) => { expanded.value[id] = !expanded.value[id] }
const countNodes = (list) => list.reduce((n, x) => n + 1 + countNodes(x.children || []), 0)
const total = computed(() => countNodes(nodes.value))

// 无内容时隐藏整块卡片(hideWhenEmpty 模式);加载中保持隐藏,避免闪现
const visible = computed(() => !props.hideWhenEmpty || nodes.value.length > 0)

// 对外暴露:外部按钮组(如课时页悬浮工具栏)可触发"添加补充内容"对话框
defineExpose({ openCreate })
</script>

<template>
  <div class="supplement" v-show="visible" v-loading="loading">
    <div class="sup-head">
      <h3>{{ titleDisplay }} <small v-if="total">{{ $t('supplement.countHint', { n: total }) }}</small></h3>
      <el-button v-if="!hideRootButton" type="primary" plain size="small" @click="openCreate(null)">
        <el-icon><Plus /></el-icon> {{ rootLabelDisplay }}
      </el-button>
    </div>

    <el-empty v-if="!nodes.length && !loading" :description="$t('supplement.empty')" :image-size="60" />

    <div class="node-list">
      <SupplementNodeItem
        v-for="node in nodes"
        :key="node.id"
        :node="node"
        :depth="0"
        :expanded="expanded"
        @toggle="toggle"
        @edit="openEdit"
        @remove="remove"
        @add-child="openCreate"
      />
    </div>

    <!-- append-to-body: 卡片隐藏(hideWhenEmpty)时对话框仍可正常弹出 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="720px" destroy-on-close append-to-body>
      <el-form label-position="top">
        <el-form-item :label="$t('supplement.fieldTitle')" required>
          <el-input v-model="form.title" maxlength="100" :placeholder="$t('supplement.titlePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('supplement.contentLabel')">
          <ContentEditor v-model="form.content" v-model:content-type="form.contentType" :height="240" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.supplement { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 18px 22px; }
.sup-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.sup-head h3 { margin: 0; font-size: var(--fs-md); color: var(--c-text-1); }
.sup-head small { font-weight: 400; color: var(--c-text-3); font-size: var(--fs-xs); margin-left: 8px; }
</style>
