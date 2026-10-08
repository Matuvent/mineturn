# 交接：客户端改动的验证门禁

更新：2026-10-08 ｜ 作者：**DeepSeek** ｜ 收件人：**Codex**

## 一句话

> **改了 `src/main/java/com/matuvent/mineturn/client/` 下任何文件，必须真实启动一次客户端才算验证完成。
> `runGameTestServer` 通过不构成对客户端改动的任何验证。**

## 为什么

`gradlew runGameTestServer` 是**专用服务端**，**永远不会构造 `client/BattleClient`**。
所以 `BattleClient` 构造函数里的每一行注册在服务器测试里都是零覆盖，且编译期毫无提示：

| 位置 | 内容 | 服务端测试能否覆盖 |
| --- | --- | --- |
| `BattleClient.java:51-52` | `EntityRenderersEvent.RegisterRenderers`（mod bus） | ❌ |
| `BattleClient.java:68` | `RegisterClientReloadListenersEvent`（mod bus） | ❌ |
| `BattleClient.java:70-71,88-91` | `ClientTickEvent.Post`、渲染/输入/HUD（游戏总线） | ❌ |
| `ActionAnimations` / `ActionAnimationData` / `BattleCameraMixin` | 演出与机位、配置加载 | ❌ |

## 实际事故（2026-10-08）

「机位做成数据包可配」那次提交（`38bddb7`）把 **mod bus 事件注册到了游戏总线**：

```java
// 错误
NeoForge.EVENT_BUS.addListener((RegisterClientReloadListenersEvent event) -> ...);
```

后果：编译通过、**服务器测试全绿**（当时 299/299），但**客户端一启动就崩**：

```
Failed to create mod instance. ModID: mineturn, class ...client.BattleClient
java.lang.IllegalArgumentException: IModBusEvent events are not allowed on the common NeoForge bus!
Caused by: ... Use a mod bus instead.
    at ...client.BattleClient.<init>(BattleClient.java:66)
```

更麻烦的是它会引发**满屏连锁错误**，把根因淹没：

```
Cowardly refusing to send event ... to a SoundEngineLoadEvent / TextureAtlasStitchedEvent / ... to a broken mod state
```

看起来像渲染或资源问题，实际原因只有一个：`BattleClient.java:66` 的总线用错了。

**修复**：`86aba1e`，改用 mod bus（`bus.addListener`），与上方两行渲染器注册一致。
**用户实测确认**：客户端正常启动，且 `MineTurn animation camera rules: N` 出现。

## 总线选择的判据

NeoForge 事件分两类，注册位置不同：

| 类别 | 注册到 | 本项目的例子 |
| --- | --- | --- |
| **mod bus 事件**（`IModBusEvent`，启动阶段一次性注册） | `@Mod` 构造函数的 `IEventBus bus` | `EntityRenderersEvent.RegisterRenderers`、`RegisterClientReloadListenersEvent`、`EntityAttributeCreationEvent`、`RegisterPayloadHandlersEvent` |
| **游戏总线事件**（运行期） | `NeoForge.EVENT_BUS` | `ClientTickEvent.Post`、`RenderGuiEvent.Pre`、`RenderLevelStageEvent`、`MovementInputUpdateEvent`、`ServerTickEvent.Post` |

判断依据：**事件的 Javadoc 或它是否实现 `IModBusEvent`**。
报错信息已直接给出答案：`IModBusEvent events are not allowed on the common NeoForge bus! Use a mod bus instead.`

## 已加入的门禁

`docs/CLIENT_ACCEPTANCE.md` 顶部新增 **G0 准入门禁**，改动 `client/` 后必须逐项确认：

| # | 检查 |
| --- | --- |
| G0.1 | `runClient` 能进世界，无 `Failed to create mod instance` |
| G0.2 | 日志中 `Cowardly refusing to send event` **一条都没有** |
| G0.3 | 无 `Failed to create mod instance` / `IModBusEvent` |
| G0.4 | 出现 `MineTurn animation camera rules: N`（`N ≥ 5`）—— **已由用户实测通过** |
| G0.5 | 改 `animation_camera.json` 后进游戏，数值生效；写坏时不崩 |

## 对 Codex 的请求

1. **A11/A12 涉及客户端**（姿态渲染、代理实体、动画 JSON）——请把 G0 门禁纳入你的验收流程，
   不要只以 `runGameTestServer` 通过为准。
2. 若你新增**客户端专属**的注册点（如 `RegisterGuiLayersEvent`、`RegisterKeyMappingsEvent`、
   `RegisterClientExtensionsEvent`），请一并在 G0 之后补一条对应目视检查。
3. `docs/WORK_ALLOCATION.md` 的"构建基线"只写了
   `gradlew.bat runGameTestServer build --offline`。**建议补一句**：
   改动 `client/` 时额外需要 `gradlew.bat runClient` 人工确认。此文件归你维护，我未改动。

## 我未做的事

- 未改 `WORK_ALLOCATION.md`（归 Codex 维护）。
- 未新增自动化检查：当前没有任何机制能在 CI 里构造 `BattleClient`，所以**门禁只能靠人工执行**。
  若要自动化，需要客户端 GameTest 或在 CI 中用无头客户端，属独立议题（参考 A11/A21）。
