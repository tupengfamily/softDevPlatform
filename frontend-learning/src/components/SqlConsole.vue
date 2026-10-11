<script setup>
// SQL 控制台: 编辑器 + 运行结果表
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSqlInfo, resetSql, runSql } from '../api/sandbox'

const props = defineProps({
  modelValue: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const sql = ref(props.modelValue)
watch(() => props.modelValue, v => { if (v !== sql.value) sql.value = v ?? '' })
watch(sql, v => emit('update:modelValue', v))

const running = ref(false)
const error = ref('')
const result = ref(null)             // {columns, rows, rowCount, elapsedMs}
const info = ref({ students: 0, courses: 0, scores: 0 })

const tablesText = computed(() => {
  const i = info.value
  return `students: ${i.students} · courses: ${i.courses} · scores: ${i.scores}`
})

// 把后端返回的技术异常翻译成学习者能看懂的中文提示(原始信息收进"技术细节")
const friendlyError = computed(() => {
  const raw = error.value
  if (!raw) return { title: '', hint: '', detail: '' }
  const grab = (re) => (raw.match(re) || [])[1] || ''

  const col = grab(/no such column:\s*([\w.]+)/i)
  if (col) return {
    title: `列「${col}」不存在`,
    hint: '请检查字段名拼写;可先执行 SELECT * FROM 表名 LIMIT 3 查看全部字段。',
    detail: raw
  }

  const tbl = grab(/no such table:\s*([\w.]+)/i)
  if (tbl) return {
    title: `表「${tbl}」不存在`,
    hint: '教学库包含 students / courses / scores 三张表,请检查表名。',
    detail: raw
  }

  if (/syntax error|near "/i.test(raw)) {
    const near = grab(/near "([^"]+)"/i)
    return {
      title: 'SQL 语法错误',
      hint: near ? `错误位置附近是「${near}」,请检查关键字拼写、逗号与引号是否配对。` : '请检查关键字拼写、逗号与引号是否配对。',
      detail: raw
    }
  }

  const nn = grab(/NOT NULL constraint failed:\s*([\w.]+)/i)
  if (nn) return { title: `字段「${nn}」不能为空`, hint: '插入或更新时必须为该字段提供值。', detail: raw }

  const uq = grab(/UNIQUE constraint failed:\s*([\w.]+)/i)
  if (uq) return { title: `字段「${uq}」的值重复`, hint: '违反唯一约束,请换一个值(例如 id 已存在)。', detail: raw }

  if (/FOREIGN KEY constraint failed/i.test(raw)) return {
    title: '外键约束失败',
    hint: '引用的记录不存在——例如给不存在的 student_id 写入成绩。',
    detail: raw
  }

  if (/只允许|仅支持|不允许|not allowed|readonly|unsupported/i.test(raw)) return {
    title: '语句类型不受支持',
    hint: '教学沙箱只允许 SELECT / INSERT / UPDATE / DELETE;建表等 DDL 请用右上角「重置」恢复初始结构。',
    detail: raw
  }

  return { title: '执行失败', hint: '请检查 SQL 语句,下方为数据库返回的原始信息。', detail: raw }
})

const loadInfo = async () => {
  try {
    const res = await getSqlInfo()
    info.value = res.data || info.value
  } catch (e) { /* 已统一弹错 */ }
}

const runQuery = async () => {
  if (!sql.value.trim()) { ElMessage.warning('请输入 SQL'); return }
  running.value = true
  error.value = ''
  result.value = null
  try {
    const res = await runSql(sql.value)
    const d = res.data
    if (d.error) { error.value = d.error; result.value = null }
    else { result.value = d }
    loadInfo()
  } catch (e) { /* 已统一弹错 */ } finally {
    running.value = false
  }
}

const onReset = async () => {
  try {
    await ElMessageBox.confirm('将删除并重建 students/courses/scores 三张表, 当前所有修改会丢失, 继续?', '重置教学库', {
      type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消'
    })
  } catch (_) { return }
  await resetSql()
  ElMessage.success('教学库已重置')
  result.value = null
  error.value = ''
  loadInfo()
}

onMounted(loadInfo)
</script>

<template>
  <div class="sql-box">
    <div class="sql-head">
      <div class="head-left">
        <el-icon class="ic"><Coin /></el-icon>
        <b>SQL 控制台</b>
        <span class="hint">教学库 sandbox.db · {{ tablesText }}</span>
      </div>
      <div class="head-right">
        <button class="reset" @click="onReset"><el-icon><RefreshLeft /></el-icon> 重置</button>
        <button class="run" :disabled="running" @click="runQuery">
          {{ running ? '执行中...' : '' }}<el-icon><VideoPlay /></el-icon> {{ running ? '' : '执行' }}
        </button>
      </div>
    </div>

    <textarea
      class="editor"
      v-model="sql"
      spellcheck="false"
      :placeholder="`-- 在此输入 SQL, 例如\nSELECT name, class_id FROM students WHERE class_id = 1;`"
    ></textarea>

    <div class="result">
      <!-- 错误: 友好提示 + 可展开的技术细节 -->
      <div v-if="error" class="err">
        <div class="err-head"><el-icon><WarningFilled /></el-icon> {{ friendlyError.title }}</div>
        <p v-if="friendlyError.hint" class="err-hint">{{ friendlyError.hint }}</p>
        <details class="err-detail">
          <summary>技术细节</summary>
          <pre>{{ friendlyError.detail }}</pre>
        </details>
      </div>
      <!-- 结果表 -->
      <div v-else-if="result && result.columns">
        <div class="meta"><el-icon><CircleCheckFilled /></el-icon> {{ result.rowCount }} 行 · {{ result.elapsedMs }} ms</div>
        <table>
          <thead>
            <tr><th v-for="c in result.columns" :key="c">{{ c }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in result.rows" :key="i">
              <td v-for="c in result.columns" :key="c">
                <span v-if="row[c] === null" class="null">NULL</span>
                <span v-else>{{ row[c] }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <!-- 受影响行数(DML) -->
      <div v-else-if="result && result.columns === undefined">
        <div class="meta"><el-icon><CircleCheckFilled /></el-icon> 影响 {{ result.rowCount }} 行 · {{ result.elapsedMs }} ms</div>
      </div>
      <!-- 空状态 -->
      <div v-else class="empty">
        <p>在上方输入 SQL 并点击"执行"。教学沙箱支持 SELECT / INSERT / UPDATE / DELETE,DDL 需走"重置"恢复初始结构。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sql-box { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); overflow:hidden; box-shadow:0 2px 6px rgba(20,30,60,.06) }
.sql-head { display:flex; align-items:center; justify-content:space-between; padding:12px 18px; background:linear-gradient(180deg,#f4faff,#fff); border-bottom:1px solid var(--c-border) }
.head-left { display:flex; align-items:center; gap:10px }
.head-left .ic { color:var(--c-primary); font-size:var(--fs-md) }
.head-left b { font-size:var(--fs-md) }
.head-left .hint { color:#64748b; font-size:12px; margin-left:6px }
.head-right { display:flex; gap:8px }
.head-right button { display:inline-flex; align-items:center; gap:4px; padding:6px 14px; min-height:32px; border-radius:var(--r-s); font-size:var(--fs-xs); cursor:pointer; border:1px solid transparent }
.head-right .reset { background:transparent; color:var(--c-text-2); border-color:var(--c-border-strong) }
.head-right .run { background:var(--c-primary); color:#fff }
.head-right .run:disabled { background:#93b4f5; cursor:wait }

.editor { width:100%; min-height:130px; border:0; padding:14px 18px; font-family:'JetBrains Mono',Menlo,Consolas,monospace; font-size:13px; line-height:1.7; color:#e6edf3; background:#0d1117; outline:0; resize:vertical; border-bottom:1px solid #30363d }

.result { padding:16px 18px; background:#fff }
.meta { color:var(--c-text-2); font-size:var(--fs-xs); margin-bottom:10px; display:flex; align-items:center; gap:4px }
table { width:100%; border-collapse:collapse; font-size:13px }
th,td { padding:8px 12px; border:1px solid var(--c-border); text-align:left }
th { background:#f5f7fb; color:var(--c-text-2); font-weight:500; font-size:12px }
tbody tr:nth-child(even) td { background:#fafbfc }
.null { color:#64748b; font-style:italic }

.err { background:#fef2f2; border:1px solid #fecaca; border-radius:var(--r-s); padding:10px 14px }
.err-head { color:#c12828; font-size:13px; font-weight:600; margin-bottom:4px; display:flex; align-items:center; gap:4px }
.err-hint { margin:0 0 8px; color:#7f1d1d; font-size:var(--fs-xs); line-height:1.7 }
.err-detail summary { cursor:pointer; color:#9a6a6a; font-size:12px; user-select:none }
.err-detail pre { margin:8px 0 0; padding:8px 10px; background:#fff5f5; border-radius:var(--r-s); color:#991b1b; font-size:12px; white-space:pre-wrap; font-family:'JetBrains Mono',monospace }

.empty { color:#64748b; font-size:13px }
.empty p { margin:0 }
</style>