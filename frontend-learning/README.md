# 前端学习项目 - Vue 3 + Element Plus 🎨

> 配合 [`spring-boot-learning`](../spring-boot-learning/) 项目的 Vue 3 前端实战,演示前后端协作开发模式。

## 📋 项目简介

本项目使用 **Vue 3 (Composition API) + Element Plus + Pinia + Axios + Vite** 构建,是**通用学习平台**的前端:除用户/角色管理等后台功能外,还包含**学习中心**(课程目录 + 课时详情 + 在线 Java/SQL 沙箱)、**学习看板**、**更新日志**(富文本)与**意见反馈**等页面。

## 🚀 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5 | 渐进式 JavaScript 框架 |
| Vue Router | 4.4 | 单页应用路由 |
| Pinia | 2.x | 状态管理 |
| Element Plus | 2.8 | 基于 Vue 3 的 UI 组件库 |
| Axios | 1.7 | HTTP 客户端 |
| Vite | 6.0 | 构建工具 |

## 🎯 涵盖的前端核心内容

### 1. Vue 3 Composition API ⭐
- `<script setup>` 语法
- `ref` / `reactive` 响应式
- `onMounted` 生命周期
- 模板语法 / 指令 / 事件绑定

### 2. Element Plus UI 组件 ⭐
- 表格 / 表单 / 对话框
- 按钮 / 输入框 / 选择器
- 消息提示 / 确认弹窗
- 面包屑 / 菜单 / 卡片

### 3. Vue Router ⭐
- 嵌套路由
- 路由懒加载
- 全局前置守卫
- 路由元信息(meta)

### 4. Axios HTTP 请求 ⭐
- 创建实例
- 请求拦截器
- 响应拦截器
- 错误统一处理

### 5. 前后端协作 ⭐
- Vite 代理(开发环境)
- CORS 跨域(生产环境)
- 统一响应格式
- 表单校验(前后端双重)

## 📂 项目结构

```
frontend-learning/
├── package.json                # 依赖配置
├── vite.config.js              # 构建配置(代理)
├── index.html                  # 入口 HTML
└── src/
    ├── main.js                 # 应用入口(注册 Element Plus、路由、图标)
    ├── App.vue                 # 根组件
    ├── router/
    │   └── index.js            # 路由配置
    ├── api/
    │   └── user.js             # 用户相关 API
    ├── utils/
    │   └── request.js          # axios 封装(拦截器)
    ├── components/
    │   └── Layout.vue          # 整体布局(侧边栏+顶部+内容)
    ├── views/
    │   ├── Home.vue            # 首页(统计卡片)
    │   ├── UserList.vue        # 用户列表(CRUD)
    │   └── About.vue           # 关于
    └── assets/
        └── styles/
            └── main.css        # 全局样式
```

## 🚀 快速开始

### 环境要求
- Node.js 18+
- npm 9+ 或 pnpm / yarn

### 安装与运行

```bash
# 1. 进入项目目录
cd frontend-learning

# 2. 安装依赖
npm install

# 3. 启动开发服务器
npm run dev

# 浏览器自动打开 http://localhost:5173
```

### 同时启动后端

```bash
# 另一个终端
cd ../spring-boot-learning
mvn spring-boot:run
```

### 验证前后端协作

1. 浏览器打开 `http://localhost:5173`
2. 进入"用户管理"页面
3. 看到从后端加载的用户列表(应有 5 个初始用户)
4. 试试新增/编辑/删除用户
5. 打开 DevTools → Network,观察 `/api/users` 请求

## 🌐 跨域问题与解决方案

### 开发环境:Vite 代理 ✅

前端在 `vite.config.js` 中配置代理:

```js
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true
    }
  }
}
```

**原理**: 浏览器看到的请求是 `http://localhost:5173/api/users`,Vite 转发到 `http://localhost:8080/api/users`,对浏览器而言是同源请求。

### 生产环境:CORS 配置

前端部署到 `https://frontend.com`,后端在 `https://api.backend.com`,代理失效,需要在**后端**配置 CORS:

[`spring-boot-learning` 的 CorsConfig.java](../spring-boot-learning/src/main/java/com/learning/springboot/config/CorsConfig.java) 已经实现了 CORS 配置。

## 📦 生产构建

```bash
# 构建生产版本
npm run build

# 输出在 dist/ 目录,部署到 Nginx 等静态服务器
```

## 🎓 学习路径

### 第 1 天:Vue 3 基础
1. 阅读 `App.vue` 和 `main.js`,理解应用启动
2. 阅读 `views/Home.vue`,学习 `<script setup>` 语法
3. 修改 Home.vue 的内容,看看响应式如何工作

### 第 2 天:路由与布局
1. 阅读 `router/index.js`,理解路由配置
2. 阅读 `components/Layout.vue`,学习整体布局
3. 添加一个新的菜单项和视图

### 第 3 天:HTTP 请求
1. 阅读 `utils/request.js`,理解 axios 拦截器
2. 阅读 `api/user.js`,学习 API 集中管理
3. 修改拦截器,加上 token 认证

### 第 4 天:CRUD 实战
1. 阅读 `views/UserList.vue`,完整 CRUD 实现
2. 学习 Element Plus 组件用法
3. 添加"分页"、"批量删除"功能

### 第 5 天+:扩展
- 引入状态管理进阶用法(项目已用 Pinia)
- 接入 VueUse 等工具库
- 添加 Echarts 数据可视化
- 接入 Swagger 接口文档

## 🧪 推荐的调试技巧

1. **Vue DevTools** 浏览器扩展,查看组件树、响应式数据
2. **DevTools Network** 观察 API 请求和响应
3. **VS Code Volar 插件** Vue 3 官方推荐的 IDE 插件
4. **Console.log** 在 `utils/request.js` 加日志,观察请求流程

## 🔧 常用命令

```bash
npm install         # 安装依赖
npm run dev         # 开发模式
npm run build       # 生产构建
npm run preview     # 预览生产构建
```

## 🎯 项目亮点

- ✅ 完整的 CRUD(增删改查)
- ✅ 前后端分离架构
- ✅ 统一响应格式(后端 `{code, message, data}`)
- ✅ 前端表单校验 + 后端 @Valid 双重校验
- ✅ 全局异常处理(后端 `@RestControllerAdvice` + 前端响应拦截器)
- ✅ 友好的 UI(Element Plus)
- ✅ 响应式设计
- ✅ 加载状态、错误提示完整
- ✅ 学习中心 + 在线 Java/SQL 沙箱(Monaco 编辑器)
- ✅ 更新日志富文本(Quill)、意见反馈与评分

## 🎓 学完后可以做什么

- 🛒 电商后台管理系统
- 📝 博客系统(前后端)
- 💬 IM 即时通讯
- 📊 数据可视化平台
- 🏢 OA / CRM / ERP 企业级应用

---

**返回**: [Java 学习项目总目录](../README.md)