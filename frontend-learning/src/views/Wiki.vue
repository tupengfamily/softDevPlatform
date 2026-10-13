<script setup>
// 我的 Wiki: 目录(支持子节点)+ 文章管理;目录/文章拖动排序;文章可分享给他人查看
import { computed, onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import {
  getWikiTree, createWikiDir, updateWikiDir, deleteWikiDir, moveWikiDir,
  createWikiArticle, updateWikiArticle, deleteWikiArticle, moveWikiArticle,
  shareWikiArticle, unshareWikiArticle
} from '../api/personal'
import { renderContent } from '../utils/markdown'
import ContentEditor from '../components/ContentEditor.vue'

const { t } = useI18n()

const tree = ref([])
const loading = ref(false)
const activeDirId = ref(null)
const activeDir = computed(() => tree.value.find(d => d.id === activeDirId.value) || null)
const articles = computed(() => activeDir.value?.articles || [])

// 目录树: 按 parentId 组层级,输出可渲染的扁平列表(带缩进层级)
const childrenOf = (pid) => tree.value.filter(d => (d.parentId ?? null) === (pid ?? null))
const visibleDirs = computed(() => {
  const out = []
  const walk = (pid, depth) => {
    for (const d of childrenOf(pid)) {
      out.push({ node: d, depth })
      walk(d.id, depth + 1)
    }
  }
  walk(null, 0)
  return out
})
const totalDirs = computed(() => tree.value.length)
const descendantCount = (d) => {
  let n = 0
  const walk = (pid) => { for (const c of childrenOf(pid)) { n++; walk(c.id) } }
  walk(d.id)
  return n
}

const load = async () => {
  loading.value = true
  try {
    const res = await getWikiTree()
    tree.value = res.data || []
    if (!tree.value.find(d => d.id === activeDirId.value)) {
      activeDirId.value = tree.value[0]?.id ?? null
    }
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

// ---------- 目录 ----------
const dirDialog = ref(false)
const dirSaving = ref(false)
const dirForm = reactive({ id: null, name: '', parentId: null })

const openDirAdd = (parent = null) => {
  Object.assign(dirForm, { id: null, name: '', parentId: parent?.id ?? null })
  dirDialog.value = true
}
const openDirEdit = (d) => { Object.assign(dirForm, { id: d.id, name: d.name, parentId: d.parentId ?? null }); dirDialog.value = true }
const parentName = computed(() => {
  if (!dirForm.parentId) return ''
  return tree.value.find(d => d.id === dirForm.parentId)?.name || ''
})
const saveDir = async () => {
  if (!dirForm.name.trim()) { ElMessage.warning(t('wiki.dirNameRequired')); return }
  dirSaving.value = true
  try {
    if (dirForm.id) await updateWikiDir(dirForm.id, { name: dirForm.name })
    else await createWikiDir({ name: dirForm.name, parentId: dirForm.parentId })
    ElMessage.success(dirForm.id ? t('wiki.dirUpdated') : t('wiki.dirCreated'))
    dirDialog.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    dirSaving.value = false
  }
}
const removeDir = async (d) => {
  const sub = descendantCount(d)
  const tip = sub > 0
    ? t('wiki.removeDirWithSubs', { name: d.name, n: sub })
    : t('wiki.removeDirArticles', { name: d.name, n: d.articles?.length || 0 })
  try {
    await ElMessageBox.confirm(tip, t('wiki.deleteConfirm'), { type: 'warning' })
    await deleteWikiDir(d.id)
    ElMessage.success(t('wiki.deleted'))
    await load()
  } catch (e) { /* 取消 */ }
}

// ---------- 文章 ----------
const artDialog = ref(false)
const artSaving = ref(false)
const artForm = reactive({ id: null, directoryId: null, title: '', content: '', contentType: 'markdown' })

const openArtAdd = () => {
  Object.assign(artForm, { id: null, directoryId: activeDirId.value, title: '', content: '', contentType: 'markdown' })
  artDialog.value = true
}
const openArtEdit = (a) => {
  Object.assign(artForm, { id: a.id, directoryId: a.directoryId, title: a.title, content: a.content, contentType: a.contentType || 'markdown' })
  artDialog.value = true
}
const saveArt = async () => {
  if (!artForm.title.trim()) { ElMessage.warning(t('wiki.titleRequired')); return }
  if (!artForm.content.trim()) { ElMessage.warning(t('wiki.contentRequired')); return }
  artSaving.value = true
  try {
    const data = { directoryId: artForm.directoryId, title: artForm.title, content: artForm.content, contentType: artForm.contentType }
    if (artForm.id) await updateWikiArticle(artForm.id, data)
    else await createWikiArticle(data)
    ElMessage.success(artForm.id ? t('wiki.artSaved') : t('wiki.artCreated'))
    artDialog.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    artSaving.value = false
  }
}
const removeArt = async (a) => {
  try {
    await ElMessageBox.confirm(t('wiki.removeArtTip', { title: a.title }), t('wiki.deleteConfirm'), { type: 'warning' })
    await deleteWikiArticle(a.id)
    ElMessage.success(t('wiki.deleted'))
    await load()
  } catch (e) { /* 取消 */ }
}

// ---------- 拖动排序(目录/文章) ----------
const dragState = reactive({ type: '', id: null, parentId: null })
const dragOverId = ref(null)

const onDragStart = (type, item, e) => {
  dragState.type = type
  dragState.id = item.id
  dragState.parentId = type === 'dir' ? (item.parentId ?? null) : item.directoryId
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', String(item.id))
}
const onDragOver = (type, item, e) => {
  if (dragState.type !== type || dragState.id === item.id) return
  e.preventDefault()
  dragOverId.value = item.id
}
const onDragLeave = () => { dragOverId.value = null }
const onDrop = async (type, item, e) => {
  e.preventDefault()
  dragOverId.value = null
  if (dragState.type !== type || dragState.id === item.id) return

  try {
    if (type === 'dir') {
      if ((dragState.parentId ?? null) !== (item.parentId ?? null)) {
        ElMessage.warning(t('wiki.dragSameLevel'))
        return
      }
      const siblings = childrenOf(dragState.parentId)
      const dragIdx = siblings.findIndex(x => x.id === dragState.id)
      const targetIdx = siblings.findIndex(x => x.id === item.id)
      if (dragIdx < 0 || targetIdx < 0) return
      const position = dragIdx < targetIdx ? targetIdx - 1 : targetIdx
      await moveWikiDir(dragState.id, position)
    } else {
      if (dragState.parentId !== item.directoryId) return   // 仅目录内排序
      const list = articles.value
      const dragIdx = list.findIndex(x => x.id === dragState.id)
      const targetIdx = list.findIndex(x => x.id === item.id)
      if (dragIdx < 0 || targetIdx < 0) return
      const position = dragIdx < targetIdx ? targetIdx - 1 : targetIdx
      await moveWikiArticle(dragState.id, position)
    }
    await load()
  } catch (err) { /* 拦截器已提示 */ }
}

// ---------- 分享 ----------
const shareDialog = ref(false)
const shareForm = reactive({ id: null, title: '', token: '' })
const shareUrl = computed(() => shareForm.token ? `${location.origin}/wiki/share/${shareForm.token}` : '')

const openShare = async (a) => {
  try {
    const res = await shareWikiArticle(a.id)
    Object.assign(shareForm, { id: a.id, title: a.title, token: res.data.shareToken })
    shareDialog.value = true
    await load()
  } catch (e) { /* 已统一弹错 */ }
}
const copyShare = async () => {
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    ElMessage.success(t('wiki.shareCopied'))
  } catch (e) {
    ElMessage.info(t('wiki.copyManually', { url: shareUrl.value }))
  }
}
const cancelShare = async () => {
  try {
    await unshareWikiArticle(shareForm.id)
    ElMessage.success(t('wiki.shareCanceled'))
    shareDialog.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ }
}

const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>{{ $t('wiki.title') }}</h1>
        <p>{{ $t('wiki.subtitle') }}</p>
      </div>
      <div class="hero-btns">
        <el-button @click="openDirAdd()"><el-icon><FolderAdd /></el-icon> {{ $t('wiki.createDir') }}</el-button>
        <el-button type="primary" :disabled="!activeDirId" @click="openArtAdd"><el-icon><DocumentAdd /></el-icon> {{ $t('wiki.createArticle') }}</el-button>
      </div>
    </div>

    <div class="wiki-wrap" v-loading="loading">
      <!-- 左: 目录树 -->
      <aside class="dirs">
        <div class="dirs-title">{{ $t('wiki.dirTitle') }} <small>({{ totalDirs }})</small></div>
        <div
          v-for="({ node: d, depth }) in visibleDirs" :key="d.id"
          class="dir-item" :class="{ active: d.id === activeDirId, 'drag-over': dragOverId === d.id }"
          :style="{ paddingLeft: 8 + depth * 18 + 'px' }"
          draggable="true"
          @dragstart="onDragStart('dir', d, $event)"
          @dragover="onDragOver('dir', d, $event)"
          @dragleave="onDragLeave"
          @drop="onDrop('dir', d, $event)"
          @click="activeDirId = d.id"
        >
          <div class="dir-name">
            <el-icon><Folder /></el-icon>
            <span class="t">{{ d.name }}</span>
            <span class="cnt">{{ d.articles?.length || 0 }}</span>
          </div>
          <div class="dir-ops" @click.stop>
            <el-button link size="small" @click="openDirAdd(d)">{{ $t('wiki.subDir') }}</el-button>
            <el-button link size="small" @click="openDirEdit(d)">{{ $t('wiki.rename') }}</el-button>
            <el-button link size="small" type="danger" @click="removeDir(d)">{{ $t('common.delete') }}</el-button>
          </div>
        </div>
        <el-empty v-if="!tree.length && !loading" :description="$t('wiki.emptyDirs')" :image-size="50" />
      </aside>

      <!-- 右: 文章列表 -->
      <main class="arts">
        <template v-if="activeDir">
          <div class="arts-title">
            {{ activeDir.name }} <small>({{ $t('wiki.articlesMeta', { n: articles.length }) }})</small>
          </div>
          <div
            v-for="a in articles" :key="a.id"
            class="art-item" :class="{ 'drag-over': dragOverId === a.id }"
            draggable="true"
            @dragstart="onDragStart('art', a, $event)"
            @dragover="onDragOver('art', a, $event)"
            @dragleave="onDragLeave"
            @drop="onDrop('art', a, $event)"
          >
            <div class="art-main">
              <div class="art-head">
                <b>{{ a.title }}</b>
                <el-tag v-if="a.shareToken" size="small" type="success" effect="plain">{{ $t('wiki.shared') }}</el-tag>
              </div>
              <div class="art-meta">{{ $t('wiki.updatedAt', { time: fmtTime(a.updateTime) }) }} · {{ a.contentType === 'richtext' ? $t('wiki.richtext') : 'Markdown' }}</div>
            </div>
            <div class="art-ops">
              <el-button link size="small" type="primary" @click="openArtEdit(a)">{{ $t('common.edit') }}</el-button>
              <el-button link size="small" type="success" @click="openShare(a)">{{ a.shareToken ? $t('wiki.shareLink') : $t('wiki.share') }}</el-button>
              <el-button link size="small" type="danger" @click="removeArt(a)">{{ $t('common.delete') }}</el-button>
            </div>
          </div>
          <el-empty v-if="!articles.length" :description="$t('wiki.emptyArticles')" :image-size="60">
            <el-button type="primary" @click="openArtAdd">{{ $t('wiki.createArticle') }}</el-button>
          </el-empty>
        </template>
        <el-empty v-else :description="$t('wiki.needDir')" :image-size="70">
          <el-button type="primary" @click="openDirAdd()">{{ $t('wiki.createDir') }}</el-button>
        </el-empty>
      </main>
    </div>

    <!-- 目录对话框 -->
    <el-dialog v-model="dirDialog" :title="dirForm.id ? $t('wiki.editDir') : $t('wiki.createDir')" width="420px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item v-if="!dirForm.id && parentName" :label="$t('wiki.parentDir')">
          <el-tag type="info" effect="plain">{{ parentName }}</el-tag>
        </el-form-item>
        <el-form-item :label="$t('wiki.dirName')" required>
          <el-input v-model="dirForm.name" maxlength="100" :placeholder="$t('wiki.dirNamePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dirDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="dirSaving" @click="saveDir">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 文章编辑对话框 -->
    <el-dialog v-model="artDialog" :title="artForm.id ? $t('wiki.editArticle') : $t('wiki.createArticle')" width="780px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="$t('wiki.fieldTitle')" required>
          <el-input v-model="artForm.title" maxlength="200" :placeholder="$t('wiki.articleTitlePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('wiki.artDir')">
          <el-select v-model="artForm.directoryId" style="width:240px">
            <el-option v-for="({ node: d, depth }) in visibleDirs" :key="d.id" :label="depth ? '　'.repeat(depth) + '└ ' + d.name : d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('wiki.contentLabel')">
          <ContentEditor v-model="artForm.content" v-model:content-type="artForm.contentType" :height="280" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="artDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="artSaving" @click="saveArt">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 分享对话框 -->
    <el-dialog v-model="shareDialog" :title="$t('wiki.shareTitle')" width="520px" destroy-on-close>
      <p class="share-tip">{{ $t('wiki.shareTip', { title: shareForm.title }) }}</p>
      <el-input :model-value="shareUrl" readonly>
        <template #append>
          <el-button @click="copyShare">{{ $t('wiki.copy') }}</el-button>
        </template>
      </el-input>
      <template #footer>
        <el-button type="danger" plain @click="cancelShare">{{ $t('wiki.cancelShare') }}</el-button>
        <el-button type="primary" @click="shareDialog = false">{{ $t('wiki.done') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; }
.hero { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.hero h1 { margin: 0 0 4px; font-size: var(--fs-xl); font-weight: 600; color: var(--c-text-1); }
.hero p { margin: 0; color: var(--c-text-2); font-size: var(--fs-base); }
.hero-btns { display: flex; gap: 10px; }

.wiki-wrap { display: grid; grid-template-columns: 280px 1fr; gap: 16px; align-items: start; }

.dirs { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 14px 12px; }
.dirs-title { font-weight: 600; padding: 0 8px 10px; color: var(--c-text-1); }
.dirs-title small { color: var(--c-text-3); font-weight: 400; }
.dir-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px; border-radius: var(--r-s); cursor: pointer;
  border: 1px solid transparent;
}
.dir-item:hover { background: #f5f7fa; }
.dir-item.active { background: var(--c-primary-bg); }
.dir-item.drag-over { border-color: var(--c-primary); background: var(--c-primary-bg); }
.dir-item[draggable="true"] { cursor: grab; }
.dir-item[draggable="true"]:active { cursor: grabbing; }
.dir-name { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: var(--fs-sm); }
.dir-name .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dir-name .cnt { color: var(--c-text-3); font-size: var(--fs-xs); flex: none; }
.dir-ops { display: none; flex: none; }
.dir-item:hover .dir-ops { display: flex; }

.arts { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; min-height: 320px; }
.arts-title { font-weight: 600; padding-bottom: 12px; border-bottom: 1px solid #f1f3f8; color: var(--c-text-1); }
.arts-title small { color: var(--c-text-3); font-weight: 400; }
.art-item {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 13px 8px; border-bottom: 1px solid #f4f6fa;
  border: 1px solid transparent; border-bottom-color: #f4f6fa;
  border-radius: var(--r-s); cursor: grab;
}
.art-item:active { cursor: grabbing; }
.art-item.drag-over { border-color: var(--c-primary); background: var(--c-primary-bg); }
.art-head { display: flex; align-items: center; gap: 8px; }
.art-head b { color: var(--c-text-1); font-size: var(--fs-base); }
.art-meta { color: var(--c-text-3); font-size: var(--fs-xs); margin-top: 4px; }
.art-ops { display: flex; align-items: center; flex: none; }
.share-tip { color: var(--c-text-2); font-size: var(--fs-sm); margin: 0 0 12px; }

/* ============ 移动端(<=768px): 目录与文章列表上下堆叠 ============ */
@media (max-width: 768px) {
  .wiki-wrap { grid-template-columns: 1fr; gap: 12px; }
  .art-item { flex-wrap: wrap; }
}
</style>
