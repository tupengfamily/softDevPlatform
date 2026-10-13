<script setup>
// 关于页(业界通用 About 结构):
// Hero(产品名 + 版本徽章 + slogan + 快捷入口) -> 核心能力矩阵 -> 技术栈徽章 -> 快速开始 -> 折叠详情(项目结构)
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getLatestRelease } from '../api/release'

const router = useRouter()
const { t } = useI18n()
const version = ref('')

// 核心能力(产品视角,而非实现细节)
const features = computed(() => [
  { icon: 'Reading',      title: t('about.feature.course.title'),   desc: t('about.feature.course.desc') },
  { icon: 'Monitor',      title: t('about.feature.sandbox.title'),  desc: t('about.feature.sandbox.desc') },
  { icon: 'Grid',         title: t('about.feature.sql.title'),      desc: t('about.feature.sql.desc') },
  { icon: 'TrendCharts',  title: t('about.feature.board.title'),    desc: t('about.feature.board.desc') },
  { icon: 'ChatLineRound',title: t('about.feature.forum.title'),    desc: t('about.feature.forum.desc') },
  { icon: 'Bell',         title: t('about.feature.notify.title'),   desc: t('about.feature.notify.desc') }
])

// 技术栈(按分组展示为徽章)
const stackGroups = computed(() => [
  {
    name: t('about.stack.backend'),
    items: ['Java 21 LTS', 'Spring Boot 3.3', 'Spring Data JPA', 'JJWT 0.12 (HS256)', 'BCrypt', t('about.stack.aopLog'), 'SQLite 3.46']
  },
  {
    name: t('about.stack.frontend'),
    items: ['Vue 3.5', 'Vite 6', 'Pinia 2.2', 'Element Plus 2.8', 'Axios 1.7', 'Monaco Editor', 'marked + DOMPurify', t('about.stack.quill')]
  },
  {
    name: t('about.stack.arch'),
    items: [t('about.separation'), t('about.stack.jwt'), t('about.stack.rbac'), 'RESTful API', t('about.stack.globalEx'), t('about.stack.docker')]
  }
])

onMounted(async () => {
  try {
    const res = await getLatestRelease()
    if (res.data?.version) version.value = res.data.version
  } catch (e) { /* 静默 */ }
})
</script>

<template>
  <div class="page">
    <!-- Hero -->
    <div class="about-hero">
      <img class="hero-logo" src="/favicon.svg" alt="" aria-hidden="true">
      <h1>{{ $t('about.title') }}</h1>
      <div class="tag-row">
        <span v-if="version" class="chip accent">v{{ version }}</span>
        <span class="chip">Spring Boot 3</span>
        <span class="chip">Vue 3</span>
        <span class="chip">SQLite</span>
        <span class="chip">{{ $t('about.separation') }}</span>
      </div>
      <p class="slogan">{{ $t('about.slogan') }}</p>
      <div class="hero-actions">
        <el-button type="primary" @click="router.push('/releases')"><el-icon><Tickets /></el-icon>&nbsp;{{ $t('about.releases') }}</el-button>
        <el-button @click="router.push('/forum')"><el-icon><ChatLineRound /></el-icon>&nbsp;{{ $t('about.forum') }}</el-button>
        <el-button @click="router.push('/feedback')"><el-icon><ChatDotSquare /></el-icon>&nbsp;{{ $t('about.feedback') }}</el-button>
      </div>
    </div>

    <!-- 核心能力 -->
    <section class="section">
      <h2 class="sec-title">{{ $t('about.featuresTitle') }}</h2>
      <div class="feature-grid">
        <div v-for="f in features" :key="f.title" class="feature">
          <div class="f-icon"><el-icon :size="22"><component :is="f.icon" /></el-icon></div>
          <div class="f-title">{{ f.title }}</div>
          <div class="f-desc">{{ f.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 技术栈 -->
    <section class="section">
      <h2 class="sec-title">{{ $t('about.stackTitle') }}</h2>
      <div class="stack-card">
        <div v-for="g in stackGroups" :key="g.name" class="stack-group">
          <div class="stack-name">{{ g.name }}</div>
          <div class="chips">
            <span v-for="it in g.items" :key="it" class="chip">{{ it }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 快速开始 -->
    <section class="section">
      <h2 class="sec-title">{{ $t('about.quickStart') }}</h2>
      <div class="quick-grid">
        <div class="quick-card">
          <h3>{{ $t('about.defaultAccounts') }}</h3>
          <table class="kv-table">
            <thead><tr><th>{{ $t('about.col.username') }}</th><th>{{ $t('about.col.role') }}</th></tr></thead>
            <tbody>
              <tr><td>admin</td><td><el-tag size="small" type="danger" effect="plain">{{ $t('about.role.admin') }}</el-tag></td></tr>
              <tr><td>user</td><td><el-tag size="small" type="success" effect="plain">{{ $t('about.role.user') }}</el-tag></td></tr>
            </tbody>
          </table>
          <p class="tip">{{ $t('about.accountTip') }}</p>
        </div>
        <div class="quick-card">
          <h3>{{ $t('about.localRun') }}</h3>
          <pre class="code-block"># 后端 (默认 8080)
cd spring-boot-learning
mvn spring-boot:run

# 前端 (默认 5173)
cd frontend-learning
npm install &amp;&amp; npm run dev</pre>
        </div>
      </div>
    </section>

    <!-- 项目结构(折叠) -->
    <section class="section">
      <el-collapse class="struct-collapse">
        <el-collapse-item :title="$t('about.structTitle')" name="struct">
          <pre class="code-block">java/
├── spring-boot-learning/        # 后端
│   ├── pom.xml                  # SQLite + JWT + BCrypt
│   └── src/main/java/com/learning/springboot/
│       ├── entity/              # User / Role / Lesson / Forum / Notification ...
│       ├── repository/          # 派生 + JPQL + 原生 SQL 三种查询
│       ├── security/            # JwtUtil + JwtAuthFilter + CurrentUser
│       ├── service/             # 业务层(学习/论坛/消息/沙箱)
│       ├── controller/          # REST API
│       ├── aop/                 # OperationLogAspect
│       ├── annotation/          # @OperationLog / @RequireAdmin
│       ├── exception/           # GlobalExceptionHandler
│       ├── bootstrap/           # 数据种子(幂等初始化)
│       └── sandbox/             # Java / SQL 沙箱执行器
└── frontend-learning/           # 前端
    └── src/
        ├── store/               # Pinia(user / subject / notification)
        ├── api/                 # auth/learning/forum/notifications/...
        ├── components/          # Layout / ContentEditor / CodeEditor / SqlConsole
        ├── router/index.js      # 路由 + 角色守卫
        └── views/               # 学习中心 / 看板 / 论坛 / 消息 / 管理后台</pre>
        </el-collapse-item>
      </el-collapse>
    </section>

    <el-alert
      :title="$t('about.notice')"
      type="warning"
      show-icon
      :closable="false"
    />

    <footer class="foot">{{ $t('about.footer') }}</footer>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px 40px; }

/* ============ Hero ============ */
.about-hero {
  text-align: center; padding: 42px 32px 38px;
  background: linear-gradient(120deg, #eef4ff 0%, #f6f1ff 55%, #fff 100%);
  border: 1px solid #e8edf9; border-radius: var(--r-l); margin-bottom: 28px;
}
.hero-logo {
  width: 60px; height: 60px; margin: 0 auto 14px; border-radius: 16px;
  background: linear-gradient(135deg, #2563eb, #4f46e5); /* 图标加载失败的兜底 */
  display: block;
  box-shadow: 0 8px 20px rgba(37, 99, 235, .3);
}
.about-hero h1 { margin: 0 0 12px; font-size: 28px; letter-spacing: .5px; color: var(--c-text-1); }
.tag-row { display: flex; justify-content: center; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.slogan { margin: 0 0 20px; color: var(--c-text-2); font-size: var(--fs-md); }
.hero-actions { display: flex; justify-content: center; gap: 10px; }

/* ============ 徽章 / 芯片 ============ */
.chip {
  display: inline-block; padding: 4px 12px; border-radius: var(--r-full);
  background: #fff; border: 1px solid var(--c-border); color: var(--c-text-2);
  font-size: var(--fs-xs); white-space: nowrap;
}
.chip.accent {
  background: linear-gradient(135deg, #2563eb, #4f46e5); color: #fff; border-color: transparent; font-weight: 600;
}

/* ============ 区块 ============ */
.section { margin-bottom: 26px; }
.sec-title {
  margin: 0 0 14px; font-size: var(--fs-lg); font-weight: 600; color: var(--c-text-1);
  padding-left: 10px; border-left: 3px solid var(--c-primary);
}

/* 核心能力 */
.feature-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.feature {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 18px 18px 16px; transition: transform .15s, box-shadow .15s;
}
.feature:hover { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(20, 30, 60, .08); }
.f-icon {
  width: 40px; height: 40px; border-radius: 10px; margin-bottom: 10px;
  background: var(--c-primary-bg); color: var(--c-primary);
  display: flex; align-items: center; justify-content: center;
}
.f-title { font-size: var(--fs-md); font-weight: 600; color: var(--c-text-1); margin-bottom: 5px; }
.f-desc { font-size: var(--fs-sm); color: var(--c-text-2); line-height: 1.65; }

/* 技术栈 */
.stack-card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 6px 20px; }
.stack-group { display: flex; align-items: baseline; gap: 16px; padding: 14px 0; border-bottom: 1px solid #f1f3f8; }
.stack-group:last-child { border-bottom: none; }
.stack-name { flex: none; width: 92px; font-size: var(--fs-sm); font-weight: 600; color: var(--c-text-1); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }

/* 快速开始 */
.quick-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.quick-card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 18px 20px; }
.quick-card h3 { margin: 0 0 12px; font-size: var(--fs-md); color: var(--c-text-1); }
.kv-table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.kv-table th, .kv-table td { border: 1px solid var(--c-border); padding: 7px 12px; text-align: left; }
.kv-table th { background: #f8fafd; color: var(--c-text-2); font-weight: 600; }
.kv-table code { background: #f1f4f9; color: #b93a86; padding: 1px 6px; border-radius: 4px; font-size: var(--fs-xs); }
.tip { margin: 10px 0 0; font-size: var(--fs-xs); color: var(--c-text-3); line-height: 1.6; }

/* 代码块 */
.code-block {
  margin: 0; background: #0f172a; color: #e2e8f0; border-radius: var(--r-s);
  padding: 14px 16px; font-family: 'JetBrains Mono', Menlo, monospace;
  font-size: var(--fs-xs); line-height: 1.7; overflow-x: auto;
}
.struct-collapse :deep(.el-collapse-item__header) { padding: 0 16px; font-size: var(--fs-sm); color: var(--c-text-1); }
.struct-collapse :deep(.el-collapse-item__content) { padding: 12px 16px 16px; }

.foot { margin-top: 26px; text-align: center; font-size: var(--fs-xs); color: var(--c-text-3); }

@media (max-width: 900px) {
  .feature-grid { grid-template-columns: repeat(2, 1fr); }
  .quick-grid { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .feature-grid { grid-template-columns: 1fr; }
}
</style>
