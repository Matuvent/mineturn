# MineTurn

**English** | [中文](README.zh_cn.md)

**Turn-based tactical combat for Minecraft 1.21.1 / NeoForge 21.1.251 / Java 21.**

MineTurn replaces vanilla Minecraft's real-time combat with a turn-based system: turn order is driven by an
**action-value (AV) timeline**, every turn grants spendable movement and main/bonus actions, and *all* combat
content — actions, mob AI, item mappings — is defined by **data packs**.

> Status: a working, feature-complete prototype. 287 server integration tests pass.
> Please read [Known Defects & Security](#known-defects--security) before running this on a public server.

---

## What it is

Attack an ordinary mob in survival mode and combat begins: the game switches to a third-person tactical camera
and opens a transparent combat GUI. Combat stops being about who clicks fastest and becomes about
*where to step, who to hit, and which action to spend*.

- **AV timeline** — every participant accumulates action value based on agility; the UI forecasts the next 16 turns
- **Action economy** — each turn grants a pool of movement plus main/bonus actions; movement can be spent **in parts**
- **Tactical movement** — walks along real block collision surfaces, steps up one block, settles fall damage with
  vanilla rules, routes around obstacles, and supports full 3D underwater movement
- **Engagement control** — entering an enemy's reach locks further movement until you spend an action to disengage
- **Multiplayer** — several players share one timeline against multiple mobs; nearby hostiles join as reinforcements

## Combat rules

### Turn order

Each participant holds 10,000 action distance; the time until their next turn is `distance / agility`.
The scheduler advances straight to the next event — it does not wait on wall-clock time. Simultaneous events are
resolved in stable join order. A mob's effective agility is `entity agility attribute × datapack agility / 100`.

### Per-turn budget

| Resource | Default | Notes |
| --- | --- | --- |
| Movement | 4 blocks (cap 12) | Scales with the movement-speed attribute; charged by real horizontal path length, spendable in parts |
| Main actions | 1 | Attack, sprint, disengage, flee |
| Bonus actions | 1 | Lightweight actions defined by the data pack |

Counts are adjustable per entity via the `mineturn:main_actions` / `mineturn:bonus_actions` attributes and the
skill interface.

### Mechanics

- **Sprint** costs one main action and doubles *remaining* movement
- **Disengage** costs one main action and lifts this turn's engagement lock (sprint cannot substitute for it)
- **Flee** requires ≥ 10 blocks of collision-box distance from every enemy, and only removes the fleeing player
- **Ranged timing** — bows and crossbows are not instant hits. You press inside a window that narrows with
  distance; a miss still consumes the action and the ammunition
- **Shield guard** blocks the next attack then expires; shields run on their own cooldown clock
- **Status timeline** — potions, burning, oxygen, hunger and freezing are all converted onto the AV clock
  instead of real ticks

## Data-pack driven

Everything about combat lives in data packs. You can add actions, mob AI and item mappings without touching code.

```text
data/<namespace>/mineturn/actions/<name>.json      # one combat action
data/<namespace>/mineturn/mobs/<name>.json         # one mob AI definition
data/<namespace>/mineturn/items/<name>.json        # item -> action mapping
data/<namespace>/mineturn/grants/<name>.json       # extra actions granted by gear/state
data/<namespace>/function/.../*.mcfunction         # function-AI callbacks
```

Shipped content (any of it can be overridden or extended by a data pack):

| Content | Count |
| --- | --- |
| Combat actions | 58 |
| Mob AI definitions | 33 |
| Item mappings | 16 |
| Registered effect types (programmable) | 51 |
| Vanilla-function AI (`.mcfunction`) | 27 |

### Two kinds of mob AI

1. **State machine** — `approach` / `weighted_action` / `wait` behaviours with `in_reach` / `out_of_reach`
   transitions. Enough for most melee and ranged mobs.
2. **Vanilla-function AI** — write callbacks in real `.mcfunction` files (`on_enter` / `on_turn` /
   `on_move_finished` / `on_action_resolved` / `on_leave`), with dedicated combat commands
   (`/ai target`, `/ai use`, `/ai move`, `/ai schedule`, `/ai query`, …), macro parameters and AV-scheduled
   functions. Used for boss fights and complex branching.

Both run under built-in summoning constraints — for example an Evoker summons at most 3 Vex and only one alive
Vex is kept on the field at a time (`BattleRaid.java:11-12`), so summons cannot flood the timeline.

Data packs are **validated as a whole** on load: effect types, field ranges, action references, state transition
targets, function entry points and macro arguments. A failed validation **keeps the previous working rule set**
rather than letting the server run on half a definition.

## Building

Requires **JDK 21**.

```bash
./gradlew build              # build the jar
./gradlew runClient          # launch a client with the mod
./gradlew runGameTestServer  # run server integration tests (289 tests, ~2 min)
```

On Windows use `gradlew.bat`.

### `git push` fails with "Connection was reset"?

On some networks (notably direct connections from mainland China), `git push` reports:

```text
fatal: unable to access 'https://github.com/...': Recv failure: Connection was reset
```

while `git ls-remote` (a read operation) works fine and github.com loads in a browser. That is
interference with the **authenticated POST transport**, not a problem with your account or the
repository. These two settings make it reliable (`HTTP/1.1` is the actual fix):

```bash
git config http.version HTTP/1.1
git config http.postBuffer 524288000
```

If it still fails, switch the remote to SSH (`git@github.com:Matuvent/mineturn.git`) or configure a local proxy.

## Playing

In survival mode, hit an ordinary mob that you do not one-shot. Left-drag rotates the camera, the scroll wheel
zooms, the bottom row of buttons maps to your hotbar, and the bottom-right corner offers
sprint / move / disengage / flee / end turn. **Player turns have no countdown** — you must click "end turn" to
hand over your remaining resources. Enemies act automatically.

Fallback command entry points:

| Command | Purpose |
| --- | --- |
| `/mineturn` | Show the current actor, battle time, distance and item actions |
| `/mineturn attack` | Use the `mineturn:melee` action of the held item (works unarmed) |
| `/mineturn use <slot> <action>` | Use a specific action from an inventory slot |
| `/mineturn move <dx> <dz>` | Move by a relative offset |
| `/mineturn sprint` / `retreat` / `flee` / `end` | Spend the turn's resources |
| `/mineturn abort` | Admin escape hatch (permission level 2) |

## Extension API

`api/CombatEffects.java` exposes a registerable effect executor:

```java
CombatEffects.register(ResourceLocation.parse("mymod:my_effect"), new CombatEffects.Effect() {
    @Override public void validateDefinition(CombatData.Action action) { /* validate params at load time */ }
    @Override public String validate(CombatEffects.Context ctx) { return null; }   // side-effect-free preflight
    @Override public void execute(CombatEffects.Context ctx) { /* runs once after commit */ }
});
```

`Context` provides the caster, target, item, action definition, current AV time, and a managed `BattleAccess`:
area fields, summons, teleports, delayed settlement (`after` / `afterChecked`), forced displacement, damage
authorization, and more. Every mutating method re-validates that the context is still live, so an expired skill
cannot take effect after combat ends.

When integrating with other mods, `BattleManager.locked(entity)` lets you block out-of-turn casting and
`BattleManager.battleTime(entity)` reads the current battle time (returns `NaN` when not in combat).

## Example data packs

`examples/` contains 12 installable example packs covering function AI, batch AI templates and overrides,
spatial/flying bosses, delayed actions, field actions, traps and devices, summon actions, accessories and
granted actions, and item adapters:

```bash
./gradlew functionAiExamplePack fieldActionExamplePack spatialBossExamplePack
# output lands in build/examples/
```

## Known Defects & Security

This section is **deliberately public**. MineTurn is a prototype, and it is **not hardened for adversarial
multiplayer**. Current open issues, with exact locations:

| Severity | Location | Issue |
| --- | --- | --- |
| (no open Major items) | — | The ranged window is now server-randomized (both its position on the bar and the pre-press lead-in are chosen by the server, and the client only reacts), so the hit no longer depends on the client's packet timing. |

Additional stated limits:

- **No combat persistence.** No offline bodies and no reconnect recovery; disconnecting removes you from combat.
- **No PvP faction system, no battle merging, and no full terrain pathfinding.**
- **Client-side code has zero automated coverage.** `BattleClient`, `BattleScreen`, the renderers and
  `ProjectileAnimations` are never executed by `runGameTestServer`, which is a dedicated server.
  Camera behaviour, motion smoothing and mouse feel can only be verified in-game.
- The battle camera is an orbital observer with wall collision near the battlefield, not unrestricted flight.
- The battlefield terrain is not protected; interference from other players, fluids and pistons is not fully isolated.
- Leaving combat fully restores vanilla behaviour. `NoAI`, game mode and player abilities are never permanently modified.

A fuller review — including several suspected issues that were investigated and **confirmed to be correct** —
is in [`docs/CODE_REVIEW_2026_10_07.md`](docs/CODE_REVIEW_2026_10_07.md) (Chinese).

> **Do not rely on this mod for competitive or anti-cheat-sensitive multiplayer.** The fixes above are in progress.

## Documentation

| Document | Contents |
| --- | --- |
| [Prototype overview](docs/PROTOTYPE.md) | Overall design, playtest notes, prototype boundaries |
| [Function AI](docs/FUNCTION_AI.md) · [Batch AI](docs/BATCH_AI.md) | `.mcfunction` callbacks, commands, template inheritance and selectors |
| [Mobility & skills](docs/MOBILITY_AND_SKILLS.md) | Flight, 3D underwater movement, boss parameters, prepaid AV skills |
| [Ranged combat](docs/RANGED_COMBAT.md) | Bow/crossbow timing, distance difficulty, ammunition consumption |
| [AV statuses](docs/AV_STATUSES.md) | Potions, burning, oxygen and hunger on the logical clock |
| [Mounted combat](docs/MOUNTED_COMBAT.md) | Riders, mounts and displacement |
| [Datapack spec](docs/DATAPACK_EXTENSION_SPEC.md) | Fields, priority and override rules |
| [Vanilla coverage](docs/VANILLA_COVERAGE.md) | Which vanilla mobs and items are adapted |
| [Task list](docs/TASKS.md) | Development progress |

Most documents are in Chinese.

## License

Copyright (C) 2026 Matuvent

Licensed under the **GNU Lesser General Public License v2.1** (`LGPL-2.1-only`).
See [LICENSE](LICENSE) for the full text.

In short (**the [LICENSE](LICENSE) text governs**):

**You may**
- use, modify and redistribute MineTurn freely
- include it in modpacks, **including closed-source and monetised modpacks** — this project imposes no extra restriction
- write extension mods against the MineTurn API and license them however you like (including closed source)
- use MineTurn as a dependency in your own project

**You must**
- if you **modify MineTurn's own source** and distribute it (as a jar or as source), release your modifications under LGPL-2.1
- keep the copyright notice and license text
- not remove or alter the license notices

**In one line:** modpack authors can use it freely, keep the pack closed, and monetise it.
Anyone who changes MineTurn's own code and distributes it must open-source those changes so improvements flow back.

> Note that LGPL's copyleft applies only to **this mod itself**. Bundling MineTurn into a modpack is
> aggregation and does not place the pack under LGPL — a modpack incurs no open-source obligation by
> including MineTurn.

### Modpack authors

You are **explicitly welcome** to include MineTurn in your modpack; no permission needed.
Crediting the mod name and source is appreciated, but not required.

> Using Minecraft mods is additionally subject to Mojang's
> [Minecraft EULA](https://www.minecraft.net/en-us/eula) and
> [Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).
> That is separate from this license and is your own responsibility.

### Third-party assets

`TEMPLATE_LICENSE.txt` is the MIT license shipped with the NeoForge MDK template (copyright NeoForged,
applying only to the template files). It is unrelated to this mod's license and is left as-is.
Any textures or sounds under `assets/mineturn/` taken from other projects remain under their authors' terms
and are not covered by this license.

Network protocol is now **22**. Update client and server together. Motion packets use monotonic sequence numbers; full battle snapshots also carry the current motion state to reject stale position updates. Action performances carry a sequence and a priority, so attacks interrupt an in-progress cut while minor actions queue behind it. Cleanup/reload error recovery and callback-overflow notices have also been fixed; see the review follow-up record.
