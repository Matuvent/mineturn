# 物品适配数据包示例

安装 `mineturn-item-adapters-example.zip` 到存档 datapacks 后执行 `/reload`。它只用于演示；未安装时不会改变原版映射，移除并重载即可恢复。勿与同优先级、相同物品的其他适配示例同时安装。

## 实际变化与验收

| 示例 | 生效物品 | 行为与检查 |
| --- | --- | --- |
| weapon | 铁剑、木棍 | 原版属性近战；铁剑读取原版伤害/附魔/耐久。木棍演示给原先未启用的物品增加战斗动作，仍只有其原版属性伤害，JSON amount 不会把木棍变成高伤武器 |
| food | 苹果 | 读取 FOOD 的饱食度、饱和度、效果与转换物品；原版进食负责一次消费 |
| potion | 喷溅药水 | 新增命名的敌方投掷动作，同时保留友方/地面/自用入口；读取实际 POTION_CONTENTS |
| projectile | 雪球 | 远程判定与原版命中；成功对烈焰人特殊伤害，未命中也消费，无缓慢 |
| teleport | 末影珍珠 | 原有选点、碰撞检查与自伤；AV 冷却及原版冷却交接保持生效 |

每组一个 actions JSON、一个 items JSON，全部位于 data/item_example/mineturn 下。动作 ID 为 item_example:weapon/food/potion/projectile/teleport。items 文件使用 priority=100 覆盖内置的低优先级物品映射，其他物品不变。物品已有自带 mineturn:combat 组件时，组件优先于物品映射。

先手中拿铁剑/木棍并放入快捷栏，确认示例动作名称；测试苹果一次消费、带不同药水内容的喷溅药水、雪球成功/失败消耗，再测试珍珠落点与冷却。界面效果需客户端和服务器版本一致。

## 新增与覆盖的区别

1. 新增动作：在自己的命名空间新增 actions/<名称>.json；在 items JSON 的 actions 列表引用完整 ID。
2. 覆盖单种物品：新建 items JSON，并设比现有映射高的 priority。列表是完整替换，不自动合并，因此示例显式保留药水的其他入口。同物品同优先级冲突会拒绝重载。
3. 覆盖现有动作：使用完全相同的命名空间与资源路径，例如 data/mineturn/mineturn/actions/melee.json。Minecraft 数据包优先级决定同路径覆盖；会影响所有引用该动作 ID 的物品/生物，修改前注意范围。
4. 适配其他模组已注册的物品：将 items 列表中的 ID 改成该模组真实 ID。数据包不能注册一个全新的物品注册表对象；物品本体仍由模组提供。
5. 附属模组内置：把相同 data 目录放到 src/main/resources/data，随模组 JAR 打包即可，适配层不需要通过代码逐条注册 JSON。

示例内不使用不存在的占位物品 ID，不依赖第三方模组。请从副本修改，保留自己的命名空间。

## 自动读取的原版组件与边界

| 执行器 | 自动读取 | 不会自动完成 |
| --- | --- | --- |
| mineturn:weapon_melee | 实际选中装备的攻击属性、原版伤害/攻击后附魔、耐久 | 自定义武器右键技能、魔力和连段；amount 不是覆盖原版攻击力的旋钮 |
| mineturn:food | FOOD 与原版 Player.eat，饱食度/饱和度/食物效果/using_converts_to、无限材料消费规则 | 任意自定义 finishUsingItem 行为；特殊食物需要已提供的专用执行器或 Java 适配 |
| mineturn:splash / lingering | POTION_CONTENTS，包括自定义颜色和效果，原版药水效果类型 | 未注册药水类型；场外实体效果和世界方块变化 |
| mineturn:projectile | 弓/弩原版附魔、实际药箭内容与 CHARGED_PROJECTILES，耐久 | 任意新弹射物的特殊命中逻辑 |
| mineturn:firework | FIREWORKS 的爆炸组件与外观 | 普通手持烟花推进鞘翅 |
| mineturn:player_snowball | 原版雪球命中语义 | 换一个物品 ID 就继承其独有投射物行为；执行器明确限定雪球 |
| mineturn:ender_pearl / chorus_fruit | 专用受约束传送、原版事件与冷却交接 | 任意传送物品的自动适配；参考 TELEPORT_API.md 注册自定义效果 |

通用组件值通过实际 ItemStack 读取，不维护另一套原版伤害/食物数值表。执行器有物品类型限制时，仅在 JSON 中改 ID 不够。mineturn:combat 只决定动作入口，不赋予物品新的原版组件或代码行为。

## 费用与安全边界

普通动作默认花一次主要行动，可用 parameters.action_cost 改为已有的主要/次要行动成本。consume 与执行器约定一致：food 使用 consume=1 但由原版进食负责扣除，不能额外再扣一次；雪球/珍珠使用 consume=1；原版武器近战 consume=0、命中时消耗耐久。冷却使用 cooldown_av，不能用原版秒数直接填写。

数据包动作不能开放战斗换装、任意原版右键或方块操作。特殊模组行为通过 CombatEffects 注册 Java 执行器，在 validate 中只检查，在 execute 中处理已支付的效果；延迟效果用受管理的 BattleAccess.after，位移和传送使用现有公共接口。切勿在客户端或条件查询函数中结算伤害/扣费。铁魔法、灾变及饰品槽目前仍需对应附属适配，不视为已兼容。

## 原版物品冷却与 AV

盾牌、末影珍珠、紫颂果和风弹共用冷却交接规则。进入战斗时读取原版剩余 tick，按 1 tick = 5 AV 转换；战斗内等待不缩短服务端冷却，AV 推进才会缩短；离战时将剩余 AV 向上取整为原版 tick，随后恢复原版读秒。

风弹发射时同时登记行动冷却和物品冷却，时长取行动 JSON 的 `cooldown_av`（AV）；判定失败也不退还。更换动作 ID 不会清除已经登记的同种物品冷却。其他模组的法术冷却和自定义资源系统不由这四种物品的规则自动管理，需通过 Java 适配器接入。
