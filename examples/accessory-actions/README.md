# 饰品条件行动完整示例

这是可直接安装的数据包，用金胸甲和金靴模拟饰品条件，不需要安装其他模组，也不注册新的物品。示例不会随 MineTurn JAR 自动启用。战斗中仍禁止换装，请在进战前穿好装备。

## 安装和体验

执行 `./gradlew.bat accessoryActionExamplePack`，将 `build/examples/mineturn-accessory-actions-example.zip` 放入存档 `datapacks`，执行 `/reload`。也可把本目录整体复制到 `datapacks`（pack.mcmeta 必须在包根目录）。客户端、服务端使用当前同版本 MineTurn。

1. 穿上金胸甲、金靴，使用 `/experience set @s 10 levels` 设置测试资源。
2. 与怪物进入战斗，右侧条件行动栏出现下表中的按钮。
3. 点击按钮后选敌人；激光还需要完成远程判定。
4. 等待现实时间不会刷新 AV 冷却；推进战斗才会刷新。资源不足时按钮禁用，不会先扣行动。

| 装备 | 动作 | 行动费用 | 资源费用 | 射程 | AV 冷却 | 效果 |
| --- | --- | --- | --- | --- | --- | --- |
| 金胸甲 | 能量激光 | 1 主要 | 2 经验等级 | 16 | 200 | 远程判定成功后造成 6 基础伤害并显示光束 |
| 金靴 | 震退 | 1 次要 | 1 经验等级 | 3 | 150 | 无远程判定，把目标水平推开最多 3 格，上抬 0.5 格 |

“震退”没有直接伤害，但从高处跌落仍可能产生原有摔伤；墙体、其他实体、未加载区块和战场边界限制位移。处在周身范围内也能使用，不花普通移动距离。完全被挡住时仍支付本次技能费用，不保证每次都能推满距离。普通攻击依旧无击退。

## 文件与覆盖

- `data/accessory_example/mineturn/actions/laser.json`：效果、射程、远程判定、主要行动费用与 AV 冷却。
- `data/accessory_example/mineturn/actions/repulse.json`：技能位移与次要行动费用。
- `data/accessory_example/mineturn/grants/laser.json`、`repulse.json`：装备条件、按钮名称/图标/排序、经验等级费用。

actions 的 `cooldown_av` 控制 AV 冷却；`parameters.action_cost` 控制主要/次要次数。grants 的 `cost` 是独立资源费用；`consume` 为 0，不消耗穿戴装备，也不扣装备耐久。示例按精确物品 ID 判断装备，不要求自定义名称或组件。

其他数据包可用相同命名空间和路径覆盖定义，用自己的路径可新增动作。不同 grant 指向同一个 action 时共享动作冷却。附属模组把 data 目录放入 `src/main/resources/data`，即可随模组加载。

## 替换为真正的饰品

附属模组在数据包加载前调用 `CombatConditions.register` 注册条件，如 `addon:wears_laser_charm`。条件实现读取对应饰品模组的装备槽，必须只读、快速、无副作用。随后从 grant 删除示例 `equipment`，换成：

```json
"conditions": ["addon:wears_laser_charm"]
```

如果保留 equipment，则两者必须同时满足。未知条件 ID 会拒绝重载，不能只写一个不存在的条件。Curios、灾变等实际饰品 API 的查询由附属模组实现；本示例不宣称已经兼容它们。

资源提供方同理：附属模组通过 `CombatResources.register` 注册魔力/能量，再把 `cost.resource` 换成其 ID。`check` 只查询，`trySpend` 必须原子扣费，失败不修改任何资源。不要在条件或效果中再次扣相同费用。

## 时序与边界

服务端依次验证回合、装备/条件、目标、射程/视线、行动次数、资源和冷却，通过后支付。激光在开始判定时预付；失败、超时、判定中装备失效都不会退款。判定结算重查条件，失效不能命中。重复提交不会再次结算或扣费。

数据包可以复用内置效果。`mineturn:repulse` 要求敌方目标、`self:false`、`consume:0`、`amount:0`；`parameters.distance` 为 (0,8]、`rise` 为 [0,2]，均为数值，默认 3 和 0.5。可另加 ranged 实现需要判定的震退。此效果只推当前选中目标，不是范围攻击。

更复杂的激光穿透、持续施法、组合伤害/位移需要新增 Java 效果，通过 `CombatEffects.register` 注册，在授权执行期间调用 `context.battle().displace(target, offset)` 等接口；不要通过原版自由速度或绕过战斗入口实现。

服务端自动测试覆盖真实示例 JSON、装备隐藏、资源不足、激光预付及条件撤销、重复提交、次要行动震退、碰撞和 AV 冷却。实际按钮布局和联机视觉仍需游戏内测试。
