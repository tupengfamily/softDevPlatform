<script setup>
// 学习配置(管理员): 通用学习平台的学习菜单维护
// - 科目(语言)管理: Java / Python / JavaScript ... 增删改、排序、启停
// - 课程结构管理: 章节/阶段增删改、调序(学习中心目录随之变化)
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminListSubjects, adminCreateSubject, adminUpdateSubject, adminDeleteSubject,
  adminListChapters, adminCreateChapter, adminUpdateChapter, adminDeleteChapter
} from '../api/learning'

const activeTab = ref('subjects')
const loading = ref(false)

// ==================== 科目(语言) ====================
const subjects = ref([])
const subjectDialog = ref(false)
const subjectSaving = ref(false)
const subjectForm = reactive({ id: null, code: '', name: '', description: '', sortNo: 0, enabled: true })

const loadSubjects = async () => {
  loading.value = true
  try {
    const res = await adminListSubjects()
    subjects.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

const openSubjectCreate = () => {
  Object.assign(subjectForm, { id: null, code: '', name: '', description: '', sortNo: subjects.value.length + 1, enabled: true })
  subjectDialog.value = true
}
const openSubjectEdit = (row) => {
  Object.assign(subjectForm, { ...row })
  subjectDialog.value = true
}
const saveSubject = async () => {
  if (!subjectForm.code.trim() || !subjectForm.name.trim()) {
    ElMessage.warning('请填写科目编码与名称'); return
  }
  subjectSaving.value = true
  try {
    const payload = { ...subjectForm }
    if (subjectForm.id) await adminUpdateSubject(subjectForm.id, payload)
    else await adminCreateSubject(payload)
    ElMessage.success('已保存')
    subjectDialog.value = false
    await loadSubjects()
  } catch (e) { /* 已统一弹错 */ } finally {
    subjectSaving.value = false
  }
}
const removeSubject = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除科目「${row.name}」?`, '删除确认', { type: 'warning' })
    await adminDeleteSubject(row.id)
    ElMessage.success('已删除')
    await loadSubjects()
    await loadChapters()
  } catch (e) { /* 取消/报错已统一弹错 */ }
}

// ==================== 章节(课程结构) ====================
const chapters = ref([])
const chapterSubjectId = ref(null)   // 筛选
const chapterDialog = ref(false)
const chapterSaving = ref(false)
const chapterForm = reactive({
  id: null, subjectId: null, stageNo: 1, stageName: '', chapterNo: 1,
  title: '', description: '', codePath: '', estimatedHours: 1
})

const loadChapters = async () => {
  loading.value = true
  try {
    const res = await adminListChapters(chapterSubjectId.value)
    chapters.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

const openChapterCreate = () => {
  Object.assign(chapterForm, {
    id: null,
    subjectId: chapterSubjectId.value || subjects.value[0]?.id || null,
    stageNo: 1, stageName: '', chapterNo: chapters.value.length + 1,
    title: '', description: '', codePath: '', estimatedHours: 1
  })
  chapterDialog.value = true
}
const openChapterEdit = (row) => {
  Object.assign(chapterForm, { ...row })
  chapterDialog.value = true
}
const saveChapter = async () => {
  if (!chapterForm.title.trim() || !chapterForm.subjectId) {
    ElMessage.warning('请填写章节标题并选择科目'); return
  }
  if (!chapterForm.stageNo || !chapterForm.stageName.trim()) {
    ElMessage.warning('请填写阶段编号与阶段名称'); return
  }
  chapterSaving.value = true
  try {
    const payload = { ...chapterForm }
    if (chapterForm.id) await adminUpdateChapter(chapterForm.id, payload)
    else await adminCreateChapter(payload)
    ElMessage.success('已保存')
    chapterDialog.value = false
    await loadChapters()
  } catch (e) { /* 已统一弹错 */ } finally {
    chapterSaving.value = false
  }
}
const removeChapter = async (row) => {
  try {
    await ElMessageBox.confirm(`删除「${row.title}」将同时删除其下 ${row.lessonCount} 个课时及学习进度,确定?`, '删除确认', { type: 'warning' })
    await adminDeleteChapter(row.id)
    ElMessage.success('已删除')
    await loadChapters()
    await loadSubjects()
  } catch (e) { /* 取消/报错已统一弹错 */ }
}

const subjectNameOf = (id) => subjects.value.find(s => s.id === id)?.name || '—'

onMounted(async () => {
  await loadSubjects()
  await loadChapters()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="hero">
      <div>
        <h1>学习配置</h1>
        <p>管理学习平台的科目(语言)与课程结构,学习中心目录与看板维度随之变化。</p>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <!-- ==================== 科目(语言) ==================== -->
      <el-tab-pane label="科目(语言)" name="subjects">
        <div class="panel">
          <div class="panel-head">
            <span class="hint">平台支持多语言课程,可在此扩展 Python、JavaScript 等科目</span>
            <el-button type="primary" @click="openSubjectCreate"><el-icon><Plus /></el-icon> 新增科目</el-button>
          </div>
          <table class="tbl">
            <thead>
              <tr>
                <th style="width:60px">排序</th>
                <th style="width:120px">编码</th>
                <th style="width:140px">名称</th>
                <th>简介</th>
                <th style="width:90px">课时数</th>
                <th style="width:90px">状态</th>
                <th style="width:150px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in subjects" :key="s.id">
                <td class="mono">{{ s.sortNo }}</td>
                <td class="mono">{{ s.code }}</td>
                <td>
                  <b>{{ s.name }}</b>
                  <el-tag v-if="s.builtin" size="small" effect="plain" type="info" style="margin-left:6px">内置</el-tag>
                </td>
                <td class="muted">{{ s.description || '—' }}</td>
                <td class="mono">{{ s.totalLessons }}</td>
                <td>
                  <el-tag size="small" :type="s.enabled ? 'success' : 'info'">{{ s.enabled ? '启用' : '停用' }}</el-tag>
                </td>
                <td>
                  <el-button link type="primary" size="small" @click="openSubjectEdit(s)">编辑</el-button>
                  <el-popconfirm v-if="!s.builtin" title="确定删除该科目?" @confirm="removeSubject(s)">
                    <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                  </el-popconfirm>
                </td>
              </tr>
              <tr v-if="!subjects.length"><td colspan="7" class="empty">暂无科目</td></tr>
            </tbody>
          </table>
        </div>
      </el-tab-pane>

      <!-- ==================== 章节(课程结构) ==================== -->
      <el-tab-pane label="章节(课程结构)" name="chapters">
        <div class="panel">
          <div class="panel-head">
            <el-select v-model="chapterSubjectId" placeholder="全部科目" clearable style="width:180px" @change="loadChapters">
              <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-button type="primary" @click="openChapterCreate"><el-icon><Plus /></el-icon> 新增章节</el-button>
          </div>
          <table class="tbl">
            <thead>
              <tr>
                <th style="width:70px">科目</th>
                <th style="width:60px">阶段</th>
                <th style="width:120px">阶段名</th>
                <th style="width:70px">章序</th>
                <th>标题</th>
                <th style="width:80px">课时</th>
                <th style="width:90px">预计时长</th>
                <th style="width:150px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in chapters" :key="c.id">
                <td class="muted">{{ subjectNameOf(c.subjectId) }}</td>
                <td class="mono">{{ c.stageNo }}</td>
                <td class="muted">{{ c.stageName }}</td>
                <td class="mono">{{ c.chapterNo }}</td>
                <td><b>{{ c.title }}</b></td>
                <td class="mono">{{ c.lessonCount }}</td>
                <td class="mono">{{ c.estimatedHours }}h</td>
                <td>
                  <el-button link type="primary" size="small" @click="openChapterEdit(c)">编辑</el-button>
                  <el-popconfirm title="删除章节将级联删除课时与进度,确定?" @confirm="removeChapter(c)">
                    <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                  </el-popconfirm>
                </td>
              </tr>
              <tr v-if="!chapters.length"><td colspan="8" class="empty">暂无章节</td></tr>
            </tbody>
          </table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 科目对话框 -->
    <el-dialog v-model="subjectDialog" :title="subjectForm.id ? '编辑科目' : '新增科目'" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="编码" required>
          <el-input v-model="subjectForm.code" placeholder="如 python / javascript(唯一)" :disabled="!!subjectForm.builtin" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="subjectForm.name" placeholder="如 Python" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="subjectForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="subjectForm.sortNo" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="subjectForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subjectDialog = false">取消</el-button>
        <el-button type="primary" :loading="subjectSaving" @click="saveSubject">保存</el-button>
      </template>
    </el-dialog>

    <!-- 章节对话框 -->
    <el-dialog v-model="chapterDialog" :title="chapterForm.id ? '编辑章节' : '新增章节'" width="560px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="科目" required>
          <el-select v-model="chapterForm.subjectId" style="width:100%">
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="阶段" required>
          <div style="display:flex; gap:8px; width:100%">
            <el-input-number v-model="chapterForm.stageNo" :min="1" :max="99" placeholder="编号" />
            <el-input v-model="chapterForm.stageName" placeholder="阶段名称,如 进阶特性" />
          </div>
        </el-form-item>
        <el-form-item label="章序" required>
          <el-input-number v-model="chapterForm.chapterNo" :min="1" :max="999" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="chapterForm.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="chapterForm.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="代码位置">
          <el-input v-model="chapterForm.codePath" placeholder="如 oop/* (可选)" />
        </el-form-item>
        <el-form-item label="预计时长">
          <el-input-number v-model="chapterForm.estimatedHours" :min="0" :max="999" />&nbsp;小时
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chapterDialog = false">取消</el-button>
        <el-button type="primary" :loading="chapterSaving" @click="saveChapter">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 26px 40px; max-width: 1240px; margin: 0 auto; }
.hero {
  background: linear-gradient(120deg, #eef4ff 0%, #f6f1ff 60%, #fff 100%);
  border: 1px solid #e8edf9; border-radius: var(--r-l); padding: 20px 26px; margin-bottom: 16px;
}
.hero h1 { margin: 0 0 6px; font-size: var(--fs-xl); }
.hero p { margin: 0; color: #6b7280; font-size: var(--fs-base); }
.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); overflow: hidden; }
.panel-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid #f1f3f8; }
.panel-head .hint { color: #94a3b8; font-size: var(--fs-xs); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-base); }
.tbl th {
  text-align: left; padding: 10px 14px; background: #f8fafd; color: #6b7280;
  font-weight: 500; font-size: var(--fs-xs); border-bottom: 1px solid var(--c-border);
}
.tbl td { padding: 11px 14px; border-bottom: 1px solid #f4f6fa; }
.tbl tbody tr:hover { background: #fafbfe; }
.mono { font-family: 'JetBrains Mono', Menlo, monospace; font-size: var(--fs-xs); color: #374151; }
.muted { color: #6b7280; font-size: var(--fs-sm); }
.empty { text-align: center; color: #6b7280; padding: 30px 0; }
</style>
