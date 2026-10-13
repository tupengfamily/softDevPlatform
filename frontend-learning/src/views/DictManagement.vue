<script setup>
/**
 * 数据字典
 * <p>
 * 左: 字典类型 / 右: 该类型下的字典项
 */
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDictStore } from '../store/dict'
import {
  listDictTypes, createDictType, updateDictType, deleteDictType,
  listDictItems, createDictItem, updateDictItem, deleteDictItem
} from '../api/dict'

const { t } = useI18n()

const types = ref([])
const items = ref([])
const loading = ref(false)
const selectedType = ref(null)

// 维护后同步刷新全站字典缓存(业务页标签即时生效)
const dictStore = useDictStore()
const syncDict = () => dictStore.refresh()

// 加载类型列表
const loadTypes = async () => {
  try {
    const r = await listDictTypes()
    types.value = r.data || []
    if (!selectedType.value && types.value.length) {
      selectedType.value = types.value[0]
      loadItems(selectedType.value.typeCode)
    }
  } catch (_) {}
}

const loadItems = async (code) => {
  if (!code) return
  loading.value = true
  try {
    const r = await listDictItems(code)
    items.value = r.data || []
  } catch (_) { items.value = [] } finally { loading.value = false }
}

watch(selectedType, (val) => {
  if (val) loadItems(val.typeCode)
})

onMounted(loadTypes)

// 新增类型
const typeDialog = ref(false)
const typeForm = reactive({ typeCode: '', typeName: '', description: '' })
const openCreateType = () => {
  Object.assign(typeForm, { typeCode: '', typeName: '', description: '' })
  typeDialog.value = true
}
const submitType = async () => {
  if (!typeForm.typeCode || !typeForm.typeName) {
    ElMessage.warning(t('dictManagement.warnTypeRequired'))
    return
  }
  try {
    await createDictType(typeForm)
    ElMessage.success(t('dictManagement.createSuccess'))
    typeDialog.value = false
    loadTypes()
    syncDict()
  } catch (_) {}
}
const onDeleteType = async (row) => {
  try {
    await ElMessageBox.confirm(t('dictManagement.deleteTypeConfirm', { code: row.typeCode }), t('dictManagement.deleteConfirmTitle'), { type: 'warning' })
    await deleteDictType(row.id)
    ElMessage.success(t('dictManagement.deleteSuccess'))
    if (selectedType.value?.id === row.id) selectedType.value = null
    loadTypes()
    syncDict()
  } catch (_) {}
}

// 新增项
const itemDialog = ref(false)
const itemForm = reactive({ id: null, typeId: null, itemCode: '', itemValue: '', itemValueEn: '', sort: 0, status: 1, remark: '' })
const openCreateItem = () => {
  if (!selectedType.value) return ElMessage.warning(t('dictManagement.warnSelectType'))
  Object.assign(itemForm, { id: null, typeId: selectedType.value.id, itemCode: '', itemValue: '', itemValueEn: '', sort: 0, status: 1, remark: '' })
  itemDialog.value = true
}
const openEditItem = (row) => {
  Object.assign(itemForm, row, { itemValueEn: row.itemValueEn || '' })
  itemDialog.value = true
}
const submitItem = async () => {
  if (!itemForm.itemCode || !itemForm.itemValue) {
    ElMessage.warning(t('dictManagement.warnItemRequired'))
    return
  }
  try {
    // itemValueEn 允许为空: 传 null 表示清空(英文界面回退中文值)
    const itemValueEn = itemForm.itemValueEn || null
    if (itemForm.id) {
      await updateDictItem(itemForm.id, {
        itemValue: itemForm.itemValue, itemValueEn,
        sort: itemForm.sort, status: itemForm.status, remark: itemForm.remark
      })
    } else {
      await createDictItem({
        typeId: itemForm.typeId, itemCode: itemForm.itemCode, itemValue: itemForm.itemValue, itemValueEn,
        sort: itemForm.sort, remark: itemForm.remark
      })
    }
    ElMessage.success(t('dictManagement.saveSuccess'))
    itemDialog.value = false
    loadItems(selectedType.value.typeCode)
    syncDict()
  } catch (_) {}
}
const onDeleteItem = async (row) => {
  try {
    await ElMessageBox.confirm(t('dictManagement.deleteItemConfirm', { code: row.itemCode }), t('dictManagement.deleteConfirmTitle'), { type: 'warning' })
    await deleteDictItem(row.id)
    ElMessage.success(t('dictManagement.deleteSuccess'))
    loadItems(selectedType.value.typeCode)
    syncDict()
  } catch (_) {}
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('dictManagement.title') }}</div>
      <div class="page-subtitle">{{ $t('dictManagement.subtitle') }}</div>
    </div>

    <div class="dict-layout">
      <!-- 左: 类型 -->
      <el-card shadow="never" class="left-pane">
        <template #header>
          <div style="display:flex;justify-content:space-between;align-items:center;">
            <strong>{{ $t('dictManagement.typeList') }}</strong>
            <el-button size="small" type="primary" @click="openCreateType"><el-icon><Plus /></el-icon></el-button>
          </div>
        </template>
        <el-menu :default-active="selectedType?.id?.toString()" @select="(idx) => selectedType = types.find(t => t.id == idx)">
          <el-menu-item v-for="t in types" :key="t.id" :index="t.id.toString()">
            {{ t.typeCode }} ({{ t.typeName }})
          </el-menu-item>
        </el-menu>
      </el-card>

      <!-- 右: 项 -->
      <el-card shadow="never" class="right-pane">
        <template #header>
          <div style="display:flex;justify-content:space-between;align-items:center;">
            <strong>{{ selectedType ? selectedType.typeCode + ' / ' + selectedType.typeName : $t('dictManagement.selectType') }}</strong>
            <div>
                <el-button size="small" @click="loadItems(selectedType.typeCode)" v-if="selectedType"><el-icon><Refresh /></el-icon></el-button>
                <el-button size="small" type="primary" @click="openCreateItem" v-if="selectedType"><el-icon><Plus /></el-icon>{{ $t('dictManagement.newItem') }}</el-button>
              </div>
          </div>
        </template>
        <el-table :data="items" v-loading="loading" border stripe>
          <el-table-column prop="itemCode" :label="$t('dictManagement.colCode')" width="120" />
          <el-table-column prop="itemValue" :label="$t('dictManagement.colValue')" />
          <el-table-column :label="$t('dictManagement.colValueEn')">
            <template #default="{ row }">{{ row.itemValueEn || '—' }}</template>
          </el-table-column>
          <el-table-column prop="sort" :label="$t('dictManagement.colSort')" width="80" />
          <el-table-column :label="$t('dictManagement.colStatus')" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'">
                {{ row.status === 1 ? $t('dictManagement.statusEnabled') : $t('dictManagement.statusDisabled') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" :label="$t('dictManagement.colRemark')" />
          <el-table-column :label="$t('common.actions')" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="openEditItem(row)">{{ $t('common.edit') }}</el-button>
              <el-button size="small" type="danger" @click="onDeleteItem(row)">{{ $t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 类型对话框 -->
    <el-dialog v-model="typeDialog" :title="$t('dictManagement.typeDialogTitle')" width="450px">
      <el-form :model="typeForm" label-width="100px">
        <el-form-item :label="$t('dictManagement.colCode')">
          <el-input v-model="typeForm.typeCode" />
        </el-form-item>
        <el-form-item :label="$t('dictManagement.formName')">
          <el-input v-model="typeForm.typeName" />
        </el-form-item>
        <el-form-item :label="$t('dictManagement.formDescription')">
          <el-input v-model="typeForm.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitType">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 项对话框 -->
    <el-dialog v-model="itemDialog" :title="itemForm.id ? $t('dictManagement.itemDialogTitleEdit') : $t('dictManagement.itemDialogTitleCreate')" width="450px">
      <el-form :model="itemForm" label-width="100px">
        <el-form-item :label="$t('dictManagement.colCode')">
          <el-input v-model="itemForm.itemCode" :disabled="!!itemForm.id" />
        </el-form-item>
        <el-form-item :label="$t('dictManagement.colValue')">
          <el-input v-model="itemForm.itemValue" />
        </el-form-item>
        <el-form-item :label="$t('dictManagement.colValueEn')">
          <el-input v-model="itemForm.itemValueEn" :placeholder="$t('dictManagement.valueEnPlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('dictManagement.colSort')">
          <el-input-number v-model="itemForm.sort" :min="0" style="width:100%" />
        </el-form-item>
        <el-form-item :label="$t('dictManagement.colStatus')">
          <el-radio-group v-model="itemForm.status">
            <el-radio :value="1">{{ $t('dictManagement.statusEnabled') }}</el-radio>
            <el-radio :value="0">{{ $t('dictManagement.statusDisabled') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="$t('dictManagement.colRemark')">
          <el-input v-model="itemForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitItem">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dict-layout { display: flex; gap: 16px; }
.left-pane { width: 240px; }
.right-pane { flex: 1; }
</style>
