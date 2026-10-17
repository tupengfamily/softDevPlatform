<script setup>
// Excel 简易预览: 渲染 readModel 的 sheets[].grid 为简单表格
// 限制: 只显示前 N 行(避免大表卡死), 只显示 N 列, 公式显示原始表达式
import { computed } from 'vue'

const props = defineProps({
  model: { type: Object, required: true }
})

const MAX_ROWS = 50
const MAX_COLS = 12

const sheet = computed(() => {
  const sheets = props.model?.sheets || []
  return sheets[0] || { name: 'Sheet1', grid: [], colWidths: [] }
})

const headers = computed(() => {
  // 列号 A B C ...
  const cols = Math.min(MAX_COLS, maxColsOfGrid())
  return Array.from({ length: cols }, (_, i) => colLetter(i))
})

const rows = computed(() => {
  const grid = sheet.value.grid || []
  const limit = Math.min(MAX_ROWS, grid.length)
  return Array.from({ length: limit }, (_, r) => {
    const row = grid[r] || []
    return Array.from({ length: headers.value.length }, (_, c) => display(row[c]))
  })
})

const rowCount = computed(() => (sheet.value.grid || []).length)
const truncated = computed(() => rowCount.value > MAX_ROWS || maxColsOfGrid() > MAX_COLS)

function maxColsOfGrid() {
  const grid = sheet.value.grid || []
  let max = 0
  for (const r of grid) if (r && r.length > max) max = r.length
  return max
}

function colLetter(idx) {
  let s = ''
  let n = idx
  while (n >= 0) { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1 }
  return s
}

function display(cell) {
  if (cell == null) return ''
  // 公式: d 字段是原始表达式
  if (cell.d) return cell.d
  return cell.v == null ? '' : String(cell.v)
}
</script>

<template>
  <div class="sheet-preview">
    <div class="sp-toolbar">
      <span class="sp-sheet-name">{{ sheet.name }}</span>
      <span class="sp-meta">{{ rowCount }} 行 × {{ maxColsOfGrid() }} 列</span>
      <span v-if="truncated" class="sp-warn">（仅显示前 {{ MAX_ROWS }} 行 / {{ MAX_COLS }} 列，完整内容请"打开编辑"）</span>
    </div>
    <div class="sp-table-wrap">
      <table class="sp-table">
        <thead>
          <tr>
            <th class="sp-rowhead"></th>
            <th v-for="h in headers" :key="h" class="sp-colhead">{{ h }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows" :key="ri">
            <th class="sp-rowhead">{{ ri + 1 }}</th>
            <td v-for="(c, ci) in row" :key="ci" :class="{ 'sp-formula': c.startsWith && c.startsWith('=') }">{{ c }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.sheet-preview {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f9fafb;
}
.sp-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid var(--c-border, #e5e7eb);
  font-size: 13px;
  color: var(--c-text-2, #4b5563);
}
.sp-sheet-name { font-weight: 600; color: var(--c-text-1, #1f2937); }
.sp-meta { color: var(--c-text-3, #6b7280); }
.sp-warn { color: #d97706; font-size: 12px; }
.sp-table-wrap { flex: 1; overflow: auto; padding: 12px; }
.sp-table {
  border-collapse: collapse;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  background: #fff;
  border: 1px solid var(--c-border, #e5e7eb);
}
.sp-table th, .sp-table td {
  border: 1px solid #e5e7eb;
  padding: 4px 8px;
  min-width: 80px;
  max-width: 200px;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sp-table thead th {
  background: #f3f4f6;
  font-weight: 600;
  text-align: center;
  position: sticky;
  top: 0;
  z-index: 1;
}
.sp-table .sp-rowhead {
  background: #f3f4f6;
  text-align: center;
  color: var(--c-text-3, #6b7280);
  min-width: 40px;
  position: sticky;
  left: 0;
  z-index: 1;
}
.sp-formula { color: #2563eb; font-style: italic; }
</style>
