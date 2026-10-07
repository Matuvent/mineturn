# MineTurn 危险领域示例

安装当前 MineTurn，将本目录或 ZIP 放进存档 datapacks，执行 /reload。进战前戴锁链头盔并准备至少 1 经验等级。

“危险领域”消耗 1 主要行动与 1 经验等级，冷却 150 AV，在自己世界坐标 +X 方向 2 格创建半径 2、高 2 格的固定区域，持续 100 AV。25/50/75 AV 时对范围内无遮挡的同场敌人造成 2 基础伤害，100 AV 消失。等待现实时间不会伤害；友军和场外实体不受影响。施法者离场自动清理。

构建：`./gradlew.bat fieldActionExamplePack`。可在 actions/hazard.json 修改尺寸、偏移、周期、寿命、伤害、行动次数和冷却，在 grants/hazard.json 修改装备及资源条件。完整接口见 docs/FIELD_ACTIONS.md。粒子边界与联机显示需实测。
