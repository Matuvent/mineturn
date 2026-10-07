# 动作动画与镜头演出系统 —— 规划（v0，未实现）

> 本文是**设计规划**，不含代码。目标是把"行动动画 + 演出镜头"作为一个可扩展系统引入 MineTurn，
> 覆盖玩家与生物的攻击、进食、近战、远程，以及数据包注册的动作。
>
> **实现状态**：P0（镜头演出）已实现 —— 见文末「实现记录」。

---

## 0. 已确认的决策（2026-10-07）

1. **暂不引入 GeckoLib**：当前版本不做骨骼级动画；后续做整合包时再评估。
2. **精度原则：每个动作都有动画，不追求精度**。用近似姿态 + 统一镜头 + 粒子即可，不追求逐骨骼。
3. **本地行动者的镜头也被强制拉过去**：不做"行动者豁免"，所有玩家（含行动者本人）共享同一演出镜头。

---

## 1. 目标与范围

### 1.1 目标

1. 任一参与者执行行动时，向**本场战斗的所有玩家**广播一次"演出"。
2. 演出由两部分组成：
   - **镜头演出（Camera）**：强制/引导所有观战玩家的镜头（本地行动者可选恢复）。
   - **行动者动画（Actor）**：行动者播放一个与动作匹配的姿态/关键帧动画 + 粒子/物品表现。
3. 由**数据包**（以及可选 Java API）定义动作 → 演出的映射。
4. 演出是**纯表现层**：不阻塞、不回滚、不改变服务端权威逻辑。

### 1.2 范围边界（v1 明确不做）

| 不做 | 原因 |
| --- | --- |
| 骨骼级自定义模型动画（自定义怪物挥舞大剑的肢体） | 原版 LivingEntity 模型没有可编程骨骼；需要 GeckoLib 或自写渲染器，风险/依赖大 |
| 服务端暂停等待动画播完 | 破坏确定性调度，回合制逻辑与表现必须解耦 |
| 客户端反向驱动服务端 | 与服务端权威冲突 |

> 用户示例中"焰魔双手举巨剑 → 斩下"属于**骨骼级**动画，v1 只能做到：
> "镜头绕半圈 + 行动者整体旋转/手臂摆动关键帧 + 剑实体举起 + 粒子 + 命中震屏"的**近似**，
> 而非逐骨骼挥舞。真实逐骨骼要在后续版本引入 GeckoLib 或自定义几何体。这一点必须在规划里明确，否则会低估工作量。

---

## 2. 现状盘点（本系统要复用的基建）

| 现有组件 | 位置 | 作用 |
| --- | --- | --- |
| 轨道镜头 | `BattleCameraMixin` + `BattleClient.yaw/pitch/distance/focus` | 镜头每帧由这 4 个客户端状态驱动 |
| 纯表现代理实体 | `ProjectileAnimations` | 服务端发包 → 客户端渲染**不加入世界**的实体模型，有去重/上限/换世界清理 |
| 表现层 | `CombatVisuals.flight()` | 纯视觉飞行，不与伤害结算耦合 |
| 行动结算点 | `BattleSession.execute()` / `runEffect()` | 动作提交后执行效果的位置 |
| 朝向 | `BattleFacing.along()` | 让实体/坐骑朝向目标 |

**结论**：镜头动画只需在客户端改写 `yaw/pitch/distance/focus`；行动者动画可复刻 `ProjectileAnimations`
的代理实体模式。服务端只负责"什么时候演什么"，不参与"怎么演"。

---

## 3. 核心数据模型

### 3.1 演出（ActionPerformance）

服务端在动作**结算时刻**构造并广播的一个事件：

```
battleId, sequence(单调), animationId, actorId, targetId, impactPoint(可选), priority
```

- `animationId`：客户端据此查表（数据包资源或 Java 注册）
- `sequence`：客户端去重 + 丢弃过期（复用已有的 `MotionOrder` 思路）
- `priority`：多个演出并发时的抢占/排队依据
- `impactPoint`：命中点（远程/地面落点），镜头和粒子要用

### 3.2 演出脚本（客户端资源）

放在客户端资源包 `assets/<ns>/mineturn/animations/<id>.json`，由**所有客户端**加载
（服务器只发 `animationId`，不传脚本本体——这样数据包作者只需维护一份定义）。

脚本是**时间线**，包含两条通道：

```jsonc
{
  "duration_ms": 1600,
  "camera": [           // 镜头关键帧序列
    {"at": 0.0, "yaw": "auto", "pitch": 30, "distance": 8, "focus": "actor_front"},
    {"at": 0.6, "yaw": "auto+180", "pitch": 25, "distance": 5, "focus": "actor"},
    {"at": 1.0, "yaw": "auto", "pitch": 20, "distance": 6, "focus": "actor"}
  ],
  "actor": {            // 行动者姿态（v1 只支持内置姿态名）
    "pose": "melee_raise_swing",        // 或 eat / bow_draw / melee_lunge
    "item_visible": true,
    "impact_particles": "mineturn:slash"
  }
}
```

- `at` 是 0–1 归一化时间
- `focus` / `yaw` 支持 `actor`、`actor_front`、`target`、`impact` 与 `auto` 这类**运行时解析的锚点**
- 关键帧间用缓动（ease-in-out）插值

### 3.3 内置姿态（v1 白名单，客户端实现）

| 姿态名 | 适用 |
| --- | --- |
| `eat` | 玩家/生物把食物举到面前 + 粒子 |
| `melee_lunge` | 近战前倾 + 手臂挥 |
| `melee_raise_swing` | 举高 → 斩下（用原版手臂摆动近似） |
| `bow_draw` | 拉弓 + 手臂抬 |
| `cast_hand` | 施法抬手 |
| `stagger` | 受击后仰 |

自定义骨骼姿态**不在 v1**；但镜头、粒子、朝向、物品显示全部可配。

---

## 4. 触发点与服务端职责

### 4.1 触发时机

在 `BattleSession` 动作结算后广播一次，天然覆盖所有入口（玩家 `use()`、怪物 `ai()`、
函数 AI `execute()`、grant 施放），因为最终都经过 `runEffect`。

**关键设计**：广播发生在 `runEffect` 的 `finally`（效果已结算）之后，且**不等待任何客户端确认**。
服务端状态机照常前进。

### 4.2 服务端要做的

1. 把动作映射到 `animationId`（优先级：动作 parameters 显式指定 > 内置效果默认 > 无演出）
2. 计算 `impactPoint`（命中点/落点/目标中心）
3. 广播给**本场战斗所有玩家**（不发给场外，参考 `CombatVisuals.flight` 的距离过滤）
4. 战斗关闭 / 玩家离场时，让客户端清理未播完的演出

---

## 5. 客户端职责

新增 `client/ActionAnimations`（对标 `ProjectileAnimations`）：

- `receive(packet)`：校验 + 去重（按 battleId + sequence）+ 上限（如 8 个）入队
- `tick()`：按 `System.nanoTime()` 推进当前演出
- 向 `BattleCameraMixin` 提供"当前镜头覆盖"：
  - 有演出 → 用脚本插值覆盖 `yaw/pitch/distance/focus`
  - 无演出 → 回落到现有自由镜头
- 演出结束 → **平滑**回到自由镜头（避免跳变）
- 世界切换 / 战斗关闭 → 清空队列

行动者动画走代理实体：渲染一份 actor 的模型副本（不加入世界、不 tick），
应用脚本里的朝向/手臂/物品位移，配合粒子。**不动真实实体**，避免干扰服务端状态。

---

## 6. 数据包 / API 扩展

### 6.1 数据包（作者友好）

动作 JSON 里加一个可选字段即可引用演出：

```jsonc
{
  "effect": "mineturn:damage",
  "animation": "mineturn:heavy_slash"   // 对应 assets/mineturn/mineturn/animations/heavy_slash.json
}
```

未指定 `animation` 的动作走效果默认映射（`mineturn:damage` → 近战动画，`mineturn:projectile` → 拉弓…）。

### 6.2 Java API（供其它模组）

```java
public interface ActionAnimation {
    record Frame(float yaw, float pitch, float distance, Vec3 focus) {}
    record Context(int actorId, int targetId, Vec3 impactPoint, ClientLevel level) {}
    List<Frame> camera(Context ctx);   // 返回关键帧，客户端插值
    void renderActor(Context ctx, float progress, PoseStack pose, MultiBufferSource buffers);
}
```

模组可在客户端 `Dist.CLIENT` 事件里 `ActionAnimations.register(id, impl)`。
这层保证"数据包注册的动作"也能有演出——即使动作本体是别的模组定义的。

---

## 7. 网络协议

- 新增 `playToClient` payload：`ActionAnimation(battleId, sequence, animationId, actorId, targetId, impactPoint, priority)`
- 协议版本 **21**（当前 20）
- 只发给本场战斗玩家；带 `sequence` 去重，与现有 `MotionOrder` 一致
- `impactPoint` 用 `Vec3`，需有限值校验（复用现有 vector 编解码）

---

## 8. 并发 / 抢占 / 清理

| 场景 | 策略 |
| --- | --- |
| 一个演出没播完又来一个 | `priority` 高者打断，同级后到者覆盖/排队 |
| 本地玩家自己行动 | 镜头仍可被脚本短暂覆盖，播完回到"自由镜头 + 保留原朝向" |
| 战斗关闭 / 玩家离场 / 换维度 | 清空该 battle 的演出队列（对标 `StatusLocks` / `MotionOrder.reset`） |
| 客户端掉线重连 | 状态快照不带演出队列，重连后不再补播（可接受） |
| 镜头穿墙 | 复用现有"带墙体碰撞"的轨道约束；脚本 focus 若穿墙，按最近合法点修正 |

---

## 9. 与现有系统的关系

| 现有 | 关系 |
| --- | --- |
| `ProjectileVisual` | 飞行表现继续独立；命中后的"斩击粒子/震屏"归新系统 |
| `BattleCameraMixin` | 从"直接读 `BattleClient`"改为"先问 `ActionAnimations` 是否有覆盖" |
| `CombatVisuals` | 保留；镜头/行动者演出是它的上一层 |
| AV 时钟 / TurnBudget | **零改动**——演出是墙钟时间，不参与 AV |

---

## 10. 实施阶段（建议顺序）

| 阶段 | 内容 | 风险 |
| --- | --- | --- |
| **P0：镜头演出** | 网络包 + 客户端时间线 + 镜头覆盖 + 内置 `eat`/`melee_lunge` 两个镜头脚本 | 低，纯客户端 |
| **P1：行动者近似动画** | 代理实体 + 朝向/手臂/物品位移 + 粒子 | 中（渲染对齐） |
| **P2：数据包 + API** | 动作 JSON 引用、内置默认映射、Java 注册接口 | 低 |
| **P3：打磨** | 平滑回落、抢占、墙体修正、多人联机实测 | 中 |
| **P4（可选）** | GeckoLib / 自定义几何体做骨骼级挥舞 | 高，需单独立项 |

> **建议先做 P0 + 内置吃食物演示**：这是用户需求里成本最低、观感提升最直接的一块，
> 也能立刻验证"广播给所有玩家 + 本地行动者恢复"两个核心机制。

---

## 12. P0 落地规格（供实现对照）

### 12.1 网络包 `ActionAnimation`

```java
record ActionAnimation(
    UUID battleId, long sequence, ResourceLocation animationId,
    int actorId, int targetId, Vec3 impactPoint
)
```

- `sequence` 每场战斗单调递增，客户端去重 + 丢弃过期（复用 `MotionOrder` 思路）
- `impactPoint` 命中点/落点/目标中心，`Vec3` 需有限值校验
- 只发给本场战斗玩家（`BattleSession` 现有 `players()`）

### 12.2 服务端触发点

`BattleSession.runEffect(...)` 的 `finally` 之后、`activeEffect` 已还原的位置，
调用一次 `broadcastActionAnimation(source, target, action, impact)`：

- 依据动作 effect/ID 决定 `animationId`（未配置时用内置默认映射，无映射则 `mineturn:generic`）
- 所有动作**必然**广播一个演出，满足"每个动作都有动画"

### 12.3 客户端 `ActionAnimations`（对标 `ProjectileAnimations`）

- `receive(packet)`：校验 + 按 `battleId + sequence` 去重 + 上限 8 入队
- `tick()`：按 `System.nanoTime()` 推进当前演出
- `cameraOverride(now)`：返回 `null` 表示无演出，否则返回当前关键帧插值后的镜头姿态
  （覆盖 `BattleClient.yaw/pitch/distance/focus`）
- 演出结束平滑回到自由镜头；世界切换/战斗关闭清空

### 12.4 `BattleCameraMixin` 改造

```java
var override = ActionAnimations.cameraOverride(now);
if (override != null) { 用 override 的 yaw/pitch/distance/focus }
else { 现有 BattleClient.yaw/pitch/distance/focus }
```

### 12.5 行动者近似（P1，非 P0）

- 复用 `ProjectileAnimations` 的"代理实体"模式，渲染 actor 模型副本（不加入世界、不 tick）
- 用脚本 `actor.pose` 映射到内置姿态：`eat` / `melee_lunge` / `melee_raise_swing` / `bow_draw` / `cast_hand`
- 姿态 = 朝向 + 手臂/物品位移 + 粒子，不逐骨骼

---

## 13. P0 验收标准

1. 玩家攻击怪物 → 所有玩家镜头短暂对准行动者 + 目标，然后回到自由镜头
2. 玩家吃食物 → 镜头对准该玩家正面，食物粒子出现
3. 生物近战 → 同一战斗的玩家镜头跟随
4. 连续两个动作 → 第二个演出按优先级抢占/排队，不叠加错乱
5. 战斗关闭 / 玩家离场 → 演出队列清空，镜头恢复

---

## 14. 实现记录（P0，2026-10-07）

已完成 P0（仅镜头演出，无行动者姿态、无粒子、无优先级抢占）：

- `network/BattleNetwork.java`：新增 `ActionAnimation` payload（battle/sequence/animationId/actorId/targetId/impact/hasImpact）+ `idFor(effect)` 映射；协议 **20 → 21**
- `battle/BattleSession.java`：新增 `actionSequence` 与 `broadcastActionAnimation`，在 `runEffect` 结算后向本场所有玩家广播
- `client/ActionAnimations.java`：单演出播放器，`cameraPose()` 返回镜头覆盖；按 battleId 去重、换世界清理、结束 150ms 平滑回落
- `mixin/BattleCameraMixin.java`：优先用 `ActionAnimations.cameraPose()`，否则回落自由镜头
- `client/BattleClient.java`：接线 `receiveActionAnimation` + 战斗退出时 `clear()`
- 测试：`ActionAnimation` 编解码往返 + `idFor` 映射断言；全量 **297/297 通过**

**尚未实现（P1+）**：行动者姿态/粒子、数据包 JSON 引用、Java API、优先级抢占、震屏慢动作。
"每个动作都有动画"当前 = 每个动作都有一段镜头演出（`generic` 兜底），暂无姿态动画。


---

## 11. 风险与未决问题

1. **客户端不同步**：所有客户端必须在同一世界时间看到同一演出 → 用 `sequence` 去重 + 固定墙钟，不依赖 tick。
2. **渲染代理实体的朝向/姿态**：原版 `LivingEntityRenderer` 的姿态主要靠 `getAnimTime`/`walkAnimation`，
   关键帧姿态能做的有限——这是 v1 最大的诚实限制，见 1.2。
3. **镜头脚本的锚点语义**（`actor_front` 相对朝向、`impact` 命中点）需要在 P0 就定清楚，
   否则后面所有脚本都要返工。
4. **数据包资源的客户端加载**：动画脚本是客户端资源，`/reload` 只能热更新服务端数据，
   客户端资源仍需重启客户端——和原版一样，需在文档里说明。
5. **是否需要"震屏/慢动作"**：属于额外观感，建议作为脚本可选字段，不作为 v1 核心。
