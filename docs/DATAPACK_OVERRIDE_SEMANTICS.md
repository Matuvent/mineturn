# 数据包覆盖 / 禁用 / 可选引用语义

本文记录**已验证的实际行为**，用于第三方数据包编写者。
它同时是 D07 的验收记录：每一项都标出由哪条测试钉住。

> 验证方式：`gradlew runGameTestServer` → **327/327 通过**。
> 新用例位于 `test/DataOverrideRegressionGameTests.java`。

---

## 1. `priority`：同键多文件如何决胜

同一个物品 / 动作 / 实体被多个文件声明时，按 `priority` 决定：

| 情形 | 行为 |
| --- | --- |
| 一方 `priority` **更高** | 高者胜，低者完全不贡献 |
| 两方 `priority` **相同** | **报错**，不做静默取舍 |

错误信息形如：

```
Conflicting items/minecraft:trident at priority 0: test:items/a and test:items/b
```

**这是刻意的**：两个文件同样优先却给出不同配置时，任何"随便挑一个"的行为都会让加载顺序决定游戏内容，且不可复现。所以必须报错让作者显式定优先级。

`priority` 范围 `-1000000 .. 1000000`，缺省 `0`。

> 由 `equalPriorityIsRejectedInsteadOfSilentlyResolved` 覆盖。同级报错、高者胜出且 `sources()` 指向胜出文件。

### 与"特异度"的关系（AI 匹配）

`mobs/` 的选择器另有一套**特异度**排序（精确实体 > 标签 > 命名空间），
与 `priority` 的关系是：**先比 `priority`，再比特异度**。
所以给一个宽泛选择器加 `priority:1` 就能压过精确匹配。
（既有 `batchSelectorsAndTemplates` 覆盖。）

---

## 2. `enabled`：**只有四种文件认识它**

| 文件 | 支持 `enabled` | 行为 |
| --- | --- | --- |
| `catalog/vanilla.json` | ✅ | `false` 时**不**做原版兜底补齐 |
| `mobs/*.json` | ✅ | `false` 时该实体**不注册**，且不回退到默认模板 |
| `items/*.json` 的 `combat` | ✅ | `false` 时该物品**存在但不可用于战斗** |
| `grants/*.json` | ✅ | `false` 时 `available()` 恒为 `false`，按钮不出现 |
| `actions/*.json` | ❌ **不支持** | 写了也**照常注册** |

> `actions` 不支持这一点由 `enabledScopeIsCatalogMobsItemsAndGrantsOnly` 钉住。
> 想让某个动作不可用，应该禁用**授予它的 grant**或**引用它的物品**，而不是动作本身。

### 禁用物品不会被原版兜底"复活"

`catalog/vanilla.json` 会给**未被显式配置**的原版物品补默认战斗配置。
判定依据是"该物品是否已在物品表里"，而 `{"enabled": false}` 会**占住这个键**，
所以禁用不会被兜底覆盖。

> 由 `explicitDisableSurvivesTheVanillaCatalog` 覆盖，同时也验证了目录关闭时不补齐。

---

## 3. 覆盖的写法

同路径、同命名空间即可覆盖；资源包/数据包优先级高者胜。

| 目标 | 做法 |
| --- | --- |
| 改一个物品的战斗配置 | 同路径放自己的 `items/<名字>.json` |
| **禁用**一个物品 | `"items": ["minecraft:trident"], "combat": {"enabled": false}` |
| 改原版兜底 | 改 `catalog/vanilla.json`，或整包禁用 |
| 覆盖一只怪 | 同路径或 `priority` 更高的 `mobs/*.json` |

> **`combat` 里的 `actions` 是可选的**（D07 修复）。此前 `actions` 是必填字段，
> 导致上面这种"只写 `enabled:false`"的禁用写法**直接解析失败**，
> 与文档承诺不符。现在只写 `enabled` 也能通过。
> 同一个 codec 也用于物品组件 `mineturn:combat`，所以手写组件同样不必列 `actions`。

---

## 4. `optional`：可选引用

引用**可能不存在**的内容（另一个模组的实体、某个标签）时，默认是**硬错误**：

```
Unknown entity absent:mod_mob
```

加 `"optional": true` 后改为**跳过并记一条日志**：

```
MineTurn optional entity skipped: absent:mod_mob
```

| 引用位置 | 不加 `optional` | 加 `optional: true` |
| --- | --- | --- |
| `match.entities` 里的未知实体 | 报错 | 跳过 |
| `match.entity_tags` 里的未知标签 | 报错 | 跳过 |
| `match.namespaces` 里的未知命名空间 | 报错 | 跳过 |

**`optional` 只影响"缺失时是否报错"**，不会关闭匹配：标签存在时照常展开。

> 由 `optionalReferencesSkipMissingContent` 覆盖，含"不写时确实报错且错误信息点名肇事者"、
> 三种未知引用都被跳过、以及**存在的标签仍然生效**（防止"optional 把匹配一起关掉"的退化）。

---

## 5. AI 模板继承（`extends`）

| 规则 | 行为 |
| --- | --- |
| `extends` | 继承父模板，子覆盖父 |
| `parameters` / 回调 | **按键合并**：子给同键覆盖，给 `null` 则**删除**该键 |
| `states` 里具名状态 | **整体替换**，不逐字段合并 |
| 循环继承 | 报错 `AI template inheritance cycle` |
| 继承层数 | 超过 **8 层**报错 |
| `ai` 与旧式 `states` | **互斥**，同时写会报错 |

> 既有 `batchSelectorsAndTemplates` 与 `invalidTemplateInheritance` 覆盖。

### 怪物定义的必填字段

写 `mobs/*.json` 时以下字段是**必填**（缺一个就在加载期报错）：

- `agility`、`reach`
- `ai`（或旧式 `states`，但**不能同时**）
- `ai.on_turn` 必填，且必须是**合法标识符**；写 `null` 会报 `JsonNull`

> 这几条是我在写 D07 夹具时踩出来的：`ai` 缺失 → `parseBrain` NPE；
> `ai.on_turn: null` → `JsonNull`；同时写 `ai` 与 `states` → 明确报错。

---

## 6. 验证矩阵

| 语义 | 测试 | 位置 |
| --- | --- | --- |
| 同级 `priority` 报错 | `equalPriorityIsRejectedInsteadOfSilentlyResolved` | 新增（D07） |
| 高 `priority` 胜出且来源正确 | 同上 | 新增（D07） |
| 禁用物品不被兜底复活 | `explicitDisableSurvivesTheVanillaCatalog` | 新增（D07） |
| 目录禁用后不补齐 | 同上 | 新增（D07） |
| `actions` 不支持 `enabled` | `enabledScopeIsCatalogMobsItemsAndGrantsOnly` | 新增（D07） |
| grant 的 `enabled` 被读取 | 同上 | 新增（D07） |
| 未知引用默认报错且点名 | `optionalReferencesSkipMissingContent` | 新增（D07） |
| `optional` 跳过三种未知引用 | 同上 | 新增（D07） |
| `optional` 不关闭存在的标签 | 同上 | 新增（D07） |
| 禁用 mob 不影响同选择器其它实体 | `disabledMobIsDroppedWithoutAffectingSiblings` | 新增（D07） |
| `priority` 压过特异度 | `batchSelectorsAndTemplates` | 既有 |
| 模板合并 / `null` 删除 | `batchSelectorsAndTemplates` | 既有 |
| 继承循环与深度限制 | `invalidTemplateInheritance` | 既有 |

## 相关文档

- [数据包扩展规范](DATAPACK_EXTENSION_SPEC.md)：完整字段说明
- [批量 AI 注册](BATCH_AI.md)：`match` 选择器与 `priority`
- [原版覆盖](VANILLA_COVERAGE.md)：`catalog` 的兜底范围
- [条件授予行动](CONDITIONAL_ACTIONS.md)：`grants` 的条件字段
- [示例静态检查](../tools/README.md)：`tools/validate_examples.py`
