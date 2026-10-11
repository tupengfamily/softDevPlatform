# 通用学习平台 📚

> **Java 全栈学习一体化项目**:结构化课程 + 在线代码沙箱 + 学习进度跟踪 + 后台管理系统,支持 Docker 一键部署。内置 Java 全栈学习路线(零基础 → Spring Boot 实战 → Vue 3 前端),亦可作为通用的在线学习平台扩展其他课程。

## 📋 项目简介

本项目由三大模块 + Docker 编排组成:

- **`java-learning/`** (根模块) — Java 零基础到核心的 16 章系统学习示例代码 + `docs/` 详细学习文档。
- **`spring-boot-learning/`** — Spring Boot 3.3 实战:后台管理系统(JWT 鉴权 + RBAC + 操作日志 + 数据字典 + Dashboard)+ **学习平台 API**(课程/进度/更新公告/反馈)+ **Java/SQL 在线沙箱**。
- **`frontend-learning/`** — Vue 3 + Element Plus + Pinia 后台前端,含**学习中心**、课程详情、学习看板、更新日志、意见反馈等页面。
- **`docker-compose.yml` / `DOCKER.md`** — 一条命令部署整个平台(详见 [DOCKER.md](DOCKER.md))。

## 📖 学习平台

登录后台后即可使用完整学习功能:

### 结构化课程体系
**8 大阶段 · 39 章 · 112 课时**,从 Hello World 一路学到框架生态与综合项目实战,每课含知识讲解 + 示例代码 + 对应练习位置:

| 阶段 | 内容 | 章/课时 |
|------|------|---------|
| 1. 入门基础 | Java 入门、数据类型、控制流程、数组、方法 | 6 章 / 13 课时 |
| 2. 核心基础 | 面向对象、字符串、集合框架、异常处理 | 4 章 / 13 课时 |
| 3. 进阶特性 | I/O、泛型、枚举注解、Lambda/Stream、日期、反射等 | 8 章 / 20 课时 |
| 4. 并发编程 | 多线程、线程池、JUC、锁与并发工具 | 4 章 / 14 课时 |
| 5. 数据库与 JDBC | SQL 基础、JDBC、事务、连接池 | 6 章 / 16 课时 |
| 6. 工程化与设计 | Maven、单元测试、设计模式、日志 | 5 章 / 19 课时 |
| 7. Web 与框架生态 | HTTP/Servlet、Spring、Spring Boot、MyBatis | 4 章 / 12 课时 |
| 8. 综合实战 | 综合项目实战 | 2 章 / 5 课时 |

### 核心能力
- **在线代码沙箱**:课时内可直接在线运行 Java 代码(实时编译执行、超时保护、编译错误定位)与 SQL 练习(独立教学库、一键重置),无需本地安装环境。
- **完整学习进度跟踪**:总进度百分比、分阶段进度条、章节完成率、最近学习时间线;目录里可一键勾选"已学",课时页支持"学完,下一课"连续学习。
- **学习看板**:全员学习进度总览(管理员)与个人进度统计。
- **进度按用户隔离**:复用后台 JWT 登录,每个账号都有独立的学习进度。
- **更新公告与更新日志**:管理员富文本发布版本公告(登录后弹窗提醒,自动去重),更新日志按版本时间线展示,支持编辑/删除。
- **意见反馈与系统评分**:用户可提交评分(星级)与 bug 反馈,管理员分页处理并回复,首页展示平均评分。
- **技术实现**:后端 `LearningChapter / LearningLesson / LessonProgress` 等实体 + REST API;课程内容由 `lessons.json` 启动时自动导入(幂等);前端 `LearningCenter.vue` + `LessonDetail.vue`(Monaco 编辑器)+ `SqlConsole.vue` 等。

## 🖥️ 后台管理系统

- **JWT 登录鉴权** + **RBAC 角色权限**(ADMIN / USER,接口级 `@RequireAdmin` 校验)
- **用户管理 / 角色管理**:表格操作列图标按钮 + 悬浮提示,改密、分配角色、启禁用
- **操作日志**:AOP 自动记录关键操作;**数据字典**、**Dashboard 数据看板**
- **系统设置 / 关于页面**;菜单分组折叠、侧栏收纳(状态持久化)

## 🚀 快速启动

### 方式一:本地开发(推荐学习时使用)

```bash
./start-all.sh
# 后端: http://localhost:8080   前端: http://localhost:5173
```

环境要求:JDK 21+、Maven 3.6+、Node.js 18+。停止服务:./stop-all.sh

### 方式二:Docker 一键部署

```bash
docker compose up -d --build
# 访问: http://localhost (默认 80 端口)
# 端口被占用时: HTTP_PORT=8088 docker compose up -d --build
```

SQLite 数据持久化在 `./data`,重建容器不丢数据。更多说明见 [DOCKER.md](DOCKER.md)。

### 默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| `admin` | `admin123` | 管理员(全部权限) |
| `user` | `user123` | 普通用户(只读权限) |

> 生产环境请立即修改默认密码。

## 📂 项目结构

```
java/
├── README.md                         # 本文档
├── docker-compose.yml                # Docker 一键部署编排
├── DOCKER.md                         # Docker 部署说明
├── start-all.sh / stop-all.sh        # 本地一键启停
├── java-learning/                    # 根模块: Java 基础示例(JDK21 + Maven)
│   ├── docs/                         # 详细学习文档(基础语法/OOP/集合/异常/高级特性)
│   └── src/main/java/com/learning/   # 16 章示例代码 + 综合项目 + 单元测试
├── spring-boot-learning/             # 后端: Spring Boot 3.3 后台管理系统 + 学习平台 API
│   ├── src/main/java/.../controller/ # REST 接口(用户/角色/学习/公告/反馈/沙箱…)
│   ├── src/main/java/.../bootstrap/  # 种子数据初始化(课程/账号/公告)
│   └── src/main/resources/data/      # lessons.json 课程数据(39 章 112 课时)
└── frontend-learning/                # 前端: Vue3 + Element Plus + Pinia + Vite
    └── src/views/                    # 登录/学习中心/课程详情/看板/更新日志/反馈/系统管理
```

## 📚 Java 基础学习路线(根模块,16 章 + 综合项目)

| 阶段 | 章节 | 内容 | 代码位置 | 预计时长 |
|------|------|------|----------|----------|
| **入门** | 第 1 章 | Java 入门与 Hello World | `basics/HelloWorld` | 1 天 |
| **基础** | 第 2 章 | 数据类型与变量 | `basics/DataTypes` | 2 天 |
| **基础** | 第 3 章 | 运算符 | `basics/Operators` | 1 天 |
| **基础** | 第 4 章 | 控制流程(if/switch/for/while) | `basics/ControlFlow` | 2 天 |
| **基础** | 第 5 章 | 数组 | `basics/ArraysDemo` | 1 天 |
| **基础** | 第 6 章 | 方法 | `basics/MethodsDemo` | 2 天 |
| **核心** | 第 7 章 | ⭐ 面向对象(OOP) | `oop/*` | 5 天 |
| **核心** | 第 8 章 | 字符串 | `string/StringDemo` | 2 天 |
| **核心** | 第 9 章 | ⭐ 集合框架(List/Set/Map) | `collection/*` | 4 天 |
| **核心** | 第 10 章 | 异常处理 | `exception/*` | 2 天 |
| **进阶** | 第 11 章 | I/O 流与文件 | `io/FileIODemo` | 2 天 |
| **进阶** | 第 12 章 | 泛型 | `generic/GenericDemo` | 2 天 |
| **进阶** | 第 13 章 | 枚举与注解 | `enumannotation/*` | 2 天 |
| **进阶** | 第 14 章 | Lambda 与 Stream API | `lambda/*` | 3 天 |
| **进阶** | 第 15 章 | 日期时间 API | `datetime/DateTimeDemo` | 1 天 |
| **进阶** | 第 16 章 | 反射 | `reflection/ReflectionDemo` | 2 天 |
| **实战** | 综合项目 | ⭐ 学生管理系统 | `project/*` | 3 天 |

### 运行根模块示例

```bash
cd java-learning
mvn clean compile
mvn exec:java -Dexec.mainClass="com.learning.basics.HelloWorld"
mvn test        # 运行全部单元测试(JUnit 5)
```

> 完整学习内容请在**学习中心**按课时学习(含在线运行),根模块示例代码作为配套练习材料。

## 🎯 推荐学习方式

1. **按课程顺序(推荐)**:登录平台 → 学习中心按 8 阶段顺序学习,每课"看讲解 → 在线运行/改代码 → 勾选已学"。
2. **项目驱动**:直接进入 `java-learning/` 的 `project/` 阅读学生管理系统,遇到不懂的概念回头查对应章节。
3. **敲代码**:不要只看不练,每个示例都要亲手敲一遍并尝试修改。
4. **做项目**:学完基础后,尝试扩展学生管理系统或自拟小项目。

## 📖 推荐学习资源

### 中文
- [菜鸟教程 Java](https://www.runoob.com/java/java-tutorial.html)
- [廖雪峰 Java 教程](https://www.liaoxuefeng.com/wiki/1252599548343744)
- [尚硅谷 Java 教程(Bilibili)](https://www.bilibili.com/video/BV1Kb411W75N)

### 英文
- [Oracle Java Tutorials](https://docs.oracle.com/javase/tutorial/)
- [Baeldung](https://www.baeldung.com/)

## 🎓 学习完成后

掌握了本项目的所有内容后,你可以继续学习:
- 🔌 JDBC 与数据库编程进阶
- 🌱 Spring Cloud 微服务
- 🔧 Maven / Gradle 进阶
- 🏗️ 设计模式深入
- 🌐 网络编程(NIO / Netty)
- ⚡ 并发编程进阶(JUC 源码)
- 🧪 单元测试进阶(Mockito、Testcontainers)
- 🐳 Kubernetes 与云原生

---

**祝你学习顺利,早日成为 Java 大佬! 🚀**

如有问题,欢迎在「意见反馈」中提交,或提 Issue 讨论。
