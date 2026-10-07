# 交接：DeepSeek D01–D03 交付

更新：2026-10-08 ｜ 作者：**DeepSeek** ｜ 收件人：**Codex**（负责审核整合）

对应 `docs/WORK_ALLOCATION.md` 的 **D01、D02、D03**。三项目标均已完成。
另：**A12 也已由本对话完成**，其交接见 [`DEEPSEEK_ANIMATION_HANDOFF.md`](DEEPSEEK_ANIMATION_HANDOFF.md)。

---

## 提交

| 提交 | 内容 |
| --- | --- |
| `efcd082` | A12 演出生命周期与抢占（协议 22） |
| `ece7b99` | D01–D03：文档对齐 + 示例校验脚本 + 客户端手测表 |

均已推送到 GitHub `main`。

---

## D01 对齐物品和动画文档中的旧"待做"描述

**状态：完成**（仅文档；**未把任何未完成项标成完成**）

| 文件 | 改动 |
| --- | --- |
| `docs/ITEM_MECHANICS_PLAN.md` | 三叉戟回收条目；激流客户端验收条目；玩家弩耐久条目 |
| `docs/ACTION_ANIMATION_PLAN.md` | 标题仍写"未实现"、状态行只提 P0 → 改为分区域状态表 |

### 逐条说明

**三叉戟「回收后续」**：原条目把两件事混在一句里，读起来像一件事。
拆成 ① **无忠诚战内回收操作尚未实现**（负责人 Codex，A06）与 ② **断线/重启拾回验收**属验证缺口（D05），
并保留已实现部分（忠诚 AV 返回与返回动画）。

**激流「表现验收」**：补充说明"**原版旋转姿态不接入是刻意决定**"，理由是原版旋转会追加实时伤害，
在纯表现路径启用等于绕过战斗结算。**未改动功能，也未把它标成完成。**

**玩家弩耐久**：`crossbowUnbreakingMatchesNativePerProjectile` 直接调用
`CombatProjectiles.payCrossbowDurability(player, bow, bow.copy(), ...)` 并自造快照，**绕过
`BattleSession.use()` 的真实接线**，因此抓不到接线回归。已核实另有多条射击用例走真实 `battle.use(...)`
并断言 `shot.weapon` 的箭道数，覆盖了该缺口。**机制本身已实现，条目仍保持 `[x]`。**

**动画文档标题**：原文标题写"（v0，未实现）"，与文末实现记录自相矛盾。改为分区域状态表：
P0 ✅ / A12 ✅ / A11 ⬜（Codex 范围）/ 震屏与骨骼 ⬜ 暂缓 / 客户端实机 ⬜ 未做。

### 未改动

- `docs/TASKS.md` 与 `docs/COMBAT_BOUNDARIES.md` 中的历史验证记录（属 Codex 维护）。
- 所有 `[x]` 条目保持原状；未把任何 `[ ]` 改为 `[x]`。

---

## D02 数据包示例静态检查脚本

**状态：完成并实测**

新增 `tools/validate_examples.py`（**仅 Python 标准库**）与 `tools/README.md`。

| 检查项 | 说明 |
| --- | --- |
| `pack.mcmeta` | 可解析、`pack_format == 48`、`description` 非空 |
| `data/` 结构 | 存在 `data/<命名空间>/…`，命名空间字符集合法 |
| JSON | 该目录树下所有 `.json` 可解析（UTF-8、无 BOM） |
| id | `actions/`、`grants/` 的文件名推导 id 合法 |
| build.gradle | 引用的 `examples/<名字>` 目录真实存在 |
| 文档链接 | 本地文件链接指向存在的文件 |
| README | 缺失时只进 notes，不算失败 |

### 刻意不检查（避免误判）

示例是**部分数据包**，设计上引用基础模组或其它数据包的动作/物品/函数，
因此**悬空引用不是错误**，不要求每个示例自包含。schema 语义由游戏内加载期校验负责。

### 实际运行结果

```
example packs checked : 12
json files parsed     : 54
notes (7)             : teleport-action 未被 build.gradle 引用；6 个示例无 README
OK: all example checks passed
```

### 反向验证（确认脚本真能抓错，验证后已全部还原）

| 注入的错误 | 结果 |
| --- | --- |
| `pack.mcmeta` 写坏 | ✅ 报 invalid JSON |
| action JSON 被截断 | ✅ 报 invalid JSON 并给出路径 |
| 文档链接指向不存在文件 | ✅ 报 link target does not exist |

注入实验产生的临时文件已删除，`git status` 已确认无残留。

### notes 中的两项待办（不阻塞，供 Codex 决定）

1. `examples/teleport-action` 未被 `build.gradle` 引用 —— 可手动放入，属正常；若要打包成 ZIP 需加 Zip 任务。
2. 6 个示例无 `README.md`：`batch-ai`、`function-ai`、`function-ai-override`、`granted-laser`、`spatial-boss`、`teleport-action`。

---

## D03 客户端/联机手测表

**状态：完成**（文档；**未宣称任何项已通过**，全部初始值为 `未测`）

新增 `docs/CLIENT_ACCEPTANCE.md`，共 12 组：

| 组 | 覆盖 |
| --- | --- |
| A | GUI 与多种缩放（含窄/宽窗口、GUI 缩放三档、聊天与暂停菜单交互） |
| B | 自由镜头（拖动旋转、滚轮缩放、Shift 平移、F 聚焦、狭窄空间抖动） |
| C | **动作演出与技能镜头**（推入/释放、连续动作衔接、大体型、贴墙、本地行动者、多人、`/reload` 改配置、换维度） |
| D | 鼠标与选点（目标选择、落点颜色、预览一致性、右键取消、重复点击） |
| E | 移动/撤退/近身控制（分次移动、疾跑、台阶与摔伤、动态障碍） |
| F | 水下选点（E/Q 深度、上岸、氧气） |
| G | 骑乘（共同移动、朝向、切换手持、伤害归属、坐骑死亡） |
| H | 远程判定与延迟（含 ping ≥ 200ms、抖动、目标离场、重复提交） |
| I | 生命周期（进入/秒杀不入战/死亡/断线/跨维度/`/reload`/关服重启/暂停菜单） |
| J | 多人同步（共同参战、互相观察、增援、同时行动、逃跑） |
| K | 表现与视觉（箭、弩多重、三叉戟、雪球、烟花、引雷、光束、图腾、激流、药水云） |
| L | 边界与压力（32 人、大量怪物、长时间挂机、低帧率） |

每行列出了 `结果 / 日期 / 环境 / 记录`，并附环境模板、严重度参考与问题清单表。

### 与其它文档的关系

C 组的依据是 `ACTION_ANIMATION_PLAN.md`；H 组依据 `RANGED_COMBAT.md`；
F/K 组的部分状态规则依据 `AV_STATUSES.md`。文末已交叉链接。

---

## 本次未做的事

- **未修改任何 Java 文件**（D01–D03 均为文档与脚本）。
- **未改动** `build.gradle`、网络协议、许可证、`TASKS.md` 的验证记录、工作区总表。
- **未运行** `runGameTestServer`：D01–D03 不含 Java 改动，按工作区约定"文档或脚本任务不要求无意义的服务器全量重跑"。
  当前基线测试数仍为 **304/304 通过**（A12 提交时的实测结果）。
- **未虚构**任何客户端手测结果。

## 需要 Codex 审核的点

1. D01 对 `ITEM_MECHANICS_PLAN.md` 与 `ACTION_ANIMATION_PLAN.md` 的措辞是否符合你的记录口径。
2. D02 的 notes 两项是否要处理（补 README / 加 teleport-action 的 Zip 任务）。
3. D03 的 12 组划分是否够用，是否需要补组。

## 工作区占用声明

本对话接下来会按 `WORK_ALLOCATION.md` 顺序继续 **D04**（新增独立 GameTest 类，**不改** `BattleExtensionGameTests`）。
会新增测试类，可能需要新生产接口时按约定先交"复现 + 预期 + 最小建议"。

**注意**：本次提交时工作区存在 Codex 的在途文件（`battle/BattleStatus.java` 修改、新增 `battle/BattleContact.java`）。
本对话**已刻意不提交它们**，仅提交自己的 5 个文件。后续仍会显式列出文件路径，不使用 `git add -A`。
