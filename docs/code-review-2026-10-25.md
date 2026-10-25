# 代码检视报告 · 稼穑全栈项目

> **检视日期**：2026-10-25
> **检视范围**：后端 `spring-boot-learning`（277 个 Java 主源文件 / 42,895 行）+ 前端 `frontend-learning`
> **方法**：静态规则扫描（336 文件）+ 两路人工深度语义审查 + **逐条实测复核**
> **重要原则**：本报告**每一条「严重」结论都经过代码复读或接口实测**，未采信的推测已单独列出。

> **✅ 修复状态（2026-10-26 更新）**：**5 条严重（S1–S5）+ 12 条中等（M1–M12）+ 3 条轻级 + 1 条架构级隐患，已全部修复并端到端实测通过**。
> 回归测试 **221 个全绿**（219 基线 + 2 条新增回归）。
> 详见 §二各条的「✅ 修复」小节、§七状态表与 **§十「修复实录（2026-10-26）」**。

---

## 一、结论先行

| 项 | 结果 |
|---|---|
| 华为 Java 编程规范评分 | **85.21 / 100（良好）** |
| 主源文件 | 277 个 Java / 42,895 行 |
| 测试 | 336 文件被扫描 / 211 个测试全绿（修复后 **221 个**） |
| 注释覆盖率 | **8.26%**（偏低，见 §五） |
| **真实严重问题** | **5 条**（全部已实测复核）→ **✅ 5 条全部已修复** |
| **真实中等问题** | **12 条** → **✅ 12 条全部已修复**（2026-10-26） |
| **轻级问题** | **3 条** → **✅ 全部已修复**（2026-10-26） |
| **架构级隐患** | **1 条**（`userWorldDate` 口径污染）→ **✅ 已改为两级时钟模型**（2026-10-26） |
| 静态脚本报出的「严重」 | 8 条 → **实测 8 条全部为误报**（见 §四） |

**总体判断：架构与工程质量是好的**（有 ArchUnit 边界测试、有 DTO 脱敏范式、异常处理统一、全局事务与限流设施齐全）。
问题**高度集中在两类**：
1. **「同型代码只修一处」的遗漏** —— `weeds()` 修了窗口口径，`pests()`/`soil()`/`disasters()` 没同步修
2. **「改权不撤权」** —— 改密 / 撤角色 / 删号三处都没递增 `tokenVersion`，旧 JWT 24 小时内仍可用

---

## 二、严重问题（5 条，均已实测确认，**现已全部修复**）

### 🔴 S1. `pests` / `soil` / `disasters` 天气窗口与 DVS 轨迹错位

**位置**：`FarmPlayService.java:2179`（pests）、`:2553`（soil）、`:2593`（disasters）

**问题**：
`doReplay()`（L2772-2778）的回放窗口是**以今天结尾往前 430 天**：
```java
LocalDate winStart = sow;
if (span > MAX_REPLAY_DAYS) { span = MAX_REPLAY_DAYS; winStart = today.minusDays(MAX_REPLAY_DAYS); }
List<DayWeather> days = weather.range(userId, zoneOf(plot), winStart, today);
```
而 `r.dvsByDay()` 就是从 `winStart` 开始记的。

但三处损失计算仍按**播种日**取天气：
```java
// L2179 pests()
List<DayWeather> days = weather.range(userId, zoneOf(plot), sow, sow.plusDays(n - 1));
```
于是 `days[k]` 的日期是 `sow+k`，`dvsTrace[k]` 对应的却是 `winStart+k` —— **天气与物候整体错位**。

**关键证据**：同文件 `weeds()` L2231-2239 **已经按正确口径修过**，注释明确写了这个坑：
```java
// 🔴 窗口口径必须与 doReplay 一致：**以今天结尾**往前推。
// 原来这里按 sow 起算 n 天，而 doReplay 的 days 是 [sow, sow+span]，
// 两者在「播种超过 430 天」时会错开 —— 杂草引擎读到的天气序列
// 与作物回放的不是同一段，导致 DVS 与天气对不上号。
LocalDate winStart = today.minusDays(Math.max(0, n - 1L));
if (winStart.isBefore(sow)) { winStart = sow; }
```
**同型代码只修了一处** —— 这是本次检视最值得记住的一条。

**触发条件**：本季已种超过 430 天（演示模式推进上限 3650 天，很容易达到）。
**影响**：病虫害、土壤退化、灾害三类损失全部算错 —— 越到季末越离谱。

**修法**：三处统一照抄 `weeds()` L2235-2239 的写法，并把 `startIdx` 与 `dvsTrace` 起点一并对齐。
> ⚠️ 顺带：L2198 / L2557 / L2563 的 `idx` 夹取也基于 `sow`，需同步改为基于回放窗口起点。

**✅ 修复（2026-10-25）**：不止改三处，而是**抽成唯一实现** —— 新增两个私有方法，
从根上消除「同型代码分叉」的隐患：
```java
/** 回放窗口起点：与 doReplay() 完全同口径（跨度 > 430 天则截断到 today-430） */
private LocalDate replayWindowStart(LocalDate sow, LocalDate today) {
    long span = ChronoUnit.DAYS.between(sow, today);
    if (span > MAX_REPLAY_DAYS) {
        return today.minusDays(MAX_REPLAY_DAYS);
    }
    return sow;
}

/** DVS 索引：统一夹取到 [0, n-1]，起点是回放窗口而非播种日 */
private int dvsIndex(LocalDate winStart, LocalDate actionDate, int n) {
    int idx = (int) ChronoUnit.DAYS.between(winStart, actionDate);
    if (idx < 0) { return 0; }
    return Math.min(n - 1, idx);
}
```
`pests` / `weeds` / `soil` / `disasters` **四处**统一改用这两个方法
（`disasters()` 签名新增 `LocalDate today` 参数，2 处调用点同步更新）。

**实测**：新增 8 个 `ReplayWindowAlignmentTest`（含跨度 0/1/29/30/100/428/429/430/431/432/543/1000 逐档核对），
钉住两条语义：`n = dvsByDay().size()` 与窗口起点的对应关系、以及原 bug 形态在跨度 543 时偏 113 天。
全量回归 **219 个测试全绿**。

> 🔴 **本条沉淀为长期铁律**：**改完一个 bug 必须全局搜同型代码**；
> 源码注释里记录的「曾经踩过的坑」就是最好的对照物 —— `weeds()` 里那句注释正是本次的破案线索。

---

### 🔴 S2. `closeFailedSeason` 返回的 `days` 恒为 1

**位置**：`FarmPlayService.java:1106` 与 `:1120`

**问题**（先写后读的顺序错）：
```java
L1106:  plot.setSowDate(today);          // ← 先把播种日改成今天
...
L1120:  out.put("days", (int) ChronoUnit.DAYS.between(plot.getSowDate(), today) + 1);
```
L1120 读到的 `getSowDate()` 已经是 `today`，结果永远是 `0 + 1 = 1`。**本季真实种植天数（可能 150 天）被抹成 1**。

**对照正确写法**（同文件 `harvest` L1169）：
```java
int days = (int) ChronoUnit.DAYS.between(plot.getSowDate(), today) + 1;   // 在 setSowDate 之前算
```

**触发条件**：任何一次「绝收/结季」调用 —— 玩家点「记失败」时。
**影响**：接口返回的种植天数恒为 1；写进季节档案的 `days` 字段也是错的。

**修法**：在 L1106 之前先算出天数存入局部变量，再 `setSowDate`。

**✅ 修复（2026-10-25）**：新增局部变量 `seasonSowDate`，**在改写 `plot.sowDate` 之前抓住本季原始播种日**：
```java
// 🔴 本季原始播种日必须在改写 plot.sowDate 之前抓住。
// 下面会 setSowDate(today) 把地块切到下一季，
// 若之后再拿 plot.getSowDate() 算本季天数，会永远得到 today-today=0 → 天数恒为 1。
LocalDate seasonSowDate = plot.getSowDate();
SimEngine.SimResult r = replay(userId, plot, seasonSowDate, today);
...
int seasonDays = (int) ChronoUnit.DAYS.between(seasonSowDate, today) + 1;
out.put("days", seasonDays);
```
`seasonActions(userId, plot, plot.getSowDate(), today)` 同步改为传 `seasonSowDate`（原本同样会被改写后的值带偏）。

---

### 🔴 S3. `tokenVersion` 撤销机制形同虚设 —— 改权不撤权

**位置**：`UserServiceImpl.java:102-111`（changePassword）、`:114-129`（assignRoles）、`:71-78`（deleteUser）

**问题**：全项目 `incrementTokenVersion` **只有 2 处调用**，都在登出时：
```
repository/UserRepository.java:126      ← 定义
controller/AuthController.java:133       ← 登出
controller/AuthController.java:146       ← 退出所有设备
```
**改密码、撤销管理员角色、删除用户，三处都不递增 `tokenVersion`**。

配套的两个放行点：
- `AdminInterceptor` 只读 JWT 里的角色快照 `roles.contains("ADMIN")`，**不查库**
- `JwtAuthFilter` 的 tv 比对在 `userTv == null`（用户已删）时**直接跳过**
- `UserStatusInterceptor` 对 `user == null` 也 `return true` 放行

**触发条件**：
1. 管理员把某用户的 ADMIN 角色撤掉 → 该用户旧 JWT 仍可访问全部 `@RequireAdmin` 接口，**最长 24 小时**（`jwt.expiration-millis: 86400000`）
2. 用户被删除后，其旧 token 完全可用
3. 用户自己改密后，被盗 token 仍有效

**修法**：三处改权点均调用 `userRepository.incrementTokenVersion(userId, now)`；
`JwtAuthFilter` 遇 `userTv == null` 应返 401 而非放行。

**✅ 修复（2026-10-25）**：
1. `UserServiceImpl` 新增私有方法 `revokeTokens(Long userId)`，**4 处调用**：
   `changePassword` / `assignRoles` / `deleteUser` / `updateStatus`（仅 `STATUS_DISABLED` 时）。
   > 🔴 **顺序铁律**：必须**先 `incrementTokenVersion`（`@Modifying` 直写 DB）再 `save(user)`**。
   > 反过来的话，JPA 一级缓存里 user 的旧 `tokenVersion` 会被 `save` 写回，把刚递增的值覆盖掉。
2. `JwtAuthFilter` 补 `userTv == null` 分支 → 返 **401**（用户已删 → token 立即失效）。

**实测**：`PUT /api/users/1/password` → 200；**旧 token 再访问 → 401**；新密码登录 → 200。
（端点是 `PUT /api/users/{id}/password`，不是 `/change-password` —— 后者会 404。）

---

### 🔴 S4. 个人文档 HTML 未消毒 + 内联 `text/html` 预览 → 存储型 XSS

**位置**：`MyDocService.java`（`saveModel` / `importFile`）+ `MyDocController.java:218-220`

**问题**：
`.html/.htm` 被映射为 `RICHTEXT`（`MyDocType.java:96`），但 **import 与 saveModel 两条写路径都没有调用 `HtmlSanitizer.sanitizeRichText`**。

实测确认 —— 同项目其它 6 处富文本入口**都做了消毒**，唯独个人文档漏了：
```
service/impl/SupplementServiceImpl.java:186   ? HtmlSanitizer.sanitizeRichText(content)
service/impl/ReleaseNoteServiceImpl.java:102  note.setContent(HtmlSanitizer.sanitizeRichText(...))
service/impl/ForumServiceImpl.java:355          ? HtmlSanitizer.sanitizeRichText(content)
service/PersonalCenterService.java:322/362/578  "richtext".equals(ctype) ? HtmlSanitizer.sanitizeRichText(content) : content
```

预览端点以 **`text/html;charset=UTF-8`** 内联返回：
```java
case HTML:
case RICHTEXT:
    return MediaType.parseMediaType("text/html;charset=UTF-8");
```

**攻击链**：上传含 `<script>` 或 `<img onerror=...>` 的 .html → `GET /api/my-docs/files/{id}/preview` → 脚本在**应用自身域**下执行 → 窃取该域 Cookie / localStorage 里的 JWT → 代替受害者操作。

**修法**：`saveModel` 与 `importFile` 对 `RICHTEXT` 统一走 `HtmlSanitizer.sanitizeRichText`；
预览加 `Content-Security-Policy: sandbox`，或改 `Content-Disposition: attachment`。

**✅ 修复（2026-10-25）**：**三层防护**全部落地：
1. **写入侧消毒**（`MyDocService`）—— `saveModel` 的 MD_RT 分支 + `importFile` 写盘前，
   两条 RICHTEXT 写路径都过 `HtmlSanitizer.sanitizeRichText(text)`。
2. **预览侧 CSP**（`MyDocController.preview`）—— 对 `HTML` / `RICHTEXT` 加：
   ```java
   headers.set("Content-Security-Policy",
           "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'");
   headers.set("X-Content-Type-Options", "nosniff");
   ```
   `sandbox` 不带 `allow-scripts` → 脚本不执行；不带 `allow-same-origin` → 沙箱页拿不到本域 localStorage（JWT 就在那）。
3. **实体层兜底** —— `User.password` 加 `@JsonIgnore`（见 S5）。

**实测**：导入 `<p>hello</p><script>alert(1)</script><img src=x onerror=alert(2)>` →
预览返回 **`<p>hello</p><img src="x">`**（script / onerror 全剥离），
响应头含 `Content-Security-Policy: sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'` + `nosniff`。

---

### 🔴 S5. `/api/users/search` 与 `/api/users/age` 直接返回 JPA 实体 → 密码 hash 泄漏 + 500

**位置**：`UserController.java:124-133` + `UserServiceImpl.java:151-160`

**问题**：这两个端点返回的是 `List<User>` **实体**，不是 DTO：
```java
@GetMapping("/search")
public ResponseEntity<?> search(@RequestParam(defaultValue = "") String keyword) {
    return Result.ok(userService.searchByUsername(keyword));   // ← 实体，不是 UserVO
}
```
而 `User` 实体有标准 getter 且**无 `@JsonIgnore`**：
```java
private String password;                    // User.java:47
public String getPassword() { return password; }   // User.java:169 → Jackson 会序列化
```

**实测结果**（用真实管理员 token 调用）：
```
GET /api/users/search?keyword=a
→ {"code":500,"message":"服务器内部错误","data":null}
```
500 的根因是 `open-in-view: false`（`application.yml:64`）+ `roles` 为 LAZY + `SELECT *` 原生查询 →
序列化 `getRoles()` 触发 `LazyInitializationException`。

**注意：同文件的 `listAll()`（L117-120）已改用 `UserVO` 脱敏**，注释还写明了原因 —— 这是**遗漏**，不是设计如此：
```java
// 返回 UserVO 而非实体: 实体带 Role.permissions 懒加载链,直接序列化会抛 LazyInitializationException
List<UserVO> data = userService.listAll().stream().map(UserVO::from).toList();
```

**缓解因素**：`UserController` 类级 `@RequireAdmin("用户管理仅管理员可操作")` → 只有管理员能触发。
严重程度从「高」降为「中」，但**密码 hash 仍不该出现在响应里**。

**修法**：两个端点改用 `UserVO.from(...)` 映射；`User.password` 加 `@JsonIgnore` 兜底。

**✅ 修复（2026-10-25）**：
1. `UserController` 的 `/search` 与 `/age` 均改为 `.stream().map(UserVO::from).toList()`。
2. `UserRepository` 的两个查询由 **原生 SQL `SELECT *`** 改为 **JPQL + `LEFT JOIN FETCH u.roles`**：
   ```java
   @Query("SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.roles "
          + "WHERE u.username LIKE %:keyword% ORDER BY u.id DESC")
   List<User> searchByUsernameLike(@Param("keyword") String keyword);
   ```
   原生 `SELECT *` 有两个问题：取不回 `roles` 关联（序列化时抛 `LazyInitializationException` → 500）、
   且把 `password` 这类敏感列也带了出来。
3. `User.password` 字段加 `@JsonIgnore`（实体层兜底，堵死任何漏网路径）。

**实测**：`GET /api/users/search?keyword=admin` → **500 → 200**，
响应体**无 `password` 字段**，`roleCodes: ["ADMIN"]` / `roleNames: ["管理员"]` 正常。

---

## 三、中等问题（12 条，摘要）—— **✅ 全部已修复（2026-10-26）**

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| M1 | `FarmPlayService.java:305/314` | `LocalDate.now()` 用了现实日期，违反「必须走 `userToday(userId)`」既定口径 | ✅ |
| M2 | `FarmPlayService.java:1397/1418` | `restart` 用现实日期；且方法注释承诺「落到最近水稻适宜播期」**根本没实现**，`worldDate` 字段还与系统口径打架 | ✅ |
| M3 | `FarmPlayService.java:375-377` | 出苗窗口在播种初期只算 1 天 → 建田当天面板显示「出苗率 0%，缺苗 100%」 | ✅ |
| M4 | `SuitabilityEngine.java:725-733` | 季节降水只取当年，跨年被截断 → 晚播作物水分供给少算 288–585mm（如 12/25 播只累加 **7 天**） | ✅ |
| M5 | `SuitabilityEngine.java:588` | 定级掺入产量带，与类注释「等级由 `P_pass` 唯一决定」**自相矛盾**（radish 实测 pPass=1.00 却判 L1） | ✅ |
| M6 | `SuitabilityEngine.java:511` | 积温供给写死 10℃ 基准，与各作物自身 `aatRef`（cabbage=5、tulip=5、potato=7…）不一致 | ✅ |
| M7 | `SuitabilityEngine.java:720-723` | `medianYearOf(LocalDate sow)` **形参从未使用**，恒返回 2021 | ✅ |
| M8 | `WikiCollaboratorController.java:100-104` | `ofResource` 无 `@CurrentUser`、无所有权校验 → 任意登录用户可枚举他人协作者、ownerId、用户名 | ✅ |
| M9 | `SupplementServiceImpl.java:63-66` | `create()` 不校验 `parentNodeId` / `chapterId` 归属 → 可把内容挂进他人树（后续会**级联删掉**他人子节点）、污染官方章节 | ✅ |
| M10 | `ForumServiceImpl.java:78-84` | 浏览数被「原子 +1」后又用内存旧值 +1 覆盖写回 → **并发下浏览数大量丢失** | ✅ |
| M11 | `LoginRateLimiter.java:97-106` | 失败计数是**非原子读改写** → 50 并发暴力破解时计数互相覆盖，5 次锁定阈值实际可绕过数十倍；`:111-115` 成功登录还会清掉 IP 维度计数 | ✅ |
| M12 | `ForumServiceImpl.java:135-147/167-172` | 点赞/收藏是 check-then-act + 覆盖写计数 → 并发下唯一约束冲突 500；转发数丢失更新 | ✅ |

**另有 3 条「轻」级问题**（不展开）：`FarmWarehouseService.store()` 已成死代码（收割绕过它手工构造实体，导致其 `kg<=0` 拦截与 `qualityFactor` 上界校验全部失效）；`WarehouseEngine` 折价系数 `0` 被当成 `1.0`（应为 `0.0`）；`FarmWarehouseService.java:193` `getStoredDate()` 未判空。 → **✅ 2026-10-26 全部修复**（收割改回调用 `store()`，恢复校验；折价 `0` 归零、负数才退回 `1.0`；`storedDate` 判空）。

---

## 四、⚠️ 静态脚本报出的 8 个「严重问题」——**全部为误报**

这一步很关键：脚本按正则匹配，**无法区分「JPQL 字符串」和「真 SQL 拼接」**。

| 脚本报的位置 | 实际内容 | 判定 |
|---|---|---|
| `TianDiTuService.java:296` | `"geocode\|" + kw + "\|" + level` | **缓存 key 拼接**，不是 SQL |
| `TianDiTuService.java:344` | `"reverse\|" + roundedLon + ...` | **缓存 key 拼接**，不是 SQL |
| `ForumPostRepository.java:20` | `@Query("UPDATE ForumPost p SET ...")` | **JPQL**，`:id` 是具名参数 |
| `WikiArticleRepository.java:27` | `@Query("UPDATE WikiArticle a SET ...")` | **JPQL** |
| `UserRepository.java:124` | `@Query("UPDATE User u SET ...")` | **JPQL** |
| `FarmDateTypePatch.java:100/118` | `"select " + column + " from " + table` | 表名/列名是**内部硬编码常量**，值走 `?` 占位符 |
| `GameService.java:306` | `"... WHERE id IN (" + in + ")"` | `in` 是**拼出来的 `?` 占位符个数**，ID 走 `ids.toArray()` 参数化 |

**结论：项目里没有真实 SQL 注入。** 建议后续在 CI 里固化这条判断规则，避免每轮审查都被这 8 条噪声干扰。

### 另有一条审查结论被实测推翻
> 审查 agent 报：「`OO_ENABLED` 默认 false，即默认部署下 `/wopi/**` 完全免鉴权，可读取并覆盖 userId=1 的所有个人文档」

**实测**（无任何鉴权头）：
```
GET /wopi/files/1 → HTTP 401  {"message":"no auth","code":401}
```
原因：`OnlyOfficeProperties.jwtEnabled` 默认 **true**（`OnlyOfficeProperties.java:38`），controller L120 先返 401，**够不到** L121 的 `userId = 1L` 兜底。

**但兜底本身仍是隐患**：当 `OO_JWT_ENABLED=false` 且 `OO_ENABLED=false` 时，L121/L122 会把未鉴权请求落到 `userId=1`。
→ **降级为「中」**：删掉 `userId = 1L` 兜底，未鉴权一律 401；`WopiAuthFilter` 在 `!enabled` 时返 503 而非放行（注释本就承诺 503）。

---

## 五、代码可读性与注释（95 条优化建议的归纳）

脚本的 95 条建议**全部是「代码可读性」**类型，且集中在测试文件与长文件：

| 文件 | 建议数 | 说明 |
|---|---|---|
| `DocxFidelityTest.java` | 16 | 测试断言过长，可提取辅助方法 |
| `DataSeedService.java` | 10 | 种子数据构造冗长 |
| `DocxCodec.java` | 8 | 1,615 行的编解码类，方法过长 |
| `WikiController.java` / `CollaborationService.java` | 各 5 | 端点缺少 javadoc |
| `FarmPlayService.java` | — | **2,847 行，仍是最大单体**（S1–S3 已从 3,120 降下来） |

**注释覆盖率 8.26%** 偏低，但**分布是合理的** —— 高价值注释集中在「为什么这么做」的地方，例如：
```java
// 🔴 2026-10-22 修复（需求 O 验收时发现）：
// 原来窗口是 [sow, sow+430]，超过 430 天的播种日会把**最近 430 天之后**
// 的农事动作（浇水 / 除草 / 打药）全部落在回放窗口之外 ——
// 引擎按 i >= days.size() 提前 break，玩家刚做的操作当场"没反应"，
```
这正是本次能定位 S1 的原因 —— **注释记录了「曾经的坑」，但同型代码没同步修**。

**建议**：注释覆盖率不是问题，**同型代码的同步修复机制缺失才是问题**。

---

## 六、已核查、确认无问题的项

| 检查项 | 结论 |
|---|---|
| SQL 注入 | 无真实值拼接（见 §四） |
| 硬编码密钥 | 无。JWT 走 `${JWT_SECRET:}`（prod 强制显式配置）；OO secret 走 `${OO_JWT_SECRET:}`；API Key 走 `API_KEY_MASTER_KEY` 或 `data/api-key-master.key`，无则启动失败，**绝不降级明文** |
| 路径穿越 | 无。`MyDocStorage` 对 `storageKey`（`^[A-Za-z0-9_-]{1,80}\.[a-z0-9]{2,6}$`）与版本名做严格白名单；头像走扩展名白名单 + 魔数校验 |
| SSRF | 无用户可控 URL 进入服务端请求。`ApiKeyService.test()` 目标 host 固定为 `api.tianditu.gov.cn` |
| `@RequireAdmin` 遗漏 | 无。7 个管理控制器均为类级注解，另有 9 处方法级 `@RequireAdmin` / `@RequireOperation` |
| `@Transactional` 在 private 方法 | 无。所有 `@Transactional` 均在 public 方法上 |
| 资源泄漏 | 无。`WeatherSeries.loadFromClasspath`、`ClimateModel.baseline` 均用 try-with-resources |
| `SimpleDateFormat` | 零出现，日期统一走 `LocalDate` |
| 共享 `Random` 竞争 | 无。`ClimateModel.synthetic` / `PestEngine.simulate` 每次 `new Random(seed(...))`，`PestEngine.rnd` 是纯函数 |
| 并发安全 | `ClimateModel.CACHE`/`STATS`、`FarmCatalogService.suitCache`、`FarmPlayService.simCache` 均为 `ConcurrentHashMap` |
| epoch 毫秒 / 秒混用 | 无。engine 与 Service 中无 `toEpochMilli`/`/1000` 换算 |
| 供给压力公式 | 实测与注释一致：150kg→1.00、500kg→0.93、2000kg→0.71、5000kg→0.48、10000kg→触 0.45 下界 |
| 园艺产量单位口径 | 禾谷/园艺两套口径正确（`SimEngine` L394 与 `yields()` L1794 同式） |
| `nearestOccurrence` vs `defaultSow` | 风险评估统一走同期锚点 `sowWindow()`，未混用未来化的 `defaultSow` |

---

## 七、修复优先级建议

> **✅ 执行状态（2026-10-26 更新）**：**全部完成**。
> - **P0**：S1–S5 全部完成并实测通过（2026-10-25）
> - **P1**：M8/M9 两处越权已完成（2026-10-26）
> - **P2**：M1/M2/M3/M4–M7/M10/M11/M12 全部完成（2026-10-26）
> - **轻级三条**：`store()` 死代码 / 折价 `0` 被当 `1.0` / `getStoredDate()` 未判空 —— 全部完成（2026-10-26）
> - **架构级**：`userWorldDate` 口径污染 → 改为**两级时钟模型**（用户级 `t_farm_user_clock` + 地块级生长时钟），完成（2026-10-26）
>
> 回归测试 **221 个全绿**（219 基线 + 2 条新增）。
> 修复实录见 **§十**。

**P0 —— 会产生错误数据或安全敞口** ✅ 已全部完成
1. **S3** `tokenVersion` 三处改权点补撤销（改动最小、收益最大）✅
2. **S2** `closeFailedSeason` 的 `days` 恒为 1（一行修复）✅
3. **S1** `pests`/`soil`/`disasters` 三处窗口口径对齐 `weeds()`（照抄现成范式）✅

**P1 —— 功能性缺陷**
4. **S4** 个人文档 HTML 消毒 + CSP sandbox ✅
5. **S5** `/users/search`、`/users/age` 改用 `UserVO` ✅
6. **M8/M9** 两处越权（`ofResource`、`create()` 归属校验）✅

**P2 —— 口径与精度**
7. **M1/M2** `LocalDate.now()` → `userToday(userId)`（2 处）✅
8. **M3** 出苗窗口播种初期恒 0% ✅
9. **M4/M5/M6/M7** `SuitabilityEngine` 四处口径不一致 ✅
10. **M10/M11/M12** 三处并发读改写 ✅

---

## 八、方法论沉淀（本次检视的可复用经验）

1. **静态规则扫描必须人工复核** —— 8 个「严重 SQL 注入」全是误报，直接采信会浪费大量排查时间
2. **「同型代码只修一处」是最高频的真实缺陷模式** —— 本次 S1 就是靠「`weeds()` 已有正确实现 + 注释说明」交叉比对发现的
3. **注释里记录的「曾经踩过的坑」是最 valuable 的资产** —— 找到同型代码时，注释就是最好的对照物
4. **审查结论必须实测推翻** —— 「WOPI 默认免鉴权」被一次 curl 推翻；若不验证，会误修一个不存在的漏洞而放过真实漏洞
5. **并发与权限类问题无法靠读代码确认** —— 需要构造并发场景或对比同项目的正确范式（如 `attempts.compute` vs `failures.get`+`put`）

---

## 九、修复实录（2026-10-25）

### 9.1 改了什么

| # | 文件 | 改动 |
|---|---|---|
| S1 | `FarmPlayService.java` | 新增 `replayWindowStart` / `dvsIndex` 两个私有方法；`pests`/`weeds`/`soil`/`disasters` **四处**统一改用；`disasters()` 签名加 `today` 参数（2 处调用点同步） |
| S2 | `FarmPlayService.java` | `closeFailedSeason` 新增 `seasonSowDate` 局部变量，在 `setSowDate` 之前抓住本季原始播种日 |
| S3 | `UserServiceImpl.java` | 新增 `revokeTokens(Long)`；`changePassword`/`assignRoles`/`deleteUser`/`updateStatus` **4 处**调用 |
| S3 | `JwtAuthFilter.java` | 补 `userTv == null` → 401 分支 |
| S4 | `MyDocService.java` | `saveModel`（MD_RT 分支）+ `importFile`（写盘前）两条 RICHTEXT 写路径加消毒 |
| S4 | `MyDocController.java` | `preview` 对 HTML/RICHTEXT 加 CSP sandbox + `nosniff` |
| S5 | `UserController.java` | `/search`、`/age` 改用 `UserVO.from` |
| S5 | `UserRepository.java` | 两个查询由原生 SQL `SELECT *` 改为 JPQL + `LEFT JOIN FETCH u.roles` |
| S5 | `User.java` | `password` 字段加 `@JsonIgnore` |
| 守门 | `ArchitectureDebtTest.java` | DEBT-C 白名单 2 → 9 条；source 匹配 `equals` → `startsWith`（修子包盲区） |
| 测试 | `ReplayWindowAlignmentTest.java` | **新增 8 个**回归测试（窗口起点 ↔ DVS 轨迹对齐） |

### 9.2 验证结果

```
后端全量测试: 219 个 | 失败 0 | 错误 0 | 跳过 10     ✅ BUILD SUCCESS
  （211 原有 + 8 ReplayWindowAlignmentTest）
架构测试:     ArchitectureLayerTest 8/8 + ArchitectureDebtTest 4/4
打包:         mvn -o -q package → jar 82,625,368 bytes（class 与 target/classes 校验一致）
服务探活:     backend 8080 = 200 / vite 5173 = 200
```

接口实测：

| 项 | 修复前 | 修复后 |
|---|---|---|
| S3 改密后旧 token | 200 | **401** ✅ |
| S3 新密码登录 | — | 200 ✅ |
| S4 导入含 `<script>` 的 HTML | 预览原样返回 | **`<p>hello</p><img src="x">`**（script/onerror 剥离）✅ |
| S4 预览响应头 | 无 CSP | `sandbox; default-src 'none'; img-src data; style-src 'unsafe-inline'` + `nosniff` ✅ |
| S5 `/api/users/search` | **500** + 密码 hash | **200**，无 `password` 字段，`roleCodes` 正常 ✅ |

### 9.3 本轮新增的 3 条教训

**① `tokenVersion` 撤销的顺序要求**
必须**先 `incrementTokenVersion`（`@Modifying` 直写 DB）再 `save(user)`**。
反过来的话，JPA 一级缓存里 user 的旧 `tokenVersion` 会被 `save` 写回，把刚递增的值覆盖掉 ——
表现是「代码明明调了撤销，旧 token 却还能用」。

**② ArchUnit 守门自身的子包盲区**
DEBT-C 规则原本用 `sourcePkg.equals("...service")` 精确匹配包名，
导致 `service.impl` 子包的 **14 个类从未被守门覆盖**。改成 `startsWith` 后一次性暴露 6 处零覆盖依赖。
> **教训**：守门规则本身也要被审 —— 「规则没报错」不等于「规则在管」。

**③ `n = dvsByDay().size()` 是截断后的长度，不能想当然反推窗口起点**
`n = min(maxDays, days.size())` 且 `maxDays = span + 1`，
所以 `today - (n-1)` 在**所有跨度下**都恰好等于 `doReplay` 的 `winStart` —— 但这是巧合性的等价，
不是可依赖的契约。最终实现直接用 `sow/today/MAX_REPLAY_DAYS` 重算（不依赖 `n` 的隐含语义），
并用 8 个测试逐档锁住语义。

### 9.4 一次走错路的记录（值得留档）

修复收尾时曾从 `spring-boot-learning/` 目录启动 jar，结果命中了
`spring-boot-learning/data/sandbox.db`（一个 2.6MB 的**早期旧库**，缺 `create_time` / `update_time` 列），
启动直接失败：`no such column: ls1_0.create_time`。

**这正是 `start-all.sh` L507-511 注释里写明的坑**：
> ⚠️ 关键：必须从仓库根目录启 jar，而不是模块目录。
> `application.yml` 用 `${user.dir}` 解析数据目录。模块目录下只有早期的小库…

**活动库是仓库根的 `data/sandbox.db`（3.7MB）**。
教训与 §八一致：**注释里记录的坑就是最好的对照物** —— 启动失败后第一反应应该是去搜这条注释，而不是去查代码。

---

## 十、修复实录（2026-10-26）

本轮把 **12 条中等问题（M1–M12）+ 3 条轻级问题 + 1 条架构级隐患** 全部修完。
过程中额外挖出一个**先于本次改动就存在的地基性瓶颈**（SQLite 500 复合项），一并根治。

### 10.1 M12 点赞/收藏/转发 —— 唯一约束冲突的正确解法

**第一版走错了**：想用「捕获 `DataIntegrityViolationException`」处理并发下的唯一约束冲突。
**这是错的**：唯一约束冲突会把当前事务标记为 **rollback-only**，后续所有操作（`incrementLikeCount` 等）全部失败。

**正确解法**：用 SQLite 原生的 `INSERT OR IGNORE` + 真实唯一索引，靠**受影响行数**判断是否真插入：

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = "INSERT OR IGNORE INTO t_forum_post_like (post_id, user_id, created_at) "
        + "VALUES (:postId, :userId, CURRENT_TIMESTAMP)", nativeQuery = true)
int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);
```

计数改成原子自增，更新后**重查实体**再返回（避免返回内存里的旧值）：

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE ForumPost p SET p.likeCount = COALESCE(p.likeCount, 0) + :delta WHERE p.id = :postId")
void incrementLikeCount(@Param("postId") Long postId, @Param("delta") int delta);
```

⚠️ **实测发现**：本项目的 `t_forum_post_like` / `t_forum_post_favorite` 表**原本没有唯一索引**
（`PRAGMA index_list` 返回空）—— 实体上的 `@UniqueConstraint` 不会在 SQLite 已有表上被
`ddl-auto: update` 补建。所以新增了 `ForumInteractSchemaPatch`：去重（保留 `MIN(id)`）→
建唯一索引 → 按真实记录数校准 `like_count` / `favorite_count`。**幂等**。

### 10.2 M11 登录限流 —— 两个独立缺陷

```java
// ❌ 旧：非原子读改写，50 并发时互相覆盖
Integer prev = failures.get(key);
failures.put(key, (prev == null ? 0 : prev) + 1);

// ✅ 新：同文件 `attempts.compute(...)` 已是注册限流的既有范式，直接对齐
failures.compute(key, (k, prev) -> {
    int count = (prev == null || prev.expired(now)) ? 1 : prev.count() + 1;
    long lockedUntil = count >= MAX_FAILURES ? now + LOCK_MS : 0L;
    return new Failure(count, firstAt, lockedUntil);
});
```

**第二个缺陷更隐蔽**：旧 `recordSuccess` 同时清了 IP 维度 —— 意味着**任意一次成功登录就能替攻击者抹掉整个 IP 的撞库进度**。
现在只清 `u:` 维度（用户名），保留 `ip:` 维度。

### 10.3 架构级隐患 —— 两级时钟模型

**原设计的病根**：用户级「今天」= `max{ 现实今天 + 每块地的 demoOffsetDays }` ——
**任何一个无关地块的脏偏移都会污染整个用户级日期口径**。
2026-10-25 实测踩坑：`plot27` 残留 `demo_offset_days = 660`，把整个用户的世界日期推到 2028-08-15，
仓库里 6 件商品全部「仓储 800+ 天 → 已变质 → 不可卖 / ¥0」。

**修复后的两级时钟**：

| 时钟 | 口径 | 用途 |
|---|---|---|
| **用户级** | 现实今天 + `t_farm_user_clock.offset_days` | 仓库老化 / 天气取段 / 推荐播期 / 商店世界日期 / 地图 today |
| **地块级** | 播期 + `plot.season_progress_days` | 作物回放 / 物候 / 出苗率 / 产量 |

新增 `t_farm_user_clock` 表 + `FarmUserClockService`（唯一入口）+ `FarmUserClockSchemaPatch`（幂等建表 + 老数据迁移）。
迁移策略 = **取该用户所有地块偏移的最大值** —— 与旧口径逐日相同，迁移当天玩家的仓库老化/天气取段/推荐播期**都不跳变**。

地块的 `demoOffsetDays` 退化为**用户时钟的冗余镜像**（advance 时统一同步），不再是「谁被推进了」的独立状态。

### 10.4 🔴 SQLite 的 500 复合项硬上限（本次额外挖出的地基性瓶颈）

**症状**：修复完代码后，后端**第二次启动直接失败**：

```
[SQLITE_ERROR] SQL error or missing database (too many terms in compound SELECT)
  at org.sqlite.jdbc3.JDBC3DatabaseMetaData.getColumns(JDBC3DatabaseMetaData.java:1260)
  at org.hibernate.tool.schema.extract.internal.InformationExtractorJdbcDatabaseMetaDataImpl
```

**根因**：`sqlite-jdbc` 的 `DatabaseMetaData.getColumns(null,null,"%","%")` 在扫全库时，
会为**每一列**生成一个 `SELECT ... UNION ALL ...` 分支；
而 SQLite 对复合 SELECT 的项数有**硬上限 500**（`SQLITE_MAX_COMPOUND_SELECT`）。
Hibernate 在 `ddl-auto: update` 时正是通过它读全库 schema → **列数一多就起不来**。

**实测临界点**：

| 库状态 | 表数 | 列数 | 复合项 | 结果 |
|---|---|---|---|---|
| 改动前 | 49 | 493 | **正好 500** | 卡在临界点（潜伏炸弹） |
| 新增 `t_farm_user_clock` 后 | 50 | **497** | **>500** | **启动失败** |

⚠️ **特别隐蔽**：第一次启动是**成功**的（那时表还没建全），
所以表现为「昨天还好好的，今天突然起不来了」。**每加一个字段都会再次炸掉。**

**修法**（sqlite-jdbc 官方 issue #487 的 workaround）：代理 `Connection.getMetaData()`，
把全库扫描**拆成逐表查询**再合并：

```java
// 单表最多 25 列 → 永远碰不到 500 上限
try (ResultSet colRs = delegate.getColumns(null, null, tableName, null)) { ... }
```

**接入点**：主数据源由 Spring Boot 自动装配（项目内没有自定义 DataSource Bean），
所以用 `BeanPostProcessor` 包一层 `DataSource` 接口 —— 主库与沙箱库**同时受益**，
且不碰 Hikari 的生命周期（只在「借出连接」这一层包代理）。

⚠️ **踩到的第二个坑**：`CachedRowSet.insertRow()` 后游标停在 **insert-row 状态**，
既不能 `next()` 也不能再 `moveToInsertRow()`，直接读会抛「光标位置无效」。
**必须补 `moveToCurrentRow()`** —— 少了这行，Hibernate 读到的列元数据为空 →
**误判「库里没表」→ `ddl-auto: update` 会去重建整库！**

> 这个坑值得单独记一笔：它不会报错，只会**静默地做错事**。

### 10.5 顺带修正的配置谎言

排查上述问题时发现：`application.yml` 的主数据源一直写着 `data/admin.db`（**0 字节，从未被使用**），
真正生效靠启动时命令行覆盖 `--spring.datasource.url=...sandbox.db` 蒙混过关。

**后果**：任何不带该参数启动的场合（IDE 直接跑、Docker 镜像默认启动、CI）
都会连到空库 → Hibernate 重建 schema → **数据全丢**。

已修正：
- yml 主数据源改为 `data/sandbox.db`，并允许 `SPRING_DATASOURCE_URL` 覆盖
- `SpringBootLearningApplication` 的启动横幅改为**从 Environment 读真实生效的 URL**
  （以前硬编码 `data/admin.db`，**横幅一直在骗人**）
- `start-all.sh` 的总结横幅同步修正

### 10.6 验证结果

| 项 | 结果 |
|---|---|
| 后端全量测试 | **221 个全绿**（219 基线 + 2 条新增回归），Failures 0 / Errors 0 / Skipped 10 |
| 后端打包 | `target/spring-boot-learning-1.0.0-SNAPSHOT.jar` = 82,646,232 bytes |
| 后端启动 | ✅ 从仓库根启动成功，日志出现 `[SQLite] 元数据查询已切到逐表模式: 50 张表 / 497 列`，**无 `too many terms`** |
| 启动横幅 | ✅ `✓ 数据库: SQLite -> /Volumes/data/demo/java/data/sandbox.db`（真实路径） |
| 接口实测 · 农场 board | ✅ `today = 2026-11-16`（= 现实 10-25 + `offset_days` 22，与 `t_farm_user_clock` 吻合） |
| 接口实测 · 仓库 | ✅ HTTP 200，6 条货物，`storedDate` 正常返回（**不再 500**），`qualityFactor = 1.0` |
| 接口实测 · 点赞 toggle | ✅ `likeCount` 0 → 1 → 0，`liked` 同步翻转 |
| 接口实测 · 收藏 toggle | ✅ `favoriteCount` 1 → 0 → 1，`favorited` 同步翻转 |
| 接口实测 · 转发 | ✅ `shareCount` 5 → 6 |
| 浏览器 · 1600×900 | ✅ 仪表盘 / 农场 3D 场景 / 中国地图浮层 / 仓库面板全部正常渲染 |
| 浏览器 · 375×812 | ✅ 农场上下堆叠布局、仓库表格自动改卡片式，均无横向溢出 |

### 10.7 本轮新增的教训

**① 捕获唯一约束异常 ≠ 处理并发冲突**
`DataIntegrityViolationException` 会把事务标记为 rollback-only，后续操作全灭。
要「幂等插入」就用数据库原生的 `INSERT OR IGNORE`，靠受影响行数判断。

**② `INSERT OR IGNORE` 只在真有唯一索引时才有意义**
实体上的 `@UniqueConstraint` 不会在 SQLite 已有表上被 `ddl-auto: update` 补建 ——
必须手写 DDL 建索引（`ForumInteractSchemaPatch`）。

**③ 代理 `ResultSet` 时要留意游标状态机**
`insertRow()` 之后必须 `moveToCurrentRow()`，否则「光标位置无效」。
更危险的是某些写法**不报错**（误判空库 → 重建表），属于静默错误。

**④ 配置与实际行为不一致 = 定时炸弹**
yml 写着 `admin.db`、实际跑 `sandbox.db`，靠命令行覆盖 —— 平时没事，
换个启动方式（IDE / Docker / CI）就炸。**配置必须和实际行为对齐**，
启动横幅也要打印**真实生效值**而不是硬编码。

**⑤ 代理 `DataSource` 时别忘了 `destroyMethod = "close"`**
`DataSource` 接口本身没有 `close()`，代理类若只实现 `DataSource`，
Spring 销毁时会抛「Couldn't find a destroy method named 'close'」——
代理时记得同时实现 `AutoCloseable`。

**⑥ 沙箱里 IPv4 和 IPv6 可能是两个世界**
`Electron`（WorkBuddy 应用）占着 `127.0.0.1:<端口>` 做流量网关（会要求鉴权），
而 Java 绑的是 IPv6 `*:<端口>`。用 `127.0.0.1` curl 会被网关拦（401 `AUTH_REQUIRED`），
用 `[::1]` 才能直连真实后端。Node 解析 `localhost` **优先 IPv6**，所以 Vite 代理用 `localhost` 是安全的。

---

*本报告由静态扫描 + 双路人工审查 + 逐条实测复核生成。所有「严重」结论均经代码复读或接口调用验证。*
*S1–S5 已于 2026-10-25 全部修复，回归测试 219 个全绿。*
*M1–M12 + 3 条轻级 + 架构级隐患已于 2026-10-26 全部修复，回归测试 221 个全绿。*
