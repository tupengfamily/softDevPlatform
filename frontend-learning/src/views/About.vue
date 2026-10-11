<script setup>
const features = [
  { title: 'JWT 无状态鉴权', desc: 'BCrypt 密码 + JJWT 签发 token,前端 axios 自动注入', icon: 'Lock' },
  { title: 'RBAC 角色权限', desc: '用户-角色-权限三表多对多,前端按角色过滤菜单', icon: 'UserFilled' },
  { title: '操作日志 AOP', desc: '@OperationLog 注解 + 切面,自动记录所有写操作', icon: 'Document' },
  { title: '数据字典', desc: '类型 + 项的两级结构,SQLite 级联删除', icon: 'Collection' },
  { title: '仪表盘', desc: '原生 SQL 聚合统计,自绘柱状图不引入 ECharts', icon: 'DataAnalysis' },
  { title: 'SQLite 持久化', desc: '文件型数据库 ./data/admin.db,重启不丢数据', icon: 'Coin' }
]

const stack = [
  { name: 'Java',         version: '21 LTS',    desc: '开发语言' },
  { name: 'Spring Boot',  version: '3.3.5',     desc: '应用框架' },
  { name: 'Spring Data JPA', version: '3.x',     desc: 'ORM + 派生/JPQL/原生 SQL' },
  { name: 'JJWT',         version: '0.12.6',    desc: 'JWT 签发与校验 (HS256)' },
  { name: 'BCrypt',       version: 'spring-security-crypto', desc: '密码哈希' },
  { name: 'SQLite',       version: '3.46',      desc: '文件型数据库' },
  { name: 'Vue 3',        version: '3.5',       desc: '前端框架 (Composition API)' },
  { name: 'Pinia',        version: '2.2',       desc: 'Vue 3 状态管理' },
  { name: 'Element Plus', version: '2.8',       desc: 'UI 组件库' },
  { name: 'Axios',        version: '1.7',       desc: 'HTTP 客户端 (拦截器注入 Token)' },
  { name: 'Vite',         version: '6.0',       desc: '下一代构建工具' }
]
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">关于本项目</div>
      <div class="page-subtitle">通用学习平台 · 前后端分离架构</div>
    </div>

    <el-card shadow="never" style="margin-bottom:20px;">
      <h3>📚 项目简介</h3>
      <p>本项目基于 Spring Boot 3 + Vue 3 + Element Plus + SQLite 实现的<strong>通用学习平台</strong>,课程内容不限于单一技术方向。</p>
      <p>覆盖了 Java 企业开发的常见环节 — JPA 三种查询、事务、连接池调优、AOP、安全、异常、缓存、字典、审计、Dashboard。</p>
    </el-card>

    <h3 style="margin: 20px 0;">✨ 核心特性</h3>
    <el-row :gutter="16">
      <el-col :span="8" v-for="f in features" :key="f.title" style="margin-bottom:16px;">
        <el-card shadow="hover" class="feature-card">
          <div class="feature-icon"><el-icon size="28" color="#2563eb"><component :is="f.icon" /></el-icon></div>
          <div class="feature-title">{{ f.title }}</div>
          <div class="feature-desc">{{ f.desc }}</div>
        </el-card>
      </el-col>
    </el-row>

    <h3 style="margin: 20px 0;">🛠 技术栈</h3>
    <el-table :data="stack" border>
      <el-table-column label="技术" prop="name" width="180" />
      <el-table-column label="版本" prop="version" width="180" />
      <el-table-column label="说明" prop="desc" />
    </el-table>

    <h3 style="margin: 20px 0;">🚀 默认账号</h3>
    <el-table :data="[
      { u: 'admin', p: 'admin123', r: 'ADMIN' },
      { u: 'user',  p: 'user123',  r: 'USER' }
    ]" border>
      <el-table-column prop="u" label="用户名" width="160" />
      <el-table-column prop="p" label="密码" width="160" />
      <el-table-column prop="r" label="角色" width="120" />
    </el-table>

    <h3 style="margin: 20px 0;">📂 项目结构</h3>
    <pre class="code-block">java/
├── spring-boot-learning/        # 后端
│   ├── pom.xml                  # SQLite + JWT + BCrypt
│   └── src/main/java/com/learning/springboot/
│       ├── entity/              # User / Role / Permission / Dict / OpLog
│       ├── repository/          # 派生 + JPQL + 原生 SQL 三种查询
│       ├── security/            # JwtUtil + JwtAuthFilter + CurrentUser
│       ├── service/             # 业务层
│       ├── controller/          # REST API
│       ├── aop/                 # OperationLogAspect
│       ├── annotation/          # @OperationLog
│       ├── exception/           # GlobalExceptionHandler
│       ├── bootstrap/           # AdminBootstrap (CommandLineRunner)
│       └── config/              # JwtProperties
└── frontend-learning/           # 前端
    └── src/
        ├── store/user.js        # Pinia
        ├── api/                 # auth/user/role/permission/dict/log/dashboard
        ├── router/index.js      # 路由 + 守卫
        └── views/               # Login/Register/Dashboard/User/Role/Dict/Log</pre>

    <el-alert
      title="注意: 默认账号仅用于本地学习,生产环境务必修改密码并使用 HTTPS + httpOnly Cookie"
      type="warning"
      show-icon
      :closable="false"
      style="margin-top:16px;"
    />
  </div>
</template>

<style scoped>
.feature-card { height: 100%; text-align: center; padding: 16px 0; }
.feature-icon { margin-bottom: 8px; }
.feature-title { font-size: 15px; font-weight: 500; margin-bottom: 4px; }
.feature-desc { font-size: 13px; color: #6b7280; line-height: 1.5; }
.code-block {
  background: #f5f7fa; border: 1px solid #ebeef5; border-radius: var(--r-s);
  padding: 16px; font-family: 'Courier New', Courier, monospace;
  font-size: 12px; line-height: 1.6; color: #303133; overflow-x: auto;
}
</style>