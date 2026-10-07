# MineTurn — GitHub 仓库描述文案

> 本文件是**文案稿**，供你复制到 GitHub 仓库的各个位置。文中的数字都是从当前代码实测得到的
> （actions 58 / mobs 33 / items 16 / AI 函数 27 / 注册效果 51 / 测试 289 / 示例数据包 12）。

---

## 1. 仓库简介（GitHub "Description" 字段，建议 120 字符内）

```
Minecraft 1.21.1 的回合制战斗模组：星穹铁道式 AV 行动条 + D&D 式行动经济，战斗规则完全由数据包驱动。
```

如果希望用英文：

```
Turn-based combat for Minecraft 1.21.1: a Star Rail-style action-value timeline with D&D-like action economy, fully datapack-driven.
```

**Topics 建议**：`minecraft` `minecraft-mod` `neoforge` `neoforge-mod` `1.21.1` `turn-based` `turn-based-combat` `datapack` `action-rpg` `java` `java21`

---

## 2. README 正文（可直接替换现有 README.md）

````markdown
# MineTurn

**Minecraft 1.21.1 / NeoForge 21.1.251 / Java 21**

把原版 Minecraft 的实时砍杀改成**回合制战术战斗**：行动顺序由 AV（行动值）行动条决定，
每次行动拥有可分配的移动距离与主要/次要行动次数，敌人 AI 与全部战斗规则均由**数据包**定义。

> 状态：可运行的完整原型。287+ 项服务器集成测试全部通过。**不适用于防作弊多人正式服**（见「已知边界」）。

---

## 这是什么

在生存模式攻击一只未被秒杀的普通怪物，就会进入战斗：自动切换到第三人称战术镜头并打开透明战斗 GUI。
战斗不再是"谁点得快"，而是"这一步走哪、打谁、用哪个动作"。

- **AV 行动条**：每个参与者按敏捷积累行动值，界面预测未来 16 次行动顺序
- **行动经济**：每次行动获得一份移动距离 + 若干主要行动 / 次要行动，移动可**分次消耗**
- **战术移动**：沿真实方块碰撞面行走，支持上一格台阶、下落结算原版摔伤、绕障寻路、水下三维移动
- **近身控制**：进入敌人周身范围后不能自由穿过，需要消耗行动"撤退"才能再次移动
- **多人协同**：多玩家共享一条行动条对抗多怪物，附近的敌对生物会自动作为增援加入

## 战斗规则

### 行动顺序

每个参与者持有 10000 点行动距离，按 `距离 / 敏捷` 决定下次行动时刻，调度器一次推进到下一个事件，
不按现实时间等待。同刻按稳定入场顺序处理。怪物有效敏捷 = `实体敏捷属性 × 数据包 agility / 100`。

### 每次行动获得

| 资源 | 默认 | 说明 |
| --- | --- | --- |
| 移动距离 | 4 格（上限 12） | 受移动速度属性影响；按实际水平路径长度扣费，可**分次**移动 |
| 主要行动 | 1 次 | 攻击、疾跑、撤退、逃跑 |
| 次要行动 | 1 次 | 由数据包指定的轻量动作 |

次数可由实体属性（`mineturn:main_actions` / `mineturn:bonus_actions`）与技能接口调整。

### 具体机制

- **疾跑**消耗 1 次主要行动，把**当前剩余**移动距离翻倍
- **撤退**消耗 1 次主要行动，解除本回合的近身移动限制（疾跑不能代替撤退）
- **逃跑**要求与所有敌人碰撞体距离 ≥ 10 格，只让逃跑者离场
- **远程判定**：弓与弩不是即时命中，而是在一个随距离变窄的窗口内按键——距离越远窗口越窄，
  未命中同样消耗行动与弹药
- **举盾**抵挡下一次攻击后失效，盾牌有独立的冷却时钟
- **状态时间轴**：药水、着火、氧气、饥饿、冰冻等全部换算到 AV 时钟，而非现实 tick

## 数据包驱动

战斗的**全部内容**都在数据包里，不改代码即可新增动作、怪物 AI 与物品映射。

```text
data/<命名空间>/mineturn/actions/<动作名>.json      # 一个战斗动作
data/<命名空间>/mineturn/mobs/<配置名>.json         # 一个怪物的 AI 配置
data/<命名空间>/mineturn/items/<配置名>.json        # 物品 → 动作 的映射
data/<命名空间>/mineturn/grants/<配置名>.json       # 由装备/状态授予的额外行动
data/<命名空间>/function/…/*.mcfunction            # 原版函数 AI 的回调
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

1. **状态机**：`approach` / `weighted_action` / `wait` 三种行为 + `in_reach` / `out_of_reach` 条件转移，
   适合大多数近战与远程怪物。
2. **原版函数 AI**：用原生 `.mcfunction` 写回调（`on_enter` / `on_turn` / `on_move_finished` /
   `on_action_resolved` / `on_leave`），配套战斗专用命令（`/ai target`、`/ai use`、`/ai move`、
   `/ai schedule`、`/ai query` 等）、宏参数与 AV 定时函数。Boss 战与复杂分支用它。

两种 AI 都在自带的召唤/生成规则约束下运作，例如唤魔者最多召唤 3 只恼鬼、且场上同时只保留 1 只
（`BattleRaid.java:11-12`），避免生成物淹没行动条。

数据包在加载时会**整体校验**：效果类型、字段范围、动作引用、状态转移目标、函数入口与宏参数。
校验失败会**保留上一套可用规则**而不是让服务器带着半套定义运行。

## 快速开始

```bash
# 需要 JDK 21
gradlew.bat build            # 构建
gradlew.bat runClient        # 启动带模组的客户端
gradlew.bat runGameTestServer # 跑服务器集成测试（289 项，约 2 分钟）
```

试玩：生存模式下打一只普通僵尸即可。左键拖动旋转镜头、滚轮缩放，底部按钮对应快捷栏，
右下角是疾跑 / 移动 / 撤退 / 逃跑 / 结束行动。玩家操作没有倒计时，必须点"结束行动"交出回合。

备用命令入口：`/mineturn`、`/mineturn attack`、`/mineturn use <槽位> <动作>`、
`/mineturn move <dx> <dz>`、`/mineturn end` / `sprint` / `retreat` / `flee`，
管理员可用 `/mineturn abort`（权限 2）紧急解除战斗。

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

`examples/` 下有 12 套可直接安装的示例数据包，覆盖函数 AI、批量 AI 模板与覆盖、
空间/飞行 Boss、延迟动作、区域动作、陷阱与机关、召唤动作、饰品与授予行动、物品适配器等：

```bash
gradlew.bat functionAiExamplePack fieldActionExamplePack spatialBossExamplePack
# 产物输出到 build/examples/
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [原型说明](docs/PROTOTYPE.md) | 总体设计、试玩、原型边界 |
| [函数 AI](docs/FUNCTION_AI.md) · [批量 AI](docs/BATCH_AI.md) | `.mcfunction` 回调、命令、模板继承与选择器 |
| [移动与特殊技能](docs/MOBILITY_AND_SKILLS.md) | 飞行、水下三维移动、Boss 效果、预付 AV 技能 |
| [远程判定](docs/RANGED_COMBAT.md) | 弓弩进度条、距离难度、弹药消耗 |
| [AV 状态时间轴](docs/AV_STATUSES.md) | 药水、燃烧、氧气、饥饿的逻辑时间规则 |
| [移动与技能](docs/MOBILITY_AND_SKILLS.md) · [骑乘战斗](docs/MOUNTED_COMBAT.md) | 坐骑、位移与特殊移动 |
| [数据包规范](docs/DATAPACK_EXTENSION_SPEC.md) | 字段、优先级与覆盖规则 |
| [原版覆盖清单](docs/VANILLA_COVERAGE.md) | 已适配的原版生物与物品 |
| [任务表](docs/TASKS.md) | 开发进度 |

## 已知边界

照实说明，避免误用：

- **不是为防作弊多人服设计的。** 没有战斗持久化、没有离线身体与重连恢复；
  远程判定的命中窗口由客户端发包时刻决定，正规服务器需要额外加固。
- **没有 PvP 阵营系统、战斗合并、基于地形的完整寻路。** 玩家之间只能通过互相攻击建立敌对。
- 战斗中的镜头是战场附近的轨道观察 + 平移（带墙体碰撞），不是无限制飞行。
- 未保护战场地形；其他玩家、流体、活塞等外部干预尚未全面隔离。
- 退出战斗后完全恢复原版逻辑，不永久修改生物的 `NoAI`、玩家游戏模式或能力。

> 详细边界见 [原型说明](docs/PROTOTYPE.md#原型边界)。

## 构建要求

- JDK 21
- Gradle（随附 wrapper）
- 开发环境使用 NeoForge ModDevGradle

## 许可

<!-- 见下方「需要你决定」一节，定稿前请勿提交公开仓库 -->
````

---

## 3. 一句话/短版（用于社交或 Modrinth 简介）

**中文**
> MineTurn 把 Minecraft 的战斗改成回合制：AV 行动条决定出手顺序，每次行动要分配移动距离与主要/次要行动，
> 怪物 AI 与全部战斗规则由数据包定义。58 个动作、33 套怪物 AI、51 种可编程效果，287 项集成测试通过。

**English**
> MineTurn turns Minecraft combat turn-based: an action-value timeline decides turn order, each turn spends
> movement and main/bonus actions, and every rule is datapack-driven. 58 actions, 33 mob AIs,
> 51 programmable effects, 287 passing integration tests.

---

## 4. 需要你决定的事（我无法替你选）

### 4.1 许可证 — **定稿前必须处理**

`gradle.properties` 里当前是：

```properties
mod_license=All Rights Reserved
```

这是**保留所有权利**，与"公开 GitHub 仓库"的目的冲突：别人不能合法使用、修改或分发。
`TEMPLATE_LICENSE.txt` 则是 NeoForge MDK 模板自带的（通常是 LGPL 2.1 或 MIT），两者不一致。

常见选择：

| 选择 | 适用 |
| --- | --- |
| `MIT` | 最宽松，希望别人随便用 |
| `LGPL-2.1-only` | 与 NeoForge 生态一致，改模组本体需开源，调用不受限 |
| `All Rights Reserved` | 只展示源码、不允许复用（**要明确写进 README，否则会一直被问**） |

选定后需要同时改三处：`gradle.properties` 的 `mod_license`、`README.md` 的许可一节、
仓库根目录新增 `LICENSE` 文件。

### 4.2 仓库名与可见性

包名是 `com.matuvent.mineturn`，`mod_id` 是 `mineturn`，所以仓库名用 `MineTurn` 最一致。

### 4.3 提交前需要补的忽略项（已核对现有 `.gitignore`）

现有 `.gitignore` **已经**覆盖了 `build/`、`run/`、`run-gametest/`、`.gradle/`、`.idea/`、`repo/`、
`.DS_Store` 等，这些不用管。**漏掉的是下面三项**，直接 `git init && git add .` 会把它们一起提交：

| 路径 | 现状 | 建议 |
| --- | --- | --- |
| `outputs/` | **未被忽略**。内含 `stream-build/build.mjs`（`import {...} from '@oai/artifact-tool'`，别人环境跑不了）、`回合进行时流派构筑-补全.xlsx`、`preview.png` | 加入 `.gitignore`，或把有用部分整理进 `docs/design/` 后删除目录 |
| 根目录 `回合进行时流派构筑-原始副本.xlsx` | **未被忽略**，位于仓库根目录 | 移到 `docs/design/` 并提交，或忽略 |
| `docs/CODE_REVIEW_2026_10_07.md` | 会被提交 | 见下 |

> `build/examples/corrupted-*.java` 属于 `build/` 目录，**已在忽略范围内**，无需单独处理。

另外提醒：`docs/CODE_REVIEW_2026_10_07.md` 是上一轮我写的待修复清单，内容包含**尚未修复的缺陷
与安全相关分析**（例如远程判定窗口的客户端信任问题、`close()` 崩溃路径）。如果不想公开，
提交前把它排除或移到本地忽略目录。

建议追加到 `.gitignore`：

```gitignore
### Review / scratch output ###
outputs/
*副本.xlsx
```


### 4.4 语言

模组内文案（GUI、消息、实体名）是中文，已有 `assets/mineturn/lang/zh_cn.json` 与 `en_us.json`。
README 若面向国际受众，建议在英文 README 之外保留中文版本（如 `README.zh_cn.md`）。
本文第 2 节是按**中文 README** 写的，需要英文版可以让我再出一份。

---

## 5. 我核对过的数字（供你复核，避免描述与代码脱节）

| 指标 | 实测值 | 来源 |
| --- | --- | --- |
| 战斗动作 | 58 | `src/main/resources/data/mineturn/mineturn/actions/*.json` |
| 怪物 AI 配置 | 33 | `.../mineturn/mobs/*.json` |
| 物品映射 | 16 | `.../mineturn/items/*.json` |
| 原版函数 AI | 27 | `.../function/ai/**/*.mcfunction` |
| 注册效果类型 | 51 | `api/*.java` 中 `CombatEffects.register(...)` 调用 |
| Java 源文件 | 133 | `src/**/*.java` |
| 混入类 | 40 | `mixin/*.java`（与 `mineturn.mixins.json` 一一对应） |
| 集成测试 | 289 项 `@GameTest` | 实跑 **287 项 required 全部通过**（另有 2 项在非 required 命名空间） |
| 示例数据包 | 12 | `examples/` 子目录数 |
| 版本基线 | MC 1.21.1 / NeoForge 21.1.251 / Java 21 | `gradle.properties` |
