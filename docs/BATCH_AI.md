# 批量注册与可复用 AI 模板

已实现：原版实体标签、实体列表、命名空间批量注册，以及可继承的 AI 模板。复杂 Boss 仍然可以直接绑定自己的 mcfunction；模板只是复用配置，不限制程序结构。

## 最短注册

在数据包或附属模组的资源目录放入 `data/my_pack/mineturn/mobs/husk.json`：

```json
{
  "entity": "minecraft:husk",
  "template": "mineturn:melee"
}
```

内置 `mineturn:melee` 会选择最近敌人，能攻击则攻击，否则靠近；受周身范围约束时先撤退。默认敏捷 100，周身范围 1.5，动作 `mineturn:melee`（4 点伤害、2.5 格攻击范围）。移动仍使用当前地面移动规则，没有飞行或游泳能力。`mineturn:idle` 每回合等待，供暂时不设计动作的生物使用。

这只是注册 AI，不会让所有动物主动加入附近战斗。现有参战规则仍生效：直接受到攻击可以入战，自动增援仍要求已配置的敌对生物。

## 批量匹配

```json
{
  "schema_version": 2,
  "match": {
    "entities": ["minecraft:husk"],
    "entity_tags": ["my_pack:melee_group"],
    "namespaces": ["target_mod"],
    "exclude": ["target_mod:boss", "#my_pack:special_ai"]
  },
  "optional": true,
  "template": "mineturn:melee",
  "priority": 0
}
```

`entity` 和 `match` 必须二选一。正向选择器取并集，随后应用排除；不需要的选择器可以省略。实体标签写在标准目录 `data/my_pack/tags/entity_type/melee_group.json`，例如：

```json
{"values": ["minecraft:husk", "minecraft:drowned"]}
```

`entity_tags` 中写标签 ID，不带 `#`；`exclude` 用 `#` 区分标签和实体 ID。命名空间按实体注册 ID 匹配，战斗系统仍只处理符合现有条件的 Mob，不会因此支持投射物等非生物实体。

默认遇到不存在的实体、标签或没有任何实体的命名空间会拒绝加载。`optional: true` 允许这些目标缺失，适合未必安装目标模组的兼容包；空匹配会记录日志。它不等于忽略所有配置错误，也不让任意未知动作/函数变得可用。该字段目前只用于生物匹配。

## 冲突与禁用

相同资源路径按数据包顺序整文件覆盖。不同注册文件命中同一实体时依次比较：

1. 显式 `priority`，值越高越优先，默认 0。
2. 同优先级时：精确实体 ID > 实体标签 > 命名空间。
3. 最高候选仍并列则拒绝加载，日志列出双方文件。

因此可以用一个标签配置整组生物，再用精确 ID 单独调整其中一个。要覆盖内置精确注册，应覆盖它的原路径，或者给新规则更高的 `priority`。

```json
{"entity":"minecraft:husk", "priority":10, "enabled":false}
```

禁用规则照常参与优先级比较，获胜后删除该实体的 MineTurn AI，不回落到较低优先级规则。`exclude` 只排除当前规则的匹配，不阻止其他规则注册它。

## 自定义模板

保存到 `data/my_pack/mineturn/ai_templates/heavy.json`：

```json
{
  "extends": "mineturn:melee",
  "agility": 120,
  "reach": 2,
  "ai": {
    "parameters": {"action": "my_pack:heavy_strike"}
  }
}
```

注册文件使用 `"template":"my_pack:heavy"`，仍可在注册文件中覆盖敏捷、周身范围和回调。动作要另行定义在 `data/my_pack/mineturn/actions/heavy_strike.json`：

```json
{"name":"重击", "effect":"mineturn:damage", "amount":6, "range":2}
```

模板可以跨数据包、跨模组命名空间继承，最多 8 层；缺失父模板或循环继承会拒绝加载。覆盖同 ID 模板文件时仍是整文件替换；只有显式 `extends` 或 `template` 引用才发生字段合并。

- 普通字段由子配置替换。
- `ai` 回调按名称合并，`ai.parameters` 按参数名合并；参数中的嵌套对象整体替换。
- `null` 删除继承字段，例如 `"on_leave": null`；必需的 `on_turn` 仍不能缺失。
- 旧状态机的 `states` 按状态名合并，每个状态整体替换。
- 子配置指定 `ai` 会替代继承的旧状态机；指定 `states` 会替代继承的函数 AI。

内置近战模板按周身范围决定贴身等待与撤退，建议 `reach` 不大于攻击动作的 `range`。它使用计分板目标 `mineturn.ai` 暂存查询结果，不要把它作为自己的持久状态。

宏参数中的动作 ID 属于程序参数，不能保证都被静态识别为动作引用；自定义函数应检查 `query ready` / `use` 的返回值。完整命令与回调说明见 [FUNCTION_AI.md](FUNCTION_AI.md)。

## 热重载与示例

`/reload` 同时重新解析注册、模板、新实体标签和函数入口；开始重载时结束旧战斗。使用 `/mineturn ai inspect minecraft:husk` 检查最终注册来源、函数入口或禁用状态。

示例源码在 `examples/batch-ai/`，运行 `gradlew.bat batchAiExamplePack` 生成 `build/examples/mineturn-batch-ai-example.zip`。安装到世界 `datapacks` 后启用并 `/reload`：

- 尸壳和溺尸通过实体标签共用重击模板。
- 默认敏捷 110、伤害 6。
- 尸壳的精确注册将敏捷覆盖为 130。

示例不依赖其他示例包。附属模组使用相同的 `src/main/resources/data/` 目录即可，不需要 Java 注册每一只生物。飞行、水下移动及 Boss 效果注册接口见 [移动与特殊技能](MOBILITY_AND_SKILLS.md)；自定义阵营和具体第三方 Boss 适配仍待实现。
