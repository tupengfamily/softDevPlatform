<script setup>
// 用户补充节点树: 章节/科目下用户自建的"补充章节/子节点"
// - 支持无限级子节点、富文本/Markdown 双格式
// - 所有登录用户可添加;作者/管理员可编辑、删除
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSupplements, createSupplement, updateSupplement, deleteSupplement } from '../api/learning'
import ContentEditor from './ContentEditor.vue'
import SupplementNodeItem from './SupplementNodeItem.vue'

const props = defineProps({
  subjectId: { type: [Number, String], default: null },
  chapterId: { type: [Number, String], default: null },
  /** 顶层按钮文案,如"添加补充章节"/"添加补充内容" */
  rootLabel: { type: String, default: '添加补充内容' },
  /** 标题,如"补充内容"/"社区补充章节" */
  title: { type: String, default: '补充内容' }
})

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
  if (form.value.id) return '编辑补充内容'
  return form.value.parentNodeId ? '添加子节点' : props.rootLabel
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
  if (!form.value.title.trim()) { ElMessage.warning('请填写标题'); return }
  const content = form.value.content || ''
  if (!content.trim()) { ElMessage.warning('请填写内容'); return }
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
    ElMessage.success(form.value.id ? '已更新' : '已添加')
    dialogVisible.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

const remove = async (node) => {
  try {
    await ElMessageBox.confirm(
      node.children?.length ? `删除「${node.title}」将同时删除其 ${node.children.length} 个子节点,确定?` : `确定删除「${node.title}」?`,
      '删除确认', { type: 'warning' }
    )
    await deleteSupplement(node.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 取消或已统一弹错 */ }
}

const toggle = (id) => { expanded.value[id] = !expanded.value[id] }
const countNodes = (list) => list.reduce((n, x) => n + 1 + countNodes(x.children || []), 0)
const total = computed(() => countNodes(nodes.value))
</script>

<template>
  <div class="supplement" v-loading="loading">
    <div class="sup-head">
      <h3>{{ title }} <small v-if="total">共 {{ total }} 条 · 由学习者共同补充</small></h3>
      <el-button type="primary" plain size="small" @click="openCreate(null)">
        <el-icon><Plus /></el-icon> {{ rootLabel }}
      </el-button>
    </div>

    <el-empty v-if="!nodes.length && !loading" description="还没有补充内容,欢迎第一个添加" :image-size="60" />

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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="720px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" placeholder="简要概括补充内容" />
        </el-form-item>
        <el-form-item label="内容(支持 Markdown / 富文本)">
          <ContentEditor v-model="form.content" v-model:content-type="form.contentType" :height="240" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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
