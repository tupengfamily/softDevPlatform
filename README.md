# Java 全栈学习项目 ☕

> Java 零基础学习 + Spring Boot 实战 + Vue 3 后台管理系统的**一体化学习项目**,内置**学习平台**——结构化课程 + 逐人学习进度跟踪。

## 📋 项目简介

本项目由三大模块组成:
- **`java-learning/`** (根模块) — Java 零基础到核心的 13 章系统学习。
- **`spring-boot-learning/`** — Spring Boot 3.3 实战: **后台管理系统**(JWT 鉴权 + RBAC + 操作日志 + 数据字典 + Dashboard + **学习平台 API**)。
- **`frontend-learning/`** — Vue 3 + Element Plus + Pinia 后台前端,含**学习中心**页面。

## 📖 学习平台(2026-10 新增)

登录后台后,左侧菜单新增 **学习中心**:

- **结构化课程体系**:4 大阶段(入门基础 → 核心基础 → 进阶特性 → 综合实战)· 17 章 · 40 课时,从 Hello World 一路学到反射与综合项目实战,每课含知识讲解 + 示例代码 + 对应练习位置。
- **完整学习进度跟踪**:总进度百分比、分阶段进度条、章节完成率、最近学习时间线;目录里可一键勾选"已学",课时页支持"学完,下一课"连续学习。
- **进度按用户隔离**:复用后台 JWT 登录,每个账号都有独立的学习进度(user 和 admin 各学各的)。
- **技术实现**:后端 `LearningChapter / LearningLesson / LessonProgress` 三张表 + `LearningController` 五个接口;课程内容由 `lessons.json` 启动时自动导入(幂等);前端 `LearningCenter.vue` + `LessonDetail.vue`。

## 🚀 快速启动

```bash
./start-all.sh
# 默认账号: admin / admin123 (ADMIN 角色)
#           user  / user123  (USER 角色)
# 浏览器: http://localhost:5173
```

详细文档见各子模块的 `README.md` 和 `docs/`。

## 🚀 快速开始

### 环境要求

- JDK 21 或更高版本
- Maven 3.6+
- 推荐 IDE: IntelliJ IDEA(社区版即可)

### 安装与运行

```bash
# 1. 验证环境
java -version    # 应显示 21.x
mvn -version     # 应显示 3.6+

# 2. 编译项目
mvn clean compile

# 3. 运行某个示例(以 HelloWorld 为例)
mvn exec:java -Dexec.mainClass="com.learning.basics.HelloWorld"

# 或在 IDEA 中直接右键运行 main 方法

# 4. 运行所有单元测试
mvn test
```

## 📚 学习路线(共 13 章)

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

## 🎯 推荐学习方式

### 方法 1: 按章节顺序(推荐)

每天学习 1-2 个章节,**先看代码 → 动手运行 → 修改实验 → 完成测试**。

### 方法 2: 项目驱动

直接进入 `project/` 目录,阅读学生管理系统,遇到不懂的概念再回头查前面的章节。

## 📂 项目结构

```
java-learning/
├── pom.xml                           # Maven 配置
├── README.md                         # 本文档
├── .gitignore                        # Git 忽略
├── docs/                             # 详细学习文档
│   ├── 01-基础语法.md
│   ├── 02-面向对象.md
│   ├── 03-集合框架.md
│   ├── 04-异常处理.md
│   └── 05-高级特性.md
└── src/
    ├── main/java/com/learning/
    │   ├── basics/                   # 第 1-6 章:基础语法
    │   ├── oop/                      # 第 7 章:面向对象
    │   ├── string/                   # 第 8 章:字符串
    │   ├── collection/               # 第 9 章:集合
    │   ├── exception/                # 第 10 章:异常
    │   ├── io/                       # 第 11 章:I/O
    │   ├── generic/                  # 第 12 章:泛型
    │   ├── enumannotation/           # 第 13 章:枚举与注解
    │   ├── lambda/                   # 第 14 章:Lambda/Stream
    │   ├── datetime/                 # 第 15 章:日期时间
    │   ├── reflection/               # 第 16 章:反射
    │   └── project/                  # 综合实战项目
    │       ├── model/                # 数据模型
    │       ├── service/              # 业务逻辑
    │       └── Main.java             # 入口
    └── test/java/com/learning/       # 单元测试
        ├── basics/
        ├── oop/
        └── collection/
```

## ⭐ 重点章节

### 第 7 章:面向对象(OOP)

Java 是纯粹的面向对象语言,这一章是**最重要的**。

**核心概念:**
- 封装: 用 `private` 修饰字段,提供 `getter/setter`
- 继承: 用 `extends`,子类复用父类代码
- 多态: 父类引用指向子类对象,运行时动态绑定
- 抽象类: `abstract class`,定义模板
- 接口: `interface`,定义行为契约

### 第 9 章:集合框架

实际开发中最常用,需要熟练掌握。

**对比表:**

| 类型 | 实现 | 特点 | 场景 |
|------|------|------|------|
| List | ArrayList | 数组实现,查询快 | 多数场景 |
| List | LinkedList | 链表实现,增删快 | 频繁增删 |
| Set | HashSet | 哈希表,无序 | 去重 |
| Set | TreeSet | 红黑树,有序 | 需要排序 |
| Map | HashMap | 键值对,无序 | 多数场景 |
| Map | LinkedHashMap | 保持插入顺序 | 需要有序 |

### 综合项目:学生管理系统

位于 `com.learning.project` 包下,综合演示:
- ✅ 面向对象设计(分层架构 model/service)
- ✅ 集合操作(ArrayList 存储)
- ✅ Stream API(分组、统计、排序)
- ✅ 异常处理(业务异常)
- ✅ Lambda 与方法引用

## 🧪 单元测试

本项目使用 JUnit 5 演示单元测试的基础写法。

```bash
# 运行所有测试
mvn test

# 运行指定测试类
mvn test -Dtest=DogTest

# IDEA:点击类/方法左侧的绿色箭头
```

## 🛠️ 常用命令

```bash
# 编译
mvn compile

# 清理 + 编译
mvn clean compile

# 运行测试
mvn test

# 打包(生成 jar)
mvn package

# 运行指定类
mvn exec:java -Dexec.mainClass="com.learning.basics.HelloWorld"

# 运行某个类的某个方法
mvn exec:java -Dexec.mainClass="com.learning.basics.DataTypes" -Dexec.args=""
```

## 📖 推荐学习资源

### 中文
- [菜鸟教程 Java](https://www.runoob.com/java/java-tutorial.html)
- [廖雪峰 Java 教程](https://www.liaoxuefeng.com/wiki/1252599548343744)
- [尚硅谷 Java 教程(Bilibili)](https://www.bilibili.com/video/BV1Kb411W75N)

### 英文
- [Oracle Java Tutorials](https://docs.oracle.com/javase/tutorial/)
- [Baeldung](https://www.baeldung.com/)

## 💡 学习建议

1. **敲代码**: 不要只看不练,每个示例都要亲手敲一遍
2. **改代码**: 试着修改示例,看看会出什么结果
3. **写笔记**: 记录关键点和易错点
4. **做项目**: 学完基础后,尝试自己写一个小项目
5. **看源码**: JDK 源码是最好的学习资料
6. **勤复习**: 每周回顾本周内容

## 🎓 学习完成后

掌握了本项目的所有内容后,你可以继续学习:
- 🔌 JDBC 与数据库编程
- 🌱 Spring Boot 框架
- 🔧 Maven / Gradle 进阶
- 🏗️ 设计模式
- 🌐 网络编程(NIO / Netty)
- ⚡ 并发编程(多线程、JUC)
- 🧪 单元测试进阶(Mockito、Testcontainers)
- 🐳 Docker 与微服务

---

**祝你学习顺利,早日成为 Java 大佬! 🚀**

如有问题,欢迎提 Issue 讨论。