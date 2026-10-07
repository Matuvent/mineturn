package com.matuvent.mineturn.test;

import com.google.gson.JsonParser;
import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.battle.BattleManager;
import com.matuvent.mineturn.battle.Timeline;
import com.matuvent.mineturn.battle.TurnBudget;
import com.matuvent.mineturn.battle.ApproachGate;
import com.matuvent.mineturn.data.CombatData;
import com.matuvent.mineturn.data.CombatItem;
import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class BattleGameTests {
    @GameTest(template = "empty")
    public static void timeline(GameTestHelper helper) {
        Timeline<String> clock = new Timeline<>();
        clock.add("player"); clock.add("zombie");
        String[] actors = {"zombie", "player", "zombie", "zombie", "player"};
        double[] times = {62.5, 100, 125, 187.5, 200};
        for (int i = 0; i < actors.length; i++) {
            helper.assertTrue(clock.next(id -> id.equals("player") ? 100 : 160).equals(actors[i]), "Incorrect actor " + i);
            helper.assertTrue(Math.abs(clock.time() - times[i]) < 1e-6, "Incorrect time " + i);
        }
        Timeline<String> changed = new Timeline<>(); changed.add("a"); changed.add("b");
        changed.next(id -> id.equals("a") ? 200 : 100);
        helper.assertTrue(changed.remaining("b") == 5000, "Partial progress lost");
        helper.assertTrue(changed.next(id -> id.equals("a") ? 100 : 200).equals("b") && changed.time() == 75, "Agility change resets progress");
        Timeline<String> ties = new Timeline<>(); ties.add("a"); ties.add("b");
        helper.assertTrue(ties.next(id -> 100).equals("a") && ties.next(id -> 100).equals("b") && ties.time() == 100, "Tie ordering/time");
        Timeline<String> preview = new Timeline<>(); preview.add("player"); preview.add("zombie");
        var queue = preview.forecast(5, id -> id.equals("player") ? 100 : 160);
        for (int i = 0; i < actors.length; i++) {
            helper.assertTrue(queue.get(i).actor().equals(actors[i]) && Math.abs(queue.get(i).inAv() - times[i]) < 1e-6, "Queue prediction differs from actual schedule");
        }
        helper.assertTrue(preview.time() == 0 && preview.remaining("player") == 10000, "Queue preview mutated the battle");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void budgets(GameTestHelper helper) {
        TurnBudget budget = new TurnBudget(4);
        helper.assertTrue(budget.canMove(), "New turn needs one movement opportunity");
        budget.disengage();
        helper.assertTrue(budget.canMove() && budget.disengaged() && !budget.canAct(), "Retreat cost incorrect");
        budget.move(4);
        helper.assertFalse(budget.canMove(), "Second move allowed");
        TurnBudget separate = new TurnBudget(4); separate.move(1);
        helper.assertTrue(separate.canAct(), "Movement consumed action");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void definitions(GameTestHelper helper) {
        var snapshot = CombatData.current;
        helper.assertTrue(snapshot.actions().get("mineturn:bite").amount() == 6, "Default actions not loaded");
        var brain = snapshot.mobs().get("minecraft:zombie");
        helper.assertTrue(brain.states().get("attack").choices().getFirst().weight() == 70, "Weights not loaded");
        try {
            CombatData.parse(Map.of(ResourceLocation.parse("test:actions/bad"), JsonParser.parseString("""
                {"name":"invalid","effect":"mineturn:damage","amount":-4,"range":2}
                """)));
            throw new AssertionError("Invalid damage accepted");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().contains("test:actions/bad"), "Error lacks file ID");
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void combatAndLocks(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 3);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            helper.assertTrue(BattleManager.locked(player) && BattleManager.locked(mob), "Nonlethal hit did not start combat");
            helper.assertTrue(BattleManager.battleTime(player) == 0, "Opening turn advanced clock");
            float health = mob.getHealth();
            mob.invulnerableTime = 0;
            mob.hurt(player.damageSources().playerAttack(player), 5);
            helper.assertTrue(mob.getHealth() == health, "Unauthorized damage accepted");
            var start = player.position();
            player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(start.x + 10, start.y, start.z, true));
            helper.assertTrue(player.position().equals(start), "Movement packet escaped battle lock");
            helper.assertTrue(command(player, "mineturn move -1 0") == 0, "Engaged player moved without retreat");
            helper.assertTrue(command(player, "mineturn retreat") == 1, "Retreat failed");
            helper.assertTrue(command(player, "mineturn attack") == 0, "Retreat did not consume main action");
            helper.assertTrue(command(player, "mineturn move -1 0") == 1, "Retreat movement failed");
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(command(player, "mineturn move -1 0") == 1, "Remaining retreat movement rejected");
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(command(player, "mineturn end") == 1, "End failed");
            helper.assertTrue(BattleManager.battleTime(player) == 100, "Wrong action time");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void itemComponentsAndCosts(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 3);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        player.getInventory().setItem(0, sword);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            sword.set(MineTurn.COMBAT.get(), new CombatItem(false, List.of("mineturn:melee"), 2.5));
            helper.assertTrue(command(player, "mineturn attack") == 0, "Explicit disable did not override datapack");
            sword.remove(MineTurn.COMBAT.get());
            float before = mob.getHealth();
            helper.assertTrue(command(player, "mineturn use 0 mineturn:melee") == 1, "Namespaced item action failed");
            helper.assertTrue(mob.getHealth() < before, "Attack did no damage");
            helper.assertTrue(command(player, "mineturn attack") == 0, "Repeated main action accepted");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void lethalHitSkipsBattle(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 3);
        try {
            mob.hurt(player.damageSources().playerAttack(player), 1000);
            helper.assertFalse(BattleManager.locked(player), "Lethal hit started combat");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void guiRequestsAreValidated(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 8);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            var state = BattleManager.snapshot(player);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                BattleNetwork.State.CODEC.encode(buffer, state);
                helper.assertTrue(BattleNetwork.State.CODEC.decode(buffer).equals(state), "GUI state codec did not round trip");
            } finally { buffer.release(); }
            helper.assertTrue(state.slots().size() == 10, "UI must map nine hotbar slots plus the offhand shield");
            Vec3 start = player.position();
            helper.setBlock(3, 1, 2, Blocks.STONE); helper.setBlock(3, 2, 2, Blocks.STONE);
            helper.setBlock(4, 1, 2, Blocks.STONE); helper.setBlock(4, 2, 2, Blocks.STONE);
            BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "move", 0, "", -1,
                    start.add(2, 0, 0), 0));
            helper.assertTrue(player.position().equals(start) && BattleManager.snapshot(player).canMove(), "Blocked landing moved player or consumed movement");
            var move = new BattleNetwork.Request(state.battle(), state.revision(), "move", 0, "", -1, start.add(0, 0, 2), 0);
            BattleManager.request(player, move);
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(player.position().distanceToSqr(start.add(0, 0, 2)) < 0.001, "Legal GUI movement failed");
            var after = BattleManager.snapshot(player);
            helper.assertTrue(after.canMove() && after.canAct() && Math.abs(after.movement() - 2) < 1e-5, "GUI movement budget incorrect");
            BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "end", 0, "", -1, Vec3.ZERO, 0));
            helper.assertTrue(BattleManager.battleTime(player) == 0, "Stale request ended a later state");
            BattleManager.request(player, new BattleNetwork.Request(after.battle(), after.revision(), "use", 99, "mineturn:melee", mob.getId(), Vec3.ZERO, 0));
            helper.assertTrue(BattleManager.snapshot(player).canAct(), "Invalid GUI slot consumed an action");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    private static void moveTo(ServerPlayer player, Vec3 target) {
        var state = BattleManager.snapshot(player);
        BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "move", 0, "", -1, target, 0));
        com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
    }
    @GameTest(template = "empty")
    public static void sprintAndSplitMovement(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 8);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            helper.assertTrue(command(player, "mineturn move 0 1") == 1, "First partial move failed");
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(Math.abs(BattleManager.snapshot(player).movement() - 3) < 1e-5, "Distance not deducted");
            helper.assertTrue(command(player, "mineturn sprint") == 1, "Sprint failed");
            var state = BattleManager.snapshot(player);
            helper.assertTrue(Math.abs(state.movement() - 6) < 1e-5 && !state.canAct(), "Sprint did not double remaining distance or spend action");
            helper.assertTrue(command(player, "mineturn sprint") == 0, "Repeated sprint accepted");
            helper.assertTrue(command(player, "mineturn move 0 3") == 1, "First split move rejected");
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(command(player, "mineturn move 3 0") == 1, "Second split move rejected");
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(!BattleManager.snapshot(player).canMove(), "Movement overspent");
            helper.assertTrue(command(player, "mineturn move 0 0.1") == 0, "Zero-budget move accepted");
            command(player, "mineturn end");
            helper.assertTrue(Math.abs(BattleManager.snapshot(player).movement() - 4) < 1e-5, "Next turn retained sprint budget");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void stepsAndHeadroom(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 9);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            Vec3 start = player.position();
            helper.setBlock(3, 1, 2, Blocks.STONE);
            helper.setBlock(2, 3, 2, Blocks.STONE); helper.setBlock(3, 3, 2, Blocks.STONE);
            moveTo(player, start.add(1, 1, 0));
            helper.assertTrue(player.position().equals(start), "Step passed through low ceiling");
            helper.setBlock(2, 3, 2, Blocks.AIR); helper.setBlock(3, 3, 2, Blocks.AIR);
            moveTo(player, start.add(1, 1, 0));
            helper.assertTrue(player.position().distanceToSqr(start.add(1, 1, 0)) < 0.001, "One-block step failed");
            moveTo(player, start.add(2, 0, 0));
            helper.assertTrue(player.position().distanceToSqr(start.add(2, 0, 0)) < 0.001, "Step down failed");
            helper.setBlock(5, 1, 2, Blocks.STONE); helper.setBlock(5, 2, 2, Blocks.STONE);
            moveTo(player, start.add(3, 2, 0));
            helper.assertTrue(player.position().distanceToSqr(start.add(2, 0, 0)) < 0.001, "Two-block wall climbed");
            helper.assertTrue(Math.abs(BattleManager.snapshot(player).movement() - 2) < 1e-5 && BattleManager.snapshot(player).canAct(), "Invalid path spent resources");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void highDropUsesVanillaDamage(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 9);
        helper.setBlock(2, 6, 2, Blocks.STONE);
        Vec3 floor = player.position();
        player.teleportTo(floor.x, floor.y + 6, floor.z);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        helper.runAfterDelay(70, () -> {
            try {
                moveTo(player, floor.add(2, 0, 0));
                helper.assertTrue(player.position().distanceToSqr(floor.add(2, 0, 0)) < 0.001, "High ledge drop failed: " + player.position() + " expected " + floor.add(2, 0, 0) + " / " + BattleManager.snapshot(player).message());
                helper.assertTrue(player.getHealth() == 17, "Six-block fall must cause three HP damage");
                helper.assertTrue(Math.abs(BattleManager.snapshot(player).movement() - 2) < 1e-5, "Drop incorrectly consumed vertical movement distance");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void airborneEntryKeepsFallDistance(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 9);
        Vec3 floor = player.position();
        helper.runAfterDelay(70, () -> {
            try {
                player.teleportTo(floor.x, floor.y + 6, floor.z); player.fallDistance = 2;
                mob.invulnerableTime = 0;
                helper.assertTrue(mob.hurt(player.damageSources().playerAttack(player), 1), "Test initiating hit was rejected");
                helper.assertTrue(player.position().distanceToSqr(floor) < 0.001, "Airborne entry hovered: " + player.position() + " expected " + floor);
                helper.assertTrue(player.getHealth() == 15, "Entry discarded accumulated falling distance");
                helper.assertTrue(Math.abs(BattleManager.snapshot(player).movement() - 4) < 1e-5 && BattleManager.snapshot(player).canAct(), "Automatic gravity spent resources");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void removedSupportDropsPlayer(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 9);
        helper.setBlock(2, 6, 2, Blocks.STONE);
        Vec3 floor = player.position();
        player.teleportTo(floor.x, floor.y + 6, floor.z);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        helper.runAfterDelay(70, () -> helper.setBlock(2, 6, 2, Blocks.AIR));
        helper.runAfterDelay(74, () -> {
            try {
                helper.assertTrue(player.position().distanceToSqr(floor) < 0.001 && player.getHealth() == 17, "Missing support did not resolve gravity exactly once");
                helper.assertTrue(Math.abs(BattleManager.snapshot(player).movement() - 4) < 1e-5, "Gravity consumed movement");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void slowFallingPreventsEntryDamage(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 9);
        Vec3 floor = player.position();
        helper.runAfterDelay(70, () -> {
            try {
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 200));
                player.teleportTo(floor.x, floor.y + 6, floor.z);
                mob.invulnerableTime = 0;
                helper.assertTrue(mob.hurt(player.damageSources().playerAttack(player), 1), "Test initiating hit was rejected");
                helper.assertTrue(player.position().distanceToSqr(floor) < 0.001 && player.getHealth() == 20, "Slow falling did not prevent automatic fall damage");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void lethalFallReleasesBattle(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 9);
        Vec3 floor = player.position();
        helper.runAfterDelay(70, () -> {
            try {
                player.teleportTo(floor.x, floor.y + 30, floor.z);
                mob.invulnerableTime = 0;
                helper.assertTrue(mob.hurt(player.damageSources().playerAttack(player), 1), "Test initiating hit was rejected");
                helper.assertFalse(player.isAlive(), "Lethal fall was ignored");
                helper.assertTrue(!BattleManager.locked(player) && !BattleManager.locked(mob), "Fall death left battle participants locked");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    private static net.minecraft.world.entity.monster.Husk functionMob(GameTestHelper helper, int x) {
        var mob = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(x, 1, 2));
        mob.setBaby(false);
        return mob;
    }
    private static int score(net.minecraft.world.entity.Entity entity, String name) {
        var board = entity.getServer().getScoreboard();
        return board.getOrCreatePlayerScore(entity, Objects.requireNonNull(board.getObjective(name))).get();
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void functionCallbacksAndBudgets(GameTestHelper helper) {
        ServerPlayer player = player(helper); var mob = functionMob(helper, 3);
        player.getInventory().setItem(0, new ItemStack(Items.STICK));
        player.getFoodData().setFoodLevel(10);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        helper.assertTrue(mob.getTags().contains("mt_entered") && score(mob, "mt_param") == 7, "Entry function/macros not called as the mob");
        helper.assertTrue(BattleManager.snapshot(player).slots().getFirst().actions().getFirst().id().equals("mineturn_test:strike"), "External item/action mapping was not loaded");
        helper.assertTrue(command(player, "mineturn ai use mineturn_test:strike") == 0, "Player forged an AI invocation");
        helper.runAfterDelay(70, () -> {
            command(player, "mineturn end");
            helper.assertTrue(score(mob, "mt_timer") == 25000, "Scheduled function did not run at 25 AV");
            helper.assertTrue(score(mob, "mt_test") == 0 && player.getHealth() == 20, "AV callback got a free main action");
            command(player, "mineturn end");
        });
        helper.runAfterDelay(86, () -> {
            try {
                helper.assertTrue(player.getHealth() == 16, "Function attack missing or executed twice");
                helper.assertTrue(score(mob, "mt_first") == 1 && score(mob, "mt_second") == 0, "Main action budget not enforced");
                helper.assertTrue(score(mob, "mt_spoof") == 0, "execute as another entity bypassed actor binding");
                helper.assertTrue(mob.getTags().contains("mt_resolved") && score(mob, "mt_test") == 1, "Action resolved callback missing");
                command(player, "mineturn abort");
                helper.assertTrue(mob.getTags().contains("mt_left"), "Leave callback missing");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void functionMovementAndWait(GameTestHelper helper) {
        ServerPlayer player = player(helper); var mob = functionMob(helper, 8); mob.addTag("mt_walk");
        Vec3 before = mob.position();
        mob.hurt(player.damageSources().playerAttack(player), 1);
        command(player, "mineturn end"); command(player, "mineturn end");
        helper.runAfterDelay(16, () -> {
            try {
                helper.assertTrue(mob.position().distanceToSqr(before.add(0, 0, 3)) < 0.001, "Function partial moves/sprint failed");
                helper.assertTrue(Math.abs(score(mob, "mt_test") - 4000) <= 1, "Function movement budget incorrect");
                helper.assertTrue(score(mob, "mt_second") == 0, "Move after wait was accepted");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void functionRecursionIsBounded(GameTestHelper helper) {
        ServerPlayer player = player(helper); var mob = functionMob(helper, 3); mob.addTag("mt_loop");
        mob.hurt(player.damageSources().playerAttack(player), 1);
        command(player, "mineturn end"); command(player, "mineturn end");
        helper.runAfterDelay(16, () -> {
            try {
                helper.assertTrue(BattleManager.battleTime(player) == 200 && BattleManager.snapshot(player).actorId() == player.getId(), "Recursive function stalled the timeline");
                helper.assertTrue(player.getHealth() == 20, "Failed AI damaged its target");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void dataPrioritiesAndMissingFunctions(GameTestHelper helper) {
        var one = ResourceLocation.parse("base:mobs/husk");
        var two = ResourceLocation.parse("addon:mobs/husk");
        var files = new HashMap<ResourceLocation, com.google.gson.JsonElement>();
        files.put(one, JsonParser.parseString("""
            {"entity":"minecraft:husk","ai":{"on_turn":"test:missing"}}
            """));
        files.put(two, JsonParser.parseString("""
            {"entity":"minecraft:husk","priority":10,"agility":160,"ai":{"on_turn":"test:missing"}}
            """));
        var parsed = CombatData.parse(files);
        helper.assertTrue(parsed.mobs().get("minecraft:husk").agility() == 160 && parsed.sources().get("mobs/minecraft:husk").equals(two.toString()), "Explicit mapping priority not honored");
        try {
            CombatData.validateFunctions(parsed, ignored -> null, helper.getLevel().getServer().getCommands().getDispatcher());
            throw new AssertionError("Missing function accepted");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().contains("ai.on_turn"), "Function error lacks field path");
        }
        files.put(one, files.get(two));
        try { CombatData.parse(files); throw new AssertionError("Equal-priority duplicate mapping accepted"); }
        catch (IllegalArgumentException expected) { helper.assertTrue(expected.getMessage().contains("Conflicting"), "Wrong conflict error"); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void batchSelectorsAndTemplates(GameTestHelper helper) {
        var files = new HashMap<ResourceLocation, com.google.gson.JsonElement>();
        files.put(ResourceLocation.parse("test:ai_templates/base"), JsonParser.parseString("""
            {"agility":100,"ai":{"on_turn":"test:turn","on_leave":"test:leave","parameters":{"damage":4,"old":1}}}
            """));
        files.put(ResourceLocation.parse("test:ai_templates/child"), JsonParser.parseString("""
            {"extends":"test:base","agility":150,"ai":{"on_leave":null,"parameters":{"damage":6,"old":null}}}
            """));
        files.put(ResourceLocation.parse("test:mobs/broad"), JsonParser.parseString("""
            {"match":{"namespaces":["minecraft"],"exclude":["minecraft:pig"]},"template":"test:base"}
            """));
        files.put(ResourceLocation.parse("test:mobs/tag"), JsonParser.parseString("""
            {"match":{"entity_tags":["test:group"]},"template":"test:child"}
            """));
        files.put(ResourceLocation.parse("test:mobs/exact"), JsonParser.parseString("""
            {"entity":"minecraft:husk","enabled":false}
            """));
        files.put(ResourceLocation.parse("test:mobs/optional"), JsonParser.parseString("""
            {"match":{"entities":["absent:mob"],"entity_tags":["absent:tag"],"namespaces":["absent"]},"optional":true}
            """));
        var tags = Map.of(ResourceLocation.parse("test:group"), Set.of("minecraft:husk", "minecraft:drowned"));
        var parsed = CombatData.parse(files, tags);
        helper.assertTrue(!parsed.mobs().containsKey("minecraft:husk") && !parsed.mobs().containsKey("minecraft:pig"), "Disabled/excluded mappings were retained");
        helper.assertTrue(parsed.sources().get("mobs/minecraft:husk").equals("test:mobs/exact"), "Exact selector did not beat tag selector");
        var brain = parsed.mobs().get("minecraft:drowned");
        helper.assertTrue(brain.agility() == 150 && brain.functions().parameters().getInt("damage") == 6
                && !brain.functions().parameters().contains("old") && !brain.functions().callbacks().containsKey("on_leave"), "Template merge/removal failed");
        files.put(ResourceLocation.parse("test:mobs/broad"), JsonParser.parseString("""
            {"match":{"namespaces":["minecraft"]},"template":"test:base","priority":1}
            """));
        helper.assertTrue(CombatData.parse(files, tags).mobs().get("minecraft:husk").agility() == 100, "Explicit priority did not beat specificity");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void invalidTemplateInheritance(GameTestHelper helper) {
        var files = new HashMap<ResourceLocation, com.google.gson.JsonElement>();
        files.put(ResourceLocation.parse("test:ai_templates/a"), JsonParser.parseString("{\"extends\":\"test:b\"}"));
        files.put(ResourceLocation.parse("test:ai_templates/b"), JsonParser.parseString("{\"extends\":\"test:a\"}"));
        try { CombatData.parse(files); throw new AssertionError("Template cycle accepted"); }
        catch (IllegalArgumentException expected) { helper.assertTrue(expected.getMessage().contains("cycle"), "Missing cycle diagnostic"); }
        files.clear();
        for (int i = 0; i < 9; i++) files.put(ResourceLocation.parse("test:ai_templates/t" + i),
                JsonParser.parseString(i == 8 ? "{}" : "{\"extends\":\"test:t" + (i + 1) + "\"}"));
        try { CombatData.parse(files); throw new AssertionError("Overlong template chain accepted"); }
        catch (IllegalArgumentException expected) { helper.assertTrue(expected.getMessage().contains("8 levels"), "Missing depth diagnostic"); }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void inheritedMeleeTemplateExecutes(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        var mob = helper.spawnWithNoFreeWill(EntityType.DROWNED, new BlockPos(3, 1, 2));
        mob.setBaby(false); player.getFoodData().setFoodLevel(10);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        helper.runAfterDelay(70, () -> {
            command(player, "mineturn end"); command(player, "mineturn end");
        });
        helper.runAfterDelay(86, () -> {
            try {
                helper.assertTrue(player.getHealth() == 16, "Inherited built-in melee function did not use its action parameter");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", batch = "function_reload", timeoutTicks = 200)
    public static void datapackOverridesAndReloadCleanup(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var original = new ArrayList<>(server.getPackRepository().getSelectedIds());
        // GameTest auto-enables discovered packs, including this fixture left by a previous run.
        original.remove("file/mineturn-ai-override-test");
        ServerPlayer player = player(helper); var mob = functionMob(helper, 3);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        var pack = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR).resolve("mineturn-ai-override-test").toAbsolutePath().normalize();
        try {
            writeTestResource(pack, "pack.mcmeta", "{\"pack\":{\"pack_format\":48,\"description\":\"temporary GameTest override\"}}");
            writeTestResource(pack, "data/mineturn_test/mineturn/actions/strike.json", "{\"name\":\"overridden\",\"effect\":\"mineturn:damage\",\"amount\":9,\"range\":2}");
            writeTestResource(pack, "data/mineturn_test/mineturn/items/stick.json", "{\"items\":[\"minecraft:stick\"],\"combat\":{\"enabled\":false,\"actions\":[],\"melee_range\":2}}");
            writeTestResource(pack, "data/mineturn_test/function/turn.mcfunction", "tag @s add mt_overridden\nreturn run mineturn ai wait\n");
            writeTestResource(pack, "data/mineturn_test/tags/entity_type/template_group.json", "{\"replace\":true,\"values\":[\"minecraft:stray\"]}");
            writeTestResource(pack, "data/mineturn_test/mineturn/ai_templates/heavy.json", "{\"extends\":\"mineturn:melee\",\"agility\":170,\"ai\":{\"parameters\":{\"action\":\"mineturn_test:strike\"}}}");
            server.getPackRepository().reload();
            var selected = new ArrayList<>(original); selected.remove("file/mineturn-ai-override-test"); selected.add("file/mineturn-ai-override-test");
            server.reloadResources(selected).join();
            helper.assertTrue(CombatData.current.mobs().containsKey("minecraft:stray") && CombatData.current.sources().get("mobs/minecraft:drowned").startsWith("mineturn:mobs/drowned ")
                    && CombatData.current.mobs().get("minecraft:stray").agility() == 170, "Reload used stale entity tags or template");
            helper.assertTrue(!BattleManager.locked(player) && mob.getTags().contains("mt_left"), "Reload did not release battle/run old leave function");
            helper.assertTrue(CombatData.current.actions().get("mineturn_test:strike").amount() == 9 && !CombatData.current.items().get("minecraft:stick").enabled(), "Higher pack JSON did not override lower resources");
            helper.assertTrue(CombatData.current.sources().get("actions/mineturn_test:strike").contains("mineturn-ai-override-test"), "Resource provenance not reported");
            // Verify a failed reload cannot publish JSON from a function library that Minecraft rejected.
            writeTestResource(pack, "data/mineturn_test/mineturn/mobs/husk.json", "{\"entity\":\"minecraft:husk\",\"ai\":{\"on_turn\":\"mineturn_test:missing\"}}");
            var accepted = CombatData.current;
            boolean rejected = false;
            try { server.reloadResources(selected).join(); } catch (java.util.concurrent.CompletionException expected) { rejected = true; }
            helper.assertTrue(rejected && CombatData.current == accepted, "Invalid reload published a mixed resource snapshot");
            java.nio.file.Files.delete(pack.resolve("data/mineturn_test/mineturn/mobs/husk.json"));
        } catch (Exception error) {
            try { server.reloadResources(original).join(); } finally { cleanup(player); mob.discard(); }
            throw new RuntimeException(error);
        }
        // Server's reload completion unlocks new battle creation on its task queue.
        helper.runAfterDelay(2, () -> {
            mob.invulnerableTime = 0; mob.hurt(player.damageSources().playerAttack(player), 1);
            command(player, "mineturn end"); command(player, "mineturn end");
        });
        helper.runAfterDelay(18, () -> {
            try {
                helper.assertTrue(mob.getTags().contains("mt_overridden"), "Higher pack mcfunction override was not executed");
            } finally { server.reloadResources(original).join(); cleanup(player); mob.discard(); }
            var restoredStray=CombatData.current.mobs().get("minecraft:stray");
            helper.assertTrue(CombatData.current.mobs().containsKey("minecraft:drowned") && restoredStray!=null
                    && CombatData.current.sources().get("mobs/minecraft:stray").startsWith("mineturn:mobs/skeletons ")
                    && restoredStray.functions().parameters().getString("action").equals("mineturn:skeleton_shoot"),
                    "Built-in skeleton mapping was not restored after removing override pack");
            helper.succeed();
        });
    }
    private static void writeTestResource(java.nio.file.Path root, String relative, String text) throws java.io.IOException {
        var file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid test path");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, text, java.nio.charset.StandardCharsets.UTF_8);
    }
    private static ServerPlayer player(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "mt-test"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        var pos = helper.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }
    @GameTest(template = "empty")
    public static void controlAreaAllowsApproach(GameTestHelper helper) {
        ApproachGate approach = new ApproachGate();
        helper.assertTrue(approach.accept(3, false) && approach.accept(1.4, true) && approach.accept(0.8, true), "Approaching through the boundary was blocked");
        helper.assertFalse(approach.accept(0.9, true), "Passing the closest point should require retreat");
        ApproachGate leave = new ApproachGate(); leave.accept(1.0, true);
        helper.assertFalse(leave.accept(2, false), "Leaving control was allowed without retreat");
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 6);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            var state = BattleManager.snapshot(player);
            Vec3 destination = player.position().add(3, 0, 0);
            BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "move", 0, "", -1, destination, 0));
            com.matuvent.mineturn.battle.BattleExtensionGameTests.finishMovement(player);
            helper.assertTrue(player.position().distanceToSqr(destination) < 0.001, "GUI could not enter the enemy control area");
            helper.assertTrue(BattleManager.snapshot(player).engaged(), "Arrival inside control not recognized");
            float health = mob.getHealth();
            helper.assertTrue(command(player, "mineturn attack") == 1 && mob.getHealth() < health, "Entering range did not preserve the main attack");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void approachInsideControl(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 3);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            Vec3 before = player.position();
            helper.assertTrue(command(player, "mineturn move 0.2 0") == 0, "A new move inside control must require retreat");
            helper.assertTrue(player.position().equals(before) && BattleManager.snapshot(player).canAct(), "Rejected move changed state");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void frozenTravelDecaysWalkAnimation(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 3);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            Vec3 position = player.position();
            player.walkAnimation.setSpeed(1.5f);
            for (int i = 0; i < 24; i++) player.travel(Vec3.ZERO);
            helper.assertTrue(player.walkAnimation.speed() < 0.001, "Frozen travel preserved hurt-induced walking jitter");
            helper.assertTrue(player.position().equals(position), "Animation decay moved the player");
        } finally { cleanup(player); mob.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void reinforcementsKeepTimeline(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 8);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        command(player, "mineturn end");
        Zombie extra = zombie(helper, 6);
        ServerPlayer ally = player(helper);
        var allyPos = helper.absolutePos(new BlockPos(5, 1, 7));
        ally.teleportTo(allyPos.getX() + 0.5, allyPos.getY(), allyPos.getZ() + 0.5);
        helper.runAfterDelay(12, () -> {
            try {
                var state = BattleManager.snapshot(player);
                helper.assertTrue(state.fighters().size() == 4, "Monster/player reinforcements missing from roster");
                helper.assertTrue(BattleManager.snapshot(ally).battle().equals(state.battle()), "Ally joined a different battle");
                helper.assertTrue(state.actorId() == player.getId() && state.time() == 100, "Reinforcement stole a turn or advanced AV");
                helper.assertTrue(state.fighters().stream().filter(f -> f.id() == extra.getId()).findFirst().orElseThrow().nextAv() == 100, "New monster did not start with a full lap");
                helper.assertTrue(state.fighters().stream().filter(f -> f.id() == mob.getId()).findFirst().orElseThrow().nextAv() == 0, "Existing monster lost its ready action");
                helper.assertTrue(command(ally, "mineturn end") == 0 && BattleManager.battleTime(ally) == 100, "Waiting ally advanced someone else's turn");
            } finally { cleanup(player); cleanup(ally); mob.discard(); extra.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void targetSelectionAndPartialVictory(GameTestHelper helper) {
        ServerPlayer player = player(helper); Zombie mob = zombie(helper, 3);
        Zombie extra = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 4));
        extra.setBaby(false); extra.setHealth(1);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        mob.hurt(player.damageSources().playerAttack(player), 1);
        try {
            var state = BattleManager.snapshot(player);
            helper.assertTrue(state.fighters().size() == 3, "Initial nearby monster not recruited");
            float firstHealth = mob.getHealth();
            BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "use", 0, "mineturn:melee", extra.getId(), Vec3.ZERO, 0));
            helper.assertFalse(extra.isAlive(), "Selected secondary target was not attacked");
            helper.assertTrue(mob.getHealth() == firstHealth && BattleManager.locked(player), "Killing one enemy ended the whole battle or hit wrong target");
            helper.assertTrue(BattleManager.snapshot(player).fighters().size() == 2, "Dead target remains in queue");
        } finally { cleanup(player); mob.discard(); extra.discard(); }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void escapingPlayerDoesNotRejoinImmediately(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ServerPlayer ally = player(helper);
        var pos = helper.absolutePos(new BlockPos(8, 1, 7));
        ally.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        Zombie mob = zombie(helper, 16);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        helper.assertTrue(command(player, "mineturn flee") == 1, "Distant player could not flee");
        helper.runAfterDelay(12, () -> {
            try {
                helper.assertFalse(BattleManager.locked(player), "Escaped player was pulled straight back in");
                helper.assertTrue(BattleManager.locked(ally), "One player's escape ended the ally's battle");
            } finally { cleanup(ally); cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 160)
    public static void waitingDoesNotHeal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 3);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        player.setHealth(10);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
        helper.runAfterDelay(90, () -> {
            try {
                helper.assertTrue(player.getHealth() == 10, "Real time healed a waiting combatant");
                helper.assertTrue(BattleManager.battleTime(player) == 0, "Waiting advanced battle time");
                command(player, "mineturn end");
                helper.assertTrue(player.getHealth() == 11, "100 AV did not heal exactly one HP");
                helper.assertTrue(player.getFoodData().getSaturationLevel() == 4, "Regen did not consume resource");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void zombieTakesScheduledAction(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 3);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        // Real joining players have 60 ticks of spawn invulnerability. Let that expire before testing damage.
        helper.runAfterDelay(70, () -> {
            command(player, "mineturn end");
            command(player, "mineturn end");
        });
        helper.runAfterDelay(86, () -> {
            try {
                helper.assertTrue(player.getHealth() < 20, "FSM did not execute its scheduled attack");
                helper.assertTrue(BattleManager.battleTime(player) == 200, "AI did not advance to the next turn");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 960)
    public static void playerTurnHasThirtySecondDeadline(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Zombie mob = zombie(helper, 3);
        mob.hurt(player.damageSources().playerAttack(player), 1);
        helper.runAfterDelay(920, () -> {
            try {
                helper.assertTrue(BattleManager.locked(player), "Battle ended while waiting");
                helper.assertTrue(BattleManager.battleTime(player) > 0, "Thirty second idle deadline did not advance the turn");
            } finally { cleanup(player); mob.discard(); }
            helper.succeed();
        });
    }
    private static Zombie zombie(GameTestHelper helper, int x) {
        Zombie mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(x, 1, 2));
        mob.setBaby(false);
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        return mob;
    }
    private static int command(ServerPlayer player, String text) {
        try { return player.getServer().getCommands().getDispatcher().execute(text, player.createCommandSourceStack().withPermission(2)); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private static void cleanup(ServerPlayer player) {
        if (BattleManager.locked(player)) command(player, "mineturn abort");
        player.getServer().getPlayerList().remove(player);
        player.discard();
    }
}
