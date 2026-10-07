# 资源消耗与魔法适配协议

范围更新：用户已取消铁魔法等魔法模组兼容计划。本文仅保留已有接口的使用说明，不代表后续开发承诺；不再新增魔法专有冷却交接适配器。

这是通用接口，不代表已经兼容铁魔法、Curios 或其他模组。条件行动默认占用一次主要行动，可在动作中用 parameters.action_cost 配置主要/次要次数，见 [ACTION_COSTS.md](ACTION_COSTS.md)，沿用目标、视线、移动锁、远程判定及 AV 冷却校验。

## 数据包资源费用

在 grants JSON 中增加可选的 cost：

```json
"cost": {"resource": "mineturn:experience_levels", "amount": 2}
```

内置 experience_levels 消耗经验等级，只接受正整数。省略 cost 的旧数据包保持免费资源消耗；示例金胸甲激光数据包无需修改也可继续使用。可将上述字段加入示例 chestplate_laser.json，执行 /reload 后用经验等级测试费用。

费用必须有限且大于 0、不超过 1000000；未知资源类型或不符合提供方规则的数值会拒绝重载。资源不足时按钮保持可见但不可用，提示由提供方返回。余额可见状态随常规战斗同步更新，真正施放会立即重检。

预览不扣费。全部战斗校验通过后调用一次 trySpend，成功才占用主要行动、启动远程判定或执行效果。扣费返回 false 时不设置动作冷却、不扣主要行动、不产生效果。支付成功后的未命中、条件撤销、退出战斗或效果执行异常均不自动退款。数据包每个 grant 配置一个资源提供方；需要同时消耗多种魔力/能量时，由一个组合提供方实现原子事务，不能逐个部分扣除。

## 注册第三方资源

附属模组在数据包加载前注册资源。所有查询和扣费均在服务器线程执行：

```java
CombatResources.register(ResourceLocation.parse("addon:mana"), new CombatResources.Resource() {
    public String check(ServerPlayer player, double amount) {
        return mana(player) >= amount ? null : "魔力不足";
    }
    public boolean trySpend(ServerPlayer player, double amount) {
        // 必须再次核对余额，并保证失败时零修改。
        return subtractManaAtomically(player, amount);
    }
});
```

示例中的 mana/subtractManaAtomically 由适配模组实现。check 必须只读、快速，返回简短原因；trySpend 不得施法或修改战斗行动，也不得在返回 false 后留下部分扣费。可覆写 validateAmount 限制整数单位等；重复注册 ID 会报错。扣费后的玩家资源同步也由提供方负责。

## 施法入口与冷却

- `CombatCasting.cast(player, grantId, targetId)`：在服务器线程提交条件行动；返回 Result，accepted 表示已接收并消耗行动，不代表远程已经命中。失败可读取 reason。战斗外返回拒绝，由原模组继续自身流程。
- `CombatCasting.allowsOriginalCast(caster)`：适配方在原模组的服务器施法入口调用。战斗内默认禁止；仅在 MineTurn 授权的动作效果执行期间允许调用底层原法术。客户端快捷键检查不能替代服务器检查。
- `CombatCasting.usesBattleClock(caster)`：战斗期间为 true，适配方据此暂停自己的实时冷却更新。
- `CombatCasting.remainingAv(player, actionId)`：查询 MineTurn 剩余动作冷却，按动作 ID 而非 grant ID；战斗外返回 0。仅在服务器线程调用。

正确链路是：外部入口提交 grant → MineTurn 校验并预付资源 → 执行动作效果（远程需先命中判定）→ 自定义 CombatEffects 效果调用原模组法术。不要在 cast 返回 accepted 后立即再次调用法术；那会跳过判定或重复施放。原法术的魔力扣费应由适配方抑制，避免与资源提供方重复收费。

接口不会自动拦截其他模组的网络包、独立 tick 或内部冷却。适配方还需决定入战/离战时原生冷却与 AV 的转换、保存及恢复，并处理其特殊投射物和持续施法。当前验证覆盖资源原子失败、预览无扣费、重复提交、未命中费用、原生入口授权判断及 AV 查询；尚未加载实际魔法模组做兼容测试。
