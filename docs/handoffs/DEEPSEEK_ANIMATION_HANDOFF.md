# 交接：DeepSeek 已完成的动作演出/机位实现

更新：2026-10-08 ｜ 作者：**DeepSeek**（本对话）｜ 收件人：**Codex**

本文向 Codex 说明本对话已在工作区做出的改动，避免重复劳动或互相覆盖。
`docs/WORK_ALLOCATION.md` 中相关条目为 **A11（动画框架 P1）** 与 **A12（演出生命周期与抢占）**。

> **重要**：本文所列改动**已推送到 GitHub `main`**。Codex 在工作区看到的相同内容不是新改动。

---

## 1. 已提交的提交

| 提交 | 内容 |
| --- | --- |
| `74c21c4` | 动作演出系统 P0：`ActionAnimation` 网络包 + 服务端广播 + 客户端镜头覆盖 + 规划/视频文档 |
| `149e8e9` | 镜头改为**固定机位**（绝对世界坐标 + 锁定朝向），不再只是改聚焦点/距离 |
| `1c4c2b1` | 机位偏移按**实体碰撞箱**缩放 |
| `38bddb7` | 机位做成**数据包可配** + 默认值调大（**此提交也误带入 Codex 的水瓶边界改动，见第 5 节**） |

---

## 2. 新增文件

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/matuvent/mineturn/client/ActionAnimations.java` | 客户端演出播放器：固定机位计算、推入/保持/释放、去重、换世界清理 |
| `src/main/java/com/matuvent/mineturn/client/ActionAnimationData.java` | 机位规则的资源包加载器（`SimpleJsonResourceReloadListener`） |
| `src/main/resources/assets/mineturn/mineturn/animation_camera.json` | 机位默认值与按动作覆盖 |
| `src/main/java/com/matuvent/mineturn/test/ActionAnimationDataGameTests.java` | 验证配置文件真的能解析 + 碰撞箱缩放 |
| `docs/ACTION_ANIMATION_PLAN.md` | 设计规划 + P0 实现记录 |
| `docs/VIDEO_OUTLINE.md` | 模组介绍视频大纲 |

## 3. 修改的共享文件（Codex 请重点复核）

| 文件 | 改动 |
| --- | --- |
| `network/BattleNetwork.java` | 新增 `ActionAnimation` record + `CODEC` + `idFor(effect)` + `priorityFor(id)`；注册 `playToClient`；**协议 20 → 21 → 22**（22 为 A12 的优先级字段） |
| `network/ActionAnimationOrder.java` | **新增（A12）**：序号去重 + 优先级仲裁的纯逻辑类，方便服务端 GameTest 覆盖 |
| `battle/BattleSession.java` | 新增字段 `actionSequence`；新增私有方法 `broadcastActionAnimation(...)`；在 `runEffect(...)` 的 `finally` 中调用 |
| `client/BattleClient.java` | 接线 `receiveActionAnimation`；注册资源重载监听器；`reset()` 中调用 `ActionAnimations.clear()` |
| `mixin/BattleCameraMixin.java` | 回填当前真实机位；演出激活时直接设绝对坐标与朝向 |
| `battle/BattleExtensionGameTests.java` | 仅新增 `ActionAnimation` 编解码 + `idFor` 断言（约 7 行） |

### 设计要点（避免与 A11/A12 冲突）

- **服务端只发"何时/谁/对谁/什么动画 id"**，不发镜头参数；"怎么演"完全在客户端决定。
- 广播点是 `runEffect` 的 `finally`，覆盖所有结算入口（玩家 `use`、怪物 `ai`、函数 AI、grant、延迟结算）。
- `idFor` 的 `default` 落到 `mineturn:generic`，因此**任何 effect 都有演出**。
- 演出**不阻塞**服务端：客户端纯表现，AV/预算零改动。

---

## 4. A12 相关：当前已做 / 未做

**已做**

- 序号去重：`ActionAnimations` 按 `battleId + sequence` 丢弃过期/重复包
- 换世界清理：`checkWorld()` 对比 `ClientWorld` 引用后 `clear()`
- 战斗退出清理：`BattleClient.reset()` → `ActionAnimations.clear()`
- 镜头恢复：释放阶段 180ms 从保持位平滑回落到释放时刻的自由机位
- 机位穿墙夹取：机位落入方块时沿视线夹到遮挡前
- **并发优先级（A12，2026-10-08）**：`ActionAnimationOrder` 提供打断/替换/排队三态仲裁；
  攻击（200）打断进行中的镜头，`generic`（100）同优先级替换，进食（50）进入单个待播槽等待；
  待播槽保留最强候选，播完后无缝接上，不再先弹回自由镜头再切回
- **战斗结束后不再输出镜头覆盖**：`cameraPose()` 在 `BattleClient.active()` 为假时直接返回 null

**未做**

- 激流纯表现旋转：**建议维持暂缓**。原版激流旋转会追加实时伤害，直接启用会绕过战斗结算；
  若要做必须在纯表现路径下屏蔽其伤害与碰撞，属独立议题，不在 A12 范围内
- 行动者姿态/代理渲染、动画 JSON schema、动作显式引用、Java API、骨骼动画 → **A11，Codex 负责**

---

## 5. 需要 Codex 注意的一个提交问题

提交 `38bddb7` 的**提交信息只描述了机位配置**，但实际内容**误带入了 Codex 当时未提交的工作**：

- `mixin/PotionBoundaryMixin.java`：新增 3 个 `@Redirect`（水瓶的伤害/灭火/美西螈补水）
- `battle/BattleExtensionGameTests.java`：新增 `nativeWaterRespectsTargetsAndOwnerBoundary` 与 `burstNativeWater`
- `docs/WORK_ALLOCATION.md`、`docs/COMBAT_BOUNDARIES.md`、`docs/TASKS.md` 的更新

原因是我使用了 `git add -A`。代码本身已通过全量测试，但**提交历史里这次水瓶改动被记为机位提交的一部分**，
追溯时会误导。**后续本对话只 `git add` 自己实际改动的文件。**

---

## 6. 本地验证记录（供 Codex 复核）

| 项 | 结果 |
| --- | --- |
| `compileJava` | 通过 |
| `runGameTestServer` | **299/299 通过**（含本次新增的机位配置测试；Codex 记录为 298，差异来自各自新增的用例） |
| 客户端实机 | **未做**。镜头观感、推入/释放手感、多人同步均需客户端手测（对应 D03） |

## 7. 未解决 / 建议后续

1. **镜头观感未经实机确认**：默认值按"普通体型约 2.5 格"设定，需手测确认。
2. **优先级语义**：本对话接着实现；如 Codex 对 A11 的 schema 设计有不同意见，请先告知再定。
3. **占用文件声明**（本对话接下来会改，避免同时写）：
   - `client/ActionAnimations.java`、`network/BattleNetwork.java`、`battle/BattleSession.java`
   - 新增 `network/ActionAnimationOrder.java`、`test/ActionAnimationOrderGameTests.java`
   - 不会修改 `BattleExtensionGameTests.java`
