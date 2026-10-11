<script setup>
// 整体布局: 可折叠侧栏 + 顶部栏 + 内容区
// 菜单按角色分级: 学习板块对所有人开放, 系统管理仅管理员可见
// 菜单分组支持展开/收起(记忆到 localStorage);新版本公告弹窗(按已读版本去重)
import { computed, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import { lessonCrumbTitle } from '../store/crumb'
import { getLatestRelease } from '../api/release'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

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
        { path: '/logs',      title: '操作日志', icon: 'Document' }
      ]
    })
  }
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
  if (route.path.startsWith('/learning')) return '/learning'
  return route.path
})
const goTo = (path) => router.push(path)

// ============== 新版本公告弹窗 ==============
// 对比 localStorage 已读版本,有新版本则弹窗;关闭即视为已读
const noticeVisible = ref(false)
const noticeRelease = ref(null)
const SEEN_KEY = 'lastSeenReleaseVersion'

onMounted(async () => {
  try {
    const res = await getLatestRelease()
    const rel = res.data
    if (rel && rel.version && localStorage.getItem(SEEN_KEY) !== rel.version) {
      noticeRelease.value = rel
      noticeVisible.value = true
    }
  } catch (e) { /* 未登录或接口异常时静默 */ }
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
  if (cmd === 'logout') {
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
        <span class="dot" :class="isAdmin ? 'admin' : 'user'"></span>
        {{ isAdmin ? '管理员模式 · 可管理全站' : '学员模式 · 专注你的学习' }}
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
            <el-breadcrumb-item v-if="route.path.startsWith('/learning')" :to="{ path: '/learning' }">
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
          <el-tag :type="isAdmin ? 'danger' : 'success'" size="small" effect="light" round>
            {{ isAdmin ? '管理员' : '普通用户' }}
          </el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="user-trigger">
              <el-avatar :size="28" :style="{ background: isAdmin ? '#e6483d' : '#16a34a' }">
                {{ userStore.username?.charAt(0)?.toUpperCase() || '?' }}
              </el-avatar>
              {{ userStore.username }}
              <el-icon style="margin-left:4px;"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="collapse">
                  <el-icon><Fold /></el-icon>{{ collapsed ? '展开菜单' : '收起菜单' }}
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main>
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
  background: linear-gradient(180deg, #0f172a 0%, #141f36 100%);
  transition: width .22s cubic-bezier(.4, 0, .2, 1);
  overflow: hidden;
}
.logo {
  display: flex; align-items: center; gap: 9px;
  height: 60px; padding: 0 20px; flex: none;
  background: rgba(255, 255, 255, .04);
}
.logo.collapsed { padding: 0; justify-content: center; }
.logo-text { color: #fff; font-size: var(--fs-md); font-weight: 600; white-space: nowrap; letter-spacing: .2px; }

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
  height: 42px; line-height: 42px; margin: 3px 10px; border-radius: 8px;
  transition: background .18s, color .18s;
}
.aside-menu :deep(.el-menu-item:hover) { background: rgba(255, 255, 255, .07) !important; color: #fff !important; }
.aside-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, #2563eb, #4f46e5) !important;
  color: #fff !important;
  box-shadow: 0 4px 12px rgba(37, 99, 235, .35);
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

/* ============ 顶部 ============ */
.main-wrap { min-width: 0; }
.header {
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

.user-trigger { display: inline-flex; align-items: center; cursor: pointer; color: var(--c-text-1); }
.user-trigger:hover { color: var(--c-primary); }

.el-main { padding: 0; background: #f5f7fb; overflow-x: hidden; }

.fade-enter-active, .fade-leave-active { transition: opacity .18s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* ============ 新版本公告弹窗 ============ */
.notice-head { display: flex; align-items: center; gap: 10px; }
.notice-badge {
  padding: 3px 10px; border-radius: var(--r-full); font-size: var(--fs-xs);
  background: linear-gradient(135deg, #2563eb, #4f46e5); color: #fff; font-weight: 600;
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
