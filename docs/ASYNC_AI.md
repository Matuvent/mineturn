# 数据包异步移动

旧 `move`、`move3d` 保持同步。新指令沿经过校验的路径逐步移动，适用于 ground、flying、swimming、climbing、phasing 和水中的 ground。move_async toward_target 支持对应模式的寻路；phasing 可以穿墙但不能隔墙攻击。显式位移仍走直线。

```mcfunction
mineturn ai target nearest_enemy
return run mineturn ai move_async toward_target
```

也可指定相对位移（三个参数依次为 dx、dy、dz）：

```mcfunction
return run mineturn ai move_async 0 2 0
```

ground 模式仍不允许主动垂直移动。开始时扣除路线距离，主要行动不自动消耗；周身范围内仍须先撤退。指令返回 1 表示已开始，0 表示未开始。遇阻的直线可以只执行合法前段，与旧移动命令一致。

生物 JSON 的 `ai` 可注册新的完成回调：

```json
{
  "entity": "minecraft:husk",
  "ai": {
    "on_turn": "my_pack:turn",
    "on_move_finished": "my_pack:after_move"
  }
}
```

成功发起后，当前函数及其嵌套函数不能再调用战斗行动指令；原版命令并不会自动暂停，因此建议使用 `return run`。完成回调在移动结束时以原生物身份运行，继承发起时选择的目标和本回合剩余预算；目标死亡或离场时攻击仍会被重新校验。

回调可以攻击、等待或发起下一段异步移动。例如：

```mcfunction
execute store result score @s my_result run mineturn ai query event_success
execute store result score @s my_ready run mineturn ai query ready mineturn:melee
execute if score @s my_ready matches 1 run return run mineturn ai use mineturn:melee
mineturn ai wait
```

上述 scoreboard 目标须由数据包 load 函数创建。`event_success` 为 1 表示走完本次已接受的路线，0 表示执行中环境变化导致提前停止；它不保证到达最初请求点或攻击范围。启动失败不触发完成回调。退出、死亡和数据包重载只清理移动，不赋予完成回调新的行动机会。

回调未启动下一段移动时结束回合；未配置回调也自动结束。移动期间 AV 不推进，`scheduled` 和 `on_action_resolved` 等其他回调不会因此获得行动权限。

可直接选择 `mineturn:animated_melee` 模板。它保留追击和攻击分开的行为，完成回调默认等待；通过 `movement_mode` 可配置 flying 或 swimming。原有 melee、flying_melee、swimming_melee 模板不变。

## 陆地追击绕障

`move_async toward_target` 在陆地直线路径受阻时尝试通往目标附近的绕行路线，正面不可达再尝试两侧和背面，与状态机 approach 共用同一实现。最多前瞻本回合移动预算加 12 格（总长不超过 40），每个候选最多展开 512 节点，不加载新区块。仅执行本回合预算内且有支撑的路线前段，按实际路线扣距。复杂地形可能找不到绕行路线，此时沿原直线的合法部分移动，完全无法移动则返回 0。

`on_move_finished` 的成功表示执行完本次接受的路线，不表示已经接近到攻击范围；回调应再次查询 ready 或 in_attack_range。已经身处周身范围时仍必须先撤退，异步追击不会自动消耗主要行动替你撤退。内置 animated_melee 模板自动获得此绕障能力。旧 move toward_target 和显式 move_async dx dy dz 保持原行为。

## 三维追击绕障

飞行、严格游泳及水中 ground 的 move_async toward_target 使用六邻接三维搜索，先尝试目标附近的落点，也可尝试目标侧面、上方和下方。每段路径以不超过 0.1 格间距检查完整碰撞箱、流体与控制区条件；沿实际三维路线逐段累加曼哈顿距离扣距。采用与陆地追击相同的前瞻距离和每候选 512 节点上限，复杂地形可能找不到可用路线。严格 swimming 不会为绕障离水，flying 不会穿入流体，aquatic 允许水面及有支撑的岸边。找不到绕行路线时保留原直线合法前段；显式 move_async dx dy dz 和旧同步命令不自动绕障。

移动命令统一使用曼哈顿预算（陆地 |dx|+|dz|，飞行/游泳 |dx|+|dy|+|dz|）。toward_target 会据此截取位移，保持原来的攻击接近距离。
