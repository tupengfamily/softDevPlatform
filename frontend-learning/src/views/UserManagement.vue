<script setup>
/**
 * 用户管理页
 * <p>
 * 演示: 分页 + CRUD + 角色分配 + 启停 + 改密码
 */
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import {
  pageUsers, createUser, updateUser, deleteUser,
  updateStatus, changePassword, assignRoles
} from '../api/user'
import { listRoles } from '../api/role'
import { useUserStore } from '../store/user'
import { useDictStore } from '../store/dict'

const { t } = useI18n()
const userStore = useUserStore()

// ========== 数据 ==========
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const allRoles = ref([])

const search = reactive({
  keyword: '',
  status: null
})

const loadData = async () => {
  loading.value = true
  try {
    const resp = await pageUsers({
      keyword: search.keyword || null,
      status: search.status,
      page: page.value,
      size: size.value
    })
    tableData.value = resp.data.records || []
    total.value = resp.data.total || 0
  } catch (_) {
    tableData.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const loadRoles = async () => {
  try {
    const r = await listRoles()
    allRoles.value = r.data || []
  } catch (_) {}
}

onMounted(() => { loadData(); loadRoles() })

const resetSearch = () => {
  search.keyword = ''
  search.status = null
  page.value = 1
  loadData()
}

// ========== 创建 / 编辑对话框 ==========
const dialogVisible = ref(false)
const dialogMode = ref('create')
const formRef = ref(null)
const form = reactive({
  id: null, username: '', password: '', email: '', age: 18, address: ''
})
const rules = computed(() => ({
  username: [{ required: true, message: t('userManagement.rule.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('userManagement.rule.passwordRequired'), trigger: 'blur' }],
  email: [
    { required: true, message: t('userManagement.rule.emailRequired'), trigger: 'blur' },
    { type: 'email', message: t('userManagement.rule.emailInvalid'), trigger: 'blur' }
  ]
}))

const openCreate = () => {
  dialogMode.value = 'create'
  Object.assign(form, { id: null, username: '', password: '', email: '', age: 18, address: '' })
  dialogVisible.value = true
  // 清掉上一次表单残留的校验错误(新增/编辑共用同一个表单实例)
  nextTick(() => formRef.value?.clearValidate())
}

const openEdit = (row) => {
  dialogMode.value = 'edit'
  // 只带入编辑需要的字段,避免把行数据里的其他字段(nickname/avatar/signature 等)混入表单
  Object.assign(form, {
    id: row.id,
    username: row.username,
    password: '',
    email: row.email || '',
    age: row.age ?? 18,
    address: row.address || ''
  })
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

const submitForm = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      if (dialogMode.value === 'create') {
        await createUser(form)
        ElMessage.success(t('userManagement.msg.created'))
      } else {
        await updateUser(form.id, { username: form.username, email: form.email, age: form.age, address: form.address })
        ElMessage.success(t('userManagement.msg.updated'))
      }
      dialogVisible.value = false
      loadData()
    } catch (_) {}
  })
}

// ========== 删除 ==========
const onDelete = async (row) => {
  try {
    await ElMessageBox.confirm(t('userManagement.msg.deleteConfirm', { name: row.username }), t('userManagement.msg.deleteTitle'), { type: 'warning' })
    await deleteUser(row.id)
    ElMessage.success(t('userManagement.msg.deleted'))
    loadData()
  } catch (_) {}
}

// ========== 状态管理(启用/只读/禁用) ==========
// 文案来自数据字典 user_status(管理员可维护);tag 颜色属 UI 表现,固定在代码
const dictStore = useDictStore()
const statusMap = computed(() => ({
  1: { label: dictStore.label('user_status', 1, t('userManagement.status.enabled')), type: 'success' },
  2: { label: dictStore.label('user_status', 2, t('userManagement.status.readonly')), type: 'warning' },
  0: { label: dictStore.label('user_status', 0, t('userManagement.status.disabled')), type: 'danger' }
}))
// 状态下拉选项: 字典就绪用字典(仅保留代码支持的 1/2/0),否则兜底
const statusOptions = computed(() => {
  const dict = dictStore.options('user_status')
    .filter(o => ['1', '2', '0'].includes(String(o.value)))
    .map(o => ({ value: Number(o.value), label: o.label }))
  return dict.length ? dict : [
    { value: 1, label: t('userManagement.status.enabled') }, { value: 2, label: t('userManagement.status.readonly') }, { value: 0, label: t('userManagement.status.disabled') }
  ]
})
const changeStatus = async (row, newStatus) => {
  if (row.status === newStatus) return
  try {
    await updateStatus(row.id, newStatus)
    ElMessage.success(t('userManagement.msg.statusChanged', { name: row.username, status: statusMap.value[newStatus].label }))
    loadData()
  } catch (_) {}
}

// ========== 修改密码对话框 ==========
const pwdDialogVisible = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ userId: null, oldPassword: '', newPassword: '' })
const pwdRules = computed(() => ({
  oldPassword: [{ required: true, message: t('userManagement.rule.oldPasswordRequired'), trigger: 'blur' }],
  newPassword: [
    { required: true, message: t('userManagement.rule.newPasswordRequired'), trigger: 'blur' },
    { min: 6, max: 50, message: t('userManagement.rule.passwordLength'), trigger: 'blur' }
  ]
}))
const openPassword = (row) => {
  pwdForm.userId = row.id
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdDialogVisible.value = true
  nextTick(() => pwdFormRef.value?.clearValidate())
}
const submitPassword = async () => {
  if (!pwdFormRef.value) return
  await pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await changePassword(pwdForm.userId, {
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword
      })
      ElMessage.success(t('userManagement.msg.passwordChanged'))
      pwdDialogVisible.value = false
    } catch (_) {}
  })
}

// ========== 分配角色对话框 ==========
const roleDialogVisible = ref(false)
const roleFormRef = ref(null)
const roleForm = reactive({ userId: null, selected: [] })
const openRole = (row) => {
  roleForm.userId = row.id
  roleForm.selected = [...(row.roleCodes || [])]
  roleDialogVisible.value = true
}
const submitRoles = async () => {
  try {
    await assignRoles(roleForm.userId, roleForm.selected)
    ElMessage.success(t('userManagement.msg.rolesAssigned'))
    roleDialogVisible.value = false
    loadData()
  } catch (_) {}
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('userManagement.title') }}</div>
      <div class="page-subtitle">{{ $t('userManagement.subtitle') }}</div>
    </div>

    <!-- 搜索 -->
    <el-form :inline="true" :model="search" class="search-form">
      <el-form-item :label="$t('userManagement.field.keyword')">
        <el-input v-model="search.keyword" :placeholder="$t('userManagement.search.keywordPlaceholder')" clearable style="width:180px" @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item :label="$t('userManagement.field.status')">
        <el-select v-model="search.status" :placeholder="$t('common.all')" clearable style="width:120px" :aria-label="$t('userManagement.search.statusFilter')">
          <el-option v-for="o in statusOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
      </el-form-item>
      <el-form-item class="search-btns">
        <el-button type="primary" @click="loadData">{{ $t('userManagement.action.query') }}</el-button>
        <el-button @click="resetSearch">{{ $t('common.reset') }}</el-button>
      </el-form-item>
    </el-form>

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>{{ $t('userManagement.action.create') }}
        </el-button>
        <el-button @click="loadData">
          <el-icon><Refresh /></el-icon>{{ $t('userManagement.action.refresh') }}
        </el-button>
      </div>
      <div class="toolbar-right">
        <span class="record-count">{{ $t('userManagement.total', { n: total }) }}</span>
      </div>
    </div>

    <!-- 表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" :label="$t('userManagement.field.username')" min-width="120" />
      <el-table-column prop="email" :label="$t('userManagement.field.email')" min-width="180" />
      <el-table-column prop="age" :label="$t('userManagement.field.age')" width="70" align="center" />
      <el-table-column :label="$t('userManagement.field.role')" min-width="160">
        <template #default="{ row }">
          <el-tag v-for="c in row.roleCodes" :key="c" size="small" style="margin-right:4px">{{ c }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('userManagement.field.status')" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="statusMap[row.status]?.type || 'info'">
            {{ statusMap[row.status]?.label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('userManagement.field.createTime')" width="170">
        <template #default="{ row }">{{ row.createTime?.slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column :label="$t('common.actions')" width="190" fixed="right">
        <template #default="{ row }">
          <div class="ops">
            <el-tooltip :content="$t('common.edit')" placement="top">
              <el-button class="op-btn" size="small" circle :aria-label="$t('userManagement.action.edit')" @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip :content="$t('userManagement.action.changePassword')" placement="top">
              <el-button class="op-btn" size="small" circle type="warning" plain :aria-label="$t('userManagement.action.changePassword')" @click="openPassword(row)">
                <el-icon><Key /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip v-if="userStore.roles.includes('ADMIN')" :content="$t('userManagement.action.assignRoles')" placement="top">
              <el-button class="op-btn" size="small" circle type="primary" plain :aria-label="$t('userManagement.action.assignRoles')" @click="openRole(row)">
                <el-icon><UserFilled /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip :content="row.id === userStore.userId ? $t('userManagement.tooltip.cannotChangeOwnStatus') : $t('userManagement.action.changeStatus')" placement="top">
              <span>
                <el-dropdown trigger="click" @command="(cmd) => changeStatus(row, Number(cmd))"
                             :disabled="row.id === userStore.userId">
                  <el-button class="op-btn" size="small" circle :type="row.status === 1 ? 'info' : 'success'" plain
                             :aria-label="$t('userManagement.a11y.changeStatusCurrent', { status: statusMap[row.status]?.label || '' })">
                    <el-icon><component :is="row.status === 0 ? 'Unlock' : 'Lock'" /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="(v, k) in statusMap" :key="k" :command="k"
                                        :disabled="row.status === Number(k)">
                        <el-tag :type="v.type" size="small" effect="plain" style="margin-right:6px">{{ v.label }}</el-tag>
                        {{ Number(k) === 1 ? $t('userManagement.status.desc1') : Number(k) === 2 ? $t('userManagement.status.desc2') : $t('userManagement.status.desc0') }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </span>
            </el-tooltip>
            <el-tooltip :content="$t('common.delete')" placement="top">
              <el-button class="op-btn" size="small" circle type="danger" plain :aria-label="$t('userManagement.action.delete')" @click="onDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[5,10,20,50]"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top:16px; justify-content: flex-end;"
      @current-change="loadData"
      @size-change="loadData"
    />

    <!-- 创建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? $t('userManagement.action.create') : $t('userManagement.action.edit')" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item :label="$t('userManagement.field.username')" prop="username">
          <el-input v-model="form.username" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" :label="$t('userManagement.field.password')" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item :label="$t('userManagement.field.email')" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item :label="$t('userManagement.field.age')">
          <el-input-number v-model="form.age" :min="0" :max="150" style="width:100%" />
        </el-form-item>
        <el-form-item :label="$t('userManagement.field.address')">
          <el-input v-model="form.address" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitForm">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码对话框 -->
    <el-dialog v-model="pwdDialogVisible" :title="$t('userManagement.action.changePassword')" width="420px">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="100px">
        <el-form-item :label="$t('userManagement.field.oldPassword')" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item :label="$t('userManagement.field.newPassword')" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitPassword">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色对话框 -->
    <el-dialog v-model="roleDialogVisible" :title="$t('userManagement.action.assignRoles')" width="500px">
      <el-form ref="roleFormRef" :model="roleForm" label-width="80px">
        <el-form-item :label="$t('userManagement.field.role')">
          <el-checkbox-group v-model="roleForm.selected">
            <el-checkbox v-for="r in allRoles" :key="r.code" :value="r.code">
              {{ r.code }} ({{ r.name }})
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitRoles">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.search-form {
  margin-bottom: 16px; padding: 16px;
  background: #f5f7fa; border-radius: var(--r-s);
}
/* 搜索区: 输入框/下拉/按钮统一垂直居中对齐(按钮包进 form-item,避免基线错位) */
.search-form :deep(.el-form-item) { margin-bottom: 0; margin-right: 12px; vertical-align: middle; }
.search-form :deep(.el-form-item__label) { align-items: center; }
.search-form :deep(.search-btns) { margin-right: 0; }
.search-form :deep(.search-btns .el-form-item__content) { gap: 8px; }
.record-count { color: #6b7280; font-size: 13px; }
/* 操作列: 图标按钮 + 悬浮提示 */
.ops { display: flex; align-items: center; gap: 4px; }
.op-btn { margin-left: 0 !important; padding: 8px !important; min-width: 32px; min-height: 32px; }
.op-wrap { display: inline-flex; }
</style>