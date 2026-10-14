<script setup>
// 我的 Wiki: 左侧树形目录(文件夹 + 文件同级展示,可展开/折叠) + 右侧直接展示文件内容
// - 树: 文件夹下递归展示子文件夹与文件;文件夹可折叠;文件为叶子节点
// - 点击文件 → 右侧立即渲染该文件内容(含类型/字符数/时间/分享状态等详情)
// - 行内 编辑/分享/删除:鼠标悬浮到行上才显示
// - 目录与文件均支持拖动排序(同层级内)
// - 支持导入 Markdown / Word(.docx) / Excel(.xlsx) 文档为文章
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../store/user'
import {
  getWikiTree, createWikiDir, updateWikiDir, deleteWikiDir, moveWikiDir, moveWikiDirParent,
  createWikiArticle, updateWikiArticle, deleteWikiArticle, moveWikiArticle,
  shareWikiArticle, unshareWikiArticle, importWikiArticle,
  applyCollaboration, cancelCollaboration, approveCollaboration, rejectCollaboration,
  getCollabInbox, getMyCollaborations, getSharedToMe
} from '../api/personal'
import { renderContent, renderContentWithToc, extractToc } from '../utils/markdown'
import ContentEditor from '../components/ContentEditor.vue'

const { t } = useI18n()
const userStore = useUserStore()

const tree = ref([])
const loading = ref(false)

// ---------- 树: 文件夹 + 文件 ----------
const childrenOf = (pid) => tree.value.filter(d => (d.parentId ?? null) === (pid ?? null))

// 展开/收起状态:按用户持久化到 localStorage(记住每个节点的状态,刷新/重登保留)
const treeStateKey = () => `wikiCollapsedDirs:${userStore.userId || 'guest'}`
const readCollapsed = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(treeStateKey()) || '{}')
    return raw && typeof raw === 'object' ? raw : {}
  } catch (_) { return {} }
}
const collapsed = ref(readCollapsed())
watch(collapsed, v => {
  try { localStorage.setItem(treeStateKey(), JSON.stringify(v)) } catch (_) { /* 忽略存储异常 */ }
}, { deep: true })
const isExpanded = (id) => !collapsed.value[id]
const toggleExpand = (id) => { collapsed.value[id] = !collapsed.value[id] }

// 扁平化树行(含文件叶子):文件夹在前,其子层递归展开后,再列本级文件
const treeRows = computed(() => {
  const out = []
  const walk = (pid, depth) => {
    for (const d of childrenOf(pid)) {
      out.push({ kind: 'dir', data: d, depth })
      if (isExpanded(d.id)) {
        walk(d.id, depth + 1)
        for (const a of (d.articles || [])) out.push({ kind: 'article', data: a, depth: depth + 1 })
      }
    }
  }
  walk(null, 0)
  return out
})
const totalDirs = computed(() => tree.value.length)
const totalArticles = computed(() => tree.value.reduce((n, d) => n + (d.articles?.length || 0), 0))

const subtreeArticles = (d) => (d.articles?.length || 0) + childrenOf(d.id).reduce((n, c) => n + subtreeArticles(c), 0)
const descendantCount = (d) => { let n = 0; const walk = (pid) => { for (const c of childrenOf(pid)) { n++; walk(c.id) } }; walk(d.id); return n }

// ---------- 选中(右侧内容) ----------
const selected = ref(null)   // { kind:'article'|'dir', id }
const selectedArticle = computed(() => {
  if (selected.value?.kind !== 'article') return null
  const id = selected.value.id
  for (const d of tree.value) {
    const hit = (d.articles || []).find(a => a.id === id)
    if (hit) return hit
  }
  return null
})
const selectedDir = computed(() =>
  selected.value?.kind === 'dir' ? (tree.value.find(d => d.id === selected.value.id) || null) : null)
// 当前"工作目录"(新建/导入落到这里):选中文件夹=该文件夹;选中文件=它所在目录
const workDirId = computed(() => {
  if (selected.value?.kind === 'dir') return selected.value.id
  if (selected.value?.kind === 'article') return selectedArticle.value?.directoryId ?? null
  return tree.value[0]?.id ?? null
})
const workDirName = computed(() => tree.value.find(d => d.id === workDirId.value)?.name || '')

// 渲染: 给所有 h1-h6 自动补 id,这样右侧目录可以锚点跳转
const contentHtml = computed(() => selectedArticle.value
  ? renderContentWithToc(selectedArticle.value.content || '', selectedArticle.value.contentType)
  : '')
// 右侧章节目录(从已渲染的 HTML 抽 h1-h3)
const tocItems = computed(() => extractToc(contentHtml.value))
// 当前正在阅读的章节(scroll-spy 高亮)
const activeHeadingId = ref('')
// TOC 展开/收起(默认展开,按用户持久化)
const tocCollapsedKey = () => `wikiTocCollapsed:${userStore.userId || 'guest'}`
const tocCollapsed = ref(localStorage.getItem(tocCollapsedKey()) === '1')
const toggleToc = () => { tocCollapsed.value = !tocCollapsed.value; try { localStorage.setItem(tocCollapsedKey(), tocCollapsed.value ? '1' : '0') } catch (_) { /* 忽略 */ } }
// 跳转
const scrollToHeading = (id) => {
  const el = document.getElementById(id)
  if (!el) return
  // 留 12px 顶部余量,避免贴边
  const top = el.getBoundingClientRect().top + window.scrollY - 12
  window.scrollTo({ top, behavior: 'smooth' })
  activeHeadingId.value = id
  // 同时把 hash 写进 URL(可复制分享/后退定位)
  try { history.replaceState(null, '', `#${id}`) } catch (_) { /* 忽略 */ }
}
const articleShareUrl = computed(() => selectedArticle.value?.shareToken
  ? `${location.origin}/wiki/share/${selectedArticle.value.shareToken}` : '')

// ============ 全屏阅读 ============
// 只在文章查看态允许全屏;按 Esc 退出,锁定 body 滚动,切换文章时自动退出
const readerFullscreen = ref(false)
const enterReader = () => {
  if (!selectedArticle.value || mode.value !== 'view') return
  readerFullscreen.value = true
  document.body.classList.add('reader-fullscreen')
}
const exitReader = () => {
  readerFullscreen.value = false
  document.body.classList.remove('reader-fullscreen')
}
const onReaderKeydown = (e) => {
  if (e.key === 'Escape' && readerFullscreen.value) exitReader()
}
// 注: 全屏相关的两个 watch 在 mode 声明之后再注册, 避免 TDZ 报错

const selectArticle = (a) => { selected.value = { kind: 'article', id: a.id } }
const selectDir = (d) => { selected.value = { kind: 'dir', id: d.id }; toggleExpand(d.id) }

const fmtTime = (v) => (v || '').replace('T', ' ').slice(0, 16)

// 内联编辑器高度: 跟随窗口高度, 保证内容区尽量大
const inlineEditorHeight = computed(() => Math.max(320, (window.innerHeight || 800) - 380))

const load = async () => {
  loading.value = true
  try {
    const res = await getWikiTree()
    tree.value = res.data || []
    // 选中项仍存在则保留,否则默认选中第一条文件
    if (selected.value) {
      const alive = selected.value.kind === 'dir'
        ? tree.value.some(d => d.id === selected.value.id)
        : tree.value.some(d => (d.articles || []).some(a => a.id === selected.value.id))
      if (!alive) selected.value = null
    }
    if (!selected.value) {
      const first = tree.value.find(d => (d.articles || []).length)?.articles[0]
      if (first) selected.value = { kind: 'article', id: first.id }
    }
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

// ---------- 目录操作 ----------
const dirDialog = ref(false)
const dirSaving = ref(false)
const dirForm = reactive({ id: null, name: '', parentId: null })

const openDirAdd = (parent = null) => {
  Object.assign(dirForm, { id: null, name: '', parentId: parent?.id ?? null })
  dirDialog.value = true
}
const openDirEdit = (d) => { Object.assign(dirForm, { id: d.id, name: d.name, parentId: d.parentId ?? null }); dirDialog.value = true }
const parentName = computed(() => dirForm.parentId ? (tree.value.find(d => d.id === dirForm.parentId)?.name || '') : '')
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
  // 强校验: 子目录或文章数 > 0 一律拒绝(后端也会做硬校验,这里给即时提示)
  const subDirs = descendantCount(d)
  const arts = subtreeArticles(d)
  if (subDirs > 0 || arts > 0) {
    ElMessage.error(t('wiki.dirNotEmpty', { name: d.name, d: subDirs, a: arts }))
    return
  }
  try {
    await ElMessageBox.confirm(t('wiki.removeDirEmpty', { name: d.name }), t('wiki.deleteConfirm'), { type: 'warning' })
    await deleteWikiDir(d.id)
    ElMessage.success(t('wiki.deleted'))
    if (selected.value?.kind === 'dir' && selected.value.id === d.id) selected.value = null
    await load()
  } catch (e) { /* 取消 */
    // 后端拒绝(并发或绕过)时,统一拦截器会弹错,这里无需再处理
  }
}

// ---------- 文章操作(内联编辑: 新建/编辑都在右侧内容区完成, 不弹窗) ----------
// mode: 'view' 查看 | 'create' 新建 | 'edit' 编辑
const mode = ref('view')
const artSaving = ref(false)
const artForm = reactive({ id: null, directoryId: null, title: '', content: '', contentType: 'markdown' })

// 全屏相关 watch(放这里避免在 mode 声明前访问 mode,触发 TDZ)
watch(readerFullscreen, (on) => {
  if (on) {
    window.addEventListener('keydown', onReaderKeydown)
  } else {
    window.removeEventListener('keydown', onReaderKeydown)
  }
})
watch([() => selectedArticle.value?.id, mode], () => { if (readerFullscreen.value) exitReader() })

// 新建: 直接切到内容区的内联编辑态(格式在创建时选择, 创建后不可改)
const openArtAdd = () => {
  if (!workDirId.value) { ElMessage.warning(t('wiki.needDir')); return }
  Object.assign(artForm, {
    id: null,
    directoryId: workDirId.value,
    title: '',
    content: '',
    contentType: 'markdown'
  })
  selected.value = null
  mode.value = 'create'
}
const openArtEdit = (a) => {
  Object.assign(artForm, {
    id: a.id,
    directoryId: a.directoryId,
    title: a.title,
    content: a.content,
    contentType: a.contentType || 'markdown'
  })
  mode.value = 'edit'
}
const cancelArt = async () => {
  mode.value = 'view'
  await load()
}
const saveArt = async () => {
  if (!artForm.title.trim()) { ElMessage.warning(t('wiki.titleRequired')); return }
  if (!artForm.content.trim()) { ElMessage.warning(t('wiki.contentRequired')); return }
  artSaving.value = true
  try {
    const data = { directoryId: artForm.directoryId, title: artForm.title, content: artForm.content, contentType: artForm.contentType }
    let targetId = artForm.id
    if (artForm.id) await updateWikiArticle(artForm.id, data)
    else { const res = await createWikiArticle(data); targetId = res.data?.id }
    ElMessage.success(artForm.id ? t('wiki.artSaved') : t('wiki.artCreated'))
    await load()
    if (targetId) selected.value = { kind: 'article', id: targetId }
    mode.value = 'view'
  } catch (e) { /* 已统一弹错 */ } finally {
    artSaving.value = false
  }
}
const removeArt = async (a) => {
  try {
    await ElMessageBox.confirm(t('wiki.removeArtTip', { title: a.title }), t('wiki.deleteConfirm'), { type: 'warning' })
    await deleteWikiArticle(a.id)
    ElMessage.success(t('wiki.deleted'))
    if (selected.value?.kind === 'article' && selected.value.id === a.id) selected.value = null
    await load()
  } catch (e) { /* 取消 */ }
}

// ---------- 拖动排序(同层级) ----------
const dragState = reactive({ kind: '', id: null, parentId: null })
const dragOverKey = ref(null)

const rowKey = (row) => `${row.kind}-${row.data.id}`
const onDragStart = (row, e) => {
  dragState.kind = row.kind
  dragState.id = row.data.id
  dragState.parentId = row.kind === 'dir' ? (row.data.parentId ?? null) : row.data.directoryId
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', String(row.data.id))
}
// 拖到文件夹行上: 表示"移动到该文件夹内(放到末尾)"
const canDropIntoFolder = (row) => {
  if (row.kind !== 'dir') return false
  if (dragState.id === row.data.id) return false
  // 文件夹不能拖进自己
  if (dragState.kind === 'dir' && dragState.id === row.data.id) return false
  return true
}
const onDragOver = (row, e) => {
  if (dragState.id === row.data.id) return
  // 允许两类目标: 同类行(排序) 或 文件夹行(移入)
  const isFolderTarget = row.kind === 'dir'
  if (!isFolderTarget && dragState.kind !== row.kind) return
  e.preventDefault()
  dragOverKey.value = rowKey(row)
}
const onDragLeave = () => { dragOverKey.value = null }
const onDrop = async (row, e) => {
  e.preventDefault()
  dragOverKey.value = null
  if (dragState.id === row.data.id) return
  try {
    // 情况 1: 拖到"文件夹行"且不同父级 → 移入该文件夹
    if (canDropIntoFolder(row)) {
      const targetParentId = row.data.id
      const currentParent = dragState.kind === 'dir' ? (dragState.parentId ?? null) : dragState.parentId
      if (dragState.kind === 'dir') {
        if (currentParent !== targetParentId) {
          await moveWikiDirParent(dragState.id, targetParentId)
          ElMessage.success(t('wiki.movedIntoFolder'))
          await load()
          return
        }
        // 同层级: 走排序
        const sib = childrenOf(dragState.parentId)
        const from = sib.findIndex(x => x.id === dragState.id)
        const to = sib.findIndex(x => x.id === row.data.id)
        if (from < 0 || to < 0) return
        await moveWikiDir(dragState.id, from < to ? to - 1 : to)
      } else {
        if (currentParent !== targetParentId) {
          const art = tree.value.flatMap(d => d.articles || []).find(x => x.id === dragState.id)
          if (!art) return
          await updateWikiArticle(art.id, { directoryId: targetParentId })
          ElMessage.success(t('wiki.movedIntoFolder'))
          await load()
          return
        }
        const list = tree.value.find(d => d.id === row.data.directoryId)?.articles || []
        const from = list.findIndex(x => x.id === dragState.id)
        const to = list.findIndex(x => x.id === row.data.id)
        if (from < 0 || to < 0) return
        await moveWikiArticle(dragState.id, from < to ? to - 1 : to)
      }
      await load()
      return
    }

    // 情况 2: 同类行之间排序(文件行 ↔ 文件行, 且必须同目录)
    if (dragState.kind !== row.kind) return
    if (row.kind === 'article') {
      if (dragState.parentId !== row.data.directoryId) return
      const list = tree.value.find(d => d.id === row.data.directoryId)?.articles || []
      const from = list.findIndex(x => x.id === dragState.id)
      const to = list.findIndex(x => x.id === row.data.id)
      if (from < 0 || to < 0) return
      await moveWikiArticle(dragState.id, from < to ? to - 1 : to)
    } else {
      if ((dragState.parentId ?? null) !== (row.data.parentId ?? null)) return
      const sib = childrenOf(dragState.parentId)
      const from = sib.findIndex(x => x.id === dragState.id)
      const to = sib.findIndex(x => x.id === row.data.id)
      if (from < 0 || to < 0) return
      await moveWikiDir(dragState.id, from < to ? to - 1 : to)
    }
    await load()
  } catch (err) { /* 拦截器已提示 */ }
}

// ---------- 分享 ----------
const shareDialog = ref(false)
const shareSaving = ref(false)
// requireLogin:false(默认)= 任何人凭链接可看; true = 必须登录
const shareForm = reactive({ id: null, title: '', token: '', requireLogin: false })
const shareUrl = computed(() => shareForm.token ? `${location.origin}/wiki/share/${shareForm.token}` : '')

const openShare = async (a) => {
  try {
    const res = await shareWikiArticle(a.id, false)
    Object.assign(shareForm, {
      id: a.id, title: a.title,
      token: res.data.shareToken,
      requireLogin: !!res.data.requireLogin
    })
    shareDialog.value = true
    await load()
    selected.value = { kind: 'article', id: a.id }
  } catch (e) { /* 已统一弹错 */ }
}
// 切换"是否要求登录"后保存
const saveShareSetting = async () => {
  shareSaving.value = true
  try {
    await shareWikiArticle(shareForm.id, shareForm.requireLogin)
    ElMessage.success(t('wiki.shareSettingSaved'))
    await load()
  } catch (e) { /* 已统一弹错 */ } finally {
    shareSaving.value = false
  }
}
const copyShare = async () => {
  try { await navigator.clipboard.writeText(shareUrl.value); ElMessage.success(t('wiki.shareCopied')) }
  catch (e) { ElMessage.info(t('wiki.copyManually', { url: shareUrl.value })) }
}
const copyArticleShareUrl = async () => {
  try { await navigator.clipboard.writeText(articleShareUrl.value); ElMessage.success(t('wiki.shareCopied')) }
  catch (e) { ElMessage.info(t('wiki.copyManually', { url: articleShareUrl.value })) }
}
const cancelShare = async () => {
  try {
    await unshareWikiArticle(shareForm.id)
    ElMessage.success(t('wiki.shareCanceled'))
    shareDialog.value = false
    await load()
  } catch (e) { /* 已统一弹错 */ }
}

// ---------- 文档导入 ----------
const importing = ref(false)
const fileInput = ref(null)
const IMPORT_EXT = /\.(md|markdown|docx|xlsx)$/i

const openImport = () => {
  if (!workDirId.value) { ElMessage.warning(t('wiki.needDir')); return }
  fileInput.value?.click()
}
const onImportFile = async (e) => {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (!IMPORT_EXT.test(file.name)) { ElMessage.warning(t('wiki.importBadFile')); return }
  if (file.size > 10 * 1024 * 1024) { ElMessage.warning(t('wiki.importTooLarge')); return }
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('directoryId', workDirId.value)
    const res = await importWikiArticle(fd)
    ElMessage.success(t('wiki.importSuccess'))
    await load()
    if (res.data?.id) selected.value = { kind: 'article', id: res.data.id }
  } catch (err) { /* 拦截器已提示 */ } finally {
    importing.value = false
  }
}

onMounted(load)

// ==================== 协作申请 / 审批 ====================
const route = useRoute()
const router = useRouter()

// 三类数据
const collabInbox = ref([])     // 资源所有者的审批中心(待办 + 历史)
const collabMine = ref([])      // 我提交的申请
const sharedToMe = ref([])      // 共享给我的资源

// 当前激活的协作 tab
const collabTab = ref('requests')  // 'requests' | 'mine' | 'shared'
const collabDrawer = ref(false)   // 协作抽屉是否打开
const collabLoading = ref(false)
const pendingCount = computed(() => collabInbox.value.filter(c => c.status === 'PENDING').length)

const loadCollabAll = async () => {
  if (!userStore.isLogin) return
  collabLoading.value = true
  try {
    const [inbox, mine, shared] = await Promise.all([
      getCollabInbox(), getMyCollaborations(), getSharedToMe()
    ])
    collabInbox.value = inbox.data || []
    collabMine.value = mine.data || []
    sharedToMe.value = shared.data || []
  } catch (e) { /* 静默 */ } finally {
    collabLoading.value = false
  }
}

// 通知跳转过来时按 query 切 tab
const initCollabTabFromQuery = () => {
  const tab = route.query.tab
  if (tab === 'requests' || tab === 'mine' || tab === 'shared') collabTab.value = tab
}

watch(() => route.query.tab, initCollabTabFromQuery)

// 审批操作
const handleApprove = async (row) => {
  try { await approveCollaboration(row.id, ''); ElMessage.success(t('wiki.collab.approve') + ' ✓'); await loadCollabAll(); await load() }
  catch (e) { /* 拦截器已提示 */ }
}
const handleReject = async (row) => {
  try { await ElMessageBox.confirm(t('wiki.collab.reject') + '?', t('common.confirm'), { type: 'warning' })
        await rejectCollaboration(row.id, ''); ElMessage.success(t('wiki.collab.reject') + ' ✓')
        await loadCollabAll() }
  catch (e) { /* 取消 */ }
}
const handleCancel = async (row) => {
  try { await ElMessageBox.confirm(t('wiki.collab.cancelApply') + '?', t('common.confirm'), { type: 'warning' })
        await cancelCollaboration(row.id); ElMessage.success(t('wiki.collab.cancelApply') + ' ✓')
        await loadCollabAll() }
  catch (e) { /* 取消 */ }
}

// 申请加入弹窗
const applyDialog = ref(false)
const applyForm = reactive({ resourceType: 'DIR', resourceId: null, resourceName: '', permission: 'READ', reason: '' })
const openApplyDialog = (resourceType, resourceId, resourceName) => {
  Object.assign(applyForm, { resourceType, resourceId, resourceName, permission: 'READ', reason: '' })
  applyDialog.value = true
}
const submitApply = async () => {
  try {
    await applyCollaboration({
      resourceType: applyForm.resourceType, resourceId: applyForm.resourceId,
      permission: applyForm.permission, reason: applyForm.reason
    })
    ElMessage.success(t('wiki.collab.applySubmitted'))
    applyDialog.value = false
    await loadCollabAll()
  } catch (e) { /* 拦截器已提示 */ }
}

// 状态标签辅助
const statusTagType = (s) => ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELED: 'info' }[s] || 'info')
const permLabel = (p) => t('wiki.collab.perm' + p)  // permREAD/permEDIT
const statusLabel = (s) => t('wiki.collab.status' + s)
const fmtCollabTime = (v) => (v || '').replace('T', ' ').slice(0, 16)

onMounted(() => { loadCollabAll(); initCollabTabFromQuery() })

// 右侧章节目录: 文章切换 / 退出编辑 → 重建 IntersectionObserver,实现滚动时高亮当前节
let tocObserver = null
const teardownTocObserver = () => {
  if (tocObserver) { tocObserver.disconnect(); tocObserver = null }
}
const setupTocObserver = async () => {
  teardownTocObserver()
  activeHeadingId.value = ''
  await nextTick()
  if (mode.value !== 'view' || !selectedArticle.value) return
  const targets = Array.from(document.querySelectorAll('.rich-body h1, .rich-body h2, .rich-body h3'))
  if (!targets.length) return
  // 选距视口顶部 0~25% 区间内最靠上的标题作为"当前节"
  tocObserver = new IntersectionObserver((entries) => {
    entries.forEach(e => {
      if (e.isIntersecting) activeHeadingId.value = e.target.id
    })
  }, { rootMargin: '-72px 0px -70% 0px', threshold: 0 })
  targets.forEach(el => tocObserver.observe(el))
}
watch(() => selectedArticle.value?.id, () => { setupTocObserver() })
watch(mode, () => { setupTocObserver() })
onBeforeUnmount(() => {
  teardownTocObserver()
  // 兜底: 卸载时若全屏未关, 清掉事件与 body class
  window.removeEventListener('keydown', onReaderKeydown)
  document.body.classList.remove('reader-fullscreen')
})
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
        <el-button :disabled="!workDirId" :loading="importing" @click="openImport"><el-icon><Upload /></el-icon> {{ importing ? $t('wiki.importing') : $t('wiki.import') }}</el-button>
        <el-button type="primary" :disabled="!workDirId" @click="openArtAdd"><el-icon><DocumentAdd /></el-icon> {{ $t('wiki.createArticle') }}</el-button>
        <el-button :type="pendingCount ? 'danger' : 'default'" plain @click="collabDrawer = !collabDrawer">
          <el-icon><UserFilled /></el-icon>
          {{ $t('wiki.collab.title') }}
          <el-badge v-if="pendingCount" :value="pendingCount" :max="99" class="collab-badge" />
        </el-button>
        <input ref="fileInput" class="hidden-input" type="file" accept=".md,.markdown,.docx,.xlsx" @change="onImportFile" />
      </div>
    </div>

    <!-- ===== 协作面板(右侧抽屉): 待我审批 / 我提交的 / 共享给我 ===== -->
    <transition name="slide-fade">
      <aside v-if="collabDrawer" class="collab-drawer">
        <div class="collab-drawer-head">
          <span><el-icon><UserFilled /></el-icon> {{ $t('wiki.collab.title') }}</span>
          <el-button link @click="collabDrawer = false"><el-icon><Close /></el-icon></el-button>
        </div>
        <el-tabs v-model="collabTab" class="collab-tabs">
          <el-tab-pane :label="$t('wiki.collab.tabRequests') + (pendingCount ? ` (${pendingCount})` : '')" name="requests">
            <div v-loading="collabLoading" class="collab-list">
              <div v-for="r in collabInbox" :key="r.id" class="collab-row">
                <div class="row-head">
                  <el-tag size="small" :type="statusTagType(r.status)" effect="plain">{{ statusLabel(r.status) }}</el-tag>
                  <el-tag size="small" type="info" effect="plain">{{ r.resourceType === 'DIR' ? $t('wiki.dirTitle') : $t('wiki.title') }}</el-tag>
                  <el-tag size="small" :type="r.permission === 'EDIT' ? 'warning' : 'primary'" effect="plain">{{ permLabel(r.permission) }}</el-tag>
                </div>
                <div class="row-meta">
                  <span class="res-name">{{ r.resourceName }}</span>
                  <span class="sep">·</span>
                  <span class="user-name">{{ r.userName }}</span>
                  <span class="sep">·</span>
                  <span class="time">{{ fmtCollabTime(r.createTime) }}</span>
                </div>
                <div v-if="r.reason" class="row-reason">"{{ r.reason }}"</div>
                <div v-if="r.handleComment" class="row-comment">{{ $t('wiki.collab.handleBy', { name: '' }) }} {{ r.handleComment }}</div>
                <div v-if="r.status === 'PENDING'" class="row-actions">
                  <el-button size="small" type="primary" @click="handleApprove(r)">{{ $t('wiki.collab.approve') }}</el-button>
                  <el-button size="small" type="danger" plain @click="handleReject(r)">{{ $t('wiki.collab.reject') }}</el-button>
                </div>
              </div>
              <el-empty v-if="!collabLoading && !collabInbox.length" :description="$t('wiki.collab.empty')" :image-size="60" />
            </div>
          </el-tab-pane>
          <el-tab-pane :label="$t('wiki.collab.tabMine')" name="mine">
            <div v-loading="collabLoading" class="collab-list">
              <div v-for="r in collabMine" :key="r.id" class="collab-row">
                <div class="row-head">
                  <el-tag size="small" :type="statusTagType(r.status)" effect="plain">{{ statusLabel(r.status) }}</el-tag>
                  <el-tag size="small" type="info" effect="plain">{{ r.resourceType === 'DIR' ? $t('wiki.dirTitle') : $t('wiki.title') }}</el-tag>
                  <el-tag size="small" :type="r.permission === 'EDIT' ? 'warning' : 'primary'" effect="plain">{{ permLabel(r.permission) }}</el-tag>
                </div>
                <div class="row-meta">
                  <span class="res-name">{{ r.resourceName }}</span>
                  <span class="sep">·</span>
                  <span class="time">{{ fmtCollabTime(r.createTime) }}</span>
                </div>
                <div v-if="r.reason" class="row-reason">"{{ r.reason }}"</div>
                <div v-if="r.handleComment" class="row-comment">审批意见: {{ r.handleComment }}</div>
                <div v-if="r.status === 'PENDING'" class="row-actions">
                  <el-button size="small" type="danger" plain @click="handleCancel(r)">{{ $t('wiki.collab.cancelApply') }}</el-button>
                </div>
              </div>
              <el-empty v-if="!collabLoading && !collabMine.length" :description="$t('wiki.collab.empty')" :image-size="60" />
            </div>
          </el-tab-pane>
          <el-tab-pane :label="$t('wiki.collab.tabShared')" name="shared">
            <div v-loading="collabLoading" class="collab-list">
              <div v-for="r in sharedToMe" :key="r.id" class="collab-row collab-shared-row">
                <div class="row-head">
                  <el-tag size="small" type="info" effect="plain">{{ r.resourceType === 'DIR' ? $t('wiki.dirTitle') : $t('wiki.title') }}</el-tag>
                  <el-tag size="small" :type="r.permission === 'EDIT' ? 'warning' : 'primary'" effect="plain">
                    {{ r.permission === 'EDIT' ? $t('wiki.collab.sharedTagEdit') : $t('wiki.collab.sharedTagRead') }}
                  </el-tag>
                </div>
                <div class="row-meta">
                  <span class="res-name">{{ r.resourceName }}</span>
                  <span class="sep">·</span>
                  <span class="time">{{ fmtCollabTime(r.createTime) }}</span>
                </div>
                <div class="row-hint">{{ $t('wiki.collab.sharedBadge') }} · 权限 {{ r.permission === 'EDIT' ? $t('wiki.collab.sharedTagEdit') : $t('wiki.collab.sharedTagRead') }} · 已加入协作者列表</div>
              </div>
              <el-empty v-if="!collabLoading && !sharedToMe.length" :description="$t('wiki.collab.empty')" :image-size="60" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </transition>

    <div class="wiki-wrap" v-loading="loading">
      <!-- 左: 树形目录(文件夹 + 文件) -->
      <aside class="tree">
        <div class="tree-head">
          <span class="tree-title">{{ $t('wiki.dirTitle') }}</span>
          <small>{{ totalDirs }} {{ $t('wiki.folderUnit') }} · {{ totalArticles }} {{ $t('wiki.fileUnit') }}</small>
        </div>

        <div
          v-for="row in treeRows" :key="rowKey(row)"
          class="trow" :class="[row.kind, { selected: selected?.kind === row.kind && selected?.id === row.data.id, 'drag-over': dragOverKey === rowKey(row) }]"
          :style="{ paddingLeft: 6 + row.depth * 16 + 'px' }"
          draggable="true"
          @dragstart="onDragStart(row, $event)"
          @dragover="onDragOver(row, $event)"
          @dragleave="onDragLeave"
          @drop="onDrop(row, $event)"
          @click="row.kind === 'dir' ? selectDir(row.data) : selectArticle(row.data)"
        >
          <!-- 折叠箭头(仅文件夹) -->
          <span v-if="row.kind === 'dir'" class="caret" @click.stop="toggleExpand(row.data.id)">
            {{ isExpanded(row.data.id) ? '▾' : '▸' }}
          </span>
          <span v-else class="caret placeholder"></span>

          <el-icon class="ico" :class="{ folder: row.kind === 'dir' }">
            <Folder v-if="row.kind === 'dir'" />
            <Document v-else />
          </el-icon>

          <span class="fname">{{ row.data.name || row.data.title }}</span>
          <el-tag v-if="row.kind === 'article' && row.data.shareToken" size="small" type="success" effect="plain" class="tag">{{ $t('wiki.shared') }}</el-tag>

          <!-- 悬浮显示操作(精简: 文件行只留分享/删除, 编辑在右侧文档区统一入口; 目录行保留子目录/编辑/删除) -->
          <span class="hover-ops" @click.stop>
            <template v-if="row.kind === 'dir'">
              <el-button link size="small" @click="openDirAdd(row.data)">{{ $t('wiki.subDir') }}</el-button>
              <el-button link size="small" @click="openDirEdit(row.data)">{{ $t('common.edit') }}</el-button>
              <el-button link size="small" type="danger" @click="removeDir(row.data)">{{ $t('common.delete') }}</el-button>
            </template>
            <template v-else>
              <el-button link size="small" type="success" @click="openShare(row.data)">{{ row.data.shareToken ? $t('wiki.shareLink') : $t('wiki.share') }}</el-button>
              <el-button link size="small" type="danger" @click="removeArt(row.data)">{{ $t('common.delete') }}</el-button>
            </template>
          </span>
        </div>

        <el-empty v-if="!tree.length && !loading" :description="$t('wiki.emptyDirs')" :image-size="50" />
      </aside>

      <!-- 右: 文件内容 / 内联编辑 -->
      <main class="viewer">
        <!-- ===== 内联编辑器(新建 / 编辑, 不弹窗) ===== -->
        <template v-if="mode !== 'view'">
          <div class="ie-head">
            <el-input
              v-model="artForm.title"
              class="ie-title"
              maxlength="200"
              :placeholder="$t('wiki.articleTitlePlaceholder')"
            />
            <div class="ie-type">
              <template v-if="mode === 'create'">
                <span class="ie-type-label">{{ $t('wiki.fileType') }}</span>
                <el-radio-group v-model="artForm.contentType" size="small">
                  <el-radio-button value="markdown">Markdown</el-radio-button>
                  <el-radio-button value="richtext">{{ $t('contentEditor.richtext') }}</el-radio-button>
                </el-radio-group>
                <small class="ie-tip">{{ $t('wiki.pickTypeTip') }}</small>
              </template>
              <template v-else>
                <span class="ie-type-label">{{ $t('wiki.fileType') }}</span>
                <el-tag type="info" effect="plain">{{ artForm.contentType === 'richtext' ? $t('wiki.richType') : $t('wiki.mdType') }}</el-tag>
                <small class="ie-tip">{{ $t('contentEditor.typeLocked') }}</small>
              </template>
              <span class="ie-dir">
                <span class="ie-type-label">{{ $t('wiki.artDir') }}</span>
                <el-select v-model="artForm.directoryId" size="small" style="width:180px">
                  <el-option
                    v-for="row in treeRows.filter(r => r.kind === 'dir')" :key="row.data.id"
                    :label="row.depth ? '　'.repeat(row.depth) + '└ ' + row.data.name : row.data.name"
                    :value="row.data.id"
                  />
                </el-select>
              </span>
            </div>
          </div>

          <div class="ie-body">
            <ContentEditor
              v-model="artForm.content"
              v-model:content-type="artForm.contentType"
              :lock-type="mode === 'edit'"
              :height="inlineEditorHeight"
            />
          </div>

          <div class="ie-actions">
            <el-button @click="cancelArt">{{ $t('common.cancel') }}</el-button>
            <el-button type="primary" :loading="artSaving" @click="saveArt">
              {{ mode === 'create' ? $t('wiki.createArticle') : $t('common.save') }}
            </el-button>
          </div>
        </template>

        <template v-else-if="selectedArticle">
          <div class="v-head">
            <div class="v-title">
              <el-icon><Document /></el-icon>
              <span>{{ selectedArticle.title }}</span>
            </div>
            <div class="v-ops">
              <el-button size="small" @click="openArtEdit(selectedArticle)"><el-icon><Edit /></el-icon> {{ $t('common.edit') }}</el-button>
              <el-button size="small" type="success" plain @click="openShare(selectedArticle)"><el-icon><Share /></el-icon> {{ $t('wiki.share') }}</el-button>
              <el-button size="small" type="danger" plain @click="removeArt(selectedArticle)"><el-icon><Delete /></el-icon> {{ $t('common.delete') }}</el-button>
              <el-button size="small" plain @click="enterReader" :title="$t('wiki.fullscreen')"><el-icon><FullScreen /></el-icon> {{ $t('wiki.fullscreen') }}</el-button>
            </div>
          </div>
          <div class="v-meta">
            <span>{{ selectedArticle.contentType === 'richtext' ? $t('wiki.richType') : $t('wiki.mdType') }}</span>
            <span>· {{ $t('wiki.charCount') }} {{ (selectedArticle.content || '').length }}</span>
            <span>· {{ $t('wiki.createdAt') }} {{ fmtTime(selectedArticle.createTime) }}</span>
            <span>· {{ $t('wiki.updatedAtLabel') }} {{ fmtTime(selectedArticle.updateTime) }}</span>
            <span v-if="selectedArticle.shareToken">
              · {{ $t('wiki.sharedYes') }} ({{ selectedArticle.viewCount || 0 }} {{ $t('wiki.viewsUnit') }})
              <el-button link size="small" type="primary" @click="copyArticleShareUrl">{{ $t('wiki.copyLink') }}</el-button>
            </span>
            <span v-else>· {{ $t('wiki.notShared') }}</span>
          </div>
          <div class="v-body rich-body" v-html="contentHtml"></div>
        </template>

        <template v-else-if="selectedDir">
          <div class="v-head">
            <div class="v-title"><el-icon><Folder /></el-icon><span>{{ selectedDir.name }}</span></div>
            <div class="v-ops">
              <el-button size="small" @click="openDirAdd(selectedDir)">{{ $t('wiki.subDir') }}</el-button>
              <el-button size="small" @click="openDirEdit(selectedDir)">{{ $t('common.edit') }}</el-button>
              <el-button size="small" type="danger" plain @click="removeDir(selectedDir)">{{ $t('common.delete') }}</el-button>
            </div>
          </div>
          <div class="v-meta">
            <span>{{ $t('wiki.folder') }}</span>
            <span>· {{ $t('wiki.subDirCount') }} {{ descendantCount(selectedDir) }}</span>
            <span>· {{ $t('wiki.fileCount') }} {{ subtreeArticles(selectedDir) }}</span>
            <span>· {{ $t('wiki.createdAt') }} {{ fmtTime(selectedDir.createTime) }}</span>
          </div>
          <el-empty :description="$t('wiki.folderHint')" :image-size="90" />
        </template>

        <template v-else>
          <el-empty :description="$t('wiki.selectHint')" :image-size="90">
            <el-button type="primary" @click="openDirAdd()">{{ $t('wiki.createDir') }}</el-button>
          </el-empty>
        </template>
      </main>

      <!-- 右: 本文章节(自动从 h1-h3 抽取,点击跳转,滚动时高亮当前节;头部可折叠) -->
      <aside class="toc" :class="{ collapsed: tocCollapsed }" v-if="mode === 'view' && selectedArticle">
        <div class="toc-head" @click="toggleToc" :title="tocCollapsed ? $t('wiki.tocExpand') : $t('wiki.tocCollapse')">
          <span class="caret">{{ tocCollapsed ? '▸' : '▾' }}</span>
          <el-icon><List /></el-icon>
          <span>{{ $t('wiki.tocTitle') }}</span>
          <small v-if="!tocCollapsed && tocItems.length">({{ tocItems.length }})</small>
        </div>
        <template v-if="!tocCollapsed">
          <ul v-if="tocItems.length" class="toc-list">
            <li
              v-for="it in tocItems" :key="it.id"
              :class="['lv-' + it.level, { active: activeHeadingId === it.id }]"
              :title="it.text"
              @click="scrollToHeading(it.id)"
            >
              <span class="toc-marker"></span>
              <span class="toc-text">{{ it.text }}</span>
            </li>
          </ul>
          <div v-else class="toc-empty">{{ $t('wiki.tocEmpty') }}</div>
        </template>
      </aside>
    </div>

    <!-- ===== 全屏阅读(只读,可滚动;按 Esc 或点 X 退出) ===== -->
    <teleport to="body" v-if="readerFullscreen && selectedArticle">
      <div class="reader-mask" @click.self="exitReader">
        <article class="reader-panel">
          <header class="reader-bar">
            <div class="reader-title">
              <el-icon><Document /></el-icon>
              <span>{{ selectedArticle.title }}</span>
            </div>
            <div class="reader-ops">
              <el-button size="small" plain @click="openArtEdit(selectedArticle)"><el-icon><Edit /></el-icon> {{ $t('common.edit') }}</el-button>
              <el-button size="small" type="success" plain @click="openShare(selectedArticle)"><el-icon><Share /></el-icon> {{ $t('wiki.share') }}</el-button>
              <el-button size="small" type="danger" plain @click="removeArt(selectedArticle)"><el-icon><Delete /></el-icon> {{ $t('common.delete') }}</el-button>
              <el-button size="small" @click="exitReader" :title="$t('wiki.exitFullscreen') + ' (Esc)'"><el-icon><Close /></el-icon></el-button>
            </div>
          </header>
          <div class="reader-meta">
            <span>{{ selectedArticle.contentType === 'richtext' ? $t('wiki.richType') : $t('wiki.mdType') }}</span>
            <span>· {{ $t('wiki.charCount') }} {{ (selectedArticle.content || '').length }}</span>
            <span>· {{ $t('wiki.createdAt') }} {{ fmtTime(selectedArticle.createTime) }}</span>
            <span>· {{ $t('wiki.updatedAtLabel') }} {{ fmtTime(selectedArticle.updateTime) }}</span>
          </div>
          <div class="reader-body rich-body" v-html="contentHtml"></div>
          <footer class="reader-foot" v-if="tocItems.length">
            <el-divider><span class="reader-foot-label">{{ $t('wiki.tocTitle') }} ({{ tocItems.length }})</span></el-divider>
            <ul class="reader-toc">
              <li
                v-for="it in tocItems" :key="it.id"
                :class="['lv-' + it.level, { active: activeHeadingId === it.id }]"
                @click="scrollToHeading(it.id)"
              >
                {{ it.text }}
              </li>
            </ul>
          </footer>
        </article>
      </div>
    </teleport>

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

    <!-- 文章编辑已改为内容区内联编辑, 不再使用弹窗 -->

    <!-- 新建: 已在内容区内联选择格式并编辑, 不再弹窗 -->

    <!-- 分享对话框 -->
    <el-dialog v-model="shareDialog" :title="$t('wiki.shareTitle')" width="560px" destroy-on-close>
      <p class="share-tip">{{ $t('wiki.shareTip', { title: shareForm.title }) }}</p>
      <el-input :model-value="shareUrl" readonly>
        <template #append>
          <el-button @click="copyShare">{{ $t('wiki.copy') }}</el-button>
        </template>
      </el-input>

      <div class="share-visit">
        <div class="sv-label">
          <span>{{ $t('wiki.shareVisit') }}</span>
          <small>{{ shareForm.requireLogin ? $t('wiki.shareVisitLoginTip') : $t('wiki.shareVisitGuestTip') }}</small>
        </div>
        <el-switch
          v-model="shareForm.requireLogin"
          :active-text="$t('wiki.needLogin')"
          :inactive-text="$t('wiki.anyoneWithLink')"
          inline-prompt
          @change="saveShareSetting"
        />
      </div>

      <template #footer>
        <el-button type="danger" plain @click="cancelShare">{{ $t('wiki.cancelShare') }}</el-button>
        <el-button type="primary" :loading="shareSaving" @click="shareDialog = false">{{ $t('wiki.done') }}</el-button>
      </template>
    </el-dialog>

    <!-- ===== 申请加入协作 ===== -->
    <el-dialog v-model="applyDialog" :title="$t('wiki.collab.applyTitle')" width="460px" destroy-on-close>
      <el-form label-width="84px">
        <el-form-item :label="$t('wiki.collab.applyTarget')">
          <el-tag type="info" effect="plain">{{ applyForm.resourceType === 'DIR' ? $t('wiki.dirTitle') : $t('wiki.title') }} · {{ applyForm.resourceName }}</el-tag>
        </el-form-item>
        <el-form-item :label="$t('wiki.collab.applyPerm')">
          <el-radio-group v-model="applyForm.permission">
            <el-radio-button value="READ">{{ $t('wiki.collab.applyPermRead') }}</el-radio-button>
            <el-radio-button value="EDIT">{{ $t('wiki.collab.applyPermEdit') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="$t('wiki.collab.applyReason')">
          <el-input v-model="applyForm.reason" type="textarea" :rows="3" :placeholder="$t('wiki.collab.applyReasonPh')" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitApply">{{ $t('wiki.collab.applySubmit') }}</el-button>
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

.wiki-wrap { display: grid; grid-template-columns: 340px minmax(0, 1fr) 220px; gap: 16px; align-items: start; }

/* ===== 左侧树 ===== */
.tree { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 12px 10px; }
.tree-head { display: flex; align-items: baseline; gap: 8px; padding: 2px 6px 10px; border-bottom: 1px solid #f1f3f8; }
.tree-title { font-weight: 600; color: var(--c-text-1); }
.tree-head small { color: var(--c-text-3); font-size: var(--fs-xs); }

.trow {
  display: flex; align-items: center; gap: 4px;
  padding: 7px 6px; border-radius: var(--r-s);
  border: 1px solid transparent; cursor: pointer; font-size: var(--fs-sm);
}
.trow:hover { background: #f5f7fa; }
.trow.selected { background: var(--c-primary-bg); border-color: var(--c-primary); }
.trow.drag-over { border-color: var(--c-primary); background: var(--c-primary-bg); }
.trow[draggable="true"]:active { cursor: grabbing; }
.trow .caret { width: 14px; flex: none; text-align: center; color: var(--c-text-3); font-size: 10px; }
.trow .caret.placeholder { visibility: hidden; }
.trow .ico { flex: none; color: var(--c-text-3); }
.trow .ico.folder { color: #e6a23c; }
.trow .fname { color: var(--c-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trow .tag { flex: none; }
.trow.article .fname { color: var(--c-text-2); }
.trow.selected .fname { color: var(--c-primary); font-weight: 600; }

/* 悬浮操作(文件名后显示) */
.hover-ops { display: none; margin-left: auto; gap: 2px; flex: none; }
.trow:hover .hover-ops { display: inline-flex; }

/* ===== 右侧内容 ===== */
.viewer { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 18px 22px; min-height: 460px; min-width: 0; }
.v-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.v-title { display: flex; align-items: center; gap: 8px; font-size: 20px; font-weight: 600; color: var(--c-text-1); min-width: 0; word-break: break-all; }
.v-title .el-icon { color: var(--c-primary); flex: none; }
.v-ops { display: flex; gap: 8px; flex: none; }
.v-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; color: var(--c-text-3); font-size: var(--fs-xs); margin: 10px 0 14px; padding-bottom: 12px; border-bottom: 1px solid #f1f3f8; }
.v-body { font-size: var(--fs-base); line-height: 1.85; color: var(--c-text-1); }

.rich-body :deep(p) { margin: 8px 0; }
.rich-body :deep(h1), .rich-body :deep(h2), .rich-body :deep(h3),
.rich-body :deep(h4), .rich-body :deep(h5), .rich-body :deep(h6) { font-size: var(--fs-lg); margin: 20px 0 10px; line-height: 1.4; }
.rich-body :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow-x: auto; font-size: 13px; }
.rich-body :deep(code) { background: rgba(100,116,139,.12); padding: 1px 5px; border-radius: 4px; font-size: .9em; }
.rich-body :deep(pre code) { background: transparent; padding: 0; }
.rich-body :deep(img) { max-width: 100%; border-radius: 4px; }
.rich-body :deep(blockquote) { border-left: 3px solid var(--c-border-strong); margin: 10px 0; padding: 2px 14px; color: var(--c-text-2); }
.rich-body :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; display: block; overflow-x: auto; }
.rich-body :deep(th), .rich-body :deep(td) { border: 1px solid var(--c-border); padding: 8px 12px; text-align: left; }
.rich-body :deep(ul), .rich-body :deep(ol) { padding-left: 24px; margin: 8px 0; }
.rich-body :deep(a) { color: var(--c-primary); text-decoration: underline; }

.hidden-input { display: none; }
.share-tip { color: var(--c-text-2); font-size: var(--fs-sm); margin: 0 0 12px; }

/* 内联编辑器(新建/编辑) */
.ie-head { display: flex; flex-direction: column; gap: 10px; padding-bottom: 12px; border-bottom: 1px solid #f1f3f8; }
.ie-title :deep(.el-input__wrapper) { box-shadow: none; padding: 4px 0; }
.ie-title :deep(.el-input__inner) { font-size: 20px; font-weight: 600; color: var(--c-text-1); }
.ie-type { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.ie-type-label { color: var(--c-text-3); font-size: var(--fs-xs); }
.ie-tip { color: var(--c-text-3); font-size: var(--fs-xs); }
.ie-dir { display: inline-flex; align-items: center; gap: 6px; margin-left: auto; }
.ie-body { padding: 14px 0; }
.ie-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; border-top: 1px solid #f1f3f8; }

/* 分享: 访问范围 */
.share-visit { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--c-border); }
.sv-label { display: flex; flex-direction: column; gap: 2px; }
.sv-label span { color: var(--c-text-1); font-size: var(--fs-sm); }
.sv-label small { color: var(--c-text-3); font-size: var(--fs-xs); }

/* ===== 右侧: 章节目录(TOC, 可折叠) ===== */
.toc {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 14px 12px;
  position: sticky; top: 12px; align-self: start;
  max-height: calc(100vh - 24px); overflow-y: auto;
  scrollbar-width: thin;
  transition: max-height .2s;
}
.toc.collapsed { padding: 12px 12px; max-height: none; overflow: visible; }
.toc::-webkit-scrollbar { width: 6px; }
.toc::-webkit-scrollbar-thumb { background: #e2e8f0; border-radius: 3px; }
.toc-head {
  display: flex; align-items: center; gap: 6px; cursor: pointer; user-select: none;
  font-weight: 600; color: var(--c-text-1); font-size: var(--fs-sm);
  padding-bottom: 8px; border-bottom: 1px solid #f1f3f8; margin-bottom: 6px;
}
.toc.collapsed .toc-head { border-bottom-color: transparent; padding-bottom: 0; margin-bottom: 0; }
.toc-head .caret { width: 12px; flex: none; color: var(--c-text-3); font-size: 10px; transition: transform .15s; }
.toc-head .el-icon { color: var(--c-primary); flex: none; }
.toc-head small { color: var(--c-text-3); font-weight: 400; margin-left: auto; }
.toc-list { list-style: none; margin: 0; padding: 0; }
.toc-list li {
  display: flex; align-items: flex-start; gap: 6px;
  padding: 5px 6px 5px 8px;
  cursor: pointer; color: var(--c-text-2); font-size: var(--fs-sm);
  border-left: 2px solid transparent; border-radius: 0 var(--r-s) var(--r-s) 0;
  line-height: 1.45; transition: background .12s, color .12s, border-color .12s;
}
.toc-list li:hover { color: var(--c-primary); background: var(--c-primary-bg); }
.toc-list li.active { color: var(--c-primary); border-left-color: var(--c-primary); background: var(--c-primary-bg); font-weight: 600; }
.toc-list li.lv-1 { color: var(--c-text-1); font-weight: 600; }
.toc-list li.lv-2 { padding-left: 18px; }
.toc-list li.lv-3 { padding-left: 30px; font-size: var(--fs-xs); color: var(--c-text-3); }
.toc-list li.lv-3.active { color: var(--c-primary); }
.toc-marker { flex: none; width: 4px; align-self: stretch; border-radius: 2px; background: currentColor; opacity: .15; }
.toc-list li.active .toc-marker { opacity: .9; }
.toc-text { overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.toc-empty { color: var(--c-text-3); font-size: var(--fs-xs); padding: 10px 4px; line-height: 1.5; }

/* 标题锚点跳转补偿(避免贴顶) */
:deep(.rich-body h1), :deep(.rich-body h2), :deep(.rich-body h3) { scroll-margin-top: 16px; }

/* ===== 协作按钮 + 抽屉 ===== */
.collab-badge { margin-left: 4px; }
.collab-drawer {
  position: fixed; right: 0; top: 56px; bottom: 0; z-index: 1500;
  width: 420px; max-width: 96vw; background: #fff;
  border-left: 1px solid var(--c-border);
  box-shadow: -8px 0 24px rgba(0,0,0,.08);
  display: flex; flex-direction: column;
}
.collab-drawer-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px; border-bottom: 1px solid var(--c-border);
  font-weight: 600; color: var(--c-text-1); font-size: var(--fs-base);
}
.collab-drawer-head .el-icon { color: var(--c-primary); margin-right: 6px; vertical-align: -2px; }
.collab-tabs { flex: 1 1 auto; display: flex; flex-direction: column; min-height: 0; }
.collab-tabs :deep(.el-tabs__header) { padding: 0 12px; margin-bottom: 4px; }
.collab-tabs :deep(.el-tabs__content) { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 6px 12px 16px; }
.collab-list { display: flex; flex-direction: column; gap: 8px; padding-top: 6px; }
.collab-row {
  background: #f8fafc; border: 1px solid #edf2f7; border-radius: var(--r-m);
  padding: 10px 12px;
}
.collab-row .row-head { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; }
.collab-row .row-meta { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; font-size: var(--fs-xs); color: var(--c-text-2); }
.collab-row .res-name { font-weight: 600; color: var(--c-text-1); }
.collab-row .user-name { color: var(--c-primary); }
.collab-row .sep { color: var(--c-text-3); }
.collab-row .row-reason {
  margin-top: 6px; font-size: var(--fs-sm); color: var(--c-text-2);
  background: #fff; border-left: 2px solid var(--c-border); padding: 4px 8px; border-radius: 0 4px 4px 0;
}
.collab-row .row-comment {
  margin-top: 4px; font-size: var(--fs-xs); color: var(--c-text-3);
}
.collab-row .row-actions { display: flex; gap: 6px; margin-top: 8px; justify-content: flex-end; }
.collab-shared-row .row-hint { margin-top: 4px; font-size: var(--fs-xs); color: var(--c-text-3); line-height: 1.5; }

.slide-fade-enter-active, .slide-fade-leave-active { transition: transform .2s ease, opacity .2s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateX(20px); opacity: 0; }

/* ===== 全屏阅读(浮层) ===== */
.reader-mask {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(15, 23, 42, .55);
  display: flex; justify-content: center; align-items: stretch;
  padding: 32px;
  animation: readerFadeIn .18s ease;
}
@keyframes readerFadeIn { from { opacity: 0 } to { opacity: 1 } }
.reader-panel {
  background: #fff; border-radius: 12px;
  width: min(960px, 100%);
  max-height: 100%;
  display: flex; flex-direction: column;
  box-shadow: 0 24px 48px rgba(0,0,0,.2);
  overflow: hidden;
}
.reader-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 20px; border-bottom: 1px solid var(--c-border);
  background: linear-gradient(180deg, #f8fafc 0%, #fff 100%);
}
.reader-title { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 600; color: var(--c-text-1); min-width: 0; }
.reader-title .el-icon { color: var(--c-primary); flex: none; }
.reader-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.reader-ops { display: flex; gap: 6px; flex: none; }
.reader-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; color: var(--c-text-3); font-size: var(--fs-xs); padding: 10px 20px; border-bottom: 1px solid #f1f3f8; }
.reader-body {
  flex: 1 1 auto; min-height: 0; overflow-y: auto;
  padding: 24px 40px; font-size: 17px; line-height: 1.85; color: var(--c-text-1);
  scrollbar-width: thin;
}
.reader-body::-webkit-scrollbar { width: 8px; }
.reader-body::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 4px; }
.reader-body :deep(p) { margin: 10px 0; }
.reader-body :deep(h1) { font-size: 26px; font-weight: 700; margin: 20px 0 12px; line-height: 1.3; }
.reader-body :deep(h2) { font-size: 22px; font-weight: 700; margin: 18px 0 10px; line-height: 1.35; }
.reader-body :deep(h3) { font-size: 18px; font-weight: 600; margin: 16px 0 8px; }
.reader-body :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow-x: auto; font-size: 14px; }
.reader-body :deep(code) { background: rgba(100,116,139,.12); padding: 1px 5px; border-radius: 4px; font-size: .9em; }
.reader-body :deep(pre code) { background: transparent; padding: 0; }
.reader-body :deep(img) { max-width: 100%; border-radius: 4px; }
.reader-body :deep(blockquote) { border-left: 3px solid var(--c-border-strong); margin: 12px 0; padding: 2px 14px; color: var(--c-text-2); }
.reader-body :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; display: block; overflow-x: auto; }
.reader-body :deep(th), .reader-body :deep(td) { border: 1px solid var(--c-border); padding: 8px 12px; text-align: left; }
.reader-body :deep(ul), .reader-body :deep(ol) { padding-left: 24px; margin: 10px 0; }
.reader-body :deep(a) { color: var(--c-primary); text-decoration: underline; }
.reader-foot { padding: 12px 20px 20px; border-top: 1px solid #f1f3f8; max-height: 30%; overflow-y: auto; }
.reader-foot-label { color: var(--c-text-3); font-size: var(--fs-xs); }
.reader-toc { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.reader-toc li { padding: 4px 8px; font-size: var(--fs-sm); color: var(--c-text-2); border-radius: 4px; cursor: pointer; }
.reader-toc li:hover { color: var(--c-primary); background: var(--c-primary-bg); }
.reader-toc li.active { color: var(--c-primary); font-weight: 600; background: var(--c-primary-bg); }
.reader-toc li.lv-1 { color: var(--c-text-1); font-weight: 600; }
.reader-toc li.lv-2 { padding-left: 18px; }
.reader-toc li.lv-3 { padding-left: 30px; color: var(--c-text-3); }

/* 全屏时锁定 body 滚动 */
:global(body.reader-fullscreen) { overflow: hidden; }

@media (max-width: 700px) {
  .reader-mask { padding: 0; }
  .reader-panel { border-radius: 0; width: 100%; max-height: 100%; }
  .reader-body { padding: 18px 20px; font-size: 16px; }
  .reader-bar { flex-direction: column; align-items: stretch; gap: 8px; }
  .reader-ops { justify-content: flex-end; flex-wrap: wrap; }
}

/* ============ 移动端: 树与内容上下堆叠 ============ */
@media (max-width: 1100px) {
  .wiki-wrap { grid-template-columns: 340px minmax(0, 1fr); }
  .toc { display: none; }
}
@media (max-width: 900px) {
  .page { padding: 16px; }
  .wiki-wrap { grid-template-columns: 1fr; gap: 12px; }
  .toc { display: none; }
  .hero { flex-direction: column; align-items: flex-start; gap: 10px; }
  .hero-btns { flex-wrap: wrap; }
  .viewer { min-height: 260px; }
}
</style>
