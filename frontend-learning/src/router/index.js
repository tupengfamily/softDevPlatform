import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import i18n from '../i18n'

// 公开路由(登录、注册)— 无需 token
// 游客路由(meta.guest: 学习中心/课时详情)— 未登录可浏览,个人操作在页面内引导登录
// 受保护路由(在 Layout 下)— 必须登录
// meta.roles: 限定可访问的角色(缺省=所有登录用户);ADMIN 专属页面在守卫中统一拦截
const routes = [
  { path: '/login',    component: () => import('../views/Login.vue'),    meta: { title: '登录', titleKey: 'nav.login', public: true } },
  { path: '/register', component: () => import('../views/Register.vue'), meta: { title: '注册', titleKey: 'nav.register', public: true } },

  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    // 按角色落到不同首页: 管理员 -> 仪表盘, 普通用户 -> 学习中心
    redirect: () => {
      const s = useUserStore()
      return s.roles.includes('ADMIN') ? '/dashboard' : '/learning'
    },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '仪表盘', titleKey: 'nav.dashboard', icon: 'DataAnalysis', roles: ['ADMIN'] }
      },
      {
        path: 'learning',
        name: 'LearningCenter',
        component: () => import('../views/LearningCenter.vue'),
        meta: { title: '学习中心', titleKey: 'nav.learningCenter', icon: 'Reading', guest: true }
      },
      {
        path: 'learning/lesson/:id',
        name: 'LessonDetail',
        component: () => import('../views/LessonDetail.vue'),
        meta: { title: '课时学习', titleKey: 'nav.lessonDetail', icon: 'Reading', guest: true }
      },
      {
        path: 'board',
        name: 'LearningBoard',
        component: () => import('../views/LearningBoard.vue'),
        meta: { title: '学习看板', titleKey: 'nav.learningBoard', icon: 'TrendCharts' }
      },
      {
        path: 'learning-config',
        name: 'LearningConfig',
        component: () => import('../views/LearningConfig.vue'),
        meta: { title: '学习配置', titleKey: 'nav.learningConfig', icon: 'Setting', roles: ['ADMIN'] }
      },
      {
        path: 'notifications',
        name: 'Notifications',
        component: () => import('../views/Notifications.vue'),
        meta: { title: '消息中心', titleKey: 'nav.notifications', icon: 'Bell' }
      },
      {
        path: 'forum',
        name: 'Forum',
        component: () => import('../views/Forum.vue'),
        meta: { title: '论坛专区', titleKey: 'nav.forum', icon: 'ChatLineRound' }
      },
      {
        path: 'forum/:id',
        name: 'ForumDetail',
        component: () => import('../views/ForumDetail.vue'),
        meta: { title: '帖子详情', titleKey: 'nav.forumDetail', icon: 'ChatLineRound' }
      },
      {
        path: 'users',
        name: 'UserManagement',
        component: () => import('../views/UserManagement.vue'),
        meta: { title: '用户管理', titleKey: 'nav.users', icon: 'User', roles: ['ADMIN'] }
      },
      {
        path: 'roles',
        name: 'RoleManagement',
        component: () => import('../views/RoleManagement.vue'),
        meta: { title: '角色管理', titleKey: 'nav.roles', icon: 'UserFilled', roles: ['ADMIN'] }
      },
      {
        path: 'dicts',
        name: 'DictManagement',
        component: () => import('../views/DictManagement.vue'),
        meta: { title: '数据字典', titleKey: 'nav.dicts', icon: 'Collection', roles: ['ADMIN'] }
      },
      {
        path: 'system-settings',
        name: 'SystemSettings',
        component: () => import('../views/SystemSettings.vue'),
        meta: { title: '系统设置', titleKey: 'nav.systemSettings', icon: 'Tools', roles: ['ADMIN'] }
      },
      {
        path: 'logs',
        name: 'OperationLog',
        component: () => import('../views/OperationLog.vue'),
        meta: { title: '操作日志', titleKey: 'nav.logs', icon: 'Document', roles: ['ADMIN'] }
      },
      {
        path: 'releases',
        name: 'ReleaseNotes',
        component: () => import('../views/ReleaseNotes.vue'),
        meta: { title: '更新日志', titleKey: 'nav.releases', icon: 'Tickets' }
      },
      {
        path: 'feedback',
        name: 'Feedback',
        component: () => import('../views/Feedback.vue'),
        meta: { title: '意见反馈', titleKey: 'nav.feedback', icon: 'ChatDotSquare' }
      },
      {
        path: 'about',
        name: 'About',
        component: () => import('../views/About.vue'),
        meta: { title: '关于', titleKey: 'nav.about', icon: 'InfoFilled' }
      },
      {
        path: 'games',
        name: 'GameCenter',
        component: () => import('../views/games/GameCenter.vue'),
        meta: { title: '游戏', titleKey: 'nav.games', icon: 'Grid' }
      },
      {
        path: 'games/2048',
        name: 'Game2048',
        component: () => import('../views/games/Game2048.vue'),
        meta: { title: '2048', titleKey: 'nav.game2048', icon: 'Aim' }
      },
      {
        path: 'games/gomoku',
        name: 'GameGomoku',
        component: () => import('../views/games/GameGomoku.vue'),
        meta: { title: '五子棋', titleKey: 'gomoku.title', icon: 'Grid' }
      },
      {
        path: 'games/guess',
        name: 'GameGuessNumber',
        component: () => import('../views/games/GameGuessNumber.vue'),
        meta: { title: '猜数字', titleKey: 'guess.title', icon: 'MagicStick' }
      },
      {
        path: 'games/sudoku',
        name: 'GameSudoku',
        component: () => import('../views/games/GameSudoku.vue'),
        meta: { title: '数独', titleKey: 'sudoku.title', icon: 'Grid' }
      },
      {
        path: 'games/one-stroke',
        name: 'GameOneStroke',
        component: () => import('../views/games/GameOneStroke.vue'),
        meta: { title: '一笔画完', titleKey: 'oneStroke.title', icon: 'EditPen' }
      },
      {
        path: 'games/history',
        name: 'GameHistory',
        component: () => import('../views/games/GameHistory.vue'),
        meta: { title: '对局记录', titleKey: 'gameHistory.title', icon: 'DataLine' }
      },
      {
        path: 'operation',
        component: () => import('../views/operation/OperationLayout.vue'),
        meta: { title: '运营管理', titleKey: 'nav.operation', icon: 'TrendCharts', roles: ['ADMIN'] },
        children: [
          { path: '',          redirect: '/operation/overview' },
          { path: 'overview',  name: 'OpOverview',  component: () => import('../views/operation/OpOverview.vue'),   meta: { title: '运营概览', titleKey: 'opNav.overview'  } },
          { path: 'pages',     name: 'OpPages',     component: () => import('../views/operation/OpPages.vue'),      meta: { title: '页面清单', titleKey: 'opNav.pages'     } },
          { path: 'user-journey', name: 'OpUserJourney', component: () => import('../views/operation/OpUserJourney.vue'), meta: { title: '用户旅程', titleKey: 'opNav.userJourney' } },
          { path: 'errors',    name: 'OpErrors',    component: () => import('../views/operation/OpErrors.vue'),     meta: { title: '错误日志', titleKey: 'opNav.errors'    } },
          { path: 'performance', name: 'OpPerformance', component: () => import('../views/operation/OpPerformance.vue'), meta: { title: '性能监控', titleKey: 'opNav.performance' } },
          { path: 'clients',   name: 'OpClients',   component: () => import('../views/operation/OpClients.vue'),    meta: { title: '客户端画像', titleKey: 'opNav.clients'   } }
        ]
      },
      {
        path: 'onboarding',
        name: 'Onboarding',
        component: () => import('../views/Onboarding.vue'),
        meta: { title: '新手指引', titleKey: 'onboarding.title', icon: 'Compass', public: true }
      },
      {
        path: 'profile',
        name: 'ProfileSettings',
        component: () => import('../views/ProfileSettings.vue'),
        meta: { title: '个人信息设置', titleKey: 'nav.profile', icon: 'User' }
      },
      {
        path: 'todos',
        name: 'TodoList',
        component: () => import('../views/TodoList.vue'),
        meta: { title: '我的待办', titleKey: 'nav.todos', icon: 'Memo' }
      },
      {
        path: 'favorites',
        name: 'Favorites',
        component: () => import('../views/Favorites.vue'),
        meta: { title: '我的收藏', titleKey: 'nav.favorites', icon: 'Star' }
      },
      {
        path: 'wiki',
        name: 'MyWiki',
        component: () => import('../views/Wiki.vue'),
        meta: { title: '我的 Wiki', titleKey: 'nav.wiki', icon: 'Notebook' }
      },
      {
        path: 'my-docs',
        name: 'MyDocuments',
        component: () => import('../views/MyDocuments.vue'),
        meta: { title: '我的文档', titleKey: 'nav.myDocs', icon: 'Files' }
      },
      {
        path: 'data-transfer',
        name: 'DataTransfer',
        component: () => import('../views/DataTransfer.vue'),
        meta: { title: '导入导出', titleKey: 'nav.dataTransfer', icon: 'Download' }
      }
    ]
  },
  // Wiki 文章分享页(公开只读,凭令牌访问,无需登录)
  { path: '/wiki/share/:token', name: 'WikiShare', component: () => import('../views/WikiShare.vue'), meta: { title: 'Wiki 分享', titleKey: 'nav.wikiShare', public: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫:
// 1. 设置页面标题
// 2. 公开路由直接放行
// 3. 受保护路由检查登录
// 4. 按 meta.roles 做角色级访问控制(普通用户进不了管理页)
router.beforeEach((to, from, next) => {
  const t = i18n.global.t
  document.title = to.meta.titleKey
    ? `${t(to.meta.titleKey)} - ${t('app.title')}`
    : t('app.title')

  const userStore = useUserStore()

  // 公开路由
  if (to.meta.public) {
    // 已登录用户访问登录/注册页 → 回到各自首页
    if (userStore.isLogin && (to.path === '/login' || to.path === '/register')) {
      return next('/')
    }
    return next()
  }

  // 受保护路由: 检查登录(游客路由放行,个人功能在页面内引导登录)
  if (!userStore.isLogin) {
    if (to.meta.guest) return next()
    // 用户相关页面: 引导登录,并记住来路
    ElMessage.info(t('guard.loginRequired'))
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  // 角色校验: meta.roles 存在时,当前用户需拥有其中之一
  const required = to.meta.roles
  if (Array.isArray(required) && required.length
      && !required.some(r => userStore.roles.includes(r))) {
    ElMessage.warning(t('guard.adminOnly'))
    return next('/learning')
  }

  next()
})

export default router