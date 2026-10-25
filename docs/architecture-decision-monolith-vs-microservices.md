# 架构选型决策：单体 vs 微服务

> 决策日期：2026-10-25
> 决策范围：`/Volumes/data/demo/java`（Java 全栈学习平台 + 稼穑农场游戏）
> 结论：**暂不拆微服务，走模块化单体（Modular Monolith）治理路线**

---

## 一、一句话结论

**当前系统不需要微服务拆分。** 需要的是**单体内部的模块化治理** —— 主要目标是拆掉 `FarmPlayService` 这个 3,120 行的上帝类、补齐数据层约束。

微服务会把当前三个真实痛点**变得更糟**：跨进程事务、分布式数据一致性、运维复杂度。

---

## 二、事实盘点（全部实测，非估计）

### 2.1 规模与形态

| 维度 | 实测值 |
|---|---|
| 后端 Java | 274 文件 / **42,644 行** |
| 前端 Vue+JS | 134 文件 / **48,281 行** |
| 单体最大类 | `FarmPlayService` **3,120 行** / 40+ 公共方法 / 10 构造器依赖 |
| 部署 | Docker Compose **2 容器**（后端 512m + Nginx） |
| 运行时 | 单 JVM / 单进程 |
| 编译 | `mvn compile` **3.9 秒** |
| 测试 | 175 个（16 测试类），全量 37~39 秒 |

### 2.2 数据层

| 维度 | 实测值 |
|---|---|
| 数据库 | SQLite **单文件**，50 张表 |
| 模式 | WAL，**单写者**，Hikari 池上限 5 |
| 外键 | **50 张表全部零外键**，关联靠应用层维护 |
| 最大表 | `t_operation_log` 737 行 / `t_learning_lesson` 461 行 |
| 双数据源 | `admin.db`（主）+ `sandbox.db`（教学沙箱，已隔离） |

### 2.3 运行时性能（50 并发实测）

```
状态码分布: {'200': 50}
P50 16.3ms   P95 22.4ms   MAX 40.2ms
```

单请求延迟 8~10ms。**资源瓶颈在 SQLite 写锁，不在 CPU / 内存。**

### 2.4 组织与交付

| 维度 | 实测值 |
|---|---|
| Git 提交者 | **1 人**（tupengfamily） |
| 分支 | 仅 `main`，无特性分支 |
| CI/CD | **无**（无 `.github/workflows` / `.gitlab-ci.yml` / `Jenkinsfile`） |
| 近 30 天提交 | 40 次 |

### 2.5 技术栈现状

- Spring Boot 3.3.5 / JDK 21 / JPA + Hibernate / Caffeine 本地缓存 / JJWT
- **无消息队列**（Kafka / RabbitMQ / Stream 均无）
- **无 Redis**
- **无分布式事务**
- 定时任务仅 1 个：`OperationLogRetentionTask`（每日 3 点清理日志）

---

## 三、为什么不需要微服务：五项硬性前提逐条比对

微服务不是"更先进的架构"，而是**为特定问题提供的解决方案**。这些前提不成立时拆分只会增加成本。

| 前提 | 微服务要求 | 本系统实测 | 判定 |
|---|---|---|---|
| **独立扩缩容** | 某模块负载远高于其他，需单独扩容 | 50 并发 P95 22ms，瓶颈在 SQLite 单写而非 CPU；拆成多进程**写锁竞争反而更重** | ❌ 不需要 |
| **故障隔离** | 某模块崩溃/超时不能拖垮整体 | `doc` 编解码 CPU 密集，但与主业务零耦合，且 `-Xmx512m` 下未触及压力 | ❌ 不需要 |
| **团队并行** | 多团队同时改同一系统 | **1 名提交者**，单分支，无 CI | ❌ 不需要 |
| **发布节奏** | 各模块发版节奏冲突，需独立发布 | 单服务 5 秒启动，无发布窗口压力 | ❌ 不需要 |
| **数据所有权** | 各服务独占自己的库，跨服务只走 API | 50 张表零外键，关联全靠应用层维护 —— **尚不具备拆库条件** | ⚠️ 需先治理 |

**五项全部不触发。** 唯一未达标的「数据所有权」是**结果**而非**原因** —— 它恰恰说明现在拆服务会立刻踩分布式一致性坑。

---

## 四、真正的痛点（这些才该解决）

### 4.1 上帝类 `FarmPlayService`（最痛）

```
3,120 行 · 40+ 公共方法 · 10 个构造器依赖 · 118 个字段
```

现有职责分区（从注释分节提取）：

| 行段 | 职责 | 应归属 |
|---|---|---|
| 219–330 | 取地块 / 建地块 | `PlotService` |
| 330–473 | 出苗 / 季节档案 | `SeasonService` |
| 473–961 | 查询（视图 / 总览） | `FarmQueryService` |
| 961–1421 | 操作（推进 / 收割 / 重开） | `SeasonAdvanceService` |
| 1421–1520 | 测试用 mock 天气 | `MockWeatherService` |
| 1520–1738 | 农事操作（核心玩法） | `FarmActionService` |
| 1738–1883 | 农资输入（`applyInput`） | `FarmInputService` |
| 1883–2366 | 风险评估 / 适宜性 | `SuitabilityService` |

**问题**：改一处要读全类；爆炸半径不可控；无法单元测试单个职责。

### 4.2 数据层零约束

50 张表全部零外键。2026-10-23 已经因此踩过一次**串场事故**：只按 `slot` 反查地块，串到了别的农场的数据。修复方式是「先按 farmId 再按 slot」—— **靠代码自觉，不是靠数据库兜底**。

### 4.3 SQLite 写锁是唯一真实瓶颈

WAL 模式单写者，连接池上限 5。但当前最大表 737 行、50 并发 P95 22ms —— **远未触及上限**。

---

## 五、技术选型：模块化单体（推荐）

### 5.1 为什么是模块化单体

| 维度 | 保持单体 + 模块化 | 拆微服务 |
|---|---|---|
| 本地事务 | ✅ `@Transactional` 直接用 | ❌ 要换 Saga/补偿，复杂度和 bug 面暴增 |
| 数据一致性 | ✅ 单库外键可加 | ❌ 跨库只能靠应用层，已踩过一次串场坑 |
| 部署 | ✅ 1 个 jar | ❌ N 个容器 + 服务发现 + 链路追踪 |
| 排障 | ✅ 一次断点 | ❌ 分布式追踪 |
| 编译/启动 | ✅ 4 秒 | ❌ 多模块构建 |
| 未来拆分 | ✅ **模块边界即服务边界，随时可拆** | ✅ 已拆 |

**模块化单体的关键价值**：把「进程边界」和「代码边界」解耦。先把代码切干净，等真的出现扩缩容/团队并行需求时，再把某个模块的进程单独拉出来 —— 那时是无缝的。

### 5.2 选型具体内容

#### 选型 A：包结构按业务能力划分（不按技术分层）

```
com.learning.springboot
├── common/           # Result / 异常 / 工具（无业务）
├── security/         # JWT / RBAC / CurrentUser（横切）
├── platform/         # 基础设施（数据源 / 缓存 / 调度 / 配置）
│
├── module/
│   ├── learning/     # 课程 / 课时 / 进度
│   ├── forum/        # 帖子 / 回复
│   ├── personal/     # 个人中心 / 收藏 / Wiki
│   ├── doc/          # 我的文档 + Office 编解码
│   ├── operation/    # 运营后台 / 公告 / 反馈
│   └── farm/         # 稼穑游戏
│       ├── plot/     # 地块
│       ├── season/   # 季节 / 生长模拟
│       ├── action/   # 农事操作
│       ├── input/    # 农资
│       ├── warehouse/ # 仓库
│       ├── shop/     # 商店
│       ├── engine/   # 纯计算引擎（无 Spring 依赖）
│       └── geo/      # 地图 / 行政区划
```

**规则（用 ArchUnit 强制，不靠自觉）**：

1. `module/*` 之间**不允许直接 import** → 必须走各自暴露的 `facade` 接口
2. `engine` 包**不允许 import 任何 Spring 注解**（`@Service` / `@Repository`）→ 保证可纯单元测试
3. `common` / `security` / `platform` 不允许 import 任何 `module/*` → 防止反向依赖
4. `module/*` 之间允许通过 **Spring 事件**（`ApplicationEventPublisher`）协作 → 避免同步调用链

#### 选型 B：拆 `FarmPlayService` 的实施顺序

**按风险从低到高**，每步独立可验证：

| 阶段 | 动作 | 验收标准 | 状态 |
|---|---|---|---|
| S1 | 加 **ArchUnit** 规则测试，锁住现有边界 | 规则测试全绿，零业务改动 | ✅ **2026-10-26 完成** |
| S2 | 抽 `MockWeatherService`（原 1421–1520 行，独立度最高） | 现有 175 测试全绿 | ✅ **2026-10-26 完成** |
| S3 | 抽 `FarmCatalogService`（原 2350–2501 行，纯计算零副作用） | 同上 | ✅ **2026-10-26 完成** |
| S4 | 抽 `FarmSuitabilityService`（原 1883–2366 行，风险评估） | 同上 | ⏳ 待办 |
| S5 | 抽 `FarmActionService` + `FarmInputService` | 同上 | ⏳ 待办 |
| S6 | 抽 `SeasonAdvanceService`（961–1421 行，**事务最重，最后动**） | 同上 + 事务边界回归 | ⏳ 待办 |
| S7 | 剩下的地块/季节收进 `plot` / `season` 子包 | 同上 | ⏳ 待办 |

> ⚠️ **顺序纪律**：从「纯读 / 纯计算」开始，**最后才动事务最重的 `advance`/`harvest`**。
> 每步都必须跑全量测试，不通过就回退该步。

##### S1–S3 的实测修正（2026-10-26）

原计划里 S3 抽的是「473–961 行的纯读查询」，但**实测后发现该区间不成立**：

| 方法 | 实际性质 | 实测依赖 | 结论 |
|---|---|---|---|
| `view()` | ❌ **有写副作用** | `getOrCreate()` 会**创建地块**；且耦合 `replay` 缓存 / `growthToday` / `germination` / `weeds` 等 15 个私有方法 | 不能当纯读抽 |
| `plotSummaries()` | ⚠️ 半纯读 | 依赖 `safeReplay`/`germination`/`weeds`/`stageOf` 等 6 个私有方法 | 耦合过深，暂不动 |
| `zones()` / `crops()` / `suitability()` / `plantingRisk()` | ✅ 真纯计算 | 仅 `FarmPlotRepository` + 引擎 | **抽成 `FarmCatalogService`** |

**已完成的实际抽取**：

| 服务 | 行数 | 依赖 | 抽走的原方法 |
|---|---|---|---|
| `MockWeatherService` | 201 | `FarmWeatherMockRepository` + `FarmWeather` + `ApplicationEventPublisher` | `applyMock` / `listMocks` / `resetMocks` |
| `FarmCatalogService` | 287 | `FarmPlotRepository` | `zones`(×3) / `crops` / `suitability`(×2) / `suitabilityOf`(×2) / `plantingRisk`(×2) / `userToday` |

**结果**：`FarmPlayService` 从 **3120 行 → 2847 行**，公共方法 **40+ → 24 个**，
构造器依赖 **10 → 11 个**（减去 `mockRepo`，加上 `catalog` + `eventPublisher`）。

##### 🔴 S2 抽取时踩到的三个隐性依赖

1. **启动装载**：原 `FarmPlayService` 构造器里会把 DB 的 mock 段灌进 `FarmWeather` 内存层。
   漏掉 → **重启后玩家注入的模拟天气全丢**。已迁到 `MockWeatherService.@PostConstruct`。
2. **回放缓存失效**：原 `applyMock`/`resetMocks` 第一行就 `invalidateSim(userId)`。
   漏掉 → 注入高温后面板还是旧 DVS，表现为**「点了没反应」**。
   已改为 `FarmMockWeatherChangedEvent`（Spring 事件）解耦。
3. **事件顺序**：必须**先改数据、后发事件** —— 事件同步派发，监听方收到后立刻重新回放；
   先发事件再改数据，监听方读到的还是旧天气。

##### S1 的 ArchUnit 实现要点

- **零债务硬规则**（`ArchitectureLayerTest`，8 条）：现在就是绿的，违规直接红。
- **债务守门**（`ArchitectureDebtTest`，4 组）：豁免清单**只许减不许增**，
  清单条目若已不存在则**测试失败**（提醒清理）—— 债务只能被清零，不能被遗忘。
- ⚠️ **清单口径必须用 ArchUnit 字节码（`getDirectDependenciesFromSelf()`）**，
  不能用 grep import 文本 —— 后者漏 8 条、多 1 条。
- ⚠️ **未使用的 import 不产生字节码依赖** → 负向验证时必须真正**使用**该类
  （加字段），否则守门不会触发。

#### 选型 C：数据层约束补齐

```sql
-- 1. 补外键（SQLite 需 PRAGMA foreign_keys=ON，已配）
ALTER TABLE t_farm_season ADD CONSTRAINT fk_season_plot
  FOREIGN KEY (plot_id) REFERENCES t_farm_plot(id) ON DELETE CASCADE;

-- 2. 补唯一索引（消除「同 farm 多块地同 slot」）
CREATE UNIQUE INDEX uk_plot_farm_slot ON t_farm_plot(farm_id, slot);

-- 3. 索引补齐（按查询口径）
CREATE INDEX idx_season_plot ON t_farm_season(plot_id);
CREATE INDEX idx_warehouse_plot ON t_farm_warehouse(plot_id, entry_date);
```

配套：把「先按 farmId 再按 slot」的查询口径**收口到 Repository 层**，杜绝各 Controller 各写一套。

#### 选型 D：中间件选型

**当前阶段不加中间件。** 理由：没有异步需求、没有跨进程通信、没有分布式锁需求。

| 能力 | 当前 | 何时引入 | 选型 |
|---|---|---|---|
| 缓存 | Caffeine 本地 | 需要跨实例共享时 | Redis |
| 消息 | 无 | 出现异步/解耦/削峰需求时 | **RabbitMQ**（比 Kafka 简单，运维轻） |
| 数据库 | SQLite | 写并发超过 500 QPS 或需要多实例 | **PostgreSQL** |
| 搜索 | 无 | 文档量过万需全文检索 | PostgreSQL tsvector（先不引 ES） |
| 分布式锁 | 无 | 多实例写同一资源时 | Redis SET NX（**不要用 DB 行锁**） |

#### 选型 E：可观测性（先补，成本最低）

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

暴露 `/actuator/health`、`/actuator/metrics`、`/actuator/prometheus`。
配合 `micrometer-registry-prometheus` + Grafana。**没有指标就没法判断什么时候该拆** —— 这是判断架构演进时机的依据。

### 5.3 未来真要拆微服务时的路径

**不是现在做，但先留好接口。** 拆分顺序（按数据耦合从低到高）：

```
① doc 服务（纯计算 + 文件存储，零业务耦合）    ← 最先拆
② geo 服务（行政区划 + 作物分布，静态数据为主）
③ learning 服务（课程只读，缓存友好）
④ farm 服务（数据耦合最重，最后拆）
```

配套决策：

| 决策点 | 选型 | 理由 |
|---|---|---|
| 服务间通信 | **REST / OpenAPI 3** | 团队 1 人，无 gRPC 生态需求；可观测性靠日志而非链路追踪 |
| 服务发现 | **Docker Compose + nginx upstream** | K8s 对当前规模是过度设计 |
| 认证 | **JWT 无状态**（当前已实现） | ✅ 已具备，无需改 |
| 分布式事务 | **Saga + 补偿**，禁止 XA | 农场流程可补偿（扣的种子/钱能退） |
| 事件 | **RabbitMQ + 事务发件箱** | 避免"DB 提交了消息没发出去" |
| 观测 | OpenTelemetry + Jaeger | 比 Sleuth/Micrometer Tracing 统一 |

**关键前提**：JWT 无状态认证**已经就绪**，这是拆分时不需要重写的部分。

---

## 六、决策依据的可复现命令

```bash
# 规模
cd spring-boot-learning && find src/main/java -name "*.java" | wc -l
find src/main/java -name "*.java" -exec cat {} + | wc -l

# 最大类
find src/main/java -name "*.java" -exec wc -l {} + | sort -rn | head -10

# 依赖方向（archunit 基线用）
cd src/main/java/com/learning/springboot && grep -rhn "^import com.learning.springboot" --include="*.java" . \
  | sed 's/.*import com.learning.springboot\.\([a-z]*\)\..*/\1/' | sort | uniq -c | sort -rn

# 数据层约束
sqlite3 data/sandbox.db "SELECT COUNT(*) FROM sqlite_master WHERE type='table';"
for t in t_farm_plot t_farm_season t_farm_warehouse; do
  echo "$t FK 数: $(sqlite3 data/sandbox.db "PRAGMA foreign_key_list($t);" | wc -l)"
done

# 性能基线
TOKEN=$(curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"..."}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
seq 1 50 | xargs -P 20 -I{} curl -s --noproxy '*' -o /dev/null -w "%{http_code} %{time_total}\n" \
  -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/api/games/farm/state?slot=0"

# 组织形态
git log --format="%an" | sort | uniq -c
ls .github/workflows/ .gitlab-ci.yml Jenkinsfile 2>/dev/null
```

---

## 七、触发重新评估的信号

**出现以下任一条，才启动微服务拆分评估**：

| 信号 | 阈值 |
|---|---|
| 写并发瓶颈 | SQLite 写锁等待 > 100ms，或写 QPS 持续 > 500 |
| 单模块资源占用 | 某模块 CPU/内存占用 > 总量的 70%，需独立扩容 |
| 团队规模 | 提交者 ≥ 5 人，且并行修改同一模块产生冲突 |
| 发版冲突 | 同一模块每周因发版冲突导致 ≥ 2 次延期 |
| 可用性要求 | 某模块要求 99.9%+ 且需要独立故障域 |
| 数据量 | 单表 > 1000 万行，或需要分库分表 |

> 上述阈值**当前全部未达到**（写 QPS 个位数，最大表 737 行，1 名开发者）。

---

## 八、立即可执行的三件事（不拆服务的前提下）

1. **加 ArchUnit 边界测试** —— 把上面的包结构规则写成可执行断言，防止边界腐化
2. **拆 `FarmPlayService` 的 S2–S4 阶段** —— 纯读/纯计算部分优先，风险最低
3. **加 Actuator + 指标** —— 建立性能基线，为未来决策提供数据依据

---

## 附：本次决策未采纳的方案

| 方案 | 未采纳原因 |
|---|---|
| 全面微服务化 | 五项前提全不触发；跨进程事务 + 分布式一致性会引入当前系统**没有**的问题 |
| 换 PostgreSQL | 当前数据量（最大 737 行）远未需要；但**换库不等于拆服务**，可独立决策 |
| 引入 K8s | 2 个容器用不上编排；运维成本远超收益 |
| 引入 gRPC | 1 人团队无多语言互操作需求；REST + OpenAPI 已足够且可观测 |
| 上 Spring Cloud Alibaba | 全套组件（Nacos/Sentinel/Seata/Gateway）对当前规模是纯负债 |
| Serverless | SQLite 本地文件 + 长连接 3D 场景不适合 FaaS |
