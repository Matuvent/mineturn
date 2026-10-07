# MineTurn 代码审查：待修复清单

审查日期：2026-10-07
审查基线：`minecraft 1.21.1` / `neoforge 21.1.251` / `Java 21`
基线验证：`gradlew.bat compileJava` 通过；`gradlew.bat runGameTestServer` → **287/287 通过**

本文汇总**需要动手改的东西**。所有条目都在当前代码上核对过行号。

> **重要前提**：本文所有内容**均未修改任何代码**。审查过程中曾实施过的改动已全部回滚，仓库当前状态 = 原始状态
> （`compileJava` 通过、287/287 通过）。见文末「附录 A：已撤回的改动」。

## 阅读须知

每条都标注了**可信度**，请按可信度决定是否动手：

| 标记 | 含义 |
| --- | --- |
| **[已证实]** | 有代码证据 + 可复现的推理链，可直接改 |
| **[需实测]** | 逻辑上成立，但需要你在游戏里跑一遍确认再改（不要照抄改法） |
| **[需确认设计]** | 取决于你想要的效果，改之前先定设计 |
| **[不成立]** | 曾被认为有问题，经复核**确认代码是对的**。列出来是为了防止重复排查 |

**并且请务必遵守**：不要依据本文改 `CombatProjectiles` 的弩快照逻辑、不要把 payload handler 包 `enqueueWork` —— 这两处已被证明是对的，理由见第 3 节。

---

## 1. `close()` 缺兜底：单次清理异常会拖垮整个服务端 tick

**可信度：[已证实]** ｜ 严重度：Critical（唯一一个能把服务端 tick 弄崩的问题）

### 位置

`src/main/java/com/matuvent/mineturn/battle/BattleManager.java:323`

```java
@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
    for (var battle : new HashSet<>(ACTIVE.values())) {
        try { battle.tick(); }
        catch (RuntimeException ex) { MineTurn.LOGGER.error("Battle failed", ex); battle.close("战斗异常，已恢复控制。"); }
    }
}
```

### 问题

`catch` 块里的 `battle.close(...)` **自身没有保护**，而 `close()` 不是不会抛：

- `BattleSession.java:480` → `drainCallbacks()` → `FunctionAi.run()`（IOException/IllegalStateException 等可逃出）
- `BattleSession.java:1252` 的 `throw new IllegalStateException("AV 状态推进超过安全上限")` 可经
  `remove → settle → next` 链上抛
- `remove()` 内部还有 `BattleNetwork.send(...)`、`BattleFields.delete(...)` 等多个可抛点

服务端 tick 抛异常会中断 `tickServer`，触发看门狗或直接踢人下线。
设计意图（"恢复控制"）是对的，**但恢复动作被放在了没有兜底的位置上**。

同一模式还有 3 处：

| 位置 | 场景 |
| --- | --- |
| `BattleManager.java:186` | `request()` 异常处理里调 `close()` |
| `BattleManager.java:193` | `submitAim()` 异常处理里调 `close()` |
| `BattleManager.java:38` | `beginReload()` 逐战斗 `close()`，一个失败会中断整个重载（并导致第 2 条的计数器泄漏） |
| `BattleManager.java:338` | `ServerStoppingEvent` 里逐战斗 `close()`，关服阶段抛出会妨碍世界保存 |

### 建议改法

**在 `BattleManager` 内加一个私有包装**，并把上述 5 处调用点全部改用它：

```java
private static void closeQuietly(BattleSession battle, String reason) {
    try { battle.close(reason); }
    catch (RuntimeException error) { MineTurn.LOGGER.error("Battle cleanup failed in {}", battle.id, error); }
}
```

**另外建议给 `BattleSession.close()` 的成员清理循环做降级**
（`BattleSession.java:474-485`），让单个成员释放失败不牵连其余成员：

```java
void close(String reason) {
    if (closed) return;
    closed = true;
    for (var field : List.copyOf(fields.values())) BattleFields.delete(this, field);
    for (var cloud : clouds) cloud.discard();
    clouds.clear();
    for (Member member : new ArrayList<>(members.values())) {
        try { remove(member.entity, reason); }
        catch (RuntimeException error) {
            MineTurn.LOGGER.error("Releasing battle member {} failed in {}", member.entity.getUUID(), id, error);
        }
    }
    scheduled.clear();
    drainCallbacks();
}
```

理由：`close()` 是**最后一道恢复路径**。它必须在任何输入下都不抛，否则"异常恢复"本身成为新的崩溃源。

---

## 2. `/reload` 计数器泄漏会**静默**永久禁用新战斗

**可信度：[已证实]** ｜ 严重度：Major（最难排查的一类故障）

### 位置

`BattleManager.java:33`、`:37-40`、`:269`、`:341`；`mixin/ServerReloadMixin.java:14-22`

```java
// BattleManager.java
private static int reloads;
public static void beginReload() { reloads++; for (...) battle.close("数据包重载，已结束战斗。"); }
public static void finishReload() { reloads = Math.max(0, reloads - 1); }

// BattleManager.java:269  —— 入场闸门
if (reloads > 0 || authorized() || event.getEntity().level().isClientSide || event.getNewDamage() <= 0 || ...) return;

// BattleManager.java:341  —— 只有关服才硬清零
ACTIVE.clear(); BattleRiding.clear(); reloads = 0; STOPPING.remove(event.getServer());
```

```java
// ServerReloadMixin.java:18-22
@Inject(method = "reloadResources", at = @At("RETURN"))
private void mineturn$finish(...) {
    var server = (MinecraftServer) (Object) this;
    callback.getReturnValue().whenComplete((value, error) -> server.execute(BattleManager::finishReload));
}
```

### 问题

`finishReload()` 只在 future 的 `whenComplete` 里、且**排回主线程后**才执行。以下情况会让它永不执行：

- future 以异常完成（`whenComplete` 仍会跑，但 `server.execute` 排队的任务在关服/卡顿时可能不执行）
- 重载途中服务器被强杀
- `beginReload()` 重入（第 1 条里 `close()` 抛出会中断循环，但 `reloads` 已经 `++` 过了）

结果：`reloads` 停在 ≥1，`damaged()` 的闸门**从此拒绝一切新战斗，且没有任何日志或提示**。
现象是"玩家打怪不再进战斗"，排查极痛苦。

### 建议改法

加一个基于游戏时间的**自愈兜底**，在 tick 里检查：

```java
private static final long[] RELOAD_DEADLINE = {0};
private static final long RELOAD_GRACE_TICKS = 6000;   // 5 分钟，可按需调整

static boolean recoverStalledReload(long gameTime) {
    if (reloads <= 0) { RELOAD_DEADLINE[0] = 0; return false; }
    if (RELOAD_DEADLINE[0] == 0) { RELOAD_DEADLINE[0] = gameTime + RELOAD_GRACE_TICKS; return false; }
    if (gameTime <= RELOAD_DEADLINE[0]) return false;
    MineTurn.LOGGER.error("Data pack reload never reported completion; re-enabling combat after {} ticks", RELOAD_GRACE_TICKS);
    reloads = 0; RELOAD_DEADLINE[0] = 0;
    return true;
}
```

在 `tick()` 开头调用：

```java
recoverStalledReload(event.getServer().overworld().getGameTime());
```

`beginReload()` 里 `reloads++` 之后把 `RELOAD_DEADLINE[0] = 0` 重置，让下一次 tick 重新起算窗口。

---

## 3. 已经查清、**不要**再改的地方

**可信度：[不成立]** ｜ 这一节是为了防止重复踩坑，请勿依据"看起来可疑"去改。

### 3.1 `CombatProjectiles.payCrossbowDurability` 的 `live` / `snapshot` 双参数（**不要改**）

曾被认为："只改 snapshot、不清 live 弩 → 已装填弩可重复发射"。

**该判断错误。** 证据链：

```java
// BattleSession.java:864  —— 付款在清空之后、耐久结算之前
if (chosenAmmo.isEmpty()) spendAmmo(player, action, stack);       // stack = live 快捷栏弩
...
// BattleSession.java:872
payCrossbowDurability(player, stack, shot.weapon, shot.ammunition); // shot.weapon 才是 snapshot
```

```java
// BattleSession.java:994-996
private void spendAmmo(LivingEntity source, CombatData.Action action, ItemStack weapon) {
    if (LoadedCrossbow.consume(weapon, action)) return;
// LoadedCrossbow.java:36
weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
```

live 弩在 `:864` 已被清空。而 `CombatProjectiles.java:31` 改 snapshot 是**必要的**——
它是 `targets()` 判断箭道数的唯一来源：

```java
// CombatProjectiles.java:66-68
var loaded = context.item().getOrDefault(DataComponents.CHARGED_PROJECTILES, EMPTY);
int count = loaded.isEmpty() ? EnchantmentHelper.processProjectileCount(...) : loaded.getItems().size();
if (count > 1) { /* +10° 与 -10° 两条分叉 → 三连发 */ }
```

**如果按错误判断去"修复"（改成清空 live / 去掉 snapshot.set），Multishot 三连发会退化成单发。**

### 3.2 NeoForge payload handler 的线程上下文（**不要加 `enqueueWork`**）

曾被认为："handler 跑在网络线程、缺 `context.enqueueWork` → 并发竞态/崩溃"。

**该判断错误**，有 NeoForge 源码级证据：

```java
// PayloadRegistrar.java:29
private HandlerThread thread = HandlerThread.MAIN;
// PayloadRegistrar.java:124
* The initial handling thread is {@link HandlerThread#MAIN}.
// PayloadRegistrar.java:166-167
if (this.thread == HandlerThread.MAIN) { handler = new MainThreadPayloadHandler<>(handler); }
```

```java
// MainThreadPayloadHandler.java:13-17
public record MainThreadPayloadHandler<T extends CustomPacketPayload>(IPayloadHandler<T> handler) implements IPayloadHandler<T> {
    @Override public void handle(T payload, IPayloadContext context) {
        context.enqueueWork(() -> this.handler().handle(payload, context));
    }
}
```

`BattleNetwork.java:27` 用的是 `event.registrar("19")` 这个**默认重载**，因此所有 handler
都已被 `MainThreadPayloadHandler` 包好并自动 `enqueueWork`。`BattleManager.request` 本来就在主线程。

> 路径参考：`.gradle/caches/neoformruntime/.../neoforge-21.1.251-sources.jar`

### 3.3 其它已复核确认正常的点

| 位置 | 曾怀疑 | 结论 |
| --- | --- | --- |
| `invulnerableTime` 归零（全代码 20 处） | 漏重置导致攻击被原版 20 tick 无敌吞掉 | 逐条核对触发顺序，**无遗漏**。`BattleStatus.java:40` 在状态 tick 开头统一归零，覆盖同 tick 内的着火/冰冻/饥饿/窒息 |
| `Timeline` AV 行动条 | 死循环 / 时间不推进 | `next()` 把选中者重置为 `LAP`、`advance()` 有上界断言、`forecast()` 在副本上运行 → **实现正确** |
| `advanceEvents`（`BattleSession.java:1249-1286`） | 各种组合下死循环 | 推演 `next > turnAt`、`next == turnAt`、clock 为空、`nextDelay == 0` 等情形，配合 `:1284` 出口与 `:1252` 上限 → **未发现死循环** |
| `CombatData` 重载原子性 | 重载后用到旧/新混合定义 | `PREPARED` + `FunctionLibraryMixin` 在 `<init>`/`replaceLibrary` 的 RETURN 注入 → **JSON 与函数库同生共死**，失败保留旧规则 |
| `mixins.json` 注册完整性 | 漏注册 / 悬空注册 | 声明 40 个（37 通用 + 3 客户端）与目录 40 个 `.java` **一一对应**；`required=true` + `defaultRequire=1` |
| 数据包 `effect` 覆盖 | 引用了未注册的 effect | 44 个已用 effect **全部有注册实现** |
| 集合并发修改 | `ConcurrentModificationException` | `members` 的所有修改点都走 `new ArrayList<>()` / `List.copyOf()` 快照 |
| 网络输入校验 | 客户端可注入崩溃/刷物品 | revision 栅栏 + operation 白名单 + 槽位/坐标有界 + `BattleManager.java:183-187` 捕获异常 → **未找到可利用面** |

---

## 4. 玩家操作 30 秒期限的实际行为与文档不符

**可信度：[需确认设计]** ｜ 严重度：Minor

### 位置

`BattleSession.java:357-360`、`:317`；`TurnBudget.java:37`

```java
// BattleSession.java:357-358
if (lastActivity != budget.activity()) { idleTicks = 0; lastActivity = budget.activity(); }
if (actor instanceof ServerPlayer && motion == null && shot == null && ++idleTicks >= 600) {
    message("操作超时，自动结束回合。"); next(); syncAll(); return;
}
```

```java
// TurnBudget.java:34-38  —— move() 也会 activity++
public void move(double distance) { ...; remaining = Math.max(0, remaining - distance); activity++; }
```

```java
// BattleSession.java:317  —— 倒计时显示
actor instanceof ServerPlayer ? (lastActivity != budget.activity() ? 30 : (600 - idleTicks + 19) / 20) : -1
```

### 问题

`budget.activity()` 在**移动**时也会自增，因此玩家只要持续小幅移动，`idleTicks` 每 tick 被清零，
回合**永远不会超时**。而 UI 发出的倒计时会反复跳回 30 秒。

`docs/PROTOTYPE.md:38` 写的是"玩家操作有约 30 秒期限"。

### 需要你决定

- **想让移动也重置期限** → 保持代码不变，改文档说明。
- **想要真正的 30 秒墙钟** → 改用 `turnTicks` 或 AV 时间计时，不再依赖 `activity()`。

两种都合理，取决于你想要的手感。**改之前请先定这个。**

---

## 5. AI 回调队列溢出时静默整队丢弃

**可信度：[已证实]** ｜ 严重度：Minor

### 位置

`BattleSession.java:1227-1235`

```java
void drainCallbacks() {
    if (drainingCallbacks) return;
    drainingCallbacks = true;
    try {
        int count = 0;
        while (!callbacks.isEmpty() && count++ < 64) FunctionAi.run(this, callbacks.removeFirst());
        if (!callbacks.isEmpty()) { callbacks.clear(); MineTurn.LOGGER.error("AI callback limit reached for battle {}", id); }
    } finally { drainingCallbacks = false; }
}
```

### 问题

一次 `drainCallbacks` 最多执行 64 个回调，**超出的全部清空**，且只有日志、玩家侧无任何提示。

对比 `scheduled` 队列满时的处理（`BattleSession.java:1278`）是有 `message(...)` 的：

```java
if (++count > 128) { scheduled.clear(); message("AV 任务触发过多，已清理队列。"); break; }
```

### 建议改法

`callbacks.clear()` 之后补一条 `message(...)`，让玩家/服主知道 AI 行为掉线了。
另外可考虑提为数据包可配的上限，而不是硬编码 64。

---

## 6. `hasAiActionBudget()` 与 `ai()` 的 null 检查不对称

**可信度：[已证实]** ｜ 严重度：Minor（当前不可达，属防御性）

### 位置

`BattleSession.java:389-399`（有检查）与 `:1303-1308`（无检查）

```java
// :394-395  —— 有 null 检查
var state = brain.states().get(owner.state);
return state != null && state.choices().stream().anyMatch(...);
```

```java
// :1303-1308  —— 无 null 检查
var state = brain.states().get(member.state);
for (var transition : state.transitions()) { ... }      // state 为 null → NPE
state = brain.states().get(member.state);
if (state.behavior().equals("approach")) { ... }        // 同样 NPE
```

### 问题

当前**不可达**：`member.state` 只由数据包校验过的 `initial_state` 与 `transition.to` 赋值
（`CombatData.java:202-204` 校验目标状态存在），且函数 AI 的 `states` 是 `Map.of()` 但会在
`:1295` 提前 return。

但这是靠"数据包校验很严"**间接**保证的，不是本地保证。一旦允许函数 AI 也走状态机、
或放宽 `initial_state` 校验，这里立刻 NPE。

### 建议改法

`ai()` 里取到 `state` 后加与 `:394` 一致的 null 守卫：

```java
var state = brain.states().get(member.state);
if (state == null) return;
```

---

## 7. 远程判定的命中窗口由**客户端发包时刻**决定

**可信度：[需实测] / 设计问题** ｜ 严重度：取决于你是否做防作弊多人服

### 位置

`battle/RangedShot.java:29-35`；`BattleManager.java:189-194`；`BattleSession.java:945-948`

```java
// RangedShot.java
startNanos = now + 1_000_000_000L;
latencyCompensationNanos = Math.clamp(shooter.connection.latency(), 0, 250) * 1_000_000L;
double elapsedMs(long now) { return (now - startNanos) / 1_000_000.0; }
boolean hit(long now) { double progress = elapsedMs(now) / action.ranged().durationMs(); return progress >= low && progress <= high; }
```

```java
// BattleManager.java:192
battle.submitShot(player, request.token(), System.nanoTime() - (battle.shot == null ? 0 : battle.shot.latencyCompensationNanos));
```

### 问题

服务端**没有任何随机源**，`low`/`high` 完全由 `action.ranged().width(距离)` 推导，是确定的。
命中与否**只取决于服务端收到 `AimSubmit` 那一刻的 `System.nanoTime()`**，而该时刻由客户端选择。
恶意客户端只需在正确纳秒发包即可必中。

`RangedShot.java:9` 的注释防住了"客户端直接报告命中/光标位置"，但没防住"客户端挑时刻"。
`docs/PROTOTYPE.md:147` 也承认"不适用于防作弊多人正式服"。

**注意**：`expireShot`（`BattleSession.java:944`）在 `durationMs + 250ms` 后强制判失败，
所以最坏情况是"总能命中"，而不是"无限重试"。

### 建议改法（不改变玩法）

把"服务端宣布答案、客户端看表"改成"**服务端出题、客户端作答**"：

1. 建 `RangedShot` 时，用 `player.getRandom()` 在 `durationMs` 内**随机**选一个窗口出现时刻，存进 shot；
2. 把"窗口当前位置"下发给客户端（现在的 `Aim` 包已在发 `elapsedMs/low/high`，改语义即可）；
3. 客户端在窗口经过时按键，服务端只校验"你按的时刻落在窗口内"。

这样从"猜表"变成"反应速度"，玩法不变，且服务端掌握随机源。

---

## 8. 客户端侧**零自动化覆盖**（工程改进，建议优先）

**可信度：[已证实]** ｜ 严重度：中（决定后续 bug 的发现成本）

### 现状

`runGameTestServer` 是专用服务端。以下客户端代码**从未被执行过**：

`client/BattleClient.java`、`client/BattleScreen.java`、`client/BattleBulletRenderer.java`、
`client/BattleDeviceRenderer.java`、`client/ProjectileAnimations.java`、`network/StatusLocks.java`

服务端侧只通过 `mixins.json` 的 `client` 数组（`BattleCameraMixin`、`BattleClientMovementMixin`、
`BattleRendererMixin`）间接覆盖，且仅限"服务端混入是否误伤客户端"这一个方向。

### 具体后果

以下三点只能实机验证，我无法用测试证明：

| 位置 | 可疑点 |
| --- | --- |
| `BattleSession.java:377-381` + `BattleClient.java:158` | 服务端每 tick 用 `player.connection.teleport()` 把偏离 anchor 的玩家拉回，客户端同时 `setDeltaMovement(ZERO)`。两个"强制归位"叠加在真实网络延迟下的稳定性 |
| `BattleClient.java:160-168` | 过场移动用服务端逐 tick 的 `motionTarget` 直接 `positionLocal()`，**无插值**；而 `BattleSession.tickMotion():570` 每 tick 最多推进 0.24 格 → 动画质量依赖 tick 频率 |
| `BattleClient.java:180` | `hud()` 在 `BattleScreen` 打开期间 `event.setCanceled(true)` 关掉整个 GUI 渲染。结合 `PROTOTYPE.md:19` 的"T 打开聊天"，聊天可能被战斗界面盖住 |

### 建议改法

给 `gradlew runClient` 加一组客户端 GameTest（`neoforge.enabledGameTestNamespaces` 在 client run
里已配置）。最小覆盖"进入战斗 → 收到 State → 打开 BattleScreen → 发请求 → 收到 closed 包后关闭界面"
这条状态链。**这比再加 100 个服务端用例更有价值。**

---

## 9. 测试有效性问题

**可信度：[已证实]** ｜ 严重度：中（测试会骗人）

### 9.1 负例不校验异常消息

`battle/BattleExtensionGameTests.java:2337`、`:2340`、`:1971`

```java
try { CombatData.parse(files); throw new AssertionError("Invalid mobility accepted"); }
catch(IllegalArgumentException expected) {}
```

生产代码目前是**对的**（已核对 `CombatData.java:162` 的 `movement_mode` 白名单确实不含 `teleport`；
`:119` 的 `validateDefinition` 有效）。但这类写法意味着：若将来该校验被删，而解析在更早处因别的原因
抛同类型异常，**测试依然通过**。

建议：把 `expected` 用起来，断言消息片段。

### 9.2 弩耐久测试绕过了真实接线

`BattleExtensionGameTests.java:3486`

```java
for (int shot = 0; shot < 40; shot++)
    CombatProjectiles.payCrossbowDurability(player, bow, bow.copy(), rockets(1, 1));
```

它用手搓的 `bow.copy()` 当 snapshot，**直接调内部函数**，绕过了 `BattleSession.use()` 的真实接线。
它验证耐久算法有效（`:3487` 的断言是有效的），但如果将来有人改坏 `use()` 传给它的参数，
**这条测试不会报警**。

> 我在实际审查中观察到：正是这个缺口，让一个审查者误判了 `payCrossbowDurability`（见 3.1）。

建议：补一条走完整 `BattleSession.use()` 的端到端弩用例，显式断言
"live 弩被清空 **且** `shot.weapon` 快照保留了本次实际发射的箭道数"。
（注：`crossbowBreakStopsRemainingLanes`（`:3434-3454`，尤其 `:3448`）其实已覆盖了大半，可以直接扩展它。）

### 9.3 测试隔离：不要在 GameTest 里调 `BattleManager.beginReload()`

`beginReload()` 会 `close()` 掉**当前所有战斗**（`BattleManager.java:38`）。GameTest 是并行分批运行的，
在测试里调用它会拆掉同时段其它测试的战斗，产生看起来毫无关联的失败
（例如 `totemHandsPreserveBattleAndAvEffects` 报 "Totem revival removed participant"）。

若需要模拟"重载完成信号丢失"，请直接注入计数器状态，不要走 `beginReload()`。

---

## 10. 小的一致性 / 健壮性项

**可信度：[已证实]** ｜ 严重度：Minor

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| 10.1 | `battle/BattleStatus.java:32` 与 `:16` | `statusTicks` 是独立自增计数器，而 `effectTick()` 的 fallback 用 `entity.tickCount`。两个时钟不是同一个 | 统一到一个时钟，或在注释里写清两者语义差异 |
| 10.2 | `api/CombatProjectiles.java:23`、`:68` | `EnchantmentHelper.processProjectileCount(...)` 对**同一次**发射在耐久结算和 `targets()` 里各调一次。若该方法有随机性，两处会不一致 | 计一次、缓存结果并传递 |
| 10.3 | `network/BattleNetwork.java:138-143` + `client/BattleClient.java:69-72` | `Motion` 包**不带 revision**，客户端不做版本检查，而 `State` 用 revision 丢弃旧包（`BattleClient.java:107`）。网络乱序时旧的 Motion 可能覆盖新的 `moving`/`motionTarget` | 给 Motion 加 revision，客户端只接受 `revision >= 当前` |
| 10.4 | `battle/BattleRiding.java:14` | `RIDERS` 是静态 `ConcurrentHashMap`，只有 `stopped()`（`BattleManager.java:341`）会 `clear()`。运行期异常路径下条目可能滞留 | 加常规清理点，或改为弱引用 |
| 10.5 | `api/GuardianBeam.java:37-38` | `target.hurt(indirectMagic, 1)` 之后又 `guardian.doHurtTarget(target)`。两者都会造成伤害 | **不要照此改**！你的实测确认有伤害且数值合理，说明 `doHurtTarget` 就是原版光束的近战分量。若要调数值请单独决定 |
| 10.6 | `data/` 目录 | **未版本控制**（无 `.git`），交付物与开发产物混在项目根：`outputs/stream-build/build.mjs`（依赖 `@oai/artifact-tool`）、`回合进行时流派构筑-原始副本.xlsx`、`build/examples/corrupted-*.java` | 建议 `git init`，并把设计文档归到 `docs/design/`、工具脚本单独隔离 |

---

## 11. 文档与代码不一致

**可信度：[已证实]** ｜ 严重度：Minor（但**持续迭代的项目里这点很贵**）

`docs/PROTOTYPE.md` 与当前代码脱节，会让后续接手的人（和下一个 AI 会话）基于错误前提做决策：

| 位置 | 文档写法 | 实际 |
| --- | --- | --- |
| `PROTOTYPE.md:133` | "远程动作与判定小游戏尚未实现" | `RangedShot` + `RANGED_COMBAT.md` 已完整实现 |
| `PROTOTYPE.md:143` | "没有…远程判定" | 同上 |
| `PROTOTYPE.md:154` | "28 项 GameTest" | **287 项**（另有 `BattleExtensionGameTests` 未在本文提及） |
| `PROTOTYPE.md:78` | "当前不提供标签批量匹配" | `MobDefinitions.match()` 已支持 `entity_tags` |
| `PROTOTYPE.md:38` | "玩家操作有约 30 秒期限" | 见第 4 节：移动会重置期限 |
| `PROTOTYPE.md:100` | "跨战斗冷却持久化尚未实现" | 需确认是否仍准确 |

建议：把 `PROTOTYPE.md` 里"边界/未实现"一节整体过一遍，或改为指向各专项文档。

---

## 附录 A：已撤回的改动

审查过程中我曾实施过 4 处改动，**已全部回滚**，仓库当前 = 原始状态。记录在此备查：

| 文件 | 曾做的改动 | 状态 |
| --- | --- | --- |
| `battle/BattleManager.java` | 加 `closeQuietly()` + 5 处调用点；加 `reloads` 自愈兜底与测试辅助方法 | **已回滚**（382 行，与原版一致） |
| `battle/BattleSession.java` | `close()` 成员清理加 per-member try/catch | **已回滚**（1408 行） |
| `api/CombatEffects.java` | 给 `mineturn:burst` 加 `canSchedule` 预检 | **已回滚**（351 行） |
| `api/GuardianBeam.java` | 改 `impact()` 的守卫条件 | **已回滚**（50 行） |
| `battle/BattleExtensionGameTests.java` | 加 4 个测试方法 | **已回滚**（4960 行） |

回滚后验证：`compileJava` 通过；`runGameTestServer` → **All 287 required tests passed**。

### 关于 `GuardianBeam` 那条的教训

我基于一个**我自己的合成测试**（打出 `scheduled=1`，即延迟任务根本没执行）就断定守卫者光束零伤害，
并改了产品代码。**你的实测确认守卫者与远古守卫者都有伤害。**

正确的做法应该是：测试装置报出"任务没执行"时，**首先怀疑测试装置本身**，并去对齐项目里
已有的 `mineturn:guardian_beam` 测试（它是通过的），而不是直接改产品逻辑。
第 10.5 条因此**明确标注为不要照改**。

---

## 附录 B：建议的动手顺序

| 顺序 | 条目 | 理由 |
| --- | --- | --- |
| 1 | 第 1 条 `close()` 兜底 | 唯一能崩服务端的问题，改动量极小，零行为风险 |
| 2 | 第 2 条 `reloads` 自愈 | 静默故障，排查成本最高 |
| 3 | 第 9 条测试有效性 + 9.3 测试隔离 | 先让测试可信，后续改动才有保障 |
| 4 | 第 8 条客户端测试 | 当前最大的验证盲区 |
| 5 | 第 4、7 条 | **需要你先定设计**，不要直接改代码 |
| 6 | 第 5、6、10、11 条 | 一致性/健壮性，可批量处理 |

**每次改动后**：`gradlew.bat compileJava` → `gradlew.bat runGameTestServer`，确认 287/287 不退化。
