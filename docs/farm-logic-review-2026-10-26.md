# 稼穑农场游戏逻辑分析报告

> 分析日期：2026-10-26
> 代码基线：`e1ccde1`（6 个 commit 已推送至 `origin/main`）
> 方法：静态代码分析 + **真实引擎探针实测**（直接调用 `CropCatalog` / `SimEngine` / `WarehouseEngine`，非手工估算）
> 范围：后端农场模块 47 个类 / 10149 行（`farm/` + `farm/engine/`）

---

## 〇、结论速览

农场引擎层（22 个类 / 6397 行）是一套**质量相当高的农艺模拟系统**：纯函数、无状态、
`dvsByDay` 作为中枢数据契约让 5 个引擎共享同一发育轨迹，`ActionEffect` 把 14 种农事
归约成 3 个标量 —— 这些设计都值得保留。

但**服务编排层存在三个结构性问题**，它们正在拖累整个游戏：

| 级别 | 问题 | 影响 |
|---|---|---|
| 🔴 P0 | **`spend()` 零调用点 → 农资施用不扣钱** | 经济闭环断裂，现金只进不出 |
| 🔴 P0 | **价格表双份定义 + 收割价 ≠ 实际入账价** | 玩家看到的数字与到手的钱对不上 |
| 🔴 P0 | **同请求内 8 次 DB 查询 + 5 次重复天气构造** | 地块一多就明显卡顿 |
| 🟠 P1 | **四引擎同型代码 + 三条平行农事通路** | 改了 3 处漏 1 处，历史已发生 4 次 |
| 🟠 P1 | **月季满产越界（1.50）+ 药剂 id 拼错 + 硬编码 visual 判断** | 数值/数据一致性隐患 |
| 🟡 P2 | `FarmPlayService` 单文件 2992 行 | 维护成本高，但**暂不紧急** |

**方向建议**：**不要大重构**。先补经济闭环（3 个小改动），再抽 `SeasonContext`
（一次中等重构），最后才考虑拆分 `FarmPlayService`。

---

## 一、架构现状：引擎层与编排层的分离

### 1.1 分层结构

```
FarmController (24 端点)
   ↓
FarmPlayService (2992 行)  ← 编排层：唯一门面，8 个引擎的调度者
   ↓
engine/ 22 个 final class（全部 static 纯函数，无 Spring Bean）
   SimEngine / SoilEngine / PestEngine / WeedEngine / DisasterEngine
   GerminationEngine / SuitabilityEngine / WarehouseEngine / OpChain
   CropCatalog / HortConfigs / CropVisual / InputCatalog
   ↓
FarmWarehouseService → FarmShopService → FarmStockService
   （依赖单向，不可反向）
```

**值得肯定的设计**：

1. **引擎纪律一致** —— 22 个引擎全部 `final class + static method`，无状态、无 IO、可单测。
2. **`dvsByDay` 是中枢数据契约** —— `SimEngine` 产出逐日 DVS 轨迹，其余 4 个引擎消费它，
   保证「同一块地的发育阶段」在各引擎中口径一致。
3. **`ActionEffect` 归约** —— 14 种 `FarmAction.Type` 归约成 3 个标量
   （`tmaxOffset` / `fertMitigation` / `floretMitigation`），引擎层只认三原语。
4. **`CropVisual.formOf()`** —— 正确处理了 `visual` 字段「通用形态族 → 作物 id」的历史语义变更，
   避免静默落到默认形态族。

### 1.2 两级时钟模型（已在本轮修复）

```
用户级世界日期 = 现实今天 + t_farm_user_clock.offset_days
                  → 仓库老化 / 天气取段 / 推荐播期 / 商店 / 地图 today
地块级生长时钟 = 播期 + t_farm_plot.season_progress_days
                  → 作物回放 / 物候 / 出苗率 / 产量
```

旧设计的病根是「用户级今天 = max{现实今天 + 每块地的 demoOffsetDays}」，
任何无关地块的脏偏移都会污染整个用户级口径。已迁移为
`t_farm_user_clock` 单表，地块 `demoOffsetDays` 退化为冗余镜像。

### 1.3 地块状态机

```
S0 待开垦 (zoneChosen=false, seasons=0)
   ↓ createField
S1 在长 GROWING (zoneChosen=true, cropId/sowDate set, FarmSeason=GROWING)
   ├── 派生 DVS ≥ 0.92 ──→ 可收割
   └── 派生枯死 (GerminationEngine) ──→ 绝收
   ↓ harvest / closeFailedSeason / restart
S4 待播种 AWAITING_SOW (zoneChosen=false, awaitingSow=true, progress=0)
   ↓ createField → 回到 S1
```

设计要点：**DVS/积温/损伤全部不落库**，只存「种下去是哪天」，
由「播种日 → 今天」的天气重算得出。这是正确的（状态机 = 派生量 + 少量持久标记）。

---

## 二、🔴 P0-1：农资施用不扣钱 —— 经济闭环断裂

### 2.1 证据

| 位置 | 事实 |
|---|---|
| `FarmShopService.java:296` | `spend(Long userId, double amount)` 完整实现，注释写着「施用农资、灌溉等消耗」 |
| **全仓 grep** | **`.spend(` 零调用点** |
| `FarmPlayService.java:1950` | `double cost = round(dose * it.pricePerUnit(), 2);` |
| `FarmPlayService.java:2024` | `a.setCost(cost);` —— cost 只**记录**到 `FarmAction` |
| `FarmPlayService.java:1590/1601` | 灌水只扣**水额度**（库存），不扣钱 |

### 2.2 后果

- 玩家的现金支出只有：**种子**（¥3–320/亩）+ **农具**（一次性）+ **水**（按次）
- 化肥/农药/除草剂**全部免费**
- 玩家初始还免费获得 17 种农资共约 6800 单位（`FarmStockService.INITIAL_STOCK`），价值约 ¥2000+
- `FarmWallet.INITIAL_BALANCE = 800` 的注释估算写着「复合肥 288 元 + 灌溉水 360 元」——
  **这两项永远不会发生**（水要买，肥免费）
- `buy()` 会累加 `totalExpense`，施用不累加 → **支出统计口径也是错的**

### 2.3 为什么这是 P0

它不是「数值调得不好」，而是**经济系统的一条腿断了**：
玩家种得多赚得多，而所有经营成本都是 0，最优策略退化为「无限扩地 + 不做任何管理」。
这让**后面精心设计的所有农艺机制（错窗减效、抗性累积、剂量报酬递减、杂草抢水肥）
全部失去经济意义** —— 因为「省下农资钱」根本不是一笔要权衡的账。

### 2.4 修法

`spend()` 已实现且注释明确「余额不足时返回 false，让调用方决定怎么办」，
所以接线是安全的：

```java
// FarmPlayService.applyInput() 内，写 FarmAction 之后
double paid = shopService.spend(userId, cost);
if (!paid) {
    warnings.add("钱包余额不足，本次施用未扣款（欠款 ¥" + fmt(cost) + "）");
}
```

⚠️ **不能抛异常** —— 抛出去会回滚整个事务，农事动作（写 `FarmAction`、扣库存）全部作废。
这与 `FarmWarehouseService.sell()` 的既有约定一致（`spend()` 注释已写明）。

灌水同理：`performAction()` 的 `IRRIGATE` 应在扣水额度时一并 `spend(水量 × 单价)`。

---

## 三、🔴 P0-2：价格表双份定义 + 收割价 ≠ 实际入账价

### 3.1 两个价格源

| 来源 | 形式 | 数量 |
|---|---|---|
| `FarmPlayService.priceOf()`（:76–105） | `switch(cropId)` 22 分支 | 22 |
| `WarehouseEngine.STORAGES`（:128–154） | `new Storage(...)` 22 条 | 22 |

**当前数值恰好一致**（逐一比对：rice 2.58/2.58、tomato 4.50/4.50、strawberry 20/20…），
但这是**靠人工同步维持的巧合**，没有任何编译期保障。

### 3.2 收割展示价 vs 实际入账

```java
// FarmPlayService.harvest() :1264-1265  —— 展示给玩家的「收入」
double cropPrice = priceOf(cropIdOf(plot));         // 无地区/品质/供给系数
double revenue = round(yield * cropPrice * priceFactor, 1);
out.put("revenue", revenue);                        // ← 玩家看到的

// FarmWarehouseService.sell() :519-523  —— 真正到手的钱
double unit = round(basePrice × zonePriceFactor × g.factor() × qf × supplyFactor, 3);
```

两套公式：收割用**裸价**，卖出用**五因子乘积**。
即便基准价一致，同一季作物的「收割提示净收益」与「仓库实际卖价」也必然不同
（地区系数、品质系数、供给系数都没进收割公式）。

这正是 `FarmPlayService.java:1390-1392` 注释试图避免的矛盾，但只修了 `qualityFactor`，
漏了 `zonePriceFactor` 与 `supplyFactor`。

### 3.3 修法（建议）

**单一真源** —— 把价格收进 `CropConfig`（或新增 `CropMeta`）：

```java
// CropConfig 新增字段
double basePrice;      // 收购基准价
int storageDays;       // 仓储期
int warnDays;          // 临期提醒提前量
boolean perishable;    // 是否易腐
```

`WarehouseEngine.STORAGES` 改为从 `CropCatalog` 派生，`priceOf()` 直接删掉。

**收割预估走完整公式** —— 让 `harvest()` 复用 `WarehouseEngine.value()`：

```java
// 而不是在 harvest 里手写 revenue
WarehouseEngine.Valuation v = WarehouseEngine.value(
        cropId, zoneId, storedKg, storedDate, today, priceFactor);
out.put("revenue", v.amount());
```

这样「收割提示」与「实际卖价」**用的是同一个函数**，不可能对不上。

---

## 四、🔴 P0-3：同请求内 8 次 DB 查询 + 5 次重复天气构造

### 4.1 `view()` 内部的重复调用

| 被调方法 | 调用次数 | 调用点 |
|---|---|---|
| `seasonActions(userId, plot, from, to)` | **8 次** | :722, :2081, :2167, :2203, :2316, :2369, :2691, :2732 |
| `weather.range(userId, zone, from, to)` | **5 次** | :2308, :2367, :2687, :2729, :2923 |

`weather.range` 每次都新建 `ArrayList<DayWeather>` 并逐日 `forDate()` 分配对象 ——
**同一段 431 天天气被构造 5 遍**。`seasonActions` 每次是一条 DB 查询（含按
`seasonStartedAt` 的内存全量过滤，`created_at` 无索引）。

`view()` 已经是 `@Transactional(readOnly = true)`，但**请求内没有任何缓存**。
唯一的缓存 `simCache` 只缓存 `SimResult`，且跨请求共享。

### 4.2 `plotSummaries()` 的 N+1

```java
// FarmPlayService :538-606，被 FarmMapService.board / plotsOfFarm 复用
for (FarmPlot p : listPlots(userId)) {              // 最多 100 块（MAX_PLOTS）
    sim = safeReplay(userId, p, p.getSowDate(), today);   // 每块 1 次 DB + 431 天回放
    ger = germination(userId, p, cfg);                     // 每块 1 次天气 range
    if (sim != null) wr = weeds(userId, p, sim, today);   // 每块 1 次 DB + 引擎
}
```

**200 次 DB 查询 + 100 次全季回放**。`FarmMapService.board` 的注释称
「100 块地也就几十毫秒」—— 但这只算了 SimEngine，未计入 200 次 DB 往返。

### 4.3 `advance()` 的 N 次写

```java
// FarmPlayService :1062-1069
for (FarmPlot p : plotRepo.findByUserIdOrderBySlotAsc(userId)) {
    p.setDemoOffsetDays(userOffset);
    if (!p.getId().equals(plot.getId())) plotRepo.save(p);   // ← 循环内逐条 UPDATE
}
```

`demoOffsetDays` 现在只是 `t_farm_user_clock.offset_days` 的**冗余镜像**
（两级时钟模型已确立它是镜像），这个循环写 100 次 UPDATE 完全没必要。

### 4.4 修法

**请求级上下文**（与第五节的 `SeasonContext` 合并做）：

```java
// 一次性取齐，所有子引擎共用
record SeasonContext(
    List<DayWeather> days,      // 天气只构造一次
    List<FarmAction> actions,   // 农事只查一次
    double[] dvsTrace,          // SimEngine 产出
    int n,
    LocalDate winStart
) { static SeasonContext of(userId, plot, r, today) { ... } }
```

**批量写**：`advance()` 的镜像同步改成一条
`UPDATE t_farm_plot SET demo_offset_days = ? WHERE user_id = ?`（`JdbcTemplate`）。

**更好的选择**：既然是冗余镜像，**直接删掉这一列**，
让 `demoOffsetDays` 只在 `createInherited` 里作为历史兼容读一次。

---

## 五、🟠 P1-1：四引擎同型 + 三条平行农事通路

### 5.1 四处同型代码

`pests()` / `weeds()` / `soil()` / `disasters()`
（`FarmPlayService :2299-2380`、`:2678-2739`）**结构几乎一模一样**：

```java
LocalDate winStart = replayWindowStart(sow, today);              // 相同
List<DayWeather> days = weather.range(userId, zoneOf(plot), winStart, today);  // 相同
for (FarmAction a : seasonActions(userId, plot, winStart, today)) { ... }      // 相同
return XxxEngine.simulate(days, 0, n, r.dvsByDay(), ..., seed);  // 仅参数不同
```

**最有说服力的证据**：这四处的历史注释**四次记录同一个 bug** ——
「窗口口径必须与 doReplay 一致」「同型代码只修一处」（:2306, :2360-2365, :2685, :2727）。
2026-10-25 修 weeds 时，另外三处仍错位。

### 5.2 三条平行通路

| 通路 | 类型 | 构造点 | 消费引擎 |
|---|---|---|---|
| A | `Map<String, List<SimEngine.ActionEffect>>` | `doReplay()` :2927-2941 | SimEngine（高温损伤） |
| B | `List<PestEngine.Spray>` | `pests()` :2309-2335 | PestEngine |
| C | `List<WeedEngine.WeedControl>` | `weeds()` :2368-2376 | WeedEngine |

三者从同一份 `FarmAction` 列表**分别遍历、分别分类、分别处理 legacy 兼容**
（`anyLegacy` 判定、`chemical` 判定、`durationDays` 展开各写一遍）。

### 5.3 修法

```java
// 一次遍历产出全部三种中间表示
record ActionBundle(
    Map<String, List<SimEngine.ActionEffect>> effects,
    List<PestEngine.Spray> sprays,
    List<WeedEngine.WeedControl> weedControls,
    Set<LocalDate> drains,
    Set<LocalDate> irrigates,
    Map<LocalDate, Double> nByDate
) { static ActionBundle of(List<FarmAction> actions) { ... } }
```

配合 `SeasonContext`，四个引擎方法各缩到 5–10 行，
**「同型代码只修一处」的历史问题结构性消失**。

---

## 六、🟠 P1-2：数值与数据一致性（3 个具体缺陷）

### 6.1 月季满产越界

真实引擎探针实测 22 种作物的「满产 / yieldRef」比值：

```
0.76–1.18  正常（21 种）
1.50       月季（切花）  ← 越界
```

根因（`HortConfigs.java:664`）：`yieldRef = 1000` 但 `yieldCap = 1500`，
满产公式算出 1500kg/亩，超出参照值 50%。

**修法**：把 `rose` 的 `yieldRef` 从 1000 提到 ~1500，或把 `yieldCap` 降到 ~1000。
前者更合理（切花月季亩产 1500kg 是真实的），但会让月季在适宜度评级里
更难达标 —— 需要一起评估。

### 6.2 药剂 id 拼写错误

```
InputCatalog.java:168        "CHLORANTRANILIPROLE"   ← 正确（氯虫苯甲酰胺）
FarmStockService.java:56     "CHLORANBENILIPROLE"    ← 拼错（TRAN→BEN）
```

`ensureInitialStock()` 遍历 `INITIAL_STOCK`，找不到定义就 `continue`
→ **氯虫苯甲酰胺的初始备货被静默跳过**，玩家首次进入时这件药库存为 0
（其余 16 种都有）。

**修法**：改 `FarmStockService.java:56` 的 id。

### 6.3 硬编码 visual 判断（4 处）

```java
FarmPlayService.java:2033    "soybean".equals(crop.visual())   // 判豆科
FarmPlayService.java:2379    "rice".equals(crop.visual())      // 判水田
FarmPlayService.java:2716    "soybean".equals(crop.visual())   // 判豆科
FarmPlayService.java:2717    "rice".equals(crop.visual())      // 判水田
```

风险：`visual` 字段的语义**已经变过一次**（2026-10-24，通用形态族 → 作物 id），
现在靠「4 个粮食作物保持旧值」兼容。若将来统一改成 crop id，
`"rice".equals(visual)` 会**静默失效** → 所有作物走旱地分支 → 水稻完全错误；
`"soybean".equals(visual)` 失效 → 固氮机制失效。

**修法**：走已有的 `CropVisual` 抽象，加 `isPaddy()` / `isLegume()` 方法：

```java
// CropVisual 新增
public static boolean isPaddy(String visual)   { return "rice".equals(visual); }
public static boolean isLegume(String visual)  { return "soybean".equals(visual); }
```

> ⚠️ 注意：`isPaddy` 目前**只能**匹配 `"rice"`（`rice_mid` 的 visual 就是 `"rice"`），
> 不能用 `Form.GRAIN` 判断 —— 小麦/玉米/大豆也是 GRAIN 但不是水田。

---

## 七、🟡 P2-1：`FarmPlayService` 2992 行的拆分建议

**但不要现在就拆。** 理由：

1. 拆分本身不产生用户价值，而当前有 3 个 P0 经济问题待修
2. 职责边界其实比较清楚（已按 A–O 分节），拆分风险可控但收益有限
3. **建议顺序**：先做第五节的 `SeasonContext` 抽象（它天然会把
   `pests`/`weeds`/`soil`/`disasters` 四个方法瘦身），
   再看剩余体量决定是否拆文件。

**若要拆**，建议按这四刀切（不是按技术分层，而是按数据流）：

| 新类 | 承载内容 | 行数估算 |
|---|---|---|
| `FarmSeasonContext` | `SeasonContext` + `ActionBundle` + 窗口口径 | ~200 |
| `FarmEngineRunner` | 四个子引擎的调用封装 | ~250 |
| `FarmYieldCalculator` | `yields()` + `toolBonusOf()` | ~200 |
| `FarmPlotLifecycle` | `createField` / `harvest` / `restart` / `closeFailed` | ~600 |

`FarmPlayService` 保留编排 + 视图组装，降到 1500 行左右。

---

## 八、经济系统实测数据（真实引擎探针）

### 8.1 满产自洽性

| 作物 | 满产 kg/亩 | yieldRef | 比值 | 单价 | 满产毛收入 |
|---|---|---|---|---|---|
| 草莓 | 1512 | 1500 | 1.01 | 20.00 | ¥30,240 |
| 月季 | 1500 | 1000 | **1.50** | 18.00 | ¥27,000 |
| 番茄 | 4590 | 4500 | 1.02 | 4.50 | ¥20,655 |
| 黄瓜 | 4860 | 5000 | 0.97 | 3.20 | ¥15,552 |
| 辣椒 | 2550 | 2500 | 1.02 | 6.00 | ¥15,300 |
| 郁金香 | 563 | 500 | 1.13 | 25.00 | ¥14,063 |
| 茄子 | 3740 | 3500 | 1.07 | 3.60 | ¥13,464 |
| 豆角 | 2040 | 2000 | 1.02 | 6.00 | ¥12,240 |
| 甜瓜 | 2295 | 2500 | 0.92 | 5.00 | ¥11,475 |
| 菊花 | 720 | 800 | 0.90 | 15.00 | ¥10,800 |
| 葡萄 | 612 | 600 | 1.02 | 12.00 | ¥7,344 |
| 西瓜 | 3570 | 3500 | 1.02 | 2.00 | ¥7,140 |
| 梨 | 1381 | 1400 | 0.99 | 5.00 | ¥6,906 |
| 大白菜 | 4950 | 5000 | 0.99 | 1.20 | ¥5,940 |
| 萝卜 | 2904 | 3000 | 0.97 | 1.50 | ¥4,356 |
| 马铃薯 | 2300 | 2000 | 1.15 | 1.60 | ¥3,680 |
| 水稻 | 631 | 600 | 1.05 | 2.58 | ¥1,627 |
| 向日葵 | 189 | 250 | 0.76 | 8.00 | ¥1,512 |
| 玉米 | 492 | 500 | 0.98 | 2.30 | ¥1,133 |
| 冬小麦 | 471 | 400 | 1.18 | 2.40 | ¥1,131 |
| 大豆 | 168 | 180 | 0.93 | 5.00 | ¥840 |
| 谷子 | 132 | 150 | 0.88 | 3.20 | ¥424 |

### 8.2 含供需压力后的实得（单亩零损伤满产）

| 作物 | 种子 ¥/亩 | 满产 kg | 供需因子 | 仓储天数 | 实得 ¥/亩 |
|---|---|---|---|---|---|
| 草莓 | 96 | 1512 | 0.77 | **3** | 23,147 |
| 月季 | 320 | 1500 | 0.77 | **5** | 20,709 |
| 郁金香 | 140 | 563 | 0.92 | **5** | 12,868 |
| 番茄 | 14 | 4590 | 0.50 | 14 | 10,333 |
| 辣椒 | 26 | 2550 | 0.65 | 21 | 9,935 |
| 菊花 | 110 | 720 | 0.89 | 7 | 9,572 |
| 豆角 | 13 | 2040 | 0.70 | 8 | 8,588 |
| 甜瓜 | 29 | 2295 | 0.67 | 21 | 7,740 |
| 黄瓜 | 24 | 4860 | 0.49 | 12 | 7,550 |
| 茄子 | 16 | 3740 | 0.55 | 12 | 7,448 |
| 葡萄 | 110 | 612 | 0.91 | 15 | 6,652 |
| 梨 | 130 | 1381 | 0.78 | 45 | 5,408 |
| 大白菜 | 9 | 4950 | **0.48** | 45 | 2,856 |
| 西瓜 | 30 | 3570 | 0.57 | 30 | 4,035 |
| 马铃薯 | 15 | 2300 | 0.67 | 60 | 2,480 |
| 水稻 | 6 | 631 | 0.90 | 270 | 1,469 |
| 大豆 | 4 | 168 | 1.00 | 300 | 837 |
| 谷子 | 5 | 132 | 1.00 | 240 | 424 |

### 8.3 如何解读

**收益差 71 倍（草莓 ¥23,147 vs 谷子 ¥424）是合理的，不是 bug**：
= 单价差 6 倍（¥20 vs ¥3.2）× 产量差 11 倍。
且高收益作物都有硬约束：

- **仓储期极短**：草莓 3 天、月季 5 天、郁金香 5 天 —— 不马上卖就归零（¥0）
- **供需压力惩罚高产**：白菜 0.48、番茄 0.50、黄瓜 0.49 —— 种得多反而单价腰斩
- **种子成本高**：月季 ¥320/亩、梨 ¥130、郁金香 ¥140

**但正因为农资免费（P0-1），这些约束目前只约束了「卖货」侧，没有约束「投入」侧** ——
玩家可以无成本地狂施农药保产量、狂浇水保干旱，而成本侧是 0。

---

## 九、改进路线图（建议按此顺序）

### 第一阶段：经济闭环（3 个小改动，约 200 行）

| # | 改动 | 文件 | 风险 |
|---|---|---|---|
| 1 | 施用农资接 `spend()`（不抛异常，欠款只提示） | `FarmPlayService.applyInput` + `performAction` | 低（`spend()` 已实现） |
| 2 | 价格单一真源：新增 `CropConfig.basePrice`，`STORAGES` 改为派生，删 `priceOf()` | `CropConfig` / `WarehouseEngine` / `FarmPlayService` | 中（22 作物 × 3 处） |
| 3 | 收割预估改走 `WarehouseEngine.value()`，与实际卖价同源 | `FarmPlayService.harvest` | 低 |

**验收**：走完一季「买种子 → 施肥 → 收割 → 卖货」，
钱包余额变化 = 收入 − 种子 − 农资 − 水；收割提示的 revenue == 仓库实际卖价 × 品质系数。

### 第二阶段：一致性修复（3 个小改动，约 50 行）

| # | 改动 | 文件 |
|---|---|---|
| 4 | 修 `FarmStockService:56` 药剂 id | `FarmStockService` |
| 5 | 月季 `yieldRef` 1000 → 1500 | `HortConfigs` |
| 6 | 4 处硬编码 visual 判断改走 `CropVisual` | `CropVisual` / `FarmPlayService` |

### 第三阶段：请求级上下文（中等重构，约 400 行）

| # | 改动 | 说明 |
|---|---|---|
| 7 | 新增 `SeasonContext` + `ActionBundle` | 天气构造 5→1，农事查询 8→1 |
| 8 | 四个子引擎方法改用 context | 各缩到 5–10 行 |
| 9 | `advance()` 镜像同步改批量 UPDATE 或删列 | 100 次写 → 1 次 |

### 第四阶段（可选）：拆分 `FarmPlayService`

待第三阶段完成后重新评估体量再决定。

---

## 十、方法论教训（本轮亲历）

1. **算数值必须用真实代码复算，不要凭印象填参数。**
   本轮一开始手工抄 `CropConfig` 参数（水稻 `paniclesPot` 猜成 18，实际是 160000），
   算出「水稻满产 0.0 kg/亩」的荒谬结果，还差点得出「收益差 878048 倍」的结论。
   改用探针直接调 `CropCatalog.all()` + `SimEngine` 公式后，
   真实数据是「71.3 倍，且成因合理」。

2. **子 agent 的结论必须独立验证。**
   本轮两个 agent 报告中有 4 处误判：
   「园艺/粮食收益差 21.5 倍、rose 1.94」（参数错）、
   「供给压力几乎不作用」（实测白菜 0.48 明显在作用）、
   hardcoded visual 的行号偏差（报 :2716-2717，实际还有 :2033/:2379）。
   agent 报告的价值在于**给出候选清单**，结论必须自己复核。

3. **「本地能跑」不等于「代码正确」。**
   推送前的 worktree 验证发现两件事：
   `CropVisual.java` 漏提交（远端 clone 编译失败）、
   `target/test-data/` 目录不存在（全新 clone 跑测试 17 个 Error）。
   两者都在本机因「早先碰巧存在」而幸免。
   → **任何要推送的改动，都必须在干净 worktree 上复现一遍。**

---

## 附录：分析涉及的关键文件

| 文件 | 行数 | 角色 |
|---|---|---|
| `farm/FarmPlayService.java` | 2992 | 编排层核心（本次分析重点） |
| `farm/FarmWarehouseService.java` | 711 | 仓储（批次/变卖/品质） |
| `farm/FarmShopService.java` | 359 | 商店（买/卖/钱包） |
| `farm/FarmStockService.java` | 308 | 农资库存 |
| `farm/FarmUserClockService.java` | 101 | 用户级时钟 |
| `farm/engine/SuitabilityEngine.java` | 1209 | 适宜度评级（10 年回放） |
| `farm/engine/HortConfigs.java` | 680 | 16 种园艺作物参数 |
| `farm/engine/PestEngine.java` | 664 | 病虫害 |
| `farm/engine/InputCatalog.java` | 565 | 6 类投入品统一 record |
| `farm/engine/WarehouseEngine.java` | 528 | 仓储引擎（价格五因子） |
| `farm/engine/SimEngine.java` | 404 | 生长/积温/产量四要素 |
| `farm/engine/CropCatalog.java` | 386 | 22 作物目录 |
| `farm/engine/SoilEngine.java` | 292 | 土壤水分/地力/氮素账本 |
| `farm/engine/WeedEngine.java` | 273 | 杂草 |
| `farm/engine/GerminationEngine.java` | 197 | 出苗/枯死 |
| `farm/engine/DisasterEngine.java` | 188 | 灾害 |

**探针位置**：`/tmp/yieldprobe/{YieldProbe,EconProbe,FarmPriceProbe}.java`
（直接调用 `CropCatalog.all()` / `WarehouseEngine.storage()` / `InputCatalog.seedOf()`）
