<script setup>
// 我的待办: 个人任务/学习计划提醒,支持快速添加、勾选完成、优先级、截止日期、筛选与编辑
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { getTodos, createTodo, updateTodo, deleteTodo } from '../api/todos'

const { t } = useI18n()
const list = ref([])
const loading = ref(false)
const filter = ref('all')   // all | active | done

const load = async () => {
  loading.value = true
  try {
    const res = await getTodos()
    list.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

// ---------- 统计 / 筛选(前端过滤,列表已整表加载) ----------
const activeCount = computed(() => list.value.filter(x => x.status === 0).length)
const doneCount = computed(() => list.value.filter(x => x.status === 1).length)
const shown = computed(() => {
  if (filter.value === 'active') return list.value.filter(x => x.status === 0)
  if (filter.value === 'done') return list.value.filter(x => x.status === 1)
  return list.value
})

// ---------- 快速添加 ----------
const quick = reactive({ title: '', priority: 1, dueDate: '' })
const quickAdding = ref(false)
const quickAdd = async () => {
  const title = quick.title.trim()
  if (!title) { ElMessage.warning(t('todo.msg.titleRequired')); return }
  quickAdding.value = true
  try {
    await createTodo({ title, priority: quick.priority, dueDate: quick.dueDate || null })
    ElMessage.success(t('todo.msg.added'))
    Object.assign(quick, { title: '', priority: 1, dueDate: '' })
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    quickAdding.value = false
  }
}

// ---------- 勾选完成 / 恢复 ----------
const toggle = async (row) => {
  try {
    await updateTodo(row.id, { status: row.status === 1 ? 0 : 1 })
    await load()
  } catch (e) { /* 已统一弹错 */ }
}

// ---------- 新增/编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ id: null, title: '', priority: 1, dueDate: '', status: 0 })

const openAdd = () => {
  Object.assign(form, { id: null, title: '', priority: 1, dueDate: '', status: 0 })
  dialogVisible.value = true
}
const openEdit = (row) => {
  Object.assign(form, {
    id: row.id, title: row.title,
    priority: row.priority ?? 1, dueDate: row.dueDate || '', status: row.status
  })
  dialogVisible.value = true
}
const save = async () => {
  if (!form.title.trim()) { ElMessage.warning(t('todo.msg.titleRequired')); return }
  saving.value = true
  try {
    const data = { title: form.title, priority: form.priority, dueDate: form.dueDate || '', status: form.status }
    if (form.id) await updateTodo(form.id, data)
    else await createTodo(data)
    ElMessage.success(form.id ? t('todo.msg.updated') : t('todo.msg.added'))
    dialogVisible.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(t('todo.remove.confirm', { title: row.title }), t('todo.remove.title'), { type: 'warning' })
    await deleteTodo(row.id)
    ElMessage.success(t('todo.msg.deleted'))
    await load()
  } catch (e) { /* 取消 */ }
}

// ---------- 展示辅助 ----------
const priorityLabel = (p) => p === 2 ? t('todo.priority.high') : p === 0 ? t('todo.priority.low') : t('todo.priority.mid')
const priorityType = (p) => p === 2 ? 'danger' : p === 0 ? 'info' : 'warning'

const today = new Date().toISOString().slice(0, 10)
const dueInfo = (row) => {
  if (!row.dueDate) return null
  if (row.status === 1) return { text: row.dueDate, type: 'info', overdue: false }
  if (row.dueDate < today) return { text: `${row.dueDate} · ${t('todo.due.overdue')}`, type: 'danger', overdue: true }
  if (row.dueDate === today) return { text: t('todo.due.today'), type: 'warning', overdue: false }
  return { text: row.dueDate, type: 'info', overdue: false }
}

const emptyText = computed(() =>
  filter.value === 'active' ? t('todo.emptyActive')
  : filter.value === 'done' ? t('todo.emptyDone')
  : t('todo.empty'))

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>{{ $t('todo.hero.title') }}</h1>
        <p>{{ $t('todo.hero.desc') }}</p>
      </div>
      <div class="stats">
        <div class="stat">
          <div class="stat-num">{{ activeCount }}</div>
          <div class="stat-label">{{ $t('todo.stats.active') }}</div>
        </div>
        <div class="stat">
          <div class="stat-num done">{{ doneCount }}</div>
          <div class="stat-label">{{ $t('todo.stats.done') }}</div>
        </div>
      </div>
    </div>

    <!-- 快速添加 -->
    <div class="quick card">
      <el-input
        v-model="quick.title"
        class="quick-input"
        :placeholder="$t('todo.quick.ph')"
        maxlength="200"
        clearable
        @keyup.enter="quickAdd"
      />
      <el-select v-model="quick.priority" class="quick-priority" :placeholder="$t('todo.field.priority')">
        <el-option :label="$t('todo.priority.low')" :value="0" />
        <el-option :label="$t('todo.priority.mid')" :value="1" />
        <el-option :label="$t('todo.priority.high')" :value="2" />
      </el-select>
      <el-date-picker
        v-model="quick.dueDate"
        class="quick-due"
        type="date"
        value-format="YYYY-MM-DD"
        :placeholder="$t('todo.form.duePh')"
      />
      <el-button type="primary" :loading="quickAdding" @click="quickAdd">
        <el-icon><Plus /></el-icon> {{ $t('todo.quick.add') }}
      </el-button>
    </div>

    <!-- 筛选 -->
    <div class="toolbar">
      <el-radio-group v-model="filter">
        <el-radio-button value="all">{{ $t('todo.filter.all') }} ({{ list.length }})</el-radio-button>
        <el-radio-button value="active">{{ $t('todo.filter.active') }} ({{ activeCount }})</el-radio-button>
        <el-radio-button value="done">{{ $t('todo.filter.done') }} ({{ doneCount }})</el-radio-button>
      </el-radio-group>
      <el-button @click="openAdd"><el-icon><Plus /></el-icon> {{ $t('todo.add.title') }}</el-button>
    </div>

    <!-- 待办列表 -->
    <div class="card list-card" v-loading="loading">
      <el-empty v-if="!loading && !shown.length" :description="emptyText" />
      <div v-for="row in shown" :key="row.id" class="todo-item" :class="{ done: row.status === 1 }">
        <el-checkbox
          :model-value="row.status === 1"
          class="todo-check"
          @change="toggle(row)"
        />
        <div class="todo-main">
          <div class="todo-title">{{ row.title }}</div>
          <div class="todo-meta">
            <el-tag size="small" :type="priorityType(row.priority)" effect="plain">
              {{ $t('todo.tag.priority') }}: {{ priorityLabel(row.priority) }}
            </el-tag>
            <el-tag v-if="dueInfo(row)" size="small" :type="dueInfo(row).type" effect="plain">
              <el-icon><Calendar /></el-icon>&nbsp;{{ dueInfo(row).text }}
            </el-tag>
            <span v-else class="muted">{{ $t('todo.due.none') }}</span>
          </div>
        </div>
        <div class="todo-actions">
          <el-button text type="primary" size="small" @click="openEdit(row)"><el-icon><EditPen /></el-icon></el-button>
          <el-button text type="danger" size="small" @click="remove(row)"><el-icon><Delete /></el-icon></el-button>
        </div>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? $t('todo.edit.title') : $t('todo.add.title')" width="480px">
      <el-form label-width="86px">
        <el-form-item :label="$t('todo.field.title')" required>
          <el-input v-model="form.title" :placeholder="$t('todo.form.titlePh')" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item :label="$t('todo.field.priority')">
          <el-radio-group v-model="form.priority">
            <el-radio-button :value="0">{{ $t('todo.priority.low') }}</el-radio-button>
            <el-radio-button :value="1">{{ $t('todo.priority.mid') }}</el-radio-button>
            <el-radio-button :value="2">{{ $t('todo.priority.high') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="$t('todo.field.due')">
          <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" :placeholder="$t('todo.form.duePh')" clearable />
        </el-form-item>
        <el-form-item v-if="form.id" :label="$t('todo.field.status')">
          <el-radio-group v-model="form.status">
            <el-radio-button :value="0">{{ $t('todo.status.active') }}</el-radio-button>
            <el-radio-button :value="1">{{ $t('todo.status.done') }}</el-radio-button>
          </el-radio-group>
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
.page { padding: 24px 32px; }
.hero { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.hero h1 { margin: 0 0 4px; font-size: var(--fs-xl); font-weight: 600; color: var(--c-text-1); }
.hero p { margin: 0; color: var(--c-text-2); font-size: var(--fs-base); }
.card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 18px 20px; box-shadow: 0 2px 6px rgba(20,30,60,.04); }

.stats { display: flex; gap: 24px; }
.stat { text-align: center; }
.stat-num { font-size: 26px; font-weight: 700; color: var(--c-primary); line-height: 1.2; }
.stat-num.done { color: var(--c-success, #67c23a); }
.stat-label { font-size: var(--fs-xs); color: var(--c-text-3); margin-top: 2px; }

.quick { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; }
.quick-input { flex: 1; }
.quick-priority { width: 110px; }
.quick-due { width: 150px; }

.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }

.list-card { min-height: 120px; }
.todo-item { display: flex; align-items: center; gap: 12px; padding: 12px 4px; border-bottom: 1px solid var(--c-border); }
.todo-item:last-child { border-bottom: none; }
.todo-item.done .todo-title { text-decoration: line-through; color: var(--c-text-3); }
.todo-check { flex-shrink: 0; }
.todo-main { flex: 1; min-width: 0; }
.todo-title { font-size: var(--fs-base); color: var(--c-text-1); font-weight: 500; word-break: break-all; }
.todo-item.done .todo-title { font-weight: 400; }
.todo-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.todo-actions { flex-shrink: 0; display: flex; gap: 2px; }
.muted { color: var(--c-text-3); font-size: var(--fs-xs); }
</style>
