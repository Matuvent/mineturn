# 原版 mcfunction AI 使用说明

适用版本：Minecraft 1.21.1 / NeoForge。本文描述已实现接口；此前设计文档中未在这里列出的能力仍属计划。

## 安装与目录

独立数据包放进 `<world>/datapacks/`；附属模组将相同的 `data/` 文件放进开发工程的 `src/main/resources/`。没有命名空间白名单，JSON 与函数可以相互跨命名空间引用。

```text
pack.mcmeta
data/my_pack/mineturn/mobs/my_mob.json
data/my_pack/mineturn/items/my_item.json
data/my_pack/mineturn/actions/my_action.json
data/my_pack/function/ai/init.mcfunction
data/my_pack/function/ai/turn.mcfunction
```

`pack.mcmeta` 使用 `pack_format: 48`。扩展名必须是 `.mcfunction`，函数目录是单数 `function`。

## 生物注册

```json
{
  "schema_version": 2,
  "entity": "minecraft:husk",
  "agility": 100,
  "reach": 1.5,
  "priority": 0,
  "ai": {
    "on_enter": "my_pack:ai/init",
    "on_turn": "my_pack:ai/turn",
    "on_action_resolved": "my_pack:ai/resolved",
    "on_leave": "my_pack:ai/cleanup",
    "parameters": {"claw_weight": 70}
  }
}
```

只有 `entity` 和 `ai.on_turn` 必填。敏捷默认 100，周身/追击停止距离基准 `reach` 默认 1.5，其余回调可省略。动作本身使用动作 JSON 的 range 校验攻击距离。当前只能注册实际存在的实体类型；自动参战仍沿用当前 PvE 原型的玩家/怪物阵营和敌对生物增援策略。

`ai` 与旧 `states` 不能同时出现。旧完整状态机格式继续可用，内置僵尸默认仍使用旧状态机，安装示例包才会切换到函数 AI。

`parameters` 作为 CompoundTag 传给每次回调与 AV 定时函数，因此入口可以直接使用原版 `$` 函数宏，例如 `$scoreboard players set @s my_value $(claw_weight)`。嵌套调用其他宏函数时，按原版语法显式传参，参数不会自动传播。

## 回调时机与上下文

| 回调 | 时机 | 是否允许花费行动/移动 |
| --- | --- | --- |
| on_enter | 生物加入战斗后初始化 | 否 |
| on_turn | 轮到该生物时 | 是 |
| on_action_resolved | 该生物的合法动作执行完成后 | 否 |
| on_leave | 生物离场、死亡或战斗结束时 | 否 |
| AV 定时函数 | 战斗时间到达指定 AV 时 | 否 |

`@s`、执行位置和维度默认为该生物。通过原版函数调用、execute、计分板、谓词、storage 和宏编写分支、循环、阶段和状态管理。

每个回调保留原生物的战斗身份；`execute as` 切换到别人后，MineTurn AI 操作返回失败，不能借此操作另一个参与者。非回调环境直接运行 AI 操作也会失败。`inspect` 是管理员诊断命令，不受此限制。

回合函数结束后自动交出回合，不需要手动调用玩家用的 `/mineturn end`。动作完成回调在本次决策函数退出后执行，防止伤害回调递归插入当前命令链。该回调表示动作已通过校验并执行，不承诺伤害一定扣血；校验失败的动作不触发它。

每只生物用自己的计分板分数保存实例状态。storage 是全局共享的，作者若使用 storage，应自行按 UUID/实例区分，避免多个 Boss 覆盖彼此的数据。当前战斗本身尚无存档恢复能力。

## 已实现命令

以下命令均省略开头 `/`，便于复制到函数文件中。一般成功返回 1，失败返回 0；查询返回对应数值。

| 命令 | 作用 |
| --- | --- |
| `mineturn ai target nearest_enemy` | 选择本场最近敌人 |
| `mineturn ai target lowest_health` | 选择生命值最低的敌人 |
| `mineturn ai target farthest_enemy` | 选择最远敌人 |
| `mineturn ai target_entity <单个实体选择器>` | 选择指定目标，检查同战斗及敌对关系 |
| `mineturn ai use <动作ID>` | 执行动作；self 动作作用于自身，其他动作作用于已选目标 |
| `mineturn ai move toward_target` | 直线追击，受剩余移动预算和地形约束 |
| `mineturn ai move <dx> <dz>` | 沿世界 X/Z 方向分次移动，遇阻提前停止 |
| `mineturn ai move3d <dx> <dy> <dz>` | 飞行/水下模式沿三维方向移动，消耗空间距离 |
| `mineturn ai sprint` | 消耗主要行动，翻倍剩余移动距离 |
| `mineturn ai retreat` | 消耗主要行动，解除本回合周身移动限制 |
| `mineturn ai wait` | 放弃剩余机会；本次函数后续战斗操作也会拒绝 |
| `mineturn ai schedule <延迟AV> <函数ID>` | 安排该生物的定时状态/决策回调，保留所选目标 |
| `mineturn ai inspect <实体类型ID>` | 显示最终匹配 JSON、来源数据包及函数入口 |

`use` 检查主要行动、目标所属战斗、距离、视线、动作冷却、物品消耗和效果预检。消耗从生物主手堆叠扣除。函数可使用已加载的动作 ID；新增 Java 效果类型仍要由模组注册。选中的敌人不会因为原版 Mob.target 改变而自动变化，AI 可每回合重新选择。

同一回合可以分多次移动并执行一个主要行动。移动不能穿墙或绕过撤退限制，地面模式的上下台阶与摔伤沿用战斗路径系统。飞行和水下模式现支持 `mineturn ai move3d <dx> <dy> <dz>`，且 `move toward_target` 会考虑高度差；配置见 [移动与特殊技能接口](MOBILITY_AND_SKILLS.md)。没有穿墙或任意传送的战斗命令。

## 查询与数值单位

使用 `execute store result score @s <目标> run mineturn ai query ...` 保存查询值。距离、移动量、生命和 AV 返回值统一乘 1000 后取整。

| 查询 | 返回值 |
| --- | --- |
| `has_target` | 所选目标仍存活且在战斗中：1，否则 0 |
| `can_act` | 当前回合回调是否还有主要行动：1/0 |
| `movement` | 当前行动者剩余距离 × 1000；非行动者为 0 |
| `health` | 当前生物生命值 × 1000 |
| `health_ratio` | 当前生物生命比例 × 1000，半血为 500 |
| `time` | 当前战斗 AV × 1000 |
| `engaged` | 是否处于敌方周身范围：1/0 |
| `distance` | 与所选目标的碰撞体间距 × 1000 |
| `height_difference` | 自身脚部 Y 减去所选目标脚部 Y，结果 × 1000；正数表示自己更高 |
| `in_attack_range` | 目标在注册 reach 内且有视线：1/0 |
| `ready <动作ID>` | 该动作现在能否完整通过执行前校验：1/0 |
| `event_action <动作ID>` | 此次动作完成回调是否对应给定动作：1/0 |
| `event_success` | 动作完成回调是否成功：1/0；远程未命中为 0，延迟技能表示施放成功而非未来命中 |

缺少目标时 distance/height_difference/in_attack_range 返回失败，不能将失败结果 0 理解为真实距离或高差。先查询 has_target，或直接查询具体动作的 ready。

## AV 调度

```mcfunction
mineturn ai schedule 50 my_pack:ai/finish_charge
```

从当前战斗时间起经过 50 AV 后执行，时间轴会在该 AV 处处理函数，再继续推进到下个行动者。现实中等待不会推进 AV。

定时函数可以改变计分板状态、阶段、目标，供下一次回合决策使用，但不会获得免费移动或攻击。蓄力技能现可使用动作效果的预付 AV 延迟接口或内置 `mineturn:burst`，详见 [特殊技能接口](MOBILITY_AND_SKILLS.md)；不能用定时函数绕过主要行动消耗。

定时任务随生物离场或战斗结束取消。最小延迟 0.01 AV、最大 100000 AV；每场最多等待 128 个任务，每次推进最多触发 128 次，避免自我调度耗尽服务器。

## 覆盖、冲突与热重载

相同命名空间和资源路径按数据包优先级整文件覆盖，适用于所有动作/物品/生物 JSON 和原版函数。支持独立世界数据包以及其他模组 JAR 内的 data 资源。

不同 JSON 文件映射同一个实体或物品时，先比较顶层整数 `priority`（默认 0，范围 ±1000000）。生物同优先级时再按精确 ID > 标签 > 命名空间选择；最高候选仍并列则拒绝加载。物品同最高优先级重复仍报错。模板继承、批量选择器、禁用和可选兼容详见 [批量 AI 使用说明](BATCH_AI.md)。

`/reload` 开始时结束现有战斗、调用旧离场回调并清理 AV 任务。新 JSON 会先校验动作引用、实体/物品是否存在、入口函数是否成功编译及宏参数；仅在 Minecraft 安装对应函数库时发布匹配的 JSON 快照。入口缺失或编译失败时拒绝更新，不把新 JSON 配到旧函数库上。

不能静态验证所有动态函数名、任意原版命令和宏分支；这些仍可能在执行时失败。普通命令失败遵循原版函数行为并继续后续命令，首条执行错误记录到日志。函数入口执行异常或命令额度耗尽时，暂停该生物本场战斗的函数 AI，回合继续推进。

每次回调最多执行 10000 次原版命令计费步骤，execute 分支数量上限 128，一次回调队列最多处理 64 个事件。这不是任意命令的 CPU/内存硬沙箱。原版数据包属于受信任服务器内容，直接执行 kill、tp 或其他管理命令仍可能绕过战斗规则；不会自动授权这些原版伤害为 MineTurn 动作。

## 可试玩示例

源码：`examples/function-ai/`；打包：`gradlew.bat functionAiExamplePack`。

安装 `build/examples/mineturn-function-ai-example.zip` 后：

- 僵尸的原 JSON 被覆盖为函数 AI。
- 入战初始化自己的计分板状态；回合中选择目标、追击或按 70/30 权重使用利爪/撕咬。
- 木棍新增 5 点伤害的战斗动作。

覆盖包源码：`examples/function-ai-override/`；打包：`gradlew.bat functionAiOverridePack`。

同时安装 `mineturn-function-ai-override.zip` 并将它设为更高优先级，僵尸会只用利爪，木棍伤害变为 8。覆盖包不重复注册生物，直接替换基础包同 ID 的函数和动作 JSON。

首次安装后使用 `/reload`，通过 `/datapack list` 检查启用状态；需要明确顺序时使用 `/datapack enable "file/mineturn-function-ai-override.zip" last`。通过 `/mineturn ai inspect minecraft:zombie` 检查最终入口和 JSON 来源。

两个示例包依赖已实现函数 AI 的新版 MineTurn；覆盖包还依赖基础示例包。它们不会自动安装进用户试玩世界。
