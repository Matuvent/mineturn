# 战斗传送扩展接口

## 数据包直接使用

可安装的示例位于 `examples/teleport-action`，复制该目录到世界的 datapacks 后 `/reload`。它将木棍映射为“向东闪现”，不会自动加入游戏内置物品注册。模组也可以把相同结构放在 `src/main/resources/data/<namespace>/mineturn/` 下，由资源重载统一加载、覆盖。

动作效果 `mineturn:teleport_offset` 使用世界坐标轴相对位移：dx 向东、dy 向上、dz 向南，不随角色朝向旋转。要求 self=true，无 ranged、无 ground_target；range 是三轴曼哈顿上限，必须大于 0 且不超过 32。parameters 支持 dx/dy/dz（默认 0）、require_sight（默认 true）、allow_water（默认 false）。零位移、非法数值和超出范围的偏移会在重载时拒绝。行动、消耗和 cooldown_av 沿用普通动作字段；默认消耗一次主要行动。

示例的动作 ID 为 `teleport_example:blink_east`。生物配置将它加入允许使用的动作集合后，mcfunction AI 可调用：

```mcfunction
mineturn ai use teleport_example:blink_east
```

此效果是固定相对坐标闪现；自定义选点 GUI 与随机选点策略由调用方提供。它不替代末影珍珠或紫颂果的专用使用流程。

## Java 附属模组

在初始化阶段通过 CombatEffects.register 注册效果。validate 阶段只预览，execute 或受管理的 after 回调才可以传送：

```java
CombatEffects.register(ResourceLocation.fromNamespaceAndPath("my_addon", "blink"),
    new CombatEffects.Effect() {
        private CombatTeleport.Rules rules() {
            return new CombatTeleport.Rules(8, false, false);
        }
        public String validate(CombatEffects.Context c) {
            return c.battle().previewTeleport(c.source(),
                c.source().position().add(3, 0, 0), rules()).error();
        }
        public void execute(CombatEffects.Context c) {
            var result = c.battle().teleportTo(c.source(),
                c.source().position().add(3, 0, 0), rules());
            if (!result.success()) throw new IllegalArgumentException(result.error());
        }
    });
```

`CombatTeleport.Rules(range, requireSight, allowWater)` 是不可变规则。Result.success 表示成功，destination 为规范化后的脚部坐标，error 为失败原因。previewTeleport 无资源和位置副作用；teleportTo 重新验证后即时结算，不再扣第二次行动或移动距离。传送对象可以是同场存活玩家或生物（包括友军/敌人）；由效果作者选择合法技能目标。范围从被传送者当前位置计算。

预览不是位置预留。预览后方块、参战者或状态变化都会导致提交失败；提交失败不移动实体。普通动作预检失败不扣费；已付费效果或延迟结算中的失败不自动退款，调用方应根据 Result 处理。过期、未付费、场外线程或已退出的执行上下文不能提交传送。

## 共同规则与边界

- X/Z 对齐方块中心；检查三维范围、战场半径/垂直边界、世界边界、建筑高度、全部碰撞箱及同场占用，不主动加载区块。
- 必须有地面支撑，或明确允许水中落点。禁止岩浆和其他危险流体；不提供空中悬停模式。普通水面会按现有水体规则下沉。
- 可以脱离周身控制，不消耗普通移动，不沿路线检测；requireSight=true 时额外检查视线。
- 骑乘者、载客实体、睡眠者、已死亡或离场对象拒绝。当前移动/远程判定未结束时拒绝，避免旧路线覆盖传送结果。
- 成功后更新服务端位置锚点、客户端状态、清除摔落距离及玩家冲量状态，并发送 TELEPORT 游戏事件；不自动产生伤害、粒子、音效、物品冷却或召唤物。
- 通用接口不伪造末影珍珠/紫颂果专属 NeoForge 事件；需要这些语义的物品仍使用各自专用执行器。第三方技能的取消事件、特效和冷却由附属模组实现。
- 原有 BattleAccess.teleport(double) 是末影人生物专用随机瞬移，保持兼容；新的通用入口是 previewTeleport/teleportTo。

骑乘整体传送、无支撑的飞行落点、客户端通用选点动作，均不在本版接口中。
