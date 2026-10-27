# 稼穑定位为「农业科学模块」的实施方案

> 前提：用户明确 **「是农业科学模块」**（不是「学习平台里的一个游戏」）。
> 本文所有结论来自**实测**（代码 grep + 真实数据库 + 浏览器实测），不是估计。
> 关联：`docs/game-iteration-roadmap.md`（上一版「游戏迭代方向」）
> → **定位变更后，优先级需要重排** —— 见 §五。

---

## 一、定位变更带来的根本差别

| | 定位 A：学习平台里的一个游戏 | 定位 B：**农业科学模块**（本次确定） |
|---|---|---|
| 成功标准 | 玩得爽、留存高 | **能教懂**「为什么这样种」 |
| 核心产出 | 分数 / 排名 / 成就 | **可迁移的农学概念与判断力** |
| 关键界面 | 排行榜、图鉴、成就墙 | **原理讲解、归因分析、概念卡** |
| 内容量优先级 | 先做循环闭环 | 先做**解释密度** |
| 平台关系 | 挂上去就行 | **必须是平台的一等学科** |

### 🔴 定位 B 暴露的硬缺口（实测）

**引擎已经算出了全套科学量，但前端一个都没展示。**

| 科学量 | 后端 | 前端 | 含义 |
|---|---|---|---|
| `aatCum` / `aatReq` | ✅ 下发（`CropView`） | ❌ 未用 | 有效积温累计 / 需求 |
| `sfi` 地力 | ✅ 下发 | ⚠️ 仅 1 处 | 土壤肥力 0–100 |
| `soilCoef` 产量系数 | ✅ 下发 | ❌ 未用 | 地力对产量的折算 |
| `nApplied` / `nNeed` 氮素 | ✅ 下发 | ❌ 未用 | 施氮量 / 全季需氮 |
| `nSatisfaction` 氮素满足度 | ✅ 下发 | ❌ 未用 | Mitscherlich 报酬递减 |
| `droughtLoss` / `waterlogLoss` | ✅ 下发 | ❌ 未用 | 干旱 / 渍水减产 |
| `waterlogDays` / `dryDays` | ✅ 下发 | ❌ 未用 | 累计受旱 / 深水天数 |
| `etToday` / `percToday` | ✅ 下发 | ⚠️ 仅 1 处 | 蒸散 / 渗漏 mm |
| `heatForce` / `daysOverOpt` | ✅ 下发 | ❌ 未用 | 热害强度 / 超.opt 天数 |
| `dmg.panicle/floret/fertility/weight` | ✅ 下发 | ❌ 未用 | 产量四要素伤害分解 |
| `vernalizationDays` 春化天数 | ✅ 下发 | ❌ 未用 | 冬小麦春化 |
| `frostStop` / `winterKilled` | ✅ 下发 | ❌ 未用 | 霜冻 / 越冬致死 |
| `yieldRaw` / `yieldCap` / `yieldCapped` | ✅ 下发 | ❌ 未用 | 原始产量 vs 上限 |

> **数据管道是通的**（`FarmView` 已下发 → 前端未消费）。
> **缺的不是算力，是「科学解释层」。**

---

## 二、🔴 平台侧缺口：8 个科目全是编程类，没有农业科目

实测 `t_learning_subject`：

```
Java(43章/128课) · Web开发(39/106) · Python(16/56) · Node.js(16/49)
JavaScript进阶(13/55) · SQL(11/38) · Git(10/29) · JavaScript(0/0)
```

**零农业科目。** 定位 B 意味着稼穑不能只是一个路由 `/games/farm`，
而要成为平台的**第 9 个学科**，有自己的章节 / 课时 / 学习进度。

### 知识载体现状
- `t_wiki_article`：**1 行**（标题 "test"）—— 知识库基本空着
- `design/gdd/` 设计文档：**代码注释里被引用，但文件不在仓库** ——
  🔴 **农学知识目前只存在于代码里，没有任何可教学的知识文档**

---

## 三、方案：三层科学解释层

### 3.1 分层结构

```
第 1 层 · 现象（Phenomenon）    现在有什么：产量 / 分数 / 告警        —— 已有
第 2 层 · 归因（Attribution）    为什么这样：哪个环节减产、减多少      —— 数据有，无 UI
第 3 层 · 原理（Principle）      背后的科学：积温/氮素/地力/水分平衡  —— 数据有，无 UI
第 4 层 · 建议（Actionable）     我该做什么：换作物 / 改播期 / 补肥    —— 仅播期环节有
```

**当前状态：第 1 层完整，第 2 层数据齐但没 UI，第 3 层数据齐但没 UI，第 4 层只有播期一个入口。**

### 3.2 复用现成的解释模板（不发明新结构）

`SuitabilityEngine.RiskItem` 已经是标准三段式：

```java
public record RiskItem(String code, String title, String detail, int level, String advice) {
}
```

`/planting-risk` 端点已经在用它产出「**为什么是这个评级，以及该怎么办**」。
→ **科学解释层不发明新结构，直接把 `RiskItem` 提升为跨环节通用结构。**

### 3.3 首批要解释的概念（按「可解释性 × 数据完备度」排序）

| # | 概念 | 数据 | 讲解要点 | 归因映射 |
|---|---|---|---|---|
| 1 | **有效积温** | `aatCum/aatReq` | 为什么早播会减产：积温不够，灌浆不足 | `eyRaw` 下降 |
| 2 | **氮素报酬递减** | `nApplied/nNeed/nSatisfaction` | 施氮到需求量的 82% 后边际收益骤降 | `soilCoef` 上升放缓 |
| 3 | **土壤水分平衡** | `etToday/percToday/dryDays/waterlogDays` | 蒸散与渗漏如何决定旱/涝 | `droughtLoss`/`waterlogLoss` |
| 4 | **产量四要素** | `dmg.panicle/floret/fertility/weight` | 减产是**穗数 / 颖花 / 结实 / 粒重**哪个环节崩的 | 直接对应 `Damage` |
| 5 | **地力与轮作** | `sfi/soilCoef` | 为什么连年重茬地力掉 | `soilCoef` 逐年下降 |
| 6 | **热害与积温胁迫** | `heatForce/daysOverOpt` | 高温天数怎么吃掉产量 | `floret` 败育 |
| 7 | **春化与越冬** | `vernalizationDays/winterKilled` | 冬小麦不春化就不抽薹 | 越冬致死 |
| 8 | **安全间隔期（PHI）** | `phiViolated/phiViolations` | 农药残留超标如何折价 | `priceFactor` |

**这 8 个概念就是「农业科学模块」的最小教学内核** —— 每个都能用「**现象 → 量 → 原理 → 建议**」四句话讲清。

---

## 四、实施方案（三期）

### 🔴 P0 · 归因明细可视化（立即做，零后端改动）

**做什么**：游戏历史页 farm 分类下，展示每局归因链。

**为什么先做**：`t_game_score.extra_json` 已有 20+ 归因字段（crop/sowDate/harvestDate/
fertility/dmg*/pestLoss/disasterLoss/revenue/net/phiViolated/yieldRaw/yieldCap/nApplied），
`GameHistory.vue` grep `extra|crop|revenue|net` **零命中** —— 数据在库里躺着没人看。

**这是定位 B 的第一块基石**：没有归因，就没有「为什么」，也就没有科学。

**验收**：游戏历史页 farm 标签可展开单局归因表；数字与 `/state` 引擎指纹一致。

### P1 · 科学解释层（定位 B 的核心工程）

**做什么**：
1. 后端新增 `FarmInsightService`（**唯一解释层出口**，禁止在 Controller 里拼文案）：
   ```java
   public record Insight(String conceptId,   // 对应 8 个概念之一
                         String title,
                         String observation,  // 现象：我观察到什么
                         String quantity,     // 量：具体数字 + 单位
                         String principle,    // 原理：背后的科学
                         String advice,       // 建议：下一步做什么
                         int severity) {}
   ```
2. **唯一实现原则**：解释文案只写一处，Controller / 场景 / 历史页 / 成就 共用
   （对齐项目铁律「分类逻辑只能有一个分类点」「抽唯一实现」）
3. 首批接入 4 个概念：有效积温、氮素报酬递减、产量四要素、水分平衡
4. 前端新增 `FarmInsightPanel.vue`（现象 → 量 → 原理 → 建议 四段式卡片）

**关键约束**：🔴 文案**不许硬编码在前端** —— 同一概念在 3 个界面出现就必须 3 份文案，
这正是项目里反复踩过的「同型代码只修一处」分叉。

### P2 · 农业科学模块接入平台学科

**做什么**：
1. `t_learning_subject` 新增「**农业科学**」科目（`code=agri`）
2. 章节结构按上面 8 个概念组织（每概念 = 1 章，课时从引擎回放数据生成）
3. 稼穑的每一次实操绑定对应课时的 `LessonProgress`
   → **「种一次水稻」= 「学完积温与积温不足」**（教学与实践同一动作，不分离）
4. `t_todo` 扩展任务类型（当前是纯用户级无类型字段，见 §二），
   预置任务链：建第一块地 → 完成一次追肥 → 收完第一季 → 解锁一个概念卡
5. 农场货币 → 平台积分**单向**打通

**验收**：学习看板出现「农业科学」科目；玩过农场后对应课时进度前进。

### P3 · 知识文档沉淀

**做什么**：把引擎里的农学知识**写成可读文档**（现在只在代码注释里），
进 `t_wiki_article`（现在是 1 篇 test）。

**为什么**：科学模块要能被引用、被检索、被讨论 —— 代码注释不是知识载体。

---

## 五、🔴 定位变更后的优先级重排

| | 上一版（按「游戏」定位） | **本版（按「科学模块」定位）** | 变化 |
|---|---|---|---|
| P0 | 归因明细可视化 | 归因明细可视化 | 不变，**仍是基石** |
| P1 | 接入平台任务/引导 | **科学解释层 `FarmInsightService`** | ⬆️ 升级，第 4 层之前先补第 2/3 层 |
| P2 | 拆 `FarmWorldMap.vue` | **农业科学模块接入平台学科** | ⬇️ 架构拆分让位（不阻塞定位落地） |
| P3 | — | 知识文档沉淀 | 新增 |
| P4 | 后端 S4–S7 拆分 | 后端 S4–S7 拆分 | 不变 |

### 🔴 有一条上一版结论在新定位下**依然成立且更重要**

**「加作物 / 加农资」依然明确不该做** —— 22 种作物 + 51 项农资已饱和，
定位 B 要的是**解释密度**而不是**内容数量**。

### 「拆 `FarmWorldMap.vue` 6712 行」降级但不取消

理由：它不阻塞科学解释层的落地；且在定位 B 下，
**地图（选区县）是「适宜性」这个概念的载体** —— 拆分时要注意保住
「为什么这块地适合种这个作物」的解释链路不能断。

---

## 六、给决策者的三个问题（定位已定，问题更聚焦）

1. **8 个概念里先讲哪 4 个？**
   → 建议「有效积温 + 氮素报酬递减 + 产量四要素 + 水分平衡」
   （数据最全、最反直觉、最能体现「科学」而非「游戏」）
2. **要不要新建「农业科学」科目，还是把 8 个概念做成独立知识库？**
   → 新科目 = 完整平台体验，但改动大；知识库 = 轻，但稼穑仍是「外挂」
3. **教学与实践如何绑定？**
   → 建议「实操即课时」（种一次 = 学完一节），而不是「先看课再种」
   —— 后者是游戏教程，做不出科学模块

---

## 附录：实测命令

```bash
# 科学量后端已下发、前端未消费
grep -n "aatCum\|sfi\|nSatisfaction\|droughtLoss" \
  spring-boot-learning/.../farm/FarmView.java          # 后端：✅
grep -rn "nSatisfaction\|droughtLoss\|aatCum" \
  frontend-learning/src/views/games/ frontend-learning/src/components/farm/   # 前端：❌ 零命中

# 平台没有农业科目
sqlite3 data/sandbox.db "SELECT name,code FROM t_learning_subject;"
# → 8 个全是编程类

# 知识库基本空着
sqlite3 data/sandbox.db "SELECT COUNT(*) FROM t_wiki_article;"   # → 1

# 解释模板（现成的三段式）
grep -n -A 3 "record RiskItem" \
  spring-boot-learning/.../farm/engine/SuitabilityEngine.java
```
