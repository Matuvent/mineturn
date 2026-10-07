# 通用临时召唤单位

本组提供单个临时生物的预检、付费后生成、战斗登记、阵营继承与 AV 寿命。不自动迁移原有唤魔者恼鬼、潜影弹或药水云；非生物场上机关/危险区域仍是后续工作。

## 数据包示例

构建 `./gradlew.bat summonActionExamplePack`，把 `build/examples/mineturn-summon-actions-example.zip` 放进存档 datapacks 并 `/reload`。进战前戴金头盔，保留至少 1 经验等级。右侧出现“召唤尸壳”：花 1 主要行动、1 经验等级，启动 150 AV 冷却，在自己世界坐标 +X 方向 2 格处召唤尸壳，存活 300 AV。

召唤物有独立行动队列，按自己的数据包 AI 与敏捷行动，加入时从完整行动间隔开始，不立即获得一次免费行动。它跟随召唤者当前敌我关系；玩家召唤的尸壳不会反过来攻击玩家。寿命只随 AV 推进，现实等待不缩短；到期、召唤者离场、召唤物死亡/离场或整场结束时清理。召唤者死亡后的战斗清理也会移除其单位。

动作定义：

```json
{
  "name": "召唤尸壳",
  "effect": "mineturn:summon",
  "self": true,
  "amount": 0,
  "consume": 0,
  "range": 8,
  "cooldown_av": 150,
  "parameters": {
    "entity": "minecraft:husk",
    "dx": 2, "dy": 0, "dz": 0,
    "lifetime_av": 300
  }
}
```

`entity` 必须是已注册实体类型，实际召唤还要求本场数据包存在可参战 AI。未配置 AI、被禁用/资源生物不允许召唤。偏移是世界坐标，不跟随镜头；长度最多 8 格，XZ 出生点居中，Y 保留。默认偏移 (2,0,0)、寿命 300 AV；寿命合法范围 1–100000 AV。动作必须 self=true、amount=0、consume=0，不配置 ranged；可用 action_cost 改主要/次要费用。

## 预检和费用

预检不创建实体、不触发生成事件、不扣任何费用，检查类型/AI、人数、地形及位置。每名施法者最多 4 个、每场最多 8 个此类召唤物，仍受全场 32 名参与者上限限制。地面/攀爬类型需支撑或入水，游泳类型需水体，飞行类型可在空中；拒绝岩浆、墙体、实体占用、未加载区块、世界边界/高度外、战场外以及大于 16 格的实体尺寸。

预检失败不扣行动、资源、冷却。通过公共动作入口支付后才执行生成。执行会重查状态和实际实体碰撞，并尊重 NeoForge EntityJoinLevelEvent 取消。生成事件取消或执行期间状态变化导致失败，会清理候选实体，但已付费用不自动退回，与其他效果的执行期失败规则一致。Java 调用者应检查返回结果。

## Java API

`CombatEffects.BattleAccess` 新增：

```java
var request = new CombatSummons.Request(
    ResourceLocation.parse("minecraft:husk"), position, 300);
String reason = context.battle().previewSummon(request); // null 表示预检通过
CombatSummons.Result result = context.battle().summon(request);
// result.accepted()、result.entity()（UUID）、result.reason()
```

preview 可在 validate 中调用；summon 仅允许当前付费效果或托管回调调用，过期/预览上下文拒绝生成。接口不再次扣行动或资源。延迟召唤可在 afterChecked 的预检中调用 previewSummon，到期授权回调中再调用 summon；出生点变化可能使它失败。

本版创建实体构造器提供的基础单位，不调用原版 finalizeSpawn：不随机生成装备、骑乘者/坐骑或生成难度变种。需要依赖复杂出生初始化的第三方生物，须进一步适配，不能只因有 AI 定义就宣称完整兼容。现有自然生成与唤魔者等专用召唤流程不变。

## 临时单位边界

- 不掉落物品与经验、不拾取掉落物，避免重复召唤刷取资源。
- 不允许通过本通用接口或既有恼鬼/蠹虫召唤动作继续召唤；临时史莱姆分裂被取消。任意自定义 mcfunction/第三方效果仍应遵守接口契约，不直接通过外部命令生成脱管单位。
- 有独立 AV 寿命表，不占用可取消技能任务队列，因此取消某个技能不会意外留下无限寿命单位。
- 不保存战斗进度；若临时实体曾被存盘，后续从磁盘加载时拒绝加入世界，避免重启后变成永久敌人。本规则依赖保留的 mineturn:temporary_summon 标签，不代表通用战斗持久化已完成。
- 目前复用已有行动队列显示，尚无专门寿命倒计时界面；不提供地面点选召唤 UI、编队指令、永久召唤或自定义装备 NBT。

## 验证

260 项服务器 GameTest 全部通过，完整构建成功（build/summon-actions.log）。真实示例、出生点拒绝与支付、阵营、AV 寿命、预览权限、每人/全场数量上限、死亡释放名额、离场清理、生成取消和掉落/经验/孤儿加载策略已验证；客户端召唤显示和联机体验仍需实测。
