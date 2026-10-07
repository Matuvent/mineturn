# MineTurn

[English](README.md) | **中文**

**Minecraft 1.21.1 / NeoForge 21.1.251 / Java 21 的回合制战术战斗模组。**

MineTurn 把原版 Minecraft 的实时砍杀改成回合制：出手顺序由 **AV（行动值）行动条**决定，每次行动获得
可分配的移动距离与主要/次要行动，而**全部战斗内容** —— 动作、怪物 AI、物品映射 —— 均由**数据包**定义。

> 状态：功能完整的可运行原型，287 项服务器集成测试通过。
> 在公开服务器上使用前，请先读 [已知缺陷与安全](#已知缺陷与安全)。

---

## 这是什么

生存模式攻击一只未被秒杀的普通怪物即可进入战斗：自动切换到第三人称战术镜头并打开透明战斗 GUI。
战斗不再是"谁点得快"，而是"这一步走哪、打谁、用哪个动作"。

- **AV 行动条** —— 每个参与者按敏捷积累行动值，界面预测未来 16 次行动顺序
- **行动经济** —— 每次行动获得一份移动距离 + 若干主要行动 / 次要行动，移动可**分次消耗**
- **战术移动** —— 沿真实方块碰撞面行走，支持上一格台阶、按原版规则结算摔伤、绕障寻路，以及完整的水下三维移动
- **近身控制** —— 进入敌人周身范围后不能自由穿过，需要消耗行动"撤退"才能再次移动
- **多人协同** —— 多玩家共享一条行动条对抗多怪物，附近的敌对生物会自动作为增援加入

## 战斗规则

### 行动顺序

每个参与者持有 10000 点行动距离，距离下次行动的时间为 `距离 / 敏捷`。调度器一次推进到下一个事件，
**不按现实时间等待**。同刻事件按稳定入场顺序处理。怪物有效敏捷 = `实体敏捷属性 × 数据包 agility / 100`。

### 每次行动获得

| 资源 | 默认 | 说明 |
| --- | --- | --- |
| 移动距离 | 4 格（上限 12） | 受移动速度属性影响；按实际水平路径长度扣费，可**分次**移动 |
| 主要行动 | 1 次 | 攻击、疾跑、撤退、逃跑 |
| 次要行动 | 1 次 | 由数据包指定的轻量动作 |

次数可由实体属性（`mineturn:main_actions` / `mineturn:bonus_actions`）与技能接口逐实体调整。

### 具体机制

- **疾跑**消耗 1 次主要行动，把**当前剩余**移动距离翻倍
- **撤退**消耗 1 次主要行动，解除本回合的近身移动限制（疾跑不能代替撤退）
- **逃跑**要求与所有敌人碰撞体距离 ≥ 10 格，只让逃跑者离场
- **远程判定** —— 弓与弩不是即时命中。你需要在随距离变窄的窗口内按键，**未命中同样消耗行动与弹药**
- **举盾**抵挡下一次攻击后失效，盾牌有独立的冷却时钟
- **状态时间轴** —— 药水、着火、氧气、饥饿、冰冻全部换算到 AV 时钟，而非现实 tick

## 数据包驱动

战斗的**全部内容**都在数据包里。不改代码即可新增动作、怪物 AI 与物品映射。

```text
data/<命名空间>/mineturn/actions/<动作名>.json      # 一个战斗动作
data/<命名空间>/mineturn/mobs/<配置名>.json         # 一个怪物的 AI 配置
data/<命名空间>/mineturn/items/<配置名>.json        # 物品 → 动作 的映射
data/<命名空间>/mineturn/grants/<配置名>.json       # 由装备/状态授予的额外行动
data/<命名空间>/function/…/*.mcfunction            # 函数 AI 的回调
```

自带内容规模（可用数据包任意覆盖或扩展）：

| 内容 | 数量 |
| --- | --- |
| 战斗动作 | 58 |
| 怪物 AI 配置 | 33 |
| 物品映射 | 16 |
| 注册的效果类型（可编程扩展） | 51 |
| 原版函数 AI（`.mcfunction`） | 27 |

### 两种怪物 AI

1. **状态机** —— `approach` / `weighted_action` / `wait` 三种行为，配合 `in_reach` / `out_of_reach`
   条件转移。大多数近战与远程怪物用它就够。
2. **原版函数 AI** —— 用真正的 `.mcfunction` 写回调（`on_enter` / `on_turn` / `on_move_finished` /
   `on_action_resolved` / `on_leave`），配套战斗专用命令（`/ai target`、`/ai use`、`/ai move`、
   `/ai schedule`、`/ai query` 等）、宏参数与 AV 定时函数。Boss 战与复杂分支用它。

两种 AI 都在自带的召唤/生成约束下运作 —— 例如唤魔者最多召唤 3 只恼鬼、且场上同时只保留 1 只
（`BattleRaid.java:11-12`），避免生成物淹没行动条。

数据包在加载时会**整体校验**：效果类型、字段范围、动作引用、状态转移目标、函数入口与宏参数。
校验失败会**保留上一套可用规则**，而不是让服务器带着半套定义运行。

## 构建

需要 **JDK 21**。

```bash
./gradlew build              # 构建 jar
./gradlew runClient          # 启动带模组的客户端
./gradlew runGameTestServer  # 跑服务器集成测试（289 项，约 2 分钟）
```

Windows 下用 `gradlew.bat`。

## 试玩

生存模式下打一只你没有秒杀的普通怪物。左键拖动旋转镜头，滚轮缩放，底部按钮对应快捷栏，
右下角提供疾跑 / 移动 / 撤退 / 逃跑 / 结束行动。
**玩家回合没有倒计时** —— 必须点"结束行动"交出剩余资源。敌人自动行动。

备用命令入口：

| 命令 | 用途 |
| --- | --- |
| `/mineturn` | 显示当前行动者、战斗时间、距离与物品动作 |
| `/mineturn attack` | 使用手持物品的 `mineturn:melee` 动作（空手也可用） |
| `/mineturn use <槽位> <动作>` | 使用背包指定槽位的某个动作 |
| `/mineturn move <dx> <dz>` | 按相对偏移移动 |
| `/mineturn sprint` / `retreat` / `flee` / `end` | 消耗本回合资源 |
| `/mineturn abort` | 管理员紧急解除战斗（权限等级 2） |

## 扩展接口

`api/CombatEffects.java` 提供可注册的效果执行器：

```java
CombatEffects.register(ResourceLocation.parse("mymod:my_effect"), new CombatEffects.Effect() {
    @Override public void validateDefinition(CombatData.Action action) { /* 加载期校验参数 */ }
    @Override public String validate(CombatEffects.Context ctx) { return null; }   // 无副作用的预检
    @Override public void execute(CombatEffects.Context ctx) { /* 提交后执行一次 */ }
});
```

`Context` 提供施放者、目标、物品、动作配置、当前 AV 时间，以及一个受管的 `BattleAccess`：
区域效果、召唤、传送、延迟结算（`after` / `afterChecked`）、强制位移、伤害授权等。
所有变更型接口都会重新校验上下文是否仍然有效，避免过期技能在战斗结束后生效。

适配其它模组时可用 `BattleManager.locked(entity)` 禁止非回合施法，
`BattleManager.battleTime(entity)` 读取当前战斗时间（未参战返回 `NaN`）。

## 示例数据包

`examples/` 下有 12 套可直接安装的示例数据包，覆盖函数 AI、批量 AI 模板与覆盖、空间/飞行 Boss、
延迟动作、区域动作、陷阱与机关、召唤动作、饰品与授予行动、物品适配器等：

```bash
./gradlew functionAiExamplePack fieldActionExamplePack spatialBossExamplePack
# 产物输出到 build/examples/
```

## 已知缺陷与安全

这一节是**刻意公开**的。MineTurn 是原型，**没有为对抗性多人环境做加固**。当前未解决的问题与精确位置：

| 级别 | 位置 | 问题 |
| --- | --- | --- |
| **Critical** | `BattleManager.java:323`、`:186`、`:193`、`:38`、`:338` | 异常处理块里的 `close()` 调用**自身没有保护**，而 `close()` 确实会抛（经 `BattleSession.java:480` 的 `drainCallbacks()`，或 `BattleSession.java:1252` 的安全上限 `IllegalStateException`）。单次清理失败可能中断服务端 tick。 |
| **Major** | `BattleManager.java:33`、`:269` + `ServerReloadMixin.java:21` | 如果 `/reload` 的完成 future 永不执行，`reloads` 计数器再也不会归零，此后 `damaged()` 会**静默拒绝开启任何新战斗，且没有日志或玩家提示**。 |
| **Major** | `RangedShot.java:29-35`、`BattleManager.java:192` | 远程命中窗口的 `low`/`high` 完全确定，命中结果只取决于服务端收到 `AimSubmit` 的时间戳。改写过的客户端可以通过精确控制发包时刻做到**每发必中**。 |
| Minor | `BattleSession.java:357-360`、`:317` | `idleTicks` 会被移动重置（`TurnBudget.java:37`），所以文档所说的约 30 秒回合期限在玩家持续小幅移动时**永不超时**；界面对应的倒计时会反复跳回 30 秒。 |
| Minor | `BattleSession.java:1233` | AI 回调队列溢出时整队丢弃，只写日志，**玩家侧无任何提示**。 |
| Minor | `BattleSession.java:1303-1308` 对比 `:394` | `ai()` 取状态时没有 `hasAiActionBudget()` 里那样的 null 守卫。当前不可达，但仅靠数据包校验严格来保证。 |
| Minor | `battle/BattleStatus.java:32` 对比 `:16` | `statusTicks` 与 `entity.tickCount` 表达同一个概念，却是两个不同的时钟。 |
| Minor | `network/BattleNetwork.java:138-143`、`client/BattleClient.java:69-72` | `Motion` 包不带 revision，而 `State` 带，因此乱序到达时可能短暂覆盖客户端插值。 |

其它已声明的限制：

- **没有战斗持久化。** 没有离线身体，没有重连恢复；断线即退出战斗。
- **没有 PvP 阵营系统、没有战斗合并、没有完整的地形寻路。**
- **客户端代码零自动化覆盖。** `BattleClient`、`BattleScreen`、各渲染器与 `ProjectileAnimations`
  从未被 `runGameTestServer` 执行过（那是专用服务端）。镜头行为、移动平滑与鼠标手感只能实机验证。
- 战斗镜头是战场附近的轨道观察 + 平移（带墙体碰撞），不是无限制飞行。
- 未保护战场地形；其他玩家、流体、活塞等外部干预尚未全面隔离。
- 退出战斗后完全恢复原版逻辑，**不永久修改**生物的 `NoAI`、玩家游戏模式或能力。

更完整的审查报告（含若干**经调查后确认代码是正确的**疑点）见
[`docs/CODE_REVIEW_2026_10_07.md`](docs/CODE_REVIEW_2026_10_07.md)。

> **请勿在对作弊敏感的竞技性多人环境中依赖本模组。** 上述修复正在进行中。

## 文档

| 文档 | 内容 |
| --- | --- |
| [原型说明](docs/PROTOTYPE.md) | 总体设计、试玩、原型边界 |
| [函数 AI](docs/FUNCTION_AI.md) · [批量 AI](docs/BATCH_AI.md) | `.mcfunction` 回调、命令、模板继承与选择器 |
| [移动与特殊技能](docs/MOBILITY_AND_SKILLS.md) | 飞行、水下三维移动、Boss 参数、预付 AV 技能 |
| [远程判定](docs/RANGED_COMBAT.md) | 弓弩进度条、距离难度、弹药消耗 |
| [AV 状态时间轴](docs/AV_STATUSES.md) | 药水、燃烧、氧气、饥饿的逻辑时间规则 |
| [骑乘战斗](docs/MOUNTED_COMBAT.md) | 坐骑、骑手与位移 |
| [数据包规范](docs/DATAPACK_EXTENSION_SPEC.md) | 字段、优先级与覆盖规则 |
| [原版覆盖清单](docs/VANILLA_COVERAGE.md) | 已适配的原版生物与物品 |
| [任务表](docs/TASKS.md) | 开发进度 |

## 许可

Copyright (C) 2026 Matuvent

本项目采用 **GNU Lesser General Public License v2.1**（`LGPL-2.1-only`），原文见 [LICENSE](LICENSE)。

简单说明（**以 [LICENSE](LICENSE) 原文为准**）：

**你可以**
- 自由使用、修改、分发 MineTurn
- 把它打包进整合包，**包括闭源整合包和以任何方式盈利的整合包** —— 本项目不做任何额外限制
- 编写调用 MineTurn API 的扩展模组，并按你自己的许可证（含闭源）发布
- 把 MineTurn 作为依赖用于你自己的项目

**你必须**
- 如果你**修改了 MineTurn 本体源码**并分发（无论以 jar 还是源码形式），修改部分需以 LGPL-2.1 开源
- 保留版权声明与许可证文本
- 不得移除或更改许可证声明

**简言之**：整合包作者可以随意使用、闭源并盈利；但想改动 MineTurn 本体的代码并分发的人，
需要把改动开源，让改进回流。

> 请注意：LGPL 的传染性只作用于**本模组本体**。把 MineTurn 打包进整合包属于聚合分发，
> 不会使整合包整体受 LGPL 约束 —— 整合包不因包含 MineTurn 而产生开源义务。

### 整合包作者

**明确欢迎**把 MineTurn 加入整合包，无需事先征得同意。
若你的整合包愿意列出来源与模组名称，我们会很感激，但这并非强制要求。

> 使用 Minecraft 模组还需遵守 Mojang 的 [Minecraft EULA](https://www.minecraft.net/en-us/eula)
> 与 [Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)，
> 那部分与本文档的许可证无关，由你自己负责。

### 第三方资源

`TEMPLATE_LICENSE.txt` 是 NeoForge MDK 模板自带的 MIT 许可（版权方 NeoForged，仅适用于模板文件），
与本模组许可证无关，其声明保持原样。
`assets/mineturn/` 下若有引自其它项目的贴图或音效，其许可归原作者所有，不受本许可证覆盖。
