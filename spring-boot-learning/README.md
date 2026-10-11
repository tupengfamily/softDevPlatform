# Spring Boot 综合实战项目 🌱

> 配套 [java-learning](../) 项目的 Spring Boot 实战教程,涵盖 Spring Boot 主流开发的核心内容。

## 📋 项目简介

这是一个基于 **Spring Boot 3.3 + Java 21 + JPA + SQLite** 的实战项目,即**通用学习平台**的后端:包含**后台管理系统**(JWT 鉴权 + RBAC + 操作日志 + 数据字典 + Dashboard)、**学习平台 API**(课程/进度/更新公告/反馈)与 **Java/SQL 在线沙箱**,带你掌握企业级 Spring Boot 开发的所有关键知识点。

## 🚀 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 LTS | 开发语言 |
| Spring Boot | 3.3.5 | 应用框架 |
| Spring MVC | 6.x | Web 框架(REST API) |
| Spring Data JPA | 3.x | ORM 持久化 |
| SQLite + JDBC | 3.x | 嵌入式数据库(零安装,数据文件持久化) |
| JJWT | 0.12.x | JWT 登录鉴权 |
| Lombok | 最新 | 简化样板代码 |
| JUnit 5 | 5.x | 单元测试 |

## 🎯 涵盖的 Spring 核心内容

### 1. Spring IoC / 依赖注入 ⭐

- **@Autowired** 自动注入
- 构造器注入(推荐) vs 字段注入 vs Setter 注入
- @Component / @Service / @Repository / @Controller / @Configuration
- @Bean 声明 Bean

### 2. Spring MVC / REST API ⭐

- @RestController / @RequestMapping
- @GetMapping / @PostMapping / @PutMapping / @DeleteMapping
- @PathVariable / @RequestParam / @RequestBody
- 统一响应格式

### 3. Spring Data JPA ⭐

- @Entity / @Table / @Id / @Column
- JpaRepository 自动 CRUD
- 方法命名约定查询
- @Query 自定义 JPQL / 原生 SQL
- 分页与排序

### 4. 数据校验

- @Valid / @NotBlank / @Email / @Size / @Min / @Max
- MethodArgumentNotValidException 处理

### 5. 全局异常处理 ⭐

- @RestControllerAdvice
- @ExceptionHandler
- 统一异常响应格式

### 6. AOP 面向切面编程

- @Aspect / @Before / @Around / @After
- 切点表达式
- 日志切面、性能监控

### 7. 配置管理

- application.yml 配置
- @Value 读取配置
- 多环境配置(dev/prod)
- Profile

### 8. 事务管理

- @Transactional 声明式事务
- 事务传播行为
- 事务回滚规则

### 9. 测试 ⭐

- @SpringBootTest 集成测试
- @AutoConfigureMockMvc Web 测试
- @Transactional + @Rollback
- JSONPath 断言

## 📂 项目结构

```
spring-boot-learning/
├── pom.xml                            # Maven 配置
├── README.md                          # 本文档
└── src/
    ├── main/
    │   ├── java/com/learning/springboot/
    │   │   ├── SpringBootLearningApplication.java  # 启动入口
    │   │   ├── entity/               # 实体类 (User)
    │   │   ├── repository/           # 数据访问 (UserRepository)
    │   │   ├── service/              # 业务层接口与实现
    │   │   ├── controller/           # REST 控制器
    │   │   ├── dto/                  # 数据传输对象
    │   │   ├── exception/            # 异常处理
    │   │   ├── aop/                  # AOP 切面
    │   │   ├── config/               # 配置类
    │   │   └── util/                 # 工具类
    │   └── resources/
    │       ├── application.yml       # 应用配置
    │       └── data.sql              # 初始数据
    └── test/java/com/learning/springboot/
        ├── SpringBootLearningApplicationTests.java
        ├── controller/
        │   └── UserControllerTest.java
        └── service/
            └── UserServiceTest.java
```

## 🚀 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+
- 推荐 IDE: IntelliJ IDEA

### 运行项目

```bash
# 1. 编译
cd spring-boot-learning
mvn clean compile

# 2. 运行测试
mvn test

# 3. 启动应用
mvn spring-boot:run

# 或直接运行主类
java -cp target/classes com.learning.springboot.SpringBootLearningApplication
```

启动成功后访问 `http://localhost:8080`。

## 🧪 API 接口测试

应用启动后,可以使用 `curl` 或 Postman 测试以下接口:

### Hello 接口

```bash
# 简单 hello
curl http://localhost:8080/api/hello

# 带路径参数
curl http://localhost:8080/api/hello/Java
```

### User CRUD 接口

```bash
# 查询所有用户
curl http://localhost:8080/api/users

# 按 ID 查询
curl http://localhost:8080/api/users/1

# 按用户名模糊查询
curl "http://localhost:8080/api/users/search?keyword=张"

# 按年龄范围查询
curl "http://localhost:8080/api/users/age?min=20&max=30"

# 创建用户
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "新人",
    "email": "new@example.com",
    "age": 25,
    "address": "某地"
  }'

# 更新用户
curl -X PUT http://localhost:8080/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{
    "username": "张三(更新)",
    "email": "zhangsan@new.com",
    "age": 26,
    "address": "北京"
  }'

# 删除用户
curl -X DELETE http://localhost:8080/api/users/1
```

### 数据库文件

数据库使用 SQLite,数据文件位于 `data/` 目录(已被 `.gitignore` 忽略):

- `sandbox.db` — 主数据库(用户/角色/课程/进度/公告/反馈等表)
- 教学沙箱库 — SQL 练习用独立数据文件

首次启动自动建表并写入种子数据(默认账号 `admin/admin123`、`user/user123`)。可用 SQLite 工具(如 `sqlite3`、DB Browser)直接打开查看。

## 📚 推荐学习顺序

### 第 1 天:Hello World
1. 阅读 `SpringBootLearningApplication.java` 理解启动流程
2. 阅读 `HelloController.java` 理解 REST 基本写法
3. 启动应用,访问 `/api/hello`

### 第 2 天:分层架构
1. 阅读 `entity/User.java` 理解 JPA 实体
2. 阅读 `repository/UserRepository.java` 理解 Repository
3. 阅读 `service/UserService.java` + `service/impl/UserServiceImpl.java` 理解业务层

### 第 3 天:REST API
1. 阅读 `controller/UserController.java` 学习 REST 写法
2. 阅读 `dto/UserDTO.java` 学习参数校验
3. 用 curl 或 Postman 测试所有接口

### 第 4 天:异常处理与 AOP
1. 阅读 `exception/GlobalExceptionHandler.java` 学习全局异常
2. 阅读 `aop/LoggingAspect.java` 学习 AOP
3. 故意触发一个异常,观察日志

### 第 5 天:测试
1. 阅读 `UserServiceTest.java` 学习 Service 测试
2. 阅读 `UserControllerTest.java` 学习 Controller 测试
3. 编写一个自己的测试方法

### 第 6 天+:扩展练习

- 添加分页查询
- 接入 MySQL / PostgreSQL
- 整合 Redis 缓存
- 添加 Swagger / OpenAPI 文档
- 编写更多单元测试与集成测试

## 🛠️ 常用 Maven 命令

```bash
mvn clean              # 清理
mvn compile            # 编译
mvn test               # 运行测试
mvn package            # 打包成 jar
mvn spring-boot:run    # 启动应用

# 跳过测试打包
mvn package -DskipTests

# 指定 Profile 启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## 🔍 调试技巧

```bash
# 启动时开启 DEBUG 日志
mvn spring-boot:run -Ddebug=true

# 或设置环境变量
DEBUG=true mvn spring-boot:run
```

## 🎓 学完本项目后

可以继续学习:
- 🔐 Spring Security(权限控制)
- 🌐 Spring Cloud(微服务)
- 📦 Spring Data Redis(缓存)
- 📨 Spring AMQP / Kafka(消息队列)
- 📊 Spring Actuator(应用监控)
- 🐳 Docker 部署
- 🔍 ELK 日志收集
- 📚 Spring 源码阅读

---

**返回** [Java 学习项目总目录](../README.md)