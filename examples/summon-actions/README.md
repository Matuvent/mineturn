# MineTurn 临时召唤示例

将目录或 ZIP 放入存档 datapacks 并 `/reload`，需要当前版本 MineTurn。进战前戴金头盔，保留至少 1 经验等级。

“召唤尸壳”消耗 1 主要行动和 1 经验等级，冷却 150 AV；在自己世界坐标 +X 方向 2 格处生成临时尸壳，存活 300 AV，独立按已有 AI 行动并跟随你的敌我关系。出生点需可站立且没有实体占用。等待现实时间不推进寿命。

每人最多 4 个、每场最多 8 个临时召唤物；离场/到期/战斗结束清理，不掉落物品或经验。示例不会默认启用。预检失败无费用；已支付后生成事件取消则不自动退款。

构建：`./gradlew.bat summonActionExamplePack`。可覆盖 actions/husk.json 的类型、偏移、寿命、冷却以及 grants/husk.json 的装备/资源条件。完整契约见仓库 docs/SUMMON_ACTIONS.md。
