# MineTurn 蓄力打击示例

使用当前版本 MineTurn。将本目录或构建产物 `mineturn-delayed-actions-example.zip` 放入存档 datapacks，执行 `/reload`。进战前戴铁头盔，保留至少 1 经验等级。

右侧“蓄力打击”：选择 8 格内敌人，支付 1 主要行动和 1 经验等级，启动 150 AV 冷却；推进 25 AV 后重检敌对关系、射程和视线，通过则造成 6 基础伤害。目标离场取消；被挡住或超出射程则落空。落空不退还费用。等待现实时间不会结算。

`actions/strike.json` 定义费用、延迟、伤害、射程和冷却，`grants/strike.json` 定义装备和资源条件。无独立蓄力动画，不增加普通击退，不默认启用。不兼容旧版缺少 delayed_strike 效果的 MineTurn。

构建命令：`./gradlew.bat delayedActionExamplePack`。完整接口文档见仓库 docs/DELAYED_ACTIONS.md。
