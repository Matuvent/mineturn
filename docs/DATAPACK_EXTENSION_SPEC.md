# MineTurn 数据包与附属模组扩展规范

状态：核心接口已实现。函数回调、单实体注册、映射 priority、跨资源引用和同路径覆盖的实际用法见 [函数 AI 使用说明](FUNCTION_AI.md)。批量选择器、匹配精度、optional 和模板继承已实现，见 [批量 AI 使用说明](BATCH_AI.md)。

## 统一来源

MineTurn 自带规则、世界数据包和其他模组内置数据使用同一套资源 ID、目录和加载逻辑。不得只读取 MineTurn 自己的 JAR，不得把允许的命名空间写死为 mineturn。

生物程序采用原版 `.mcfunction` 扩展名，不采用 `.mcf` 简写，也不新增 Lua 依赖。JSON 注册入口、参数、物品和动作，函数实现控制逻辑。通用模板最终是可复用的函数及默认配置，复杂生物可以直接绑定自己的函数。

## 附属模组的目录

以示例模组 `cataclysm_mineturn` 为例，在其开发工程放置：

```text
src/main/resources/
└─ data/
   └─ cataclysm_mineturn/
      ├─ mineturn/
      │  ├─ mobs/
      │  │  └─ example_boss.json
      │  ├─ items/
      │  │  └─ example_weapon.json
      │  └─ actions/
      │     └─ heavy_strike.json
      └─ function/
         └─ boss/
            ├─ init.mcfunction
            ├─ turn.mcfunction
            └─ phase_two.mcfunction
```

打包后这些文件位于附属模组 JAR 的 `data/` 下，由 NeoForge/Minecraft 的资源体系提供给 MineTurn。不是在运行目录 `mods/` 中随意新建一个 `datapacks/` 子目录；后者不会因此自动成为数据来源。

资源路径中的 `cataclysm_mineturn` 是作者命名空间，`mineturn` 是本模组识别的自定义数据目录。两者职责不同。

独立数据包则使用相同的 `data/` 内容，在包根目录添加 `pack.mcmeta`，安装到 `<world>/datapacks/<pack>/` 或相应 ZIP 包中。Minecraft 1.21.1 的数据包格式版本为 48，函数目录为单数 `function`。

## 资源 ID 与文件路径的对应

**这是最容易踩的坑**：`mineturn/` 目录层**不属于资源 ID**，它由加载器剥掉。

| 磁盘路径 | 资源 ID |
| --- | --- |
| `data/mineturn/mineturn/actions/melee.json` | `mineturn:melee` |
| `data/cataclysm_mineturn/mineturn/actions/heavy_strike.json` | `cataclysm_mineturn:heavy_strike` |
| `data/cataclysm_mineturn/mineturn/items/example_weapon.json` | `cataclysm_mineturn:example_weapon` |
| `data/my_pack/mineturn/mobs/boss.json` | `my_pack:boss` |

`data/` 下的**第一段**是命名空间，`mineturn/` 是识别目录，其后的 `actions` / `items` / `mobs` /
`grants` / `ai_templates` / `catalog` 才是文件夹类型。

若写成 `cataclysm_mineturn:mineturn/actions/heavy_strike` 会得到
`Unknown definition folder` —— 加载器只认那六个文件夹名。

## 生物注册

生物注册示例（`target_mod:example_boss` 是占位实体 ID）：

```json
{
  "schema_version": 2,
  "entity": "target_mod:example_boss",
  "ai": {
    "on_enter": "cataclysm_mineturn:boss/init",
    "on_turn": "cataclysm_mineturn:boss/turn"
  }
}
```

### `ai` 支持的事件（**共五个，`on_turn` 必填**）

| 字段 | 是否必填 | 触发时机 |
| --- | --- | --- |
| `on_turn` | **必填** | 轮到该生物行动 |
| `on_enter` | 可选 | 加入战斗时 |
| `on_move_finished` | 可选 | 一次移动结束时 |
| `on_action_resolved` | 可选 | 一次行动结算完成时 |
| `on_leave` | 可选 | 退出战斗时 |
| `parameters` | 可选 | 自定义参数，供 mcfunction 读取 |

写其它字段会报 `Unknown ai field`；缺 `on_turn` 会报 `ai.on_turn is required`。
`ai` 与旧式 `states` **互斥**，不能同时出现。

示例物品映射沿用现有格式：

```json
{
  "items": ["target_mod:example_weapon"],
  "combat": {
    "enabled": true,
    "actions": ["cataclysm_mineturn:heavy_strike"],
    "melee_range": 3.5
  }
}
```

其中动作定义位于 `data/cataclysm_mineturn/mineturn/actions/heavy_strike.json`。实体/物品所属模组、注册规则命名空间、函数命名空间和动作命名空间可以不同。MineTurn 验证实际引用，不要求它们同名。

函数使用原版函数加载器解析，通过资源 ID 绑定到 AI 事件；不自行把 mcfunction 当普通文本逐行执行。自定义战斗命令需要在命令注册阶段存在，不能等函数加载完再注册。

## 新增与覆盖

### 新增

使用自己的命名空间和新资源路径添加生物、物品、动作、函数，不需要修改 MineTurn 本体。所有依赖引用有效时才启用相应定义。

### 覆盖已有文件

更高优先级的数据包提供相同命名空间、相同路径的文件，即可替换原文件。例如覆盖上述 Boss 行动函数：

```text
<world>/datapacks/my_balance_pack/
├─ pack.mcmeta
└─ data/
   └─ cataclysm_mineturn/
      └─ function/
         └─ boss/
            └─ turn.mcfunction
```

虽然数据包目录名是 my_balance_pack，覆盖时仍使用原资源命名空间 cataclysm_mineturn。这不需要修改附属模组 JAR。

同理，覆盖 `data/mineturn/mineturn/actions/claw.json` 可以修改内置利爪动作。JSON 默认整文件替换，不自动逐字段合并；mcfunction 也整文件替换，不自动追加命令。原版实体标签按原版标签合并及 replace 规则处理。

资源覆盖按启用数据包的实际优先级决定，不通过文件修改时间、目录扫描顺序或模组显示名称决定。对等模组之间需要稳定覆盖时，应使用明确的上层数据包或下述映射优先级，不能假设某个模组总是最后加载。

### 不同文件映射同一实体或物品

这不是同一资源文件的覆盖，必须另行解决冲突。

映射先比较 `priority`；生物同优先级再按精确 ID > 标签 > 命名空间匹配。最高候选仍并列时报告双方文件并拒绝发布；物品同最高优先级重复仍报错。动作和函数按自身资源 ID 唯一解析，不使用这种实体映射优先级。

替换现有物品或生物配置时，可以覆盖原 JSON 路径，也可以在新命名空间创建 priority 更高的映射；生物还可以使用更精确的匹配规则。最高优先级和匹配精度均相同的重复映射仍会冲突。

## 纯数据适配与 Java 适配

只使用已有移动方式、战斗效果和命令能力时，适配内容可以全部写在数据包里；不一定需要单独的附属模组。

附属模组可以同时提供 Java 能力扩展与上述数据文件。例如 Java 负责安全调用某个 Boss 的特殊技能，数据中的动作定义与 mcfunction 决定何时使用。新增效果类型/技能接口仍需 Java 注册；不能只写一个未知 effect ID 就执行其他模组内部方法。

附属模组应声明对 MineTurn 和目标模组的必要依赖。生物注册现可使用 optional 字段处理未安装的模组；不得静默把不存在的实体视为成功注册。

## 重载与一致性

JSON、原版函数及标签都属于服务端数据，由数据包加载/重载流程更新。新增 Java 效果执行器仍需重启，不能通过 `/reload` 加载 Java 代码。

发布前检查函数入口、动作 ID、实体/物品 ID、重复映射、数据版本和参数。错误报告包含资源 ID 与字段路径。

> **关于"诊断命令"**：早期版本的本规范声称可以通过命令查看某个定义的最终来源，
> **实际上没有这样的命令**——`/mineturn` 只有 `end`、`sprint`、`retreat`、`flee`、`attack`、`use`、
> `move`、`abort` 八个子命令，都不显示资源来源。来源信息（`CombatData.Snapshot.sources()`）只在
> Java 与测试中可读。**排查覆盖冲突目前只能靠二分法禁用数据包**，本规范不再承诺不存在的入口。

原有“正在进行的战斗保持旧 JSON 快照”不能直接保证 mcfunction 一致性：原版函数及其间接引用可能在重载后变化。第一版函数 AI 已在资源重载开始时结束现有战斗并清理其 AV 定时任务；JSON 只有在 Minecraft 安装对应新函数库时发布，加载与交叉校验完成后才允许新函数 AI 入战。不能承诺旧 JSON、旧函数和旧标签自动一起回滚。

## 可复制的示例

`examples/` 下有 13 个**可直接安装**的数据包/资源包，覆盖物品适配、函数 AI、批量选择器、条件授予、
空间 Boss、延迟行动、传送、陷阱、场地效果、动作镜头等。每个包都是**部分数据包**——引用本模组自带
定义而不重复声明，所以单独安装时看到"缺少某个动作"是正常的，与主模组一起用即可。

> **示例会被测试真正加载**：`test/ExamplePackValidationGameTests.java` 把每个示例包连同本模组定义
> 一起喂给真实解析器。示例一旦与代码脱节，测试就会失败——文档承诺的东西因此不会悄悄失效。

## 验收用例

- 一个独立数据包新增动作与物品映射，无需修改 MineTurn JAR。
- 一个附属模组从自身 resources/data 提供 JSON 和 AI 函数。
- 上层数据包单独覆盖附属模组的 on_turn 函数，注册 JSON 不变。
- 上层数据包单独修改动作伤害，函数不变。
- 相同资源路径按实际包优先级覆盖；不同路径重复映射报告明确冲突。
- 跨命名空间引用、缺失依赖、函数语法错误及数据版本错误均有可定位诊断。
- `/reload` 后新配置生效，旧战斗及其延迟函数不继续混用资源版本。
- **`examples/` 下每个示例包都能被当前解析器加载**（自动化，见上）。

参考：[NeoForge 资源文档](https://docs.neoforged.net/docs/1.21.1/resources/)。
