<script setup>
// 学习配置(管理员): 通用学习平台的学习菜单维护
// - 科目(语言)管理: Java / Python / JavaScript ... 增删改、排序、启停
// - 课程结构管理: 章节/阶段增删改、调序(学习中心目录随之变化)
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminListSubjects, adminCreateSubject, adminUpdateSubject, adminDeleteSubject,
  adminListChapters, adminCreateChapter, adminUpdateChapter, adminDeleteChapter
} from '../api/learning'

const { t } = useI18n()

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
    ElMessage.warning(t('learningConfig.warnSubjectRequired')); return
  }
  subjectSaving.value = true
  try {
    const payload = { ...subjectForm }
    if (subjectForm.id) await adminUpdateSubject(subjectForm.id, payload)
    else await adminCreateSubject(payload)
    ElMessage.success(t('learningConfig.saveSuccess'))
    subjectDialog.value = false
    await loadSubjects()
  } catch (e) { /* 已统一弹错 */ } finally {
    subjectSaving.value = false
  }
}
const removeSubject = async (row) => {
  try {
    await ElMessageBox.confirm(t('learningConfig.deleteSubjectConfirm', { name: row.name }), t('learningConfig.deleteConfirmTitle'), { type: 'warning' })
    await adminDeleteSubject(row.id)
    ElMessage.success(t('learningConfig.deleteSuccess'))
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
    ElMessage.warning(t('learningConfig.warnChapterRequired')); return
  }
  if (!chapterForm.stageNo || !chapterForm.stageName.trim()) {
    ElMessage.warning(t('learningConfig.warnStageRequired')); return
  }
  chapterSaving.value = true
  try {
    const payload = { ...chapterForm }
    if (chapterForm.id) await adminUpdateChapter(chapterForm.id, payload)
    else await adminCreateChapter(payload)
    ElMessage.success(t('learningConfig.saveSuccess'))
    chapterDialog.value = false
    await loadChapters()
  } catch (e) { /* 已统一弹错 */ } finally {
    chapterSaving.value = false
  }
}
const removeChapter = async (row) => {
  try {
    await ElMessageBox.confirm(t('learningConfig.deleteChapterConfirm', { title: row.title, n: row.lessonCount }), t('learningConfig.deleteConfirmTitle'), { type: 'warning' })
    await adminDeleteChapter(row.id)
    ElMessage.success(t('learningConfig.deleteSuccess'))
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
        <h1>{{ $t('learningConfig.title') }}</h1>
        <p>{{ $t('learningConfig.subtitle') }}</p>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <!-- ==================== 科目(语言) ==================== -->
      <el-tab-pane :label="$t('learningConfig.tabSubjects')" name="subjects">
        <div class="panel">
          <div class="panel-head">
            <span class="hint">{{ $t('learningConfig.subjectsHint') }}</span>
            <el-button type="primary" @click="openSubjectCreate"><el-icon><Plus /></el-icon> {{ $t('learningConfig.newSubject') }}</el-button>
          </div>
          <table class="tbl">
            <thead>
              <tr>
                <th style="width:60px">{{ $t('learningConfig.colSort') }}</th>
                <th style="width:120px">{{ $t('learningConfig.colCode') }}</th>
                <th style="width:140px">{{ $t('learningConfig.colName') }}</th>
                <th>{{ $t('learningConfig.colDesc') }}</th>
                <th style="width:90px">{{ $t('learningConfig.colLessonCount') }}</th>
                <th style="width:90px">{{ $t('learningConfig.colStatus') }}</th>
                <th style="width:150px">{{ $t('common.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in subjects" :key="s.id">
                <td class="mono">{{ s.sortNo }}</td>
                <td class="mono">{{ s.code }}</td>
                <td>
                  <b>{{ s.name }}</b>
                  <el-tag v-if="s.builtin" size="small" effect="plain" type="info" style="margin-left:6px">{{ $t('learningConfig.builtin') }}</el-tag>
                </td>
                <td class="muted">{{ s.description || '—' }}</td>
                <td class="mono">{{ s.totalLessons }}</td>
                <td>
                  <el-tag size="small" :type="s.enabled ? 'success' : 'info'">{{ s.enabled ? $t('learningConfig.statusEnabled') : $t('learningConfig.statusDisabled') }}</el-tag>
                </td>
                <td>
                  <el-button link type="primary" size="small" @click="openSubjectEdit(s)">{{ $t('common.edit') }}</el-button>
                  <el-popconfirm v-if="!s.builtin" :title="$t('learningConfig.deleteSubjectPopconfirm')" @confirm="removeSubject(s)">
                    <template #reference><el-button link type="danger" size="small">{{ $t('common.delete') }}</el-button></template>
                  </el-popconfirm>
                </td>
              </tr>
              <tr v-if="!subjects.length"><td colspan="7" class="empty">{{ $t('learningConfig.emptySubjects') }}</td></tr>
            </tbody>
          </table>
        </div>
      </el-tab-pane>

      <!-- ==================== 章节(课程结构) ==================== -->
      <el-tab-pane :label="$t('learningConfig.tabChapters')" name="chapters">
        <div class="panel">
          <div class="panel-head">
            <el-select v-model="chapterSubjectId" :placeholder="$t('learningConfig.allSubjectsPlaceholder')" clearable style="width:180px" @change="loadChapters">
              <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-button type="primary" @click="openChapterCreate"><el-icon><Plus /></el-icon> {{ $t('learningConfig.newChapter') }}</el-button>
          </div>
          <table class="tbl">
            <thead>
              <tr>
                <th style="width:70px">{{ $t('learningConfig.colSubject') }}</th>
                <th style="width:60px">{{ $t('learningConfig.colStage') }}</th>
                <th style="width:120px">{{ $t('learningConfig.colStageName') }}</th>
                <th style="width:70px">{{ $t('learningConfig.colChapterNo') }}</th>
                <th>{{ $t('learningConfig.colTitle') }}</th>
                <th style="width:80px">{{ $t('learningConfig.colLesson') }}</th>
                <th style="width:90px">{{ $t('learningConfig.colEstHours') }}</th>
                <th style="width:150px">{{ $t('common.actions') }}</th>
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
                  <el-button link type="primary" size="small" @click="openChapterEdit(c)">{{ $t('common.edit') }}</el-button>
                  <el-popconfirm :title="$t('learningConfig.deleteChapterPopconfirm')" @confirm="removeChapter(c)">
                    <template #reference><el-button link type="danger" size="small">{{ $t('common.delete') }}</el-button></template>
                  </el-popconfirm>
                </td>
              </tr>
              <tr v-if="!chapters.length"><td colspan="8" class="empty">{{ $t('learningConfig.emptyChapters') }}</td></tr>
            </tbody>
          </table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 科目对话框 -->
    <el-dialog v-model="subjectDialog" :title="subjectForm.id ? $t('learningConfig.editSubjectTitle') : $t('learningConfig.newSubject')" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item :label="$t('learningConfig.colCode')" required>
          <el-input v-model="subjectForm.code" :placeholder="$t('learningConfig.codePlaceholder')" :disabled="!!subjectForm.builtin" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colName')" required>
          <el-input v-model="subjectForm.name" :placeholder="$t('learningConfig.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colDesc')">
          <el-input v-model="subjectForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.sortNoLabel')">
          <el-input-number v-model="subjectForm.sortNo" :min="0" :max="999" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.enabledLabel')">
          <el-switch v-model="subjectForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subjectDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="subjectSaving" @click="saveSubject">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 章节对话框 -->
    <el-dialog v-model="chapterDialog" :title="chapterForm.id ? $t('learningConfig.editChapterTitle') : $t('learningConfig.newChapter')" width="560px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item :label="$t('learningConfig.colSubject')" required>
          <el-select v-model="chapterForm.subjectId" style="width:100%">
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colStage')" required>
          <div style="display:flex; gap:8px; width:100%">
            <el-input-number v-model="chapterForm.stageNo" :min="1" :max="99" :placeholder="$t('learningConfig.stageNoPlaceholder')" />
            <el-input v-model="chapterForm.stageName" :placeholder="$t('learningConfig.stageNamePlaceholder')" />
          </div>
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colChapterNo')" required>
          <el-input-number v-model="chapterForm.chapterNo" :min="1" :max="999" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colTitle')" required>
          <el-input v-model="chapterForm.title" maxlength="100" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colDesc')">
          <el-input v-model="chapterForm.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.codePathLabel')">
          <el-input v-model="chapterForm.codePath" :placeholder="$t('learningConfig.codePathPlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('learningConfig.colEstHours')">
          <el-input-number v-model="chapterForm.estimatedHours" :min="0" :max="999" />&nbsp;{{ $t('learningConfig.hoursUnit') }}
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chapterDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="chapterSaving" @click="saveChapter">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px 40px; }
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
