<script setup>
/**
 * 用户管理页
 * <p>
 * 演示: 分页 + CRUD + 角色分配 + 启停 + 改密码
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageUsers, createUser, updateUser, deleteUser,
  updateStatus, changePassword, assignRoles
} from '../api/user'
import { listRoles } from '../api/role'
import { useUserStore } from '../store/user'

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
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ]
}

const openCreate = () => {
  dialogMode.value = 'create'
  Object.assign(form, { id: null, username: '', password: '', email: '', age: 18, address: '' })
  dialogVisible.value = true
}

const openEdit = (row) => {
  dialogMode.value = 'edit'
  Object.assign(form, { ...row, password: '' })
  dialogVisible.value = true
}

const submitForm = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      if (dialogMode.value === 'create') {
        await createUser(form)
        ElMessage.success('创建成功')
      } else {
        await updateUser(form.id, { username: form.username, email: form.email, age: form.age, address: form.address })
        ElMessage.success('更新成功')
      }
      dialogVisible.value = false
      loadData()
    } catch (_) {}
  })
}

// ========== 删除 ==========
const onDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除用户 "${row.username}" ?`, '删除确认', { type: 'warning' })
    await deleteUser(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (_) {}
}

// ========== 状态切换 ==========
const toggleStatus = async (row) => {
  const newStatus = row.status === 1 ? 0 : 1
  try {
    await updateStatus(row.id, newStatus)
    ElMessage.success('状态已更新')
    loadData()
  } catch (_) {}
}

// ========== 修改密码对话框 ==========
const pwdDialogVisible = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ userId: null, oldPassword: '', newPassword: '' })
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 50, message: '长度 6~50', trigger: 'blur' }
  ]
}
const openPassword = (row) => {
  pwdForm.userId = row.id
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdDialogVisible.value = true
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
      ElMessage.success('密码已修改')
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
    ElMessage.success('角色已分配')
    roleDialogVisible.value = false
    loadData()
  } catch (_) {}
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">用户管理</div>
      <div class="page-subtitle">演示分页 + CRUD + 角色分配 + 启停 + 改密码</div>
    </div>

    <!-- 搜索 -->
    <el-form :inline="true" :model="search" class="search-form">
      <el-form-item label="关键词">
        <el-input v-model="search.keyword" placeholder="用户名/邮箱" clearable style="width:180px" @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="search.status" placeholder="全部" clearable style="width:120px">
          <el-option :value="1" label="启用" />
          <el-option :value="0" label="禁用" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="loadData">查询</el-button>
      <el-button @click="resetSearch">重置</el-button>
    </el-form>

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>新增用户
        </el-button>
        <el-button @click="loadData">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
      <div class="toolbar-right">
        <span class="record-count">共 {{ total }} 条</span>
      </div>
    </div>

    <!-- 表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column prop="age" label="年龄" width="70" align="center" />
      <el-table-column label="角色" min-width="160">
        <template #default="{ row }">
          <el-tag v-for="c in row.roleCodes" :key="c" size="small" style="margin-right:4px">{{ c }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ row.createTime?.slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <div class="ops">
            <el-tooltip content="编辑" placement="top">
              <el-button class="op-btn" size="small" circle @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="修改密码" placement="top">
              <el-button class="op-btn" size="small" circle type="warning" plain @click="openPassword(row)">
                <el-icon><Key /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip v-if="userStore.roles.includes('ADMIN')" content="分配角色" placement="top">
              <el-button class="op-btn" size="small" circle type="primary" plain @click="openRole(row)">
                <el-icon><UserFilled /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip :content="row.status === 1 ? '禁用' : '启用'" placement="top">
              <el-button class="op-btn" size="small" circle :type="row.status === 1 ? 'info' : 'success'" plain @click="toggleStatus(row)">
                <el-icon><component :is="row.status === 1 ? 'Lock' : 'Unlock'" /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button class="op-btn" size="small" circle type="danger" plain @click="onDelete(row)">
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
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增用户' : '编辑用户'" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="年龄">
          <el-input-number v-model="form.age" :min="0" :max="150" style="width:100%" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码对话框 -->
    <el-dialog v-model="pwdDialogVisible" title="修改密码" width="420px">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="100px">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色对话框 -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="500px">
      <el-form ref="roleFormRef" :model="roleForm" label-width="80px">
        <el-form-item label="角色">
          <el-checkbox-group v-model="roleForm.selected">
            <el-checkbox v-for="r in allRoles" :key="r.code" :value="r.code">
              {{ r.code }} ({{ r.name }})
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRoles">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.search-form {
  margin-bottom: 16px; padding: 16px;
  background: #f5f7fa; border-radius: var(--r-s);
}
.record-count { color: #6b7280; font-size: 13px; }
/* 操作列: 图标按钮 + 悬浮提示 */
.ops { display: flex; align-items: center; gap: 4px; }
.op-btn { margin-left: 0 !important; padding: 5px !important; }
.op-wrap { display: inline-flex; }
</style>