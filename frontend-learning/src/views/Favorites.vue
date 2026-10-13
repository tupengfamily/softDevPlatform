<script setup>
// 我的收藏: 网址收藏(常用网站/工具/文档),支持分类/备注/排序;热门网站一键添加
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { getFavorites, createFavorite, updateFavorite, deleteFavorite, moveFavorite } from '../api/personal'

const { t } = useI18n()
const list = ref([])
const loading = ref(false)

// 热门网站快捷添加(分类为 UI 预置值,包 computed 保证切换语言即时生效)
const HOT_SITES = computed(() => [
  { title: 'GitHub', url: 'https://github.com', category: t('favorites.hot.cat.dev') },
  { title: 'Gitee', url: 'https://gitee.com', category: t('favorites.hot.cat.dev') },
  { title: 'Stack Overflow', url: 'https://stackoverflow.com', category: t('favorites.hot.cat.dev') },
  { title: 'MDN Web Docs', url: 'https://developer.mozilla.org', category: t('favorites.hot.cat.docs') },
  { title: '菜鸟教程', url: 'https://www.runoob.com', category: t('favorites.hot.cat.docs') },
  { title: '廖雪峰 Git 教程', url: 'https://liaoxuefeng.com/books/git', category: t('favorites.hot.cat.docs') },
  { title: '掘金', url: 'https://juejin.cn', category: t('favorites.hot.cat.dev') },
  { title: 'CSDN', url: 'https://www.csdn.net', category: t('favorites.hot.cat.dev') },
  { title: 'LeetCode', url: 'https://leetcode.cn', category: t('favorites.hot.cat.practice') },
  { title: 'Bilibili', url: 'https://www.bilibili.com', category: t('favorites.hot.cat.video') }
])

const load = async () => {
  loading.value = true
  try {
    const res = await getFavorites()
    list.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

// ---------- 新增/编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ id: null, title: '', url: '', category: '', remark: '' })

const openAdd = (preset = null) => {
  Object.assign(form, {
    id: null,
    title: preset?.title || '',
    url: preset?.url || '',
    category: preset?.category || '',
    remark: ''
  })
  dialogVisible.value = true
}
const openEdit = (row) => {
  Object.assign(form, { id: row.id, title: row.title, url: row.url, category: row.category || '', remark: row.remark || '' })
  dialogVisible.value = true
}
const save = async () => {
  if (!form.title.trim()) { ElMessage.warning(t('favorites.msg.titleRequired')); return }
  if (!form.url.trim()) { ElMessage.warning(t('favorites.msg.urlRequired')); return }
  saving.value = true
  try {
    const data = { title: form.title, url: form.url, category: form.category, remark: form.remark }
    if (form.id) await updateFavorite(form.id, data)
    else await createFavorite(data)
    ElMessage.success(form.id ? t('favorites.msg.updated') : t('favorites.msg.added'))
    dialogVisible.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(t('favorites.remove.confirm', { title: row.title }), t('favorites.remove.title'), { type: 'warning' })
    await deleteFavorite(row.id)
    ElMessage.success(t('favorites.msg.deleted'))
    await load()
  } catch (e) { /* 取消 */ }
}
const move = async (row, direction) => {
  try {
    await moveFavorite(row.id, direction)
    await load()
  } catch (e) { /* 已统一弹错 */ }
}
const openUrl = (url) => window.open(url, '_blank', 'noopener')

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>{{ $t('favorites.hero.title') }}</h1>
        <p>{{ $t('favorites.hero.desc') }}</p>
      </div>
      <el-button type="primary" @click="openAdd()"><el-icon><Plus /></el-icon> {{ $t('favorites.add') }}</el-button>
    </div>

    <!-- 热门网站快捷添加 -->
    <div class="hot card">
      <div class="hot-title">{{ $t('favorites.hot.title') }} <small>{{ $t('favorites.hot.hint') }}</small></div>
      <div class="hot-list">
        <el-tag
          v-for="s in HOT_SITES" :key="s.url"
          class="hot-tag" effect="plain" round
          @click="openAdd(s)"
        >+ {{ s.title }}</el-tag>
      </div>
    </div>

    <div class="card" v-loading="loading">
      <el-table :data="list" stripe>
        <el-table-column :label="$t('favorites.table.sort')" width="110">
          <template #default="{ $index }">
            <div class="sort-btns">
              <el-button link :disabled="$index === 0" @click="move(list[$index], 'up')"><el-icon><Top /></el-icon></el-button>
              <el-button link :disabled="$index === list.length - 1" @click="move(list[$index], 'down')"><el-icon><Bottom /></el-icon></el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="$t('favorites.field.name')" min-width="200">
          <template #default="{ row }">
            <a class="fav-link" :href="row.url" target="_blank" rel="noopener">{{ row.title }}</a>
          </template>
        </el-table-column>
        <el-table-column prop="url" :label="$t('favorites.field.url')" min-width="240" show-overflow-tooltip />
        <el-table-column :label="$t('favorites.field.category')" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.category" size="small" effect="plain">{{ row.category }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" :label="$t('favorites.field.remark')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column :label="$t('common.actions')" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openUrl(row.url)">{{ $t('favorites.table.visit') }}</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">{{ $t('common.edit') }}</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">{{ $t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" :description="$t('favorites.empty')" :image-size="70" />
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? $t('favorites.editTitle') : $t('favorites.add')" width="520px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item :label="$t('favorites.field.name')" required>
          <el-input v-model="form.title" maxlength="200" :placeholder="$t('favorites.form.titlePh')" />
        </el-form-item>
        <el-form-item :label="$t('favorites.field.url')" required>
          <el-input v-model="form.url" maxlength="500" placeholder="https://..." />
        </el-form-item>
        <el-form-item :label="$t('favorites.field.category')">
          <el-input v-model="form.category" maxlength="50" :placeholder="$t('favorites.form.categoryPh')" />
        </el-form-item>
        <el-form-item :label="$t('favorites.field.remark')">
          <el-input v-model="form.remark" maxlength="200" :placeholder="$t('favorites.form.remarkPh')" />
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
.hot { margin-bottom: 16px; }
.hot-title { font-weight: 600; margin-bottom: 10px; color: var(--c-text-1); }
.hot-title small { color: var(--c-text-3); font-weight: 400; margin-left: 8px; font-size: var(--fs-xs); }
.hot-list { display: flex; flex-wrap: wrap; gap: 8px; }
.hot-tag { cursor: pointer; user-select: none; }
.hot-tag:hover { color: var(--c-primary); border-color: var(--c-primary); }
.fav-link { color: var(--c-primary); text-decoration: none; font-weight: 500; }
.fav-link:hover { text-decoration: underline; }
.muted { color: var(--c-text-3); }
.sort-btns { display: flex; gap: 2px; }
</style>
