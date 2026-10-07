# 场上区域与周期效果（第一版）

后续已加入进入触发陷阱与可攻击核心，见 [TRAPS_AND_DEVICES.md](TRAPS_AND_DEVICES.md)。以下记录首版周期区域规则；新增功能使用协议 19。

新增公共 CombatFields 接口和 mineturn:hazard_field 数据包效果。场上区域是服务器托管记录，不生成可交互实体、不放置方块、不加入行动队列；用粒子环提示范围。第一版覆盖固定危险区域及 Java 自定义周期效果，不包含可攻击机关、进入瞬间触发的陷阱、地面点选 UI 或网络寿命面板。

## 安装示例

构建 `./gradlew.bat fieldActionExamplePack`，将 `build/examples/mineturn-field-actions-example.zip` 放入存档 datapacks 并 `/reload`。进战前戴锁链头盔并保留至少 1 经验等级。

右侧“危险领域”支付 1 主要行动、1 经验等级，启动 150 AV 冷却；在自己世界坐标 +X 方向 2 格的位置创建半径 2 格、高 2 格的圆柱区域，持续 100 AV。每 25 AV 对同场敌人造成 2 基础伤害。首次在 25 AV 触发，随后为 50、75；100 AV 到期优先删除，不再触发。等待现实时间只展示边界，不会造成伤害。

文件位于 `examples/field-actions/data/field_example/mineturn/`。其他数据包可同路径覆盖动作或装备/资源条件；不会随模组默认启用。示例不消耗头盔耐久，不额外附带点燃、击退或方块破坏。

```json
{
  "name": "危险领域", "effect": "mineturn:hazard_field",
  "self": true, "amount": 2, "range": 8, "consume": 0, "cooldown_av": 150,
  "parameters": {
    "dx": 2, "dy": 0, "dz": 0,
    "radius": 2, "height": 2,
    "interval_av": 25, "lifetime_av": 100
  }
}
```

偏移是世界坐标，长度不超过 8 格；区域固定，不跟随施法者或目标。radius 为 (0,4]、height 为 (0,4]，interval_av 为 5–10000，lifetime_av 必须大于 interval_av 且不超过 10000；全部为有限数值。self 必须为 true，consume 为 0，不配置 ranged；行动次数仍可用 parameters.action_cost 配置。

## 生命周期、目标与边界

- 每名施法者最多 4 个、每场最多 8 个区域，与召唤物数量分别计数。数量或位置预检失败不支付费用；执行失败/到期/主动移除不退款。
- 创建时检查中心到施法者的距离、整片区域的战场/世界边界、已加载区块和中心视线；不允许中心放在实体碰撞方块内，可在空中或水中。
- 每次触发重新查找活着的同场敌人。按目标 XZ 位置到中心的水平距离与实体包围盒的高度相交判断，不伤害友军或场外实体。区域中心到目标视线被墙阻挡时跳过。
- 不因目标死亡而结束整片区域；后续进入区域的敌人可在下次周期受影响。离开后下次不受影响，跨过区域不会立即触发。
- 原有状态伤害先结算，再处理召唤寿命和区域，随后执行普通延迟任务。每个周期先推进下一次触发时间，再调用效果，防止重入或重复结算。
- 同场多个区域独立结算，伤害可以叠加。多个目标顺序按战斗成员顺序；回调可改变后续目标状态，服务端会重检。
- 施法者死亡/离场或整场结束清理区域。它们只存在内存，不留下实体或方块；不跨服务器重启恢复。已发出的短暂粒子可能在逻辑区域结束后稍晚消散。

## Java 接口

```java
var request = new CombatFields.Request(center, 2, 2, 25, 100);
String reason = context.battle().previewField(request); // null 表示合法，只读
var result = context.battle().createField(request, pulse -> {
    pulse.battle().hurt(pulse.target(), 2);
});
UUID id = result.id();
// 同一施法者在后续授权动作/回调中可调用：
boolean removed = context.battle().removeField(id);
```

createField 必须在已支付的效果或托管回调中调用；不会二次收费。每次周期对每个有效敌人调用一次回调，提供新的 Context 与当前 AV 时间，原动作参数和物品快照保留。不要保存上下文供异步使用；效果回调结束即失效。施法者只能移除自己的区域，重复移除返回 false。

第一版目标固定为敌人；自定义回调可通过已有授权 API 添加位移等效果，不应直接使用命令绕过参与者和费用检查。回调发生 RuntimeException 时记录日志并移除该区域，不重试。自定义效果不应在每次触发时重复扣原动作费用。

## 后续

进入触发、一次性陷阱、友军增益区域、可攻击机关、粒子样式配置与完整 UI 仍可在此基础上扩展。原版药水云保留现有独立行为，不在本组迁移。

## 验证

264 项服务器 GameTest 全部通过，构建成功，日志 build/field-actions.log。覆盖真实数据包、费用、周期、到期优先、敌我/场外过滤、动态遮挡与离开、展示不推进 AV、容量/权限、离场清理、真实时间轴及回调上下文过期。客户端粒子边界与联机表现仍待游戏内实测。
