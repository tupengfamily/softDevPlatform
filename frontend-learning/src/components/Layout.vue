<script setup>
// 整体布局: 可折叠侧栏 + 顶部栏 + 内容区
// 菜单按角色分级: 学习板块对所有人开放, 系统管理仅管理员可见
// 菜单分组支持展开/收起(记忆到 localStorage);新版本公告弹窗(按已读版本去重)
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../store/user'
import { useSubjectStore } from '../store/subject'
import { useNotificationStore } from '../store/notification'
import { useCollabInboxStore } from '../store/collabInbox'
import { useDictStore } from '../store/dict'
import { useLocaleStore } from '../store/locale'
import { lessonCrumbTitle } from '../store/crumb'
import { getLatestRelease } from '../api/release'
import { sanitizeHtml } from '../utils/markdown'
import { recentNotifications, markRead, markAllRead } from '../api/notifications'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const subjectStore = useSubjectStore()
const notifStore = useNotificationStore()
const dictStore = useDictStore()
const localeStore = useLocaleStore()
const collabInboxStore = useCollabInboxStore()
const { t } = useI18n()

const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

// ============== 游客访问 ==============
// 未登录用户可浏览学习中心/课时详情;个人操作由页面内引导登录
const isGuest = computed(() => !userStore.isLogin)
/** 只读账号: 可浏览,禁止编辑/发言等操作(由后端拦截器强制) */
const isReadOnly = computed(() => userStore.userInfo?.status === 2)
const guestBannerVisible = ref(sessionStorage.getItem('guestBannerClosed') !== '1')
const dismissGuestBanner = () => {
  guestBannerVisible.value = false
  sessionStorage.setItem('guestBannerClosed', '1')
}
const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })
const goRegister = () => router.push({ path: '/register', query: { redirect: route.fullPath } })

// 头像展示:URL 显示图片;emoji/字符显示文本;未设置显示名字首字母
const avatarSrc = computed(() => {
  const a = userStore.avatar || ''
  return (/^https?:\/\//.test(a) || a.startsWith('data:') || a.startsWith('/uploads/')) ? a : undefined
})
const avatarChar = computed(() => {
  const a = userStore.avatar || ''
  return a && !avatarSrc.value ? a : (userStore.displayName || '?').charAt(0).toUpperCase()
})

// ============== 学习语言选择(头像旁) ==============
// 记忆用户偏好:下次进入默认展示该语言的学习内容(store 内双重记忆:本地+后端)
const langChanging = ref(false)
const onLangChange = async (sid) => {
  langChanging.value = true
  await subjectStore.setPreferred(sid)
  langChanging.value = false
}

// 侧栏折叠状态(记忆到 localStorage)
const collapsed = ref(localStorage.getItem('menuCollapsed') === '1')
watch(collapsed, v => localStorage.setItem('menuCollapsed', v ? '1' : '0'))

// ============== 移动端适配 ==============
// <=768px 时侧栏改为 off-canvas 抽屉:汉堡按钮唤出,遮罩点击/选中菜单即收起
const isMobile = ref(false)
const drawerOpen = ref(false)
const asideWidth = computed(() => (isMobile.value ? '228px' : (collapsed.value ? '56px' : '228px')))
const menuCollapsed = computed(() => collapsed.value && !isMobile.value)
let mq = null
const onMq = (e) => {
  isMobile.value = e.matches
  if (!e.matches) drawerOpen.value = false   // 回到桌面时复位抽屉
}
onMounted(() => {
  mq = window.matchMedia('(max-width: 768px)')
  isMobile.value = mq.matches
  mq.addEventListener('change', onMq)
})
onBeforeUnmount(() => { mq?.removeEventListener('change', onMq) })
// 路由切换(点菜单/跳转)后收起抽屉
watch(() => route.path, () => { if (isMobile.value) drawerOpen.value = false })

// 菜单分组: 学习 -> 交流 -> 个人中心 -> 系统管理(仅管理员) -> 其他
// 标题存 i18n key(渲染时 $t 翻译);分组展开状态用 key 做标识,切换语言不丢记忆
const menuGroups = computed(() => {
  const groups = [
    {
      titleKey: 'layout.group.learning',
      items: [
        { path: '/learning', titleKey: 'nav.learningCenter', icon: 'Reading' },
        { path: '/board',    titleKey: 'nav.learningBoard', icon: 'TrendCharts' }
      ]
    },
    {
      titleKey: 'layout.group.social',
      items: [
        { path: '/forum', titleKey: 'nav.forum', icon: 'ChatLineRound' }
      ]
    },
    {
      titleKey: 'layout.group.personal',
      items: [
        { path: '/todos',         titleKey: 'nav.todos', icon: 'Memo' },
        { path: '/notifications', titleKey: 'nav.notifications', icon: 'Bell' },
        { path: '/favorites',     titleKey: 'nav.favorites', icon: 'Star' },
        { path: '/wiki',          titleKey: 'nav.wiki', icon: 'Notebook' },
        { path: '/my-docs',       titleKey: 'nav.myDocs', icon: 'Files' },
        { path: '/data-transfer', titleKey: 'nav.dataTransfer', icon: 'Download' }
      ]
    }
  ]
  if (isAdmin.value) {
    groups.push({
      titleKey: 'layout.group.system',
      items: [
        { path: '/dashboard', titleKey: 'nav.dashboard',   icon: 'DataAnalysis' },
        { path: '/users',     titleKey: 'nav.users', icon: 'User' },
        { path: '/roles',     titleKey: 'nav.roles', icon: 'UserFilled' },
        { path: '/dicts',     titleKey: 'nav.dicts', icon: 'Collection' },
        { path: '/logs',      titleKey: 'nav.logs', icon: 'Document' },
        { path: '/learning-config', titleKey: 'nav.learningConfig', icon: 'Setting' }
      ]
    })
    // 运营管理独立顶级分组(仅管理员可见, 含 6 个子模块)
    groups.push({
      titleKey: 'layout.group.operation',
      items: [
        { path: '/operation/overview',    titleKey: 'opNav.overview',    icon: 'Odometer' },
        { path: '/operation/pages',       titleKey: 'opNav.pages',       icon: 'Document' },
        { path: '/operation/user-journey', titleKey: 'opNav.userJourney', icon: 'Connection' },
        { path: '/operation/errors',      titleKey: 'opNav.errors',      icon: 'Warning' },
        { path: '/operation/performance', titleKey: 'opNav.performance', icon: 'Histogram' },
        { path: '/operation/clients',     titleKey: 'opNav.clients',     icon: 'DataLine' }
      ]
    })
  }
  groups.push({
    titleKey: 'layout.group.other',
    items: [
      { path: '/releases', titleKey: 'nav.releases', icon: 'Tickets' },
      { path: '/feedback', titleKey: 'nav.feedback', icon: 'ChatDotSquare' },
      { path: '/about',    titleKey: 'nav.about',     icon: 'InfoFilled' }
    ]
  })
  return groups
})

// 分组展开/收起:按"用户 + 分组 key"持久化到 localStorage(刷新/重登都保留用户的选择)
// key 用 titleKey(稳定,不受界面语言影响);解析失败兜底 []
const collapseKey = () => `menuCollapsedGroups:${userStore.userId || 'guest'}`
const readCollapsed = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(collapseKey()) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch (_) {
    return []
  }
}
const collapsedGroups = ref(readCollapsed())
watch(collapsedGroups, v => {
  try { localStorage.setItem(collapseKey(), JSON.stringify(v)) } catch (_) { /* 存储满/隐私模式忽略 */ }
}, { deep: true })

const isGroupCollapsed = (key) => collapsedGroups.value.includes(key)
const toggleGroup = (key) => {
  const i = collapsedGroups.value.indexOf(key)
  if (i >= 0) collapsedGroups.value.splice(i, 1)
  else collapsedGroups.value.push(key)
}

// 仅在"首次进入"时保证当前页所在分组可见;之后完全遵循用户的展开/收起选择(不再被路由切换覆盖)
let collapseInitialized = false
watch(() => route.path, (p) => {
  if (collapseInitialized) return
  collapseInitialized = true
  for (const group of menuGroups.value) {
    if (group.items.some(it => p === it.path || p.startsWith(it.path + '/'))) {
      const i = collapsedGroups.value.indexOf(group.titleKey)
      if (i >= 0) collapsedGroups.value.splice(i, 1)
    }
  }
}, { immediate: true })

// 课时详情页(/learning/lesson/:id)也高亮"学习中心"
const activePath = computed(() => {
  if (route.path.startsWith('/learning/')) return '/learning'
  if (route.path.startsWith('/forum/')) return '/forum'
  return route.path
})
const goTo = (path) => router.push(path)

// i18n 文案可能内嵌 HTML(如 <b>/<a>),经 sanitizeHtml 消毒后再交给 v-html
const tHtml = (key) => sanitizeHtml(t(key))

// 移动端头部紧凑标题(替代面包屑,单行省略)
const mobileTitle = computed(() => {
  if (route.path.startsWith('/learning/lesson') && lessonCrumbTitle.value) return lessonCrumbTitle.value
  if (route.meta.titleKey) return t(route.meta.titleKey)
  return route.meta.title || t('app.title')
})

// ============== 消息中心(头部铃铛) ==============
// 未读数 60s 轮询(store 与消息页共享);面板展示最近 8 条,点击跳转关联内容(帖子/课时)
const unread = computed(() => notifStore.unread)
const msgList = ref([])
const msgVisible = ref(false)
const typeTag = { SYSTEM: 'primary', REPLY: 'warning', TASK: 'success', COLLAB_REQUEST: 'danger', COLLAB_RESULT: 'info' }
// 类型文案优先取数据字典 notification_type,后端 typeLabel 兜底
const typeText = (n) => dictStore.label('notification_type', n.type, n.typeLabel || n.type)

const openMsgPanel = async () => {
  msgVisible.value = !msgVisible.value
  if (!msgVisible.value) return
  try {
    const res = await recentNotifications()
    msgList.value = res.data || []
  } catch (e) { /* 静默 */ }
}
const fmtMsgTime = (time) => (time || '').replace('T', ' ').slice(0, 16)
const onMsgClick = async (n) => {
  msgVisible.value = false
  if (!n.read) {
    try {
      await markRead(n.id)
      n.read = true
      notifStore.decUnread()
    } catch (e) { /* 静默 */ }
  }
  if (n.relatedType === 'POST' && n.relatedId) router.push(`/forum/${n.relatedId}`)
  else if (n.relatedType === 'LESSON' && n.relatedId) router.push(`/learning/lesson/${n.relatedId}`)
  else if (n.relatedType === 'WIKI_DIR' || n.relatedType === 'WIKI_ART') {
    const tab = n.type === 'COLLAB_REQUEST' ? 'requests' : 'mine'
    const query = { tab, dir: n.relatedId, art: n.relatedId }
    // 协作申请消息: 自动打开协作抽屉(避免用户再点一次"协作者"按钮)
    if (n.type === 'COLLAB_REQUEST') query.openCollab = '1'
    router.push({ path: '/wiki', query })
  }
}
const readAllMsgs = async () => {
  try {
    await markAllRead()
    notifStore.clearUnread()
    msgList.value.forEach(n => { n.read = true })
    ElMessage.success(t('layout.msg.markAllReadDone'))
  } catch (e) { /* 已统一弹错 */ }
}
const goNotifications = () => { msgVisible.value = false; router.push('/notifications') }
// 跳转到协作申请页: Wiki 抽屉自动打开 + 切到 requests tab
const goCollabInbox = () => {
  router.push({ path: '/wiki', query: { tab: 'requests', openCollab: '1' } })
}

let msgTimer = null
let collabTimer = null

// ============== 新版本公告弹窗 ==============
// 对比 localStorage 已读版本,有新版本则弹窗;关闭即视为已读
const noticeVisible = ref(false)
const noticeRelease = ref(null)
const SEEN_KEY = 'lastSeenReleaseVersion'

onMounted(async () => {
  subjectStore.load()   // 科目列表 + 学习语言偏好(异步即可,不阻塞公告;游客仅拉科目列表)
  dictStore.load()      // 数据字典标签映射(游客可读,业务页文案统一从这取)
  localeStore.pullFromServer()   // 登录用户拉取服务端界面语言偏好
  // 新手指引: 已登录但未完成 onboarding → 推到 /onboarding (仅在不是 onboarding 自身页时)
  if (userStore.isLogin && route.path !== '/onboarding') {
    try {
      const { getOnboardingMe } = await import('../api/onboarding')
      const r = await getOnboardingMe()
      if (r.data && r.data.needsOnboarding) {
        router.push({ path: '/onboarding', query: { redirect: route.fullPath } })
        return
      }
    } catch (e) { /* 静默, 不阻塞 Layout 加载 */ }
  }
  if (userStore.isLogin) {
    notifStore.fetchUnread()
    msgTimer = setInterval(() => notifStore.fetchUnread(), 60000)   // 未读数轮询
    // 协作申请待审批数(独立轮询, 每 60s; 资源所有者关键入口)
    collabInboxStore.refresh(true)
    collabTimer = setInterval(() => collabInboxStore.refresh(), 60000)
  }
  try {
    // 新版本公告面向登录用户(游客跳过,避免匿名 401)
    if (!userStore.isLogin) return
    const res = await getLatestRelease()
    const rel = res.data
    if (rel && rel.version && localStorage.getItem(SEEN_KEY) !== rel.version) {
      noticeRelease.value = rel
      noticeVisible.value = true
    }
  } catch (e) { /* 未登录或接口异常时静默 */ }
})

// 跨 Tab 同步登出: 其他 Tab 清掉 localStorage token 时, 本 Tab 也跟着跳登录页
// 配合后端 tokenVersion 机制: 一处 logout → 全端失效
const onStorageChange = (e) => {
  // 只关心 token key, 且新值为空(被清空)
  if (e.key === 'token' && !e.newValue && userStore.isLogin) {
    ElMessage.warning('您的账号已在其他位置登出, 本窗口已退出登录')
    userStore.clear()
    if (router.currentRoute.value.path !== '/login') {
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  }
}

onMounted(() => {
  window.addEventListener('storage', onStorageChange)
})

onBeforeUnmount(() => {
  if (msgTimer) clearInterval(msgTimer)
  if (collabTimer) clearInterval(collabTimer)
  window.removeEventListener('storage', onStorageChange)
})

const closeNotice = () => {
  if (noticeRelease.value?.version) localStorage.setItem(SEEN_KEY, noticeRelease.value.version)
  noticeVisible.value = false
}
const goReleases = () => {
  closeNotice()
  router.push('/releases')
}

// 公告内容为富文本 HTML(管理员在更新日志页编辑)

// 用户下拉菜单
const onCommand = async (cmd) => {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm(t('layout.logout.confirm'), t('layout.logout.tip'), {
        confirmButtonText: t('layout.logout.ok'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      })
      await userStore.logout()
      ElMessage.success(t('layout.logout.done'))
      router.push('/login')
    } catch (_) { /* 取消 */ }
  } else if (cmd === 'logoutAll') {
    // 退出所有设备: 该用户所有 token 立即失效
    try {
      await ElMessageBox.confirm(
        t('layout.logoutAll.confirm', '将退出所有已登录设备,需要重新登录'),
        t('layout.logoutAll.tip', '退出所有设备'),
        { confirmButtonText: t('common.ok'), cancelButtonText: t('common.cancel'), type: 'warning' }
      )
      await userStore.logoutAll()
      ElMessage.success(t('layout.logoutAll.done', '已退出所有设备'))
      router.push('/login')
    } catch (_) { /* 取消 */ }
  } else if (cmd === 'collapse') {
    collapsed.value = !collapsed.value
  }
}
</script>

<template>
  <el-container class="layout-container" :class="{ 'is-mobile': isMobile, 'drawer-open': drawerOpen }">
    <!-- 移动端抽屉遮罩 -->
    <transition name="fade">
      <div v-if="isMobile && drawerOpen" class="drawer-mask" @click="drawerOpen = false"></div>
    </transition>

    <!-- 左侧菜单(桌面可折叠;移动端为 off-canvas 抽屉) -->
    <el-aside :width="asideWidth" class="aside" :class="{ collapsed: menuCollapsed, mobile: isMobile }">
      <router-link to="/" class="logo" :class="{ collapsed: menuCollapsed }" :title="$t('app.title')">
        <img class="logo-icon" src="/favicon.svg" alt="" aria-hidden="true">
        <span v-show="!menuCollapsed" class="logo-text">{{ $t('app.title') }}</span>
      </router-link>

      <el-scrollbar class="menu-scroll" role="navigation" :aria-label="$t('layout.a11y.mainNav')">
        <el-menu
          :default-active="activePath"
          :collapse="menuCollapsed"
          :collapse-transition="false"
          class="aside-menu"
          background-color="transparent"
          text-color="#c8d2e0"
          active-text-color="#fff"
          @select="goTo"
        >
          <template v-for="group in menuGroups" :key="group.titleKey">
            <div
              v-show="!menuCollapsed"
              class="group-title"
              :class="{ collapsed: isGroupCollapsed(group.titleKey) }"
              role="button"
              tabindex="0"
              @click="toggleGroup(group.titleKey)"
              @keydown.enter="toggleGroup(group.titleKey)"
            >
              <span>{{ $t(group.titleKey) }}</span>
              <el-icon class="g-arrow"><ArrowDown /></el-icon>
            </div>
            <template v-if="menuCollapsed || !isGroupCollapsed(group.titleKey)">
              <el-menu-item
                v-for="item in group.items"
                :key="item.path"
                :index="item.path"
                :title="$t(item.titleKey)"
              >
                <el-icon><component :is="item.icon" /></el-icon>
                <template #title>
                  <span class="mi-title">{{ $t(item.titleKey) }}</span>
                </template>
              </el-menu-item>
            </template>
          </template>
        </el-menu>
      </el-scrollbar>

      <!-- 底部角色提示 -->
      <div class="aside-foot" v-show="!menuCollapsed">
        <span class="dot" :class="isGuest ? 'guest' : (isAdmin ? 'admin' : 'user')"></span>
        {{ isGuest ? $t('layout.role.guest') : (isAdmin ? $t('layout.role.admin') : $t('layout.role.user')) }}
      </div>
    </el-aside>

    <!-- 展开/收起把手: 主流做法,悬浮于侧栏右缘垂直居中处(移动端隐藏,改用抽屉) -->
    <button
      v-if="!isMobile"
      class="edge-toggle"
      :style="{ left: (collapsed ? 40 : 212) + 'px' }"
      :title="collapsed ? $t('layout.menu.expand') : $t('layout.menu.collapse')"
      :aria-label="collapsed ? $t('layout.menu.expand') : $t('layout.menu.collapse')"
      @click="collapsed = !collapsed"
    >
      <el-icon :size="13"><component :is="collapsed ? 'ArrowRight' : 'ArrowLeft'" /></el-icon>
    </button>

    <el-container class="main-wrap">
      <!-- 顶部 -->
      <el-header class="header">
        <div class="header-left">
          <!-- 移动端汉堡按钮: 唤出侧栏抽屉 -->
          <button
            v-if="isMobile"
            class="burger"
            :aria-label="$t('layout.menu.open')"
            @click="drawerOpen = !drawerOpen"
          >
            <el-icon :size="18"><Expand v-if="drawerOpen" /><Fold v-else /></el-icon>
          </button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: isAdmin ? '/dashboard' : '/learning' }">{{ $t('layout.crumb.home') }}</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.path === '/learning' || route.path.startsWith('/learning/')" :to="{ path: '/learning' }">
              {{ $t('nav.learningCenter') }}
            </el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.path.startsWith('/learning/lesson') && lessonCrumbTitle">
              {{ lessonCrumbTitle }}
            </el-breadcrumb-item>
            <el-breadcrumb-item v-else-if="route.meta.title && route.path !== '/dashboard' && route.path !== '/learning'">
              {{ $t(route.meta.titleKey || '') || route.meta.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
          <!-- 移动端紧凑标题(替代面包屑,单行省略) -->
          <span class="mobile-title">{{ mobileTitle }}</span>
        </div>
        <div class="header-right">
          <!-- 工具组: 学习选择 + 界面语言 -->
          <div class="hr-tools">
            <el-select
              class="lang-select"
              :model-value="subjectStore.preferredSubjectId"
              :placeholder="$t('layout.subject.select')"
              :aria-label="$t('layout.subject.select')"
              size="small"
              :disabled="langChanging"
              @change="onLangChange"
            >
              <template #prefix>
                <el-icon><Connection /></el-icon>
              </template>
              <el-option v-for="s in subjectStore.subjects" :key="s.id" :label="s.name" :value="s.id">
                <span>{{ s.name }}</span>
                <small class="lang-opt-meta">{{ $t('layout.subject.lessonCount', { n: s.totalLessons }) }}</small>
              </el-option>
            </el-select>

            <!-- 界面语言切换(中/EN): 切换即时生效并持久化 -->
            <div class="locale-toggle" role="group" :aria-label="$t('locale.switch')">
              <button
                type="button"
                class="locale-btn"
                :class="{ active: localeStore.locale === 'zh-CN' }"
                :title="$t('locale.switch')"
                :aria-pressed="localeStore.locale === 'zh-CN'"
                @click="localeStore.setLocale('zh-CN')"
              >中</button>
              <button
                type="button"
                class="locale-btn"
                :class="{ active: localeStore.locale === 'en-US' }"
                :title="$t('locale.switch')"
                :aria-pressed="localeStore.locale === 'en-US'"
                @click="localeStore.setLocale('en-US')"
              >EN</button>
            </div>
          </div>

          <!-- 用户组: 消息铃铛 + 身份标签 + 头像菜单 -->
          <div class="hr-user">
          <!-- 协作申请铃铛(登录用户): 资源所有者关键入口,红点=待审批数 -->
          <div v-if="!isGuest" class="collab-bell-wrap">
            <button class="msg-bell collab-bell" :title="$t('layout.collab.title')" :aria-label="$t('layout.collab.title')" @click="goCollabInbox">
              <el-badge :value="collabInboxStore.pendingCount" :hidden="!collabInboxStore.pendingCount" :max="99" type="danger">
                <el-icon :size="18"><UserFilled /></el-icon>
              </el-badge>
            </button>
          </div>
          <!-- 消息中心铃铛(登录用户) -->
          <div v-if="!isGuest" class="msg-wrap">
            <button class="msg-bell" :title="$t('layout.msg.title')" :aria-label="$t('layout.msg.title')" @click="openMsgPanel">
              <el-badge :value="unread" :hidden="!unread" :max="99">
                <el-icon :size="18"><Bell /></el-icon>
              </el-badge>
            </button>
            <div v-if="msgVisible" class="msg-mask" @click="msgVisible = false"></div>
            <transition name="fade">
              <div v-if="msgVisible" class="msg-panel">
                <div class="msg-head">
                  <b>{{ $t('layout.msg.title') }}</b>
                  <el-button v-if="unread" text type="primary" size="small" @click="readAllMsgs">{{ $t('layout.msg.markAllRead') }}</el-button>
                </div>
                <div class="msg-body">
                  <div
                    v-for="n in msgList"
                    :key="n.id"
                    class="msg-row"
                    :class="{ unread: !n.read }"
                    role="button"
                    tabindex="0"
                    @click="onMsgClick(n)"
                    @keydown.enter="onMsgClick(n)"
                  >
                    <el-tag size="small" effect="plain" :type="typeTag[n.type] || 'info'">{{ typeText(n) }}</el-tag>
                    <div class="msg-text">
                      <div class="msg-t">{{ n.title }}</div>
                      <div class="msg-c">{{ n.content }}</div>
                      <div class="msg-time">{{ fmtMsgTime(n.createTime) }}</div>
                    </div>
                  </div>
                  <el-empty v-if="!msgList.length" :description="$t('layout.msg.empty')" :image-size="48" />
                </div>
                <div class="msg-foot" role="button" tabindex="0" @click="goNotifications" @keydown.enter="goNotifications">
                  {{ $t('layout.msg.viewAll') }}
                </div>
              </div>
            </transition>
          </div>

          <!-- 游客:身份提示 + 登录引导 -->
          <template v-if="isGuest">
            <el-tag type="info" size="small" effect="light" round>
              <el-icon style="vertical-align:-2px;margin-right:2px"><UserFilled /></el-icon>{{ $t('layout.guest.tag') }}
            </el-tag>
            <el-button size="small" @click="goLogin">{{ $t('nav.login') }}</el-button>
            <el-button size="small" type="primary" @click="goRegister">{{ $t('nav.register') }}</el-button>
          </template>

          <template v-else>
            <el-tag :type="isAdmin ? 'danger' : 'success'" size="small" effect="light" round>
              {{ isAdmin ? $t('layout.role.adminTag') : $t('layout.role.userTag') }}
            </el-tag>
            <el-dropdown trigger="click" @command="onCommand">
              <span class="user-trigger">
                <el-avatar :size="28" :src="avatarSrc" :style="{ background: isAdmin ? '#c22b2b' : '#15803d', fontSize: avatarSrc ? undefined : '13px' }">
                  {{ avatarChar }}
                </el-avatar>
                <span class="uname">{{ userStore.displayName }}</span>
                <el-icon class="u-arrow" style="margin-left:4px;"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">
                    <el-icon><User /></el-icon>{{ $t('nav.profile') }}
                  </el-dropdown-item>
                  <el-dropdown-item command="collapse" divided>
                    <el-icon><Fold /></el-icon>{{ collapsed ? $t('layout.menu.expand') : $t('layout.menu.collapse') }}
                  </el-dropdown-item>
                  <el-dropdown-item command="logout" divided>
                    <el-icon><SwitchButton /></el-icon>{{ $t('layout.user.logout') }}
                  </el-dropdown-item>
                  <el-dropdown-item command="logoutAll">
                    <el-icon><Position /></el-icon>{{ $t('layout.user.logoutAll', '退出所有设备') }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          </div>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main>
        <!-- 游客访问提示: 明确告知当前身份与登录后的权益 -->
        <div v-if="isGuest && guestBannerVisible" class="guest-bar" role="status">
          <el-icon class="gb-ic"><UserFilled /></el-icon>
          <span class="gb-text" v-html="tHtml('layout.guest.banner')"></span>
          <div class="gb-btns">
            <el-button size="small" type="primary" @click="goLogin">{{ $t('layout.guest.login') }}</el-button>
            <el-button size="small" text @click="dismissGuestBanner">{{ $t('layout.guest.dismiss') }}</el-button>
          </div>
        </div>
        <!-- 只读账号提示: 明确告知当前账号权限范围 -->
        <div v-if="isReadOnly" class="readonly-bar" role="status">
          <el-icon class="gb-ic"><View /></el-icon>
          <span class="gb-text" v-html="tHtml('layout.readonly.banner')"></span>
        </div>
        <RouterView v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </RouterView>
      </el-main>
    </el-container>

    <!-- 新版本公告弹窗(每个版本只弹一次) -->
    <el-dialog v-model="noticeVisible" width="520px" :show-close="false" :close-on-click-modal="false" class="notice-dialog">
      <template #header>
        <div class="notice-head">
          <span class="notice-badge">{{ $t('layout.notice.badge') }}</span>
          <span class="notice-ver">v{{ noticeRelease?.version }}</span>
        </div>
      </template>
      <h3 class="notice-title">{{ noticeRelease?.title }}</h3>
      <div class="notice-body" v-html="sanitizeHtml(noticeRelease?.content)"></div>
      <template #footer>
        <el-button @click="closeNotice">{{ $t('layout.notice.later') }}</el-button>
        <el-button type="primary" @click="goReleases">{{ $t('layout.notice.viewAll') }}</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.layout-container { height: 100vh; position: relative; }

/* ============ 移动端抽屉(<=768px) ============ */
.drawer-mask {
  position: fixed; inset: 0; z-index: 90;
  background: rgba(15, 23, 42, .45); backdrop-filter: blur(2px);
}
.burger {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; padding: 0; flex: none;
  border: 1px solid var(--c-border-strong); border-radius: var(--r-s);
  background: #fff; color: var(--c-text-2); cursor: pointer;
}
.burger:hover { color: var(--c-primary); border-color: var(--c-primary); }
.aside.mobile {
  position: fixed; left: 0; top: 0; bottom: 0; z-index: 100;
  transform: translateX(-100%);
  transition: transform .22s cubic-bezier(.4, 0, .2, 1);
  box-shadow: 8px 0 24px rgba(15, 23, 42, .18);
}
.aside.mobile:not(.collapsed) { transition: transform .22s cubic-bezier(.4, 0, .2, 1); }
.layout-container.drawer-open .aside.mobile { transform: translateX(0); }

/* ============ 侧栏 ============ */
.aside {
  display: flex; flex-direction: column;
  background: linear-gradient(180deg, var(--c-sidebar-from, #0f172a) 0%, var(--c-sidebar-to, #141f36) 100%);
  transition: width .22s cubic-bezier(.4, 0, .2, 1);
  overflow: hidden;
}
/* logo 改为 router-link, 整块可点击回首页 */
a.logo {
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  transition: background .12s;
}
a.logo:hover { background: rgba(255, 255, 255, .08); }
.logo {
  display: flex; align-items: center; gap: 9px;
  height: 52px; padding: 0 20px; flex: none;
  background: rgba(255, 255, 255, .04);
}
.logo.collapsed { padding: 0; justify-content: center; }
.logo-icon { width: 26px; height: 26px; border-radius: 7px; flex: none; display: block; }
.logo-text { color: #fff; font-size: var(--fs-md); font-weight: 600; white-space: nowrap; letter-spacing: .2px; line-height: 1; }

.menu-scroll { flex: 1; }
.aside-menu { border-right: none; padding: 6px 0; }
/* 一级: 分组标题 —— 视觉上明显大于二级菜单项, 作为分区标题 */
.group-title {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px 7px;
  color: #cbd5e1; font-size: var(--fs-md); font-weight: 600;
  letter-spacing: .2px; user-select: none;
  cursor: pointer; transition: color .18s;
}
.group-title:hover { color: #fff; }
.group-title .g-arrow { transition: transform .2s; font-size: 14px; color: #94a3b8; }
.group-title.collapsed .g-arrow { transform: rotate(-90deg); }
/* 二级: 菜单项 —— 略小于分组标题, 形成清晰层级 */
.aside-menu :deep(.el-menu-item) {
  height: 38px; line-height: 38px; margin: 2px 10px; border-radius: 8px;
  font-size: var(--fs-sm);
  transition: background .18s, color .18s;
}
.aside-menu :deep(.el-menu-item:hover) { background: rgba(var(--c-primary-rgb, 37, 99, 235), .16) !important; color: #fff !important; }
.aside-menu :deep(.el-menu-item.is-active) {
  background: var(--c-primary-grad, linear-gradient(90deg, #2563eb, #4f46e5)) !important;
  color: #fff !important;
  box-shadow: 0 4px 12px rgba(var(--c-primary-rgb, 37, 99, 235), .35);
}
.aside.collapsed .aside-menu { width: 56px; }
.aside.collapsed .aside-menu :deep(.el-menu-item) {
  display: flex; align-items: center; justify-content: center;
  margin: 3px 6px; padding: 0 !important;
}
/* 折叠态: 图标包在 tooltip 触发层内, 需对该层做居中并隐藏标题 */
.aside.collapsed .aside-menu :deep(.el-menu-tooltip__trigger) {
  display: flex; align-items: center; justify-content: center; width: 100%;
}
.aside.collapsed .aside-menu :deep(.el-menu-item .el-icon),
.aside.collapsed .aside-menu :deep(.el-menu-tooltip__trigger .el-icon) { margin: 0; }
.aside.collapsed .mi-title { display: none; }
.aside.collapsed .aside-menu :deep(.el-menu-tooltip__trigger .el-menu-item__title) { display: none; }
.mi-title { margin-left: 2px; }
.aside-foot {
  flex: none; padding: 12px 20px; font-size: var(--fs-xs); color: #94a3b8;
  border-top: 1px solid rgba(255, 255, 255, .06); white-space: nowrap;
}
.aside-foot .dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 6px; }
.aside-foot .dot.admin { background: #f87171; }
.aside-foot .dot.user { background: #4ade80; }
.aside-foot .dot.guest { background: #94a3b8; }

/* ============ 顶部 ============ */
.main-wrap { min-width: 0; }
.header {
  --el-header-height: 52px;
  height: 52px; line-height: 1;
  display: flex; justify-content: space-between; align-items: center;
  background: #fff; border-bottom: 1px solid #eceff4; padding: 0 20px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .04); z-index: 10;
}
.header-left { display: flex; align-items: center; gap: 14px; }
.mobile-title { display: none; }

/* 侧栏右缘展开/收起把手(垂直居中, 随侧栏宽度平移) */
.edge-toggle {
  position: absolute; top: 50%; z-index: 20;
  transform: translateY(-50%);
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; padding: 0;
  border: 1px solid #d0d7e2; border-radius: 50%;
  background: #fff; color: #5a6478; cursor: pointer;
  box-shadow: 0 2px 6px rgba(15, 23, 42, .12);
  transition: left .22s cubic-bezier(.4, 0, .2, 1), color .18s, border-color .18s, transform .18s;
}
.edge-toggle:hover {
  color: var(--c-primary); border-color: var(--c-primary);
  transform: translateY(-50%) scale(1.12);
  box-shadow: 0 3px 10px rgba(37, 99, 235, .28);
}
.header-right { display: flex; align-items: center; gap: 12px; }
.hr-tools { display: flex; align-items: center; gap: 12px; }
.hr-user { display: flex; align-items: center; gap: 12px; }

/* 头像旁学习语言选择框 */
.lang-select { width: 150px; }
.lang-select :deep(.el-input__wrapper) { border-radius: var(--r-full); background: #f5f7fb; }
.lang-opt-meta { color: var(--c-text-3); font-size: var(--fs-xs); margin-left: 8px; }

/* 界面语言切换(中/EN 双按钮,当前语言高亮) */
.locale-toggle {
  display: inline-flex; align-items: center; flex: none;
  gap: 2px; padding: 2px;
  border: 1px solid var(--c-border); border-radius: var(--r-full); background: #f5f7fb;
}
.locale-btn {
  min-width: 32px; height: 24px; padding: 0 8px;
  border: none; border-radius: var(--r-full); background: transparent;
  color: var(--c-text-2); font-size: var(--fs-xs); line-height: 24px; cursor: pointer;
  transition: background .15s, color .15s;
}
.locale-btn:hover { color: var(--c-primary); }
.locale-btn.active { background: var(--c-primary); color: #fff; font-weight: 600; }

/* 消息中心铃铛 + 下拉面板 */
.msg-wrap { position: relative; display: inline-flex; }
.collab-bell-wrap { position: relative; display: inline-flex; margin-right: 4px; }
.collab-bell { color: var(--c-text-2); }
.msg-bell {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; padding: 0; border: none; border-radius: 50%;
  background: transparent; color: var(--c-text-2); cursor: pointer;
  transition: background .15s, color .15s;
}
.msg-bell:hover { background: var(--c-primary-bg); color: var(--c-primary); }
.msg-mask { position: fixed; inset: 0; z-index: 99; }
.msg-panel {
  position: absolute; top: calc(100% + 10px); right: -8px; z-index: 100;
  width: 320px; background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  box-shadow: 0 12px 32px rgba(15, 23, 42, .14); overflow: hidden;
}
.msg-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px; border-bottom: 1px solid #f1f3f8; font-size: var(--fs-sm);
}
.msg-body { max-height: 340px; overflow: auto; }
.msg-row {
  display: flex; gap: 8px; padding: 10px 14px; cursor: pointer;
  border-bottom: 1px solid #f5f7fa; transition: background .12s;
}
.msg-row:hover { background: #f8fafd; }
.msg-row.unread { background: #f6f9ff; }
.msg-text { flex: 1; min-width: 0; }
.msg-t { font-size: var(--fs-sm); font-weight: 600; color: var(--c-text-1); }
.msg-c {
  margin-top: 2px; font-size: var(--fs-xs); color: var(--c-text-2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.msg-time { margin-top: 4px; font-size: var(--fs-xs); color: #94a3b8; }
.msg-foot {
  padding: 9px 14px; text-align: center; font-size: var(--fs-xs); color: var(--c-primary);
  cursor: pointer; background: #fafbfd;
}
.msg-foot:hover { background: var(--c-primary-bg); }

.user-trigger { display: inline-flex; align-items: center; cursor: pointer; color: var(--c-text-1); }
.user-trigger .uname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 120px; margin-left: 6px; }
.user-trigger:hover { color: var(--c-primary); }

.el-main { padding: 0; background: #f5f7fb; overflow-x: hidden; }

/* ============ 游客访问提示条 ============ */
.guest-bar {
  display: flex; align-items: center; gap: 10px;
  margin: 12px 24px 0; padding: 10px 16px;
  background: linear-gradient(90deg, #eef2ff, #f0f7ff);
  border: 1px solid #dbe4ff; border-radius: var(--r-m);
  font-size: var(--fs-sm); color: var(--c-text-1);
}
.guest-bar .gb-ic { color: #4f46e5; font-size: 18px; flex: none; }
.guest-bar .gb-text { flex: 1; min-width: 0; line-height: 1.6; }
.guest-bar .gb-text strong { color: #4f46e5; }
.guest-bar .gb-btns { flex: none; display: flex; align-items: center; gap: 4px; }
/* 只读账号横幅 */
.readonly-bar {
  display: flex; align-items: center; gap: 10px;
  margin: 12px 16px 0; padding: 10px 16px;
  background: #fff7e6; border: 1px solid #ffd591; border-radius: var(--r-s);
  font-size: var(--fs-sm); color: #ad6800;
}
.readonly-bar .gb-ic { color: #d46b08; font-size: 18px; flex: none; }
.readonly-bar .gb-text { flex: 1; min-width: 0; line-height: 1.6; }
.readonly-bar .gb-text strong { color: #d46b08; }

.fade-enter-active, .fade-leave-active { transition: opacity .18s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* ============ 新版本公告弹窗 ============ */
.notice-head { display: flex; align-items: center; gap: 10px; }
.notice-badge {
  padding: 3px 10px; border-radius: var(--r-full); font-size: var(--fs-xs);
  background: var(--c-primary-grad, linear-gradient(135deg, #2563eb, #4f46e5)); color: #fff; font-weight: 600;
}
.notice-ver { font-size: var(--fs-lg); font-weight: 600; color: var(--c-text-1); }
.notice-title { margin: 0 0 14px; font-size: var(--fs-md); color: var(--c-text-1); }
.notice-body { max-height: 320px; overflow: auto; font-size: var(--fs-sm); line-height: 1.85; color: var(--c-text-1); word-break: break-word; }
.notice-body :deep(p) { margin: 0 0 8px; }
.notice-body :deep(ul), .notice-body :deep(ol) { margin: 0 0 8px; padding-left: 22px; }
.notice-body :deep(li) { margin-bottom: 4px; }
.notice-body :deep(h1), .notice-body :deep(h2), .notice-body :deep(h3) { margin: 10px 0 8px; font-size: var(--fs-md); }
.notice-body :deep(a) { color: var(--c-primary); text-decoration: underline; }
.notice-body :deep(blockquote) { margin: 8px 0; padding: 6px 12px; border-left: 3px solid var(--c-primary); background: var(--c-primary-bg); color: var(--c-text-2); }
.notice-body :deep(pre.ql-syntax) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: var(--r-s); overflow-x: auto; }

/* ============ 移动端顶栏(媒体查询放最后,保证覆盖基础样式) ============ */
@media (max-width: 768px) {
  .header { padding: 0 12px; gap: 8px; }
  .header-left { gap: 8px; min-width: 0; flex: 1; }
  .header-left :deep(.el-breadcrumb) { display: none; }
  .mobile-title {
    display: block; flex: 1; min-width: 0;
    font-size: var(--fs-md); font-weight: 600; color: var(--c-text-1);
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .header-right { gap: 8px; flex: none; }
  .hr-tools { gap: 8px; }
  .lang-select { width: 110px; }
  .locale-btn { min-width: 28px; padding: 0 6px; }
  .msg-panel { width: min(320px, calc(100vw - 24px)); }
  .user-trigger .uname { max-width: 60px; }
  .hr-user .el-tag { display: none; }
  .guest-bar { margin: 10px 12px 0; flex-wrap: wrap; }
  .guest-bar .gb-btns { width: 100%; justify-content: flex-end; }
  .readonly-bar { margin: 10px 12px 0; }
}
@media (max-width: 480px) {
  /* 窄屏: 学习选择与中/EN 收进头部第二行,避免与标题/头像挤在一行 */
  .header { height: auto; min-height: 52px; flex-wrap: wrap; padding: 6px 12px; row-gap: 6px; }
  .header-right { display: contents; }
  .header-left { order: 1; }
  .hr-user { order: 2; margin-left: auto; gap: 8px; }
  .hr-tools { order: 3; width: 100%; justify-content: flex-end; }
  .user-trigger .uname, .user-trigger .u-arrow { display: none; }
  .lang-select { width: min(180px, 55vw); }
  .locale-btn { min-width: 26px; padding: 0 5px; }
}
</style>
