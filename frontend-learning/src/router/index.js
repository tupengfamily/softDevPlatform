import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'

// 公开路由(登录、注册)— 无需 token
// 游客路由(meta.guest: 学习中心/课时详情)— 未登录可浏览,个人操作在页面内引导登录
// 受保护路由(在 Layout 下)— 必须登录
// meta.roles: 限定可访问的角色(缺省=所有登录用户);ADMIN 专属页面在守卫中统一拦截
const routes = [
  { path: '/login',    component: () => import('../views/Login.vue'),    meta: { title: '登录', public: true } },
  { path: '/register', component: () => import('../views/Register.vue'), meta: { title: '注册', public: true } },

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
        meta: { title: '仪表盘', icon: 'DataAnalysis', roles: ['ADMIN'] }
      },
      {
        path: 'learning',
        name: 'LearningCenter',
        component: () => import('../views/LearningCenter.vue'),
        meta: { title: '学习中心', icon: 'Reading', guest: true }
      },
      {
        path: 'learning/lesson/:id',
        name: 'LessonDetail',
        component: () => import('../views/LessonDetail.vue'),
        meta: { title: '课时学习', icon: 'Reading', guest: true }
      },
      {
        path: 'board',
        name: 'LearningBoard',
        component: () => import('../views/LearningBoard.vue'),
        meta: { title: '学习看板', icon: 'TrendCharts' }
      },
      {
        path: 'learning-config',
        name: 'LearningConfig',
        component: () => import('../views/LearningConfig.vue'),
        meta: { title: '学习配置', icon: 'Setting', roles: ['ADMIN'] }
      },
      {
        path: 'notifications',
        name: 'Notifications',
        component: () => import('../views/Notifications.vue'),
        meta: { title: '消息中心', icon: 'Bell' }
      },
      {
        path: 'forum',
        name: 'Forum',
        component: () => import('../views/Forum.vue'),
        meta: { title: '论坛专区', icon: 'ChatLineRound' }
      },
      {
        path: 'forum/:id',
        name: 'ForumDetail',
        component: () => import('../views/ForumDetail.vue'),
        meta: { title: '帖子详情', icon: 'ChatLineRound' }
      },
      {
        path: 'users',
        name: 'UserManagement',
        component: () => import('../views/UserManagement.vue'),
        meta: { title: '用户管理', icon: 'User', roles: ['ADMIN'] }
      },
      {
        path: 'roles',
        name: 'RoleManagement',
        component: () => import('../views/RoleManagement.vue'),
        meta: { title: '角色管理', icon: 'UserFilled', roles: ['ADMIN'] }
      },
      {
        path: 'dicts',
        name: 'DictManagement',
        component: () => import('../views/DictManagement.vue'),
        meta: { title: '数据字典', icon: 'Collection', roles: ['ADMIN'] }
      },
      {
        path: 'logs',
        name: 'OperationLog',
        component: () => import('../views/OperationLog.vue'),
        meta: { title: '操作日志', icon: 'Document', roles: ['ADMIN'] }
      },
      {
        path: 'releases',
        name: 'ReleaseNotes',
        component: () => import('../views/ReleaseNotes.vue'),
        meta: { title: '更新日志', icon: 'Tickets' }
      },
      {
        path: 'feedback',
        name: 'Feedback',
        component: () => import('../views/Feedback.vue'),
        meta: { title: '意见反馈', icon: 'ChatDotSquare' }
      },
      {
        path: 'about',
        name: 'About',
        component: () => import('../views/About.vue'),
        meta: { title: '关于', icon: 'InfoFilled' }
      }
    ]
  },
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
  document.title = to.meta.title
    ? `${to.meta.title} - 通用学习平台`
    : '通用学习平台'

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
    ElMessage.info('请先登录后再访问该功能')
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  // 角色校验: meta.roles 存在时,当前用户需拥有其中之一
  const required = to.meta.roles
  if (Array.isArray(required) && required.length
      && !required.some(r => userStore.roles.includes(r))) {
    ElMessage.warning('该页面仅管理员可访问')
    return next('/learning')
  }

  next()
})

export default router