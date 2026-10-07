# 数据包示例静态检查（`tools/validate_examples.py`）

在仓库根目录运行：

```bash
python tools/validate_examples.py
```

退出码 `0` 表示全部通过；`1` 表示至少有一处问题，并在末尾逐条列出。
**只用 Python 标准库**，无第三方依赖，不需要启动游戏或 Gradle。

## 它检查什么

| # | 检查项 |
| --- | --- |
| 1 | 每个 `examples/<名字>/` 都有合法的 `pack.mcmeta`：JSON 可解析、`pack.pack_format == 48`、`description` 非空 |
| 2 | 每个示例都有 `data/<命名空间>/…` 结构，且命名空间名符合资源位置字符集 |
| 3 | 该目录树下所有 `.json` 都能解析（UTF-8、无 BOM、语法正确） |
| 4 | `actions/` 与 `grants/` 下的文件名推导出的 id 是合法资源位置 |
| 5 | `build.gradle` 里引用的 `examples/<名字>` 目录都真实存在 |
| 6 | 文档中的**本地文件链接**指向存在的文件（跳过 `http(s)`、`mailto:`、纯锚点） |
| 7 | 缺少 `README.md` 的示例会在 notes 中提示（不算失败） |

## 它刻意**不**检查什么

示例是**部分数据包**，设计上会引用基础模组（`src/main/resources/data/mineturn/`）或其它数据包里的
动作、物品和函数。因此：

- **悬空引用不是错误**。脚本不要求每个示例自包含。
- 不校验动作 schema 的字段语义（那由游戏内的加载期校验负责，见 `CombatData`）。
- 不校验原版函数（`.mcfunction`）的命令是否合法。
- 不校验数值平衡或行为正确性。

## 已知局限

- 只做语法与结构检查，**不能替代** `gradlew runGameTestServer` 的行为验证。
- 链接检查是**文本级**的：不做大小写不敏感匹配，也不检查锚点（`#section`）是否真的存在。
- 依赖 `pack_format == 48`（Minecraft 1.21.1）。升级游戏版本时需同步改脚本里的常量。

## 排除目录

以下目录不属于交付内容，扫描时会跳过，避免第三方依赖里的 README 产生大量假报错：

`.git`、`.gradle`、`.idea`、`.github`、`build`、`run`、`run-gametest`、`outputs`、`node_modules`、
`repo`、`__pycache__`

## 实测结果（2026-10-08）

```
example packs checked : 12
json files parsed     : 54
notes (7): ...
OK: all example checks passed
```

反向验证（确认脚本真的能抓错，验证后已全部还原）：

| 注入的错误 | 是否被抓到 |
| --- | --- |
| `pack.mcmeta` 被写坏（BOM / 语法错误） | ✅ 报 invalid JSON |
| action JSON 被截断 | ✅ 报 invalid JSON 并给出文件路径 |
| 文档中的链接指向不存在的文件 | ✅ 报 link target does not exist |

## notes 里两个待办（不阻塞）

1. `examples/teleport-action` 未被 `build.gradle` 引用 —— 它是可手动放入的数据包，属正常；
   如需打包成 ZIP，在 `build.gradle` 里加一个 Zip 任务即可。
2. 6 个示例没有 `README.md`（`batch-ai`、`function-ai`、`function-ai-override`、`granted-laser`、
   `spatial-boss`、`teleport-action`）—— 建议补一段简短说明，但不是错误。
