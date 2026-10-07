package com.matuvent.mineturn.test;

import com.matuvent.mineturn.battle.BattleManager;
import com.matuvent.mineturn.network.BattleNetwork;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Independent regression coverage for consumption and containers, driven through the real command entry
 * point rather than by calling the session internals. It deliberately reuses coverage that already
 * exists elsewhere (last-stack container replacement, full inventory, infinite materials, potion and
 * milk containers) and only asserts what those tests do not:
 *
 * <ul>
 *   <li>a successful consume charges the main action but leaves bonus actions and movement untouched;</li>
 *   <li>a rejected consume charges nothing at all.</li>
 * </ul>
 *
 * <p>The fixture helpers are duplicated from the sibling test class on purpose: {@code BattleSession}
 * lives in another package, so a separate class cannot share its package-private helpers, and the
 * workspace convention is to add a new class instead of growing the shared suites.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class ItemConsumptionRegressionGameTests {
    private static ServerPlayer player(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        }
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "mt-item"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        var pos = helper.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    private static int command(ServerPlayer player, String text) {
        try {
            return player.getServer().getCommands().getDispatcher()
                    .execute(text, player.createCommandSourceStack().withPermission(2));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void cleanup(ServerPlayer player) {
        if (BattleManager.locked(player)) command(player, "mineturn abort");
        player.getServer().getPlayerList().remove(player);
        player.discard();
    }

    /** Starts a battle with the player as the first actor and returns the session. */
    private static BattleNetwork.State startBattle(GameTestHelper helper, ServerPlayer player) {
        var mob = helper.spawnWithNoFreeWill(EntityType.PILLAGER, new BlockPos(8, 1, 2));
        mob.invulnerableTime = 0;
        player.invulnerableTime = 0;
        // A direct call does not fire LivingIncomingDamageEvent, so no authorization scope is needed and
        // the battle starts through the ordinary damage hook.
        mob.hurt(player.damageSources().playerAttack(player), 1);
        var state = BattleManager.snapshot(player);
        if (state == null || !state.active()) throw new AssertionError("the fixture did not start a battle");
        return state;
    }

    @GameTest(template = "empty")
    public static void eatingChargesOnlyTheMainAction(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            startBattle(helper, player);
            player.getFoodData().setFoodLevel(6);
            var apple = new ItemStack(Items.APPLE, 2);
            player.getInventory().setItem(0, apple);

            var before = BattleManager.snapshot(player);
            helper.assertTrue(before.mainActions() == 1 && before.bonusActions() == 1,
                    "fixture did not start with one main and one bonus action: " + before.mainActions() + "/" + before.bonusActions());
            double movementBefore = before.movement();
            int hungerBefore = player.getFoodData().getFoodLevel();

            helper.assertTrue(command(player, "mineturn use 0 mineturn:eat") > 0, "eat command was rejected");

            var after = BattleManager.snapshot(player);
            helper.assertTrue(player.getFoodData().getFoodLevel() > hungerBefore,
                    "eating did not restore hunger: " + hungerBefore + " -> " + player.getFoodData().getFoodLevel());
            helper.assertTrue(apple.getCount() == 1, "eating did not consume exactly one item: " + apple.getCount());
            helper.assertTrue(after.mainActions() == 0, "eating did not charge exactly one main action: " + after.mainActions());
            helper.assertTrue(after.bonusActions() == 1,
                    "eating wrongly consumed the bonus action: " + after.bonusActions());
            helper.assertTrue(Math.abs(after.movement() - movementBefore) < 1e-6,
                    "eating wrongly consumed movement: " + movementBefore + " -> " + after.movement());
            helper.assertTrue(!after.canAct() && after.canMove(), "eat left the wrong capabilities: canAct="
                    + after.canAct() + " canMove=" + after.canMove());
        } finally {
            cleanup(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectedConsumeChargesNothing(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            startBattle(helper, player);
            player.getFoodData().setFoodLevel(6);

            // An empty stack: the adapter cannot confirm the item, so the action must be refused.
            var empty = new ItemStack(Items.APPLE, 0);
            player.getInventory().setItem(0, empty);
            var before = BattleManager.snapshot(player);

            command(player, "mineturn use 0 mineturn:eat");

            var after = BattleManager.snapshot(player);
            helper.assertTrue(after.mainActions() == before.mainActions() && after.bonusActions() == before.bonusActions(),
                    "a rejected consume charged actions: " + before.mainActions() + "/" + before.bonusActions()
                            + " -> " + after.mainActions() + "/" + after.bonusActions());
            helper.assertTrue(Math.abs(after.movement() - before.movement()) < 1e-6,
                    "a rejected consume charged movement");
            helper.assertTrue(player.getFoodData().getFoodLevel() == 6,
                    "a rejected consume still restored hunger: " + player.getFoodData().getFoodLevel());

            // A non-food item in the slot must be refused for the same reason and cost the same nothing.
            var stone = new ItemStack(Items.STONE, 1);
            player.getInventory().setItem(0, stone);
            command(player, "mineturn use 0 mineturn:eat");
            var afterStone = BattleManager.snapshot(player);
            helper.assertTrue(afterStone.mainActions() == before.mainActions() && stone.getCount() == 1,
                    "eating a non-food item was accepted or consumed it");
            helper.assertTrue(player.getFoodData().getFoodLevel() == 6, "eating stone changed hunger");
        } finally {
            cleanup(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void drinkingChargesOneMainActionAndReturnsTheContainer(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            startBattle(helper, player);
            var milk = new ItemStack(Items.MILK_BUCKET, 1);
            player.getInventory().setItem(0, milk);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.POISON, 200));

            var before = BattleManager.snapshot(player);
            helper.assertTrue(command(player, "mineturn use 0 mineturn:drink") > 0, "drink command was rejected");
            var after = BattleManager.snapshot(player);

            helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.POISON),
                    "drinking milk did not clear the effect");
            helper.assertTrue(player.getInventory().contains(new ItemStack(Items.BUCKET)),
                    "drinking milk did not return the empty bucket");
            helper.assertTrue(after.mainActions() == before.mainActions() - 1,
                    "drinking did not charge exactly one main action: " + before.mainActions() + " -> " + after.mainActions());
            helper.assertTrue(after.bonusActions() == before.bonusActions(),
                    "drinking wrongly consumed the bonus action");
            helper.assertTrue(Math.abs(after.movement() - before.movement()) < 1e-6,
                    "drinking wrongly consumed movement");
        } finally {
            cleanup(player);
        }
        helper.succeed();
    }
}
