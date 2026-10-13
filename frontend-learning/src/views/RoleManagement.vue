<script setup>
/**
 * 角色管理
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { listRoles, createRole, updateRole, deleteRole, assignPermissions } from '../api/role'
import { listPermissions } from '../api/permission'

const { t } = useI18n()

const tableData = ref([])
const loading = ref(false)
const allPermissions = ref([])

const loadData = async () => {
  loading.value = true
  try {
    const r = await listRoles()
    tableData.value = r.data || []
  } catch (_) {} finally { loading.value = false }
}

const loadPerms = async () => {
  try {
    const r = await listPermissions()
    allPermissions.value = r.data || []
  } catch (_) {}
}

onMounted(() => { loadData(); loadPerms() })

// 编辑对话框
const dialogVisible = ref(false)
const dialogMode = ref('create')
const formRef = ref(null)
const form = reactive({
  id: null, code: '', name: '', description: '', permissionIds: []
})
const rules = computed(() => ({
  code: [{ required: true, message: t('roleManagement.rule.codeRequired'), trigger: 'blur' }],
  name: [{ required: true, message: t('roleManagement.rule.nameRequired'), trigger: 'blur' }]
}))

const openCreate = () => {
  dialogMode.value = 'create'
  Object.assign(form, { id: null, code: '', name: '', description: '', permissionIds: [] })
  dialogVisible.value = true
}
const openEdit = (row) => {
  dialogMode.value = 'edit'
  Object.assign(form, {
    id: row.id, code: row.code, name: row.name, description: row.description,
    permissionIds: allPermissions.value.filter(p => (row.permissionCodes || []).includes(p.code)).map(p => p.id)
  })
  dialogVisible.value = true
}
const submitForm = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      if (dialogMode.value === 'create') {
        await createRole({ code: form.code, name: form.name, description: form.description, permissionIds: form.permissionIds })
      } else {
        await updateRole(form.id, { code: form.code, name: form.name, description: form.description, permissionIds: form.permissionIds })
      }
      ElMessage.success(t('roleManagement.msg.saved'))
      dialogVisible.value = false
      loadData()
    } catch (_) {}
  })
}

const onDelete = async (row) => {
  try {
    await ElMessageBox.confirm(t('roleManagement.msg.deleteConfirm', { code: row.code }), t('roleManagement.msg.deleteTitle'), { type: 'warning' })
    await deleteRole(row.id)
    ElMessage.success(t('roleManagement.msg.deleted'))
    loadData()
  } catch (_) {}
}

// 权限分配对话框
const permDialogVisible = ref(false)
const permForm = reactive({ id: null, permissionIds: [] })
const openPerm = (row) => {
  permForm.id = row.id
  permForm.permissionIds = allPermissions.value
    .filter(p => (row.permissionCodes || []).includes(p.code))
    .map(p => p.id)
  permDialogVisible.value = true
}
const submitPerms = async () => {
  try {
    await assignPermissions(permForm.id, permForm.permissionIds)
    ElMessage.success(t('roleManagement.msg.permsUpdated'))
    permDialogVisible.value = false
    loadData()
  } catch (_) {}
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('roleManagement.title') }}</div>
      <div class="page-subtitle">{{ $t('roleManagement.subtitle') }}</div>
    </div>

    <div class="toolbar">
      <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>{{ $t('roleManagement.action.create') }}</el-button>
      <el-button @click="loadData"><el-icon><Refresh /></el-icon>{{ $t('roleManagement.action.refresh') }}</el-button>
    </div>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="code" :label="$t('roleManagement.field.code')" width="120" />
      <el-table-column prop="name" :label="$t('roleManagement.field.name')" width="160" />
      <el-table-column prop="description" :label="$t('roleManagement.field.description')" min-width="200" />
      <el-table-column :label="$t('roleManagement.field.permission')" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="c in row.permissionCodes" :key="c" size="small" type="info" style="margin:2px;">{{ c }}</el-tag>
          <span v-if="!row.permissionCodes?.length" style="color:#6b7280;">{{ $t('roleManagement.empty') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('common.actions')" width="130" fixed="right">
        <template #default="{ row }">
          <div class="ops">
            <el-tooltip :content="$t('common.edit')" placement="top">
              <el-button class="op-btn" size="small" circle :aria-label="$t('roleManagement.action.edit')" @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip :content="$t('roleManagement.action.assignPerms')" placement="top">
              <el-button class="op-btn" size="small" circle type="primary" plain :aria-label="$t('roleManagement.action.assignPerms')" @click="openPerm(row)">
                <el-icon><Key /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip :disabled="row.code !== 'ADMIN'" :content="$t('common.delete')" placement="top">
              <span class="op-wrap">
                <el-button class="op-btn" size="small" circle type="danger" plain :aria-label="$t('roleManagement.action.delete')" :disabled="row.code === 'ADMIN'" @click="onDelete(row)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? $t('roleManagement.action.create') : $t('roleManagement.action.edit')" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="$t('roleManagement.field.code')" prop="code">
          <el-input v-model="form.code" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item :label="$t('roleManagement.field.name')" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item :label="$t('roleManagement.field.description')">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item :label="$t('roleManagement.field.permission')">
          <el-checkbox-group v-model="form.permissionIds">
            <el-checkbox v-for="p in allPermissions" :key="p.id" :value="p.id">
              {{ p.code }} ({{ p.name }})
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitForm">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 权限分配 -->
    <el-dialog v-model="permDialogVisible" :title="$t('roleManagement.action.assignPerms')" width="560px">
      <el-form :model="permForm" label-width="100px">
        <el-form-item :label="$t('roleManagement.field.permission')">
          <el-checkbox-group v-model="permForm.permissionIds">
            <el-checkbox v-for="p in allPermissions" :key="p.id" :value="p.id">
              {{ p.code }} ({{ p.name }})
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="permDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitPerms">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
/* 操作列: 图标按钮 + 悬浮提示 */
.ops { display: flex; align-items: center; gap: 4px; }
.op-btn { margin-left: 0 !important; padding: 8px !important; min-width: 32px; min-height: 32px; }
.op-wrap { display: inline-flex; }
</style>