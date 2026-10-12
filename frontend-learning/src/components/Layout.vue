<script setup>
// 整体布局: 可折叠侧栏 + 顶部栏 + 内容区
// 菜单按角色分级: 学习板块对所有人开放, 系统管理仅管理员可见
// 菜单分组支持展开/收起(记忆到 localStorage);新版本公告弹窗(按已读版本去重)
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import { useSubjectStore } from '../store/subject'
import { useNotificationStore } from '../store/notification'
import { lessonCrumbTitle } from '../store/crumb'
import { getLatestRelease } from '../api/release'
import { recentNotifications, markRead, markAllRead } from '../api/notifications'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const subjectStore = useSubjectStore()
const notifStore = useNotificationStore()

const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

// ============== 游客访问 ==============
// 未登录用户可浏览学习中心/课时详情;个人操作由页面内引导登录
const isGuest = computed(() => !userStore.isLogin)
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

// 菜单分组: 学习 -> 系统管理(仅管理员) -> 其他
const menuGroups = computed(() => {
  const groups = [
    {
      title: '学习',
      items: [
        { path: '/learning', title: '学习中心', icon: 'Reading' },
        { path: '/board',    title: '学习看板', icon: 'TrendCharts' }
      ]
    }
  ]
  if (isAdmin.value) {
    groups.push({
      title: '系统管理',
      items: [
        { path: '/dashboard', title: '仪表盘',   icon: 'DataAnalysis' },
        { path: '/users',     title: '用户管理', icon: 'User' },
        { path: '/roles',     title: '角色管理', icon: 'UserFilled' },
        { path: '/dicts',     title: '数据字典', icon: 'Collection' },
        { path: '/logs',      title: '操作日志', icon: 'Document' },
        { path: '/learning-config', title: '学习配置', icon: 'Setting' }
      ]
    })
  }
  groups.push({
    title: '交流',
    items: [
      { path: '/forum', title: '论坛专区', icon: 'ChatLineRound' }
    ]
  })
  groups.push({
    title: '其他',
    items: [
      { path: '/releases', title: '更新日志', icon: 'Tickets' },
      { path: '/feedback', title: '意见反馈', icon: 'ChatDotSquare' },
      { path: '/about',    title: '关于',     icon: 'InfoFilled' }
    ]
  })
  return groups
})

// 分组展开/收起(记忆到 localStorage)
const collapsedGroups = ref(JSON.parse(localStorage.getItem('menuCollapsedGroups') || '[]'))
watch(collapsedGroups, v => localStorage.setItem('menuCollapsedGroups', JSON.stringify(v)), { deep: true })
const isGroupCollapsed = (title) => collapsedGroups.value.includes(title)
const toggleGroup = (title) => {
  const i = collapsedGroups.value.indexOf(title)
  if (i >= 0) collapsedGroups.value.splice(i, 1)
  else collapsedGroups.value.push(title)
}
// 当前路由所在分组若被收起,自动展开(保证激活项可见)
watch(() => route.path, (p) => {
  for (const group of menuGroups.value) {
    if (group.items.some(it => p === it.path || p.startsWith(it.path + '/'))) {
      const i = collapsedGroups.value.indexOf(group.title)
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

// ============== 消息中心(头部铃铛) ==============
// 未读数 60s 轮询(store 与消息页共享);面板展示最近 8 条,点击跳转关联内容(帖子/课时)
const unread = computed(() => notifStore.unread)
const msgList = ref([])
const msgVisible = ref(false)
const typeTag = { SYSTEM: 'primary', REPLY: 'warning', TASK: 'success' }

const openMsgPanel = async () => {
  msgVisible.value = !msgVisible.value
  if (!msgVisible.value) return
  try {
    const res = await recentNotifications()
    msgList.value = res.data || []
  } catch (e) { /* 静默 */ }
}
const fmtMsgTime = (t) => (t || '').replace('T', ' ').slice(0, 16)
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
}
const readAllMsgs = async () => {
  try {
    await markAllRead()
    notifStore.clearUnread()
    msgList.value.forEach(n => { n.read = true })
    ElMessage.success('已全部标记已读')
  } catch (e) { /* 已统一弹错 */ }
}
const goNotifications = () => { msgVisible.value = false; router.push('/notifications') }

let msgTimer = null

// ============== 新版本公告弹窗 ==============
// 对比 localStorage 已读版本,有新版本则弹窗;关闭即视为已读
const noticeVisible = ref(false)
const noticeRelease = ref(null)
const SEEN_KEY = 'lastSeenReleaseVersion'

onMounted(async () => {
  subjectStore.load()   // 科目列表 + 学习语言偏好(异步即可,不阻塞公告;游客仅拉科目列表)
  if (userStore.isLogin) {
    notifStore.fetchUnread()
    msgTimer = setInterval(() => notifStore.fetchUnread(), 60000)   // 未读数轮询
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

onBeforeUnmount(() => { if (msgTimer) clearInterval(msgTimer) })

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
      await ElMessageBox.confirm('确定退出登录吗?', '提示', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await userStore.logout()
      ElMessage.success('已退出登录')
      router.push('/login')
    } catch (_) { /* 取消 */ }
  } else if (cmd === 'collapse') {
    collapsed.value = !collapsed.value
  }
}
</script>

<template>
  <el-container class="layout-container">
    <!-- 左侧菜单(可折叠) -->
    <el-aside :width="collapsed ? '56px' : '228px'" class="aside" :class="{ collapsed }">
      <div class="logo" :class="{ collapsed }">
        <el-icon size="22" color="#fff"><Promotion /></el-icon>
        <span v-show="!collapsed" class="logo-text">通用学习平台</span>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="activePath"
          :collapse="collapsed"
          :collapse-transition="false"
          class="aside-menu"
          background-color="transparent"
          text-color="#c8d2e0"
          active-text-color="#fff"
          @select="goTo"
        >
          <template v-for="group in menuGroups" :key="group.title">
            <div
              v-show="!collapsed"
              class="group-title"
              :class="{ collapsed: isGroupCollapsed(group.title) }"
              role="button"
              tabindex="0"
              @click="toggleGroup(group.title)"
              @keydown.enter="toggleGroup(group.title)"
            >
              <span>{{ group.title }}</span>
              <el-icon class="g-arrow"><ArrowDown /></el-icon>
            </div>
            <template v-if="collapsed || !isGroupCollapsed(group.title)">
              <el-menu-item
                v-for="item in group.items"
                :key="item.path"
                :index="item.path"
                :title="item.title"
              >
                <el-icon><component :is="item.icon" /></el-icon>
                <template #title>
                  <span class="mi-title">{{ item.title }}</span>
                </template>
              </el-menu-item>
            </template>
          </template>
        </el-menu>
      </el-scrollbar>

      <!-- 底部角色提示 -->
      <div class="aside-foot" v-show="!collapsed">
        <span class="dot" :class="isGuest ? 'guest' : (isAdmin ? 'admin' : 'user')"></span>
        {{ isGuest ? '游客模式 · 登录后记录学习进度' : (isAdmin ? '管理员模式 · 可管理全站' : '学员模式 · 专注你的学习') }}
      </div>
    </el-aside>

    <!-- 展开/收起把手: 主流做法,悬浮于侧栏右缘垂直居中处 -->
    <button
      class="edge-toggle"
      :style="{ left: (collapsed ? 45 : 217) + 'px' }"
      :title="collapsed ? '展开菜单' : '收起菜单'"
      :aria-label="collapsed ? '展开菜单' : '收起菜单'"
      @click="collapsed = !collapsed"
    >
      <el-icon :size="13"><component :is="collapsed ? 'ArrowRight' : 'ArrowLeft'" /></el-icon>
    </button>

    <el-container class="main-wrap">
      <!-- 顶部 -->
      <el-header class="header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: isAdmin ? '/dashboard' : '/learning' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.path === '/learning' || route.path.startsWith('/learning/')" :to="{ path: '/learning' }">
              学习中心
            </el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.path.startsWith('/learning/lesson') && lessonCrumbTitle">
              {{ lessonCrumbTitle }}
            </el-breadcrumb-item>
            <el-breadcrumb-item v-else-if="route.meta.title && route.path !== '/dashboard' && route.path !== '/learning'">
              {{ route.meta.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-select
            class="lang-select"
            :model-value="subjectStore.preferredSubjectId"
            placeholder="选择学习语言"
            size="small"
            :disabled="langChanging"
            @change="onLangChange"
          >
            <template #prefix>
              <el-icon><Connection /></el-icon>
            </template>
            <el-option v-for="s in subjectStore.subjects" :key="s.id" :label="s.name" :value="s.id">
              <span>{{ s.name }}</span>
              <small class="lang-opt-meta">{{ s.totalLessons }} 课时</small>
            </el-option>
          </el-select>

          <!-- 消息中心铃铛(登录用户) -->
          <div v-if="!isGuest" class="msg-wrap">
            <button class="msg-bell" title="消息中心" aria-label="消息中心" @click="openMsgPanel">
              <el-badge :value="unread" :hidden="!unread" :max="99">
                <el-icon :size="18"><Bell /></el-icon>
              </el-badge>
            </button>
            <div v-if="msgVisible" class="msg-mask" @click="msgVisible = false"></div>
            <transition name="fade">
              <div v-if="msgVisible" class="msg-panel">
                <div class="msg-head">
                  <b>消息中心</b>
                  <el-button v-if="unread" text type="primary" size="small" @click="readAllMsgs">全部已读</el-button>
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
                    <el-tag size="small" effect="plain" :type="typeTag[n.type] || 'info'">{{ n.typeLabel }}</el-tag>
                    <div class="msg-text">
                      <div class="msg-t">{{ n.title }}</div>
                      <div class="msg-c">{{ n.content }}</div>
                      <div class="msg-time">{{ fmtMsgTime(n.createTime) }}</div>
                    </div>
                  </div>
                  <el-empty v-if="!msgList.length" description="暂无消息" :image-size="48" />
                </div>
                <div class="msg-foot" role="button" tabindex="0" @click="goNotifications" @keydown.enter="goNotifications">
                  查看全部消息 →
                </div>
              </div>
            </transition>
          </div>

          <!-- 游客:身份提示 + 登录引导 -->
          <template v-if="isGuest">
            <el-tag type="info" size="small" effect="light" round>
              <el-icon style="vertical-align:-2px;margin-right:2px"><UserFilled /></el-icon>游客访问
            </el-tag>
            <el-button size="small" @click="goLogin">登录</el-button>
            <el-button size="small" type="primary" @click="goRegister">注册</el-button>
          </template>

          <template v-else>
            <el-tag :type="isAdmin ? 'danger' : 'success'" size="small" effect="light" round>
              {{ isAdmin ? '管理员' : '普通用户' }}
            </el-tag>
            <el-dropdown trigger="click" @command="onCommand">
              <span class="user-trigger">
                <el-avatar :size="28" :src="avatarSrc" :style="{ background: isAdmin ? '#e6483d' : '#16a34a', fontSize: avatarSrc ? undefined : '13px' }">
                  {{ avatarChar }}
                </el-avatar>
                {{ userStore.displayName }}
                <el-icon style="margin-left:4px;"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">
                    <el-icon><User /></el-icon>个人信息设置
                  </el-dropdown-item>
                  <el-dropdown-item command="collapse" divided>
                    <el-icon><Fold /></el-icon>{{ collapsed ? '展开菜单' : '收起菜单' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="logout" divided>
                    <el-icon><SwitchButton /></el-icon>退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main>
        <!-- 游客访问提示: 明确告知当前身份与登录后的权益 -->
        <div v-if="isGuest && guestBannerVisible" class="guest-bar" role="status">
          <el-icon class="gb-ic"><UserFilled /></el-icon>
          <span class="gb-text">
            您当前处于<strong>游客访问</strong>模式,可自由浏览全部课程;登录后可标记学习完成、同步学习进度并参与互动。
          </span>
          <div class="gb-btns">
            <el-button size="small" type="primary" @click="goLogin">去登录</el-button>
            <el-button size="small" text @click="dismissGuestBanner">不再提示</el-button>
          </div>
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
          <span class="notice-badge">新版本</span>
          <span class="notice-ver">v{{ noticeRelease?.version }}</span>
        </div>
      </template>
      <h3 class="notice-title">{{ noticeRelease?.title }}</h3>
      <div class="notice-body" v-html="noticeRelease?.content"></div>
      <template #footer>
        <el-button @click="closeNotice">稍后再看</el-button>
        <el-button type="primary" @click="goReleases">查看全部更新日志</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.layout-container { height: 100vh; position: relative; }

/* ============ 侧栏 ============ */
.aside {
  display: flex; flex-direction: column;
  background: linear-gradient(180deg, var(--c-sidebar-from, #0f172a) 0%, var(--c-sidebar-to, #141f36) 100%);
  transition: width .22s cubic-bezier(.4, 0, .2, 1);
  overflow: hidden;
}
.logo {
  display: flex; align-items: center; gap: 9px;
  height: 52px; padding: 0 20px; flex: none;
  background: rgba(255, 255, 255, .04);
}
.logo.collapsed { padding: 0; justify-content: center; }
.logo-text { color: #fff; font-size: var(--fs-md); font-weight: 600; white-space: nowrap; letter-spacing: .2px; line-height: 1; }

.menu-scroll { flex: 1; }
.aside-menu { border-right: none; padding: 6px 0; }
.group-title {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px 6px; color: #94a3b8; font-size: var(--fs-xs);
  letter-spacing: 1px; text-transform: uppercase; user-select: none;
  cursor: pointer; transition: color .18s;
}
.group-title:hover { color: #e2e8f0; }
.group-title .g-arrow { transition: transform .2s; font-size: 12px; }
.group-title.collapsed .g-arrow { transform: rotate(-90deg); }
.aside-menu :deep(.el-menu-item) {
  height: 40px; line-height: 40px; margin: 3px 10px; border-radius: 8px;
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

/* 侧栏右缘展开/收起把手(垂直居中, 随侧栏宽度平移) */
.edge-toggle {
  position: absolute; top: 50%; z-index: 20;
  transform: translateY(-50%);
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; padding: 0;
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

/* 头像旁学习语言选择框 */
.lang-select { width: 150px; }
.lang-select :deep(.el-input__wrapper) { border-radius: var(--r-full); background: #f5f7fb; }
.lang-opt-meta { color: var(--c-text-3); font-size: var(--fs-xs); margin-left: 8px; }

/* 消息中心铃铛 + 下拉面板 */
.msg-wrap { position: relative; display: inline-flex; }
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
</style>
