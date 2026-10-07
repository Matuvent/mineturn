# 条件行动与装备激光示例

条件定义放在 `data/<命名空间>/mineturn/grants/<名称>.json`。动作仍放在原有 `mineturn/actions/` 下；其他数据包或附属模组可以增加自己的定义，也可以用相同资源路径覆盖已有定义。重载沿用原有整套校验与失败回滚。

```json
{
  "action": "grant_example:laser",
  "name": "发射激光",
  "icon": "minecraft:beacon",
  "order": 10,
  "enabled": true,
  "equipment": {"chest": "minecraft:golden_chestplate"},
  "effects": [],
  "conditions": []
}
```

所有条件同时满足才显示按钮。`equipment` 使用原版装备槽名称（head/chest/legs/feet/mainhand/offhand）和精确物品 ID；`effects` 是必须拥有的药水效果 ID。未设置条件的启用定义始终提供行动，`enabled:false` 可禁用。图标采用物品图标，order 小的靠前，同值按定义 ID 排序。名称最多 64 字符，整包最多 128 个条件行动。

右侧现有操作旁显示独立按钮列，每页三个，超出后用“更多行动”翻页。点选敌方动作后点击场内目标；自身动作立即提交。未轮到、移动中、该动作所需行动次数不足、冷却未结束或没有合法目标时按钮不可用并显示原因。可用列表随战斗同步刷新（通常不超过一秒）；服务端执行前立即重新判断条件，不依赖按钮是否仍显示。

每个行动默认消耗一次主要行动，可通过动作的 parameters.action_cost 配置主要/次要行动次数，见 [ACTION_COSTS.md](ACTION_COSTS.md)，复用动作定义的目标规则、弹药消耗及 AV 冷却。条件动作的 `consume` 必须为 0，不会隐式消耗装备；效果收到的物品上下文为 EMPTY，所以依赖手持物品内容的饮用/近战武器效果应继续用快捷栏。可用 cost 配置资源提供方和金额，参见 MAGIC_ADAPTERS.md；行动次数与资源成本分别校验，具体魔法模组需实现资源提供方。不同条件定义指向同一动作时共享该动作冷却。

带 ranged 的动作使用现有远程判定，发起时预扣行动、弹药和冷却。判定提交时重新检查条件、目标视线和效果限制；条件失效则不命中，但不退还预付消耗。重复或伪造提交不会获得额外行动。

## 附属模组条件

附属模组可在数据包加载前注册纯服务端条件：

```java
CombatConditions.register(ResourceLocation.parse("addon:wears_laser_charm"),
        player -> /* 查询该模组的饰品槽 */ hasLaserCharm(player));
```

数据包随后配置 `"conditions":["addon:wears_laser_charm"]`。条件会频繁查询，应只读、快速、无副作用；不要在里面扣除资源。未知条件 ID、物品/效果/图标 ID、缺失动作引用会使重载失败。这是扩展接口，尚未直接集成 Curios、灾变或铁魔法。

## 可安装示例

将 `mineturn-granted-laser-example.zip` 放入存档 `datapacks` 文件夹并执行 `/reload`，穿上金胸甲后进入战斗。右侧出现“发射激光”：6 点基础伤害、16 格射程、200 AV 冷却，不消耗弹药，命中时显示光束粒子，不击退、不破坏方块。此数据包仅为测试示例，不会默认改变所有金胸甲；移除数据包即可移除示例。

该版本新增网络协议，联机客户端和服务器须同时更新。服务器回归测试不覆盖实际 GUI 缩放、分页布局和光束观感，需要客户端实测。

## 完整资源与位移示例

新增 [accessory-actions 示例](../examples/accessory-actions/README.md)，构建任务 `accessoryActionExamplePack`。金胸甲提供经验等级付费激光，金靴提供次要行动震退，完整展示装备、费用、目标、冷却、位移及真实饰品槽替换步骤。原免费金胸甲示例仍独立保留；同时安装两包会显示两套按钮，测试完整示例时建议只启用新包。
