# 生物 AI 便捷数据包注册方案

状态：早期设计方案，部分已由 mcfunction 方案实现。本文仍包含未实现的接口，不宜直接照抄；当前可用格式以 [函数 AI](FUNCTION_AI.md) 与 [批量模板](BATCH_AI.md) 使用说明为准。

后续确认：AI 主编程入口改为原版 `.mcfunction`，JSON 负责注册、参数及动作；模板作为通用函数与配置的包装。下文关于“所有模板编译为固定状态机”的早期设想不再作为能力上限，旧状态机保留兼容。跨数据包及附属模组的路径、引用、覆盖和重载要求以 [数据包扩展规范](DATAPACK_EXTENSION_SPEC.md) 为准。

## 目标与基本选择

普通生物只写一个短 JSON，就能使用回合制 AI；同类生物可通过实体类型标签批量注册。需要特殊机制时，再逐层扩展参数、动作列表和状态机，不要求每个生物复制完整状态机。

采用“注册规则 → AI 模板 → 编译后的状态机 → 回合执行器”的结构。模板只是状态机的简写，战斗运行时只有一套执行逻辑。

目标覆盖原版和其他模组的 `Mob` 生物。玩家使用玩家控制器；盔甲架等非 Mob 实体默认排除。末影龙、多部件 Boss、特殊飞行/水生生物等可注册，但原版特殊阶段、攻击动画与模组技能不会自动得到完整适配，必要时使用 Java 扩展接口。

## 1. 最简单：一个生物一个文件

文件：`data/my_pack/mineturn/mobs/my_monster.json`

```json
{
  "schema_version": 2,
  "entity": "othermod:my_monster",
  "template": "mineturn:melee"
}
```

默认得到“追击 → 攻击”状态机：追击最近合法仇恨目标，进入攻击距离后使用普通近战动作。无需额外编写动作文件。

默认敏捷为 100、周身范围为 1.5 格；普通攻击优先读取实体的 `minecraft:generic.attack_damage` 属性，缺少该属性时使用模板默认伤害 2。伤害最终仍经过正常的护甲、抗性和伤害事件处理。模板默认值必须由模组版本稳定管理，不能随实体显示名称猜测。

## 2. 常用配置：少量参数加动作表

```json
{
  "schema_version": 2,
  "entity": "minecraft:zombie",
  "template": "mineturn:melee",
  "parameters": {
    "agility": 100,
    "control_radius": 1.5,
    "attack_range": 1.5
  },
  "actions": [
    {"action": "mineturn:claw", "weight": 70},
    {"action": "mineturn:bite", "weight": 30}
  ]
}
```

沿用现有动作文件格式，利爪和撕咬分别配置 4、6 点基础伤害。提供 actions 后替换模板默认攻击列表。权重无需合计 100；冷却、资源、距离或目标不合法的动作先过滤，再在剩余动作中抽取。

`control_radius` 是约束对手移动的周身范围，`attack_range` 是选取攻击目标的距离，两者不再混用。具体动作若声明自己的范围，优先使用动作范围。敏捷继续按现有规则与实体敏捷属性组合，不额外重复乘算。

## 3. 批量注册：实体列表、标签和命名空间

注册文件仍放在 `mineturn/mobs/`，使用 `match` 替代 `entity`：

```json
{
  "schema_version": 2,
  "match": {
    "entity_tags": ["my_pack:melee_creatures"],
    "exclude": ["othermod:special_boss"]
  },
  "template": "mineturn:melee"
}
```

对应标签文件：`data/my_pack/tags/entity_type/melee_creatures.json`

```json
{
  "replace": false,
  "values": [
    "minecraft:zombie",
    "minecraft:husk",
    {"id": "othermod:my_monster", "required": false}
  ]
}
```

`match` 支持 `entities`、`entity_tags`、`namespaces`、`all_mobs` 四种选择器；多个正向选择器取并集，再扣除 `exclude`。第一版不做正则表达式。`entity` 是 `match.entities` 的单项简写，两者不能同时出现。

可用 `optional: true` 跳过未安装模组中的实体 ID，同时报告跳过结果；默认缺失实体属于加载错误。标签中可选成员使用原版 `required: false`。空标签产生明确警告，不能悄悄当成注册成功。

## 4. 所有生物都有默认方案

模组内置一个优先级最低的 `all_mobs` 规则，使用 `mineturn:auto` 模板。这样未配置生物也有明确的回合行为，再用精确规则替换需要定制的生物。

| 类型 | 默认模板 | 参战方式 |
| --- | --- | --- |
| 敌对地面生物 | melee | 玩家附近战斗可作为敌方增援 |
| 被动动物 | flee | 被攻击时参战，优先远离攻击者 |
| 中立生物 | neutral | 仅被激怒或成为攻击目标时参战 |
| 驯服生物 | companion | 主人参战时按主人阵营加入 |
| 无法分类或特殊移动生物 | idle | 被直接卷入时等待，并显示待适配原因 |

这是一套保守默认行为，不代表每种生物都获得了原版特殊技能。飞行和水下移动控制器未实现前，不套用地面追击，也不把这些实体强制拉到地面。

增加显式配置 `join_policy`（`nearby_hostile` / `provoked` / `owner` / `disabled`）、`faction`、`locomotion`（`ground` / `flying` / `swimming` / `stationary`）。显式值优先于自动分类。注册 AI 不等于把生物变成敌对单位，附近牛羊不能无条件拉进每场战斗。

当前服务端通过 `Enemy` 接口筛选增援、通过“玩家/怪物”区分阵营；要支持这一步，必须同时改为配置驱动的参战政策和阵营关系，不能只扩展 JSON 解析。

## 5. 模板目录与继承

```text
data/<namespace>/mineturn/ai_templates/<name>.json
data/<namespace>/mineturn/mobs/<registration>.json
data/<namespace>/mineturn/actions/<action>.json
data/<namespace>/tags/entity_type/<group>.json
```

计划内置模板：

| 模板 | 行为 | 实施阶段 |
| --- | --- | --- |
| melee | 追击、近战 | 第一阶段 |
| idle | 等待 | 第一阶段 |
| flee | 远离威胁，需要时先撤退 | 第二阶段 |
| neutral | 激怒后反击 | 第二阶段 |
| companion | 跟随主人阵营，攻击共同敌人 | 第二阶段 |
| ranged | 维持射程、远程攻击 | 远程系统完成后 |
| caster / support | 技能优先级、治疗或支援 | 资源和效果体系扩展后 |

自定义模板可用 `extends` 继承一个父模板，并覆盖 `parameters`、`actions` 或指定状态。约定：参数按键合并，动作数组整体替换，状态按状态名整体替换；不隐式合并 transitions/choices 数组。最多继承 8 层，循环继承直接报错。

参数采用有类型的字段引用，例如 `{"parameter":"attack_range"}`，只允许出现在声明支持参数的数值字段；不对整个 JSON 做字符串替换，不执行任意脚本。

## 6. 高级需求仍使用状态机

复杂生物可以在模板上覆盖 `states`，或直接使用完整状态机。保留当前 `approach`、`weighted_action`、`wait` 行为以及 `in_reach`、`out_of_reach` 条件。

后续条件通过注册表扩展，例如生命比例、目标距离、动作是否冷却完成、主人是否受伤；行为扩展为撤退、移动到目标、远离目标、执行动作。条件只读，不能在判断时消耗资源或修改世界。

一个回合只做有上限的状态转换和动作决策。默认上限 8 次转换；若没有合法行为则等待并记录诊断，不能陷入无限循环或用失败动作刷回合。

同一生物每回合最多消耗一个主要行动，移动可分次使用预算。疾跑和撤退也必须经过统一预算执行器，AI 不能绕过周身范围、移动距离、摔伤、冷却或目标校验。

## 7. 冲突与热重载规则

1. 相同资源路径按原版数据包优先级覆盖。
2. 不同注册文件都匹配同一实体时，先比较显式 `priority`（默认 0）。
3. 同优先级按匹配精度排序：精确实体 ID > 实体标签 > 命名空间 > all_mobs。
4. 同优先级、同精度仍冲突时拒绝整次重载，列出实体及全部冲突文件，禁止依赖文件遍历顺序。
5. 内置兜底使用最低优先级；`enabled: false` 的获胜规则显式排除该实体，不再回退到默认模板。

模板、注册规则、动作和实体标签要在同一次资源重载中完成解析与校验。标签须在新标签完成绑定后解析，不能读取旧标签然后发布新 AI。生成不可变的 EntityType → CompiledBrain 索引，再一次性替换当前快照。

加载失败保留上一个有效快照。正在进行的战斗和其后续增援继续使用该场战斗创建时的快照；新战斗使用新版本，避免半场更换 AI。

## 8. 让作者容易发现问题

规划以下命令（尚未实现）：

- `/mineturn ai inspect <entity_type>`：最终匹配规则、模板、参数、覆盖来源和参战政策。
- `/mineturn ai inspect_target`：准星生物的实际配置、当前状态、目标及最近一次决策原因。
- `/mineturn ai validate`：统计已匹配实体、缺失模板/动作、冲突和可选跳过项。
- `/mineturn ai example melee`：输出可复制的最小 JSON 和放置路径；不自动覆盖作者文件。

报错包含文件 ID 和字段路径，例如 `my_pack:mobs/goblin → actions[1].action → 缺少动作 my_pack:slash`。提示“由于所有技能冷却中而等待”，比笼统显示“AI 无动作”更便于调试。

## 9. 对现有代码的改造

| 位置 | 改造内容 |
| --- | --- |
| CombatData | 拆分动作、模板、注册规则解析；增加继承展开、选择器解析及冲突检查 |
| Brain / Snapshot | 增加编译后策略、移动类型和来源信息；以实体类型索引查询 |
| BattleManager | 使用统一 AI resolver 决定能否触发战斗 |
| BattleSession | 使用 join_policy 和阵营关系；执行器从硬编码分支转向行为注册表 |
| CombatEffects | 沿用现有效果接口，增加普通攻击读取实体属性的内置实现 |
| 新扩展接口 | CombatBehaviors、CombatConditions、CombatTargetSelectors、CombatLocomotion |

兼容现有含 `entity + agility + reach + initial_state + states` 的旧格式，将其编译成等价的精确注册规则。第一版不要求作者迁移所有旧文件；旧格式与新格式争用同一实体时仍遵循明确冲突规则。

不直接恢复其他模组的实时 AI 来“执行一次技能”：它可能在一个游戏 tick 内触发多次伤害、召唤或传送。需要保留的特殊技能应通过有明确资源与动作消耗的扩展行为调用。

## 10. 实施顺序与验收

**第一阶段：少写文件。** 实现 melee/idle 模板、单实体简写、actions 覆盖、旧格式兼容及 inspect。验收：模组生物只填实体 ID 和模板即可完成追击攻击；原有僵尸行为不变。

**第二阶段：批量覆盖所有生物。** 实现列表/标签/命名空间、冲突规则、兜底策略、被动/中立/宠物参战政策及阵营。验收：一份标签注册几十种生物；牛羊不主动卷入战斗；精确覆盖稳定生效；可选模组未安装也能加载。

**第三阶段：高级组合。** 实现模板继承、可扩展条件/行为、决策诊断与特殊移动控制器。再接入远程、施法和复杂 Boss。

自动化测试覆盖：继承循环、缺失参数、非法类型、覆盖冲突、标签热重载、旧格式迁移、兜底排除、缺少攻击属性、阵营选择、资源消耗、无合法动作时等待，以及重载失败后旧战斗继续运行。
