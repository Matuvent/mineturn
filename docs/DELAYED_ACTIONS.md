# 公共 AV 延迟技能（第一组）

本组统一可取消、可重检的预付延迟结算，不代表通用召唤/场上单位已完成。原有 `after` 保持兼容，继续仅自动检查参与者身份与生存状态；需要更严格规则的效果使用 `afterChecked`。

## Java 接口

在 `CombatEffects.Effect.validate` 中使用 `context.battle().canSchedule(delayAv)` 只读检查队列容量；所有延迟共用每场 128 个待执行任务上限，包括 mcfunction 定时任务。延迟必须为有限数值，范围 0.01–100000 AV。

在已经付费的 `execute` 或托管回调中：

```java
UUID ticket = context.battle().afterChecked(25,
    check -> check.source().hasLineOfSight(check.target())
        && check.source().distanceTo(check.target()) <= check.action().range()
        && check.battle().enemies().contains(check.target()),
    ready -> ready.battle().hurt(ready.target(), (float) ready.action().amount()));
```

返回的 UUID 是当前战斗中的取消句柄。可以由同一施法者在后续授权效果/回调中调用 `context.battle().cancelScheduled(ticket)`；返回 true 表示取消成功。其他施法者、过期上下文、预览阶段不能取消这项任务。重复取消返回 false，不支付也不退还任何费用。句柄应由适配器按战斗/施法者管理，不要缓存整个 Context 后异步使用。

结算先自动检查施法者和原目标仍是同场、同一批参与者且存活，再运行纯查询 predicate；查询获得最新 AV 时间和只读战斗访问上下文。其修改类 API 会拒绝调用。predicate 返回 false 时终止此次结算，不退款，不再次支付行动/资源/冷却；返回 true 才开启托管执行权限。自定义条件必须无副作用，不要直接修改实体或使用外部命令绕过检查。

物品与弹药在安排时复制；作用目标锁定为原目标，不自动换敌人。施法者/目标离场立即删除相关技能任务，重新进入不能继承旧任务；战斗关闭清空任务。相同 AV 的任务依安排顺序执行，每项先出队再结算，异常不会自动重试。原有同时间戳状态伤害仍先于技能结算，致死会取消待执行技能。现实等待不推进 AV。

## 数据包动作

新增 `mineturn:delayed_strike`：敌方实体目标，`consume:0`，不配置 ranged，`parameters.delay_av` 为必填数值。`amount` 是基础伤害，`range` 同时限制发起和结算。动作费用和 `cooldown_av` 仍使用公共规则。队列已满时预检拒绝，既不扣行动，也不扣 grant 资源或启动冷却。

效果结算重新检查敌对关系、距离和视线；命中调用授权伤害接口，不附加普通击退。后续装备/资源变化不自动撤销这个已经发起的技能；如需要持续佩戴或持有物品，由 Java 适配器在 predicate 中加入该条件。

## 可安装示例

构建 `delayedActionExamplePack`，将 `build/examples/mineturn-delayed-actions-example.zip` 放入存档 `datapacks` 并 `/reload`。进战前戴铁头盔，至少保留 1 经验等级；右侧出现“蓄力打击”。选敌人后支付 1 主要行动、1 经验等级，启动 150 AV 冷却；25 AV 后重检并造成 6 基础伤害，射程 8 格。发起不直接伤害，需推进战斗 AV。示例不会随模组默认启用。

数据文件位于 `examples/delayed-actions/data/timeline_example/mineturn/`，其他数据包可同路径覆盖。没有新增网络字段，协议仍为 18。本组尚未制作专门蓄力动画或待结算队列面板。

## 后续分组

- 通用临时召唤已完成第二组：[SUMMON_ACTIONS.md](SUMMON_ACTIONS.md)，包含出生点预检、敌我继承、生成失败、数量限制及 AV 寿命清理。
- 非生物场上区域已完成周期危险区域首版：[FIELD_ACTIONS.md](FIELD_ACTIONS.md)。进入触发陷阱和可攻击机关已在 [TRAPS_AND_DEVICES.md](TRAPS_AND_DEVICES.md) 中实现；临时召唤生物的生命周期已接入。
- 原有恼鬼、潜影弹、药水云等迁移需按各自语义逐项验证，不直接套用统一实体删除规则。
- 断线/重启持久化属于后续恢复工作；当前延迟队列只存在内存，不跨重启恢复。

## 本组验证

255 项服务器 GameTest 全部通过，构建成功，日志 `build/delayed-actions.log`。真实示例覆盖预付、命中/遮挡/超距/离场、取消及只读重检、容量拒绝零费用与退战清理；原有蓄力和光束测试保持通过。客户端按钮与联机体验仍需实测。
