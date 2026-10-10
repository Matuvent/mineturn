# 发布验收与安装说明

> **本清单不自动发布任何东西。** 它不调用 `publish`、不上传、不改许可证、不动版本号。
> 每一步都由人执行并留痕。

**本页数字与命令均已实测。** 未验证的项标为 ⬜ 并写明"需人工"。

---

## 0. 版本与协议（发布前必须两端一致）

| 项 | 值 | 出处 |
| --- | --- | --- |
| 模组版本 | `1.0.0` | `gradle.properties` → `mod_version` |
| 模组 ID / 名称 | `mineturn` / `MineTurn` | `mod_id` / `mod_name` |
| Minecraft | `1.21.1`（范围 `[1.21.1]`） | `minecraft_version` |
| NeoForge | `21.1.251` | `neo_version` |
| **网络协议** | **`22`** | `BattleNetwork.register` → `event.registrar("22")` |
| 许可证 | `LGPL-2.1-only` | `mod_license` |
| 数据包格式 | `48` | 与 1.21.1 对应 |
| 产物 | `build/libs/mineturn-1.0.0.jar` | — |

### ⚠️ 两端必须同时更新

**协议号不匹配时客户端无法连接。** 服务端与客户端必须使用**同一构建**。

NeoForge 会拒绝协议不一致的连接，因此**不要**混用不同版本的 jar。
若只更新了一端，症状是连接被拒或战斗数据不同步——**不是**"看起来能用但偶尔出错"。

修改协议号的位置是 `src/main/java/com/matuvent/mineturn/network/BattleNetwork.java` 里的
`event.registrar("22")`。**改了它必须同步更新本表和 README。**

---

## 1. 构建与测试（可自动化，已验证）

```bash
# 完整构建 + 服务器测试
gradlew.bat build runGameTestServer --offline --console=plain

# 构建全部 11 个示例数据包 ZIP
gradlew.bat functionAiExamplePack functionAiOverridePack batchAiExamplePack \
  spatialBossExamplePack grantedActionExamplePack itemAdapterExamplePack \
  accessoryActionExamplePack delayedActionExamplePack summonActionExamplePack \
  fieldActionExamplePack trapActionExamplePack --offline --console=plain
```

**实测基线（2026-10-10）**：

| 检查 | 期望 | 实际 |
| --- | --- | --- |
| 服务器测试 | **全绿**（`All N required tests passed`） | ✅ **350 / 350 通过** |
| 模组 jar | 生成 | ✅ `build/libs/mineturn-1.0.0.jar`（≈ 854 KB） |
| 示例 ZIP | 11 个 | ✅ `build/examples/*.zip` |

> **判定标准是"全绿"，不是某一个固定数字。** 测试数会随开发增长（本页写就时从 349 变成 350）。
> 看到 `All N required tests passed :)` 即为通过；若有 `tests failed` 才需处理。
> **不要**把某个数字当成"必须完全相等"——那会让正常新增测试变成假失败。
>
> 需要的固定数字（生物登记数、可用物品数等）由
> [CoverageMatrixGameTests](../src/main/java/com/matuvent/mineturn/test/CoverageMatrixGameTests.java)
> 从解析结果断言，见[原版覆盖矩阵](VANILLA_COVERAGE.md)。

> **注意**：`gradlew build` **不包含**示例 ZIP——它们没有挂到 `assemble` 上。
> 必须像上面那样显式列出任务（或用 `build` + 逐个任务）。

### 客户端必须单独验证（**不能靠服务器测试替代**）

`runGameTestServer` 是**专用服务端**，**永远不会构造 `client/BattleClient`**。
因此客户端注册、镜头、音效、粒子全部**零覆盖**。

> **改了 `src/main/java/com/matuvent/mineturn/client/` 下任何文件，必须真实启动一次客户端。**
> 详见 [客户端手测表](CLIENT_ACCEPTANCE.md) 的 **G0 准入门禁**（含"日志无 broken mod state"等硬性检查）。

---

## 2. 示例数据包 ZIP 的根目录

**每个 ZIP 的根目录必须直接是 `pack.mcmeta` + `data/`**，不能多包一层目录。

实测正确的结构：

```
mineturn-item-adapters-example.zip
├─ pack.mcmeta          ← 必须在根
├─ README.md
└─ data/
   └─ item_example/mineturn/{actions,items}/...
```

**错误示范**（多套一层目录，游戏无法识别）：

```
mineturn-item-adapters-example.zip
└─ mineturn-item-adapters/     ← ❌ 多了一层
   ├─ pack.mcmeta
   └─ data/
```

**核对方法**：解压后直接看到 `pack.mcmeta` 即为正确。

**已知**：`examples/animation-camera` 是**资源包**（只有 `assets/`，无 `data/`），
**没有对应的 ZIP 任务**，需要手动打包并放到客户端 `resourcepacks/`。

---

## 3. 安装

### 客户端

1. 把 `mineturn-1.0.0.jar` 放入 `.minecraft/mods/`
2. 需要 **NeoForge 21.1.251**（Minecraft 1.21.1）
3. 可选：示例资源包放入 `resourcepacks/`

### 专服

```bash
# 1. 用 NeoForge 21.1.251 安装器建立服务端
# 2. 把 mineturn-1.0.0.jar 放入 mods/
# 3. 启动
java -Xmx4G -jar neoforge-21.1.251-installer.jar --installServer
java -Xmx4G -jar server.jar nogui
```

### 示例数据包

- 放入 `<world>/datapacks/`
- 执行 `/reload`
- 或解压到 `<world>/datapacks/<名字>/`（同样要求 `pack.mcmeta` 在那一层）

> **示例是部分数据包**：它们引用本模组自带定义而不重复声明。
> 单独安装时看到"缺少某个动作"属正常，与主模组一起用即可。

### 示例之间的冲突

**不要同时安装引用同一物品/实体的两个示例**。同优先级映射同一键会**拒绝重载**并报
`Conflicting ...`。逐一测试，或确认它们不重叠。

---

## 4. 日志归档

发布前请附上以下日志，便于定位问题：

| 日志 | 路径 | 用途 |
| --- | --- | --- |
| 客户端 | `.minecraft/logs/latest.log` | 客户端启动、渲染、连接 |
| 专服 | `<server>/logs/latest.log` | 加载、战斗、数据包错误 |
| 服务器测试 | `run-gametest/logs/latest.log` | 测试结果与基准表 |
| 崩溃报告 | `.minecraft/crash-reports/` 或 `<server>/crash-reports/` | 崩溃堆栈 |

**必须检查的关键行**：

```
# 客户端正常启动（缺失说明机位配置根本没加载）
MineTurn animation camera rules: 6

# 测试全绿
All N required tests passed :)

# 危险信号：出现任意一条即说明模组构造失败
Failed to create mod instance
Cowardly refusing to send event
IModBusEvent events are not allowed
```

> `Cowardly refusing to send event` 会刷屏并**掩盖真正原因**。看到它就往上翻找
> `Failed to create mod instance`，而不是逐条看那些被拒的事件。

---

## 5. 已知限制（发布说明必须如实写明）

### 客户端相关

| 限制 | 说明 |
| --- | --- |
| 行动者**没有姿态动画** | 目前只有镜头，角色身体不做挥剑/举食物等动作（属 A11 范围） |
| 技能镜头**数值未经广泛实测** | 构图可调，但未在各体型/地形上普遍验证 |
| 激流**原版旋转姿态未接入** | 刻意决定：原版旋转会追加实时伤害，纯表现启用等于绕过结算 |

### 服务端相关

| 限制 | 说明 |
| --- | --- |
| **返回任务不跨重启保存** | 三叉戟忠诚计时是内存态；重启后物品留在世界掉落物里，需自行拾取 |
| 掉落物被摧毁**不重建** | 刻意设计（防复制）；岩浆/爆炸销毁即永久失去 |
| **无战内回收动作** | 无忠诚三叉戟投出后只能战后走过去捡 |
| 部分物种机制不完整 | 已登记 ≠ 机制完整。例如流浪者变种转换、沼骸剪蘑菇、北极熊站立警告仍未适配 |
| 月相：多数原版物品**被禁用** | 1333 项已登记中仅 **86** 项可在战斗中使用，其余为兜底登记 |
| 铁魔法、灾变、饰品槽 | 需对应附属适配，**不视为已兼容** |

### 排查工具的限制

**没有查看"某个定义来自哪个数据包"的命令。** `/mineturn` 只有
`end`/`sprint`/`retreat`/`flee`/`attack`/`use`/`move`/`abort`。
覆盖冲突只能用**二分法禁用数据包**排查。

### 资源生物

**明确不参战**（38 种，见 `mineturn:non_combatants`），且有测试硬性保证——
若被改回参战，测试会失败。

---

## 6. 旧世界备份与回滚

### 升级前（必做）

1. **停服**（正常 `stop`，不要强杀）
2. 备份整个世界目录：`<world>/`（含 `level.dat`、`region/`、`datapacks/`）
3. 记录当前 jar 与协议版本
4. 若安装了示例数据包，记录清单

### 回滚步骤

1. **停服**
2. 删除新版 jar，放回旧版 `mineturn-<旧版本>.jar`
3. **还原世界备份**（不要只换 jar 就继续用旧世界——存档内可能有新版写入的实体状态）
4. 若协议号有变化，**客户端也要一起回滚到对应版本**
5. 启动后检查日志无 `Failed to create mod instance`

> **为什么建议还原世界**：战斗中生成的掉落物、返回任务、状态 AV 等都写入过存档。
> 只回滚 jar 可能留下新版写入的数据，旧版未必能正确解释。

### 移除示例数据包

删除 `<world>/datapacks/<包名>/` 或对应 ZIP，然后 `/reload`。
示例覆盖的是**同优先级/更高优先级**映射，移除后自动恢复内置行为。

---

## 7. 发布前检查表

### 自动化（可脚本验证）

- [ ] `gradlew build runGameTestServer` 全绿（判定为 `All N required tests passed :)`）
- [ ] `build/libs/mineturn-1.0.0.jar` 已生成
- [ ] 11 个示例 ZIP 已生成
- [ ] 每个示例 ZIP 根目录直接是 `pack.mcmeta` + `data/`
- [ ] `python tools/validate_examples.py` 通过（13 包 / 56 JSON）

### 人工确认（无法自动化）

- [ ] **客户端能启动**且日志无 `Failed to create mod instance` / `broken mod state`
- [ ] 日志出现 `MineTurn animation camera rules: 6`（证明机位配置被加载）
- [ ] 改动过 `client/` 时，已按 [G0 门禁](CLIENT_ACCEPTANCE.md) 逐项确认
- [ ] 专服能在 NeoForge 21.1.251 下启动
- [ ] 两端协议一致（都是 **22**）
- [ ] 旧世界已备份，且备份可还原
- [ ] 已知限制已写入发布说明（§5）
- [ ] 版本号与许可证**未被本次发布流程自动修改**

---

## 8. 本清单明确不做的事

| 不做 | 原因 |
| --- | --- |
| 不自动发布 / 上传 | 由人决定何时发布 |
| 不改许可证 | 法律文本不应被脚本改动 |
| 不改版本号 | 版本由维护者显式决定 |
| 不新增 CI | 属独立议题 |

## 相关文档

- [客户端手测表](CLIENT_ACCEPTANCE.md)：G0 启动门禁与各组验收项
- [数据包示例静态检查](../tools/README.md)：可脚本化的结构检查
- [数据包扩展规范](DATAPACK_EXTENSION_SPEC.md)：示例、覆盖与诊断的真实能力
- [原版覆盖矩阵](VANILLA_COVERAGE.md)：已登记/可用/禁用的实测数字
- [三叉戟拾回验收](TRIDENT_RECOVERY_ACCEPTANCE.md)：重启相关限制的细节
- [GitHub 仓库说明](GITHUB_REPO_DESCRIPTION.md)
