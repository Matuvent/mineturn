package com.matuvent.mineturn.test;

import com.matuvent.mineturn.battle.BattleManager;
import com.matuvent.mineturn.network.BattleNetwork;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Recovery coverage for thrown tridents. Existing suites already assert the loyalty AV delay, that a
 * recovered trident keeps its components and durability, that a replay cannot return it twice, the
 * full-inventory fallback and that a destroyed drop is never recreated.
 *
 * <p>What they do <b>not</b> assert is the <b>total</b> number of tridents across both the inventory and
 * the world. Counting only {@code Inventory.countItem} cannot see a leftover {@link ItemEntity}, which
 * is exactly how a duplication bug would present. These tests close that gap, plus the request codec on
 * the wire path and what survives a battle closing with a return still pending.
 *
 * <p>Fixture helpers are duplicated from the sibling suites on purpose: {@code BattleSession} and
 * {@code BattleTridents} are package-private in another package, so a class in {@code test} can only
 * drive public entry points, and the workspace convention is to add a class rather than grow the shared
 * suites.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class TridentRecoveryGameTests {
    private static ServerPlayer player(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        }
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "mt-trident"), false);
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

    /** Starts a battle and returns the hostile mob that joined it. */
    private static LivingEntity startBattle(GameTestHelper helper, ServerPlayer player) {
        var mob = helper.spawnWithNoFreeWill(EntityType.PILLAGER, new BlockPos(8, 1, 2));
        mob.invulnerableTime = 0;
        player.invulnerableTime = 0;
        mob.hurt(player.damageSources().playerAttack(player), 1);
        var state = BattleManager.snapshot(player);
        if (state == null || !state.active()) throw new AssertionError("the fixture did not start a battle");
        return mob;
    }

    /** Sends a throw request over the real wire path. */
    private static void throwTrident(ServerPlayer player, int slot, LivingEntity target) {
        var state = BattleManager.snapshot(player);
        BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "use",
                slot, "mineturn:throw_trident", target.getId(), Vec3.ZERO, -1));
        if (BattleManager.snapshot(player).canAct()) {
            throw new AssertionError("the throw request was rejected; the trident was never committed");
        }
    }

    private static ItemStack loyalTrident(GameTestHelper helper, int level) {
        var trident = new ItemStack(Items.TRIDENT);
        trident.enchant(helper.getLevel().registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY),
                level);
        return trident;
    }

    /** Total tridents the player owns: inventory slots plus live dropped items nearby. */
    private static int totalTridents(GameTestHelper helper, ServerPlayer player) {
        int inInventory = player.getInventory().countItem(Items.TRIDENT);
        int dropped = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(24),
                        e -> e.getItem().is(Items.TRIDENT))
                .stream()
                .mapToInt(e -> e.getItem().getCount())
                .sum();
        return inInventory + dropped;
    }

    @GameTest(template = "empty")
    public static void throwRequestSurvivesTheWireCodec(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            var mob = startBattle(helper, player);
            var state = BattleManager.snapshot(player);
            var request = new BattleNetwork.Request(state.battle(), state.revision(), "use", 4,
                    "mineturn:throw_trident", mob.getId(), new Vec3(1.25, 2.5, -3.75), 7);
            var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                BattleNetwork.Request.CODEC.encode(buffer, request);
                var decoded = BattleNetwork.Request.CODEC.decode(buffer);
                helper.assertTrue(decoded.equals(request),
                        "Trident throw request lost fields over the codec: " + decoded);
            } finally {
                buffer.release();
            }
        } finally {
            cleanup(player);
        }
        helper.succeed();
    }

    /**
     * Throwing commits exactly one recoverable trident. There must never be two, whether the second
     * would be an inventory copy or a leftover drop.
     */
    @GameTest(template = "empty")
    public static void throwingCommitsExactlyOneRecoverableTrident(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        try {
            var mob = startBattle(helper, player);
            var weapon = loyalTrident(helper, 3);
            player.getInventory().setItem(4, weapon);

            throwTrident(player, 4, mob);

            helper.assertTrue(weapon.isEmpty(), "the thrown trident stayed in the inventory: " + weapon.getCount());
            helper.assertTrue(totalTridents(helper, player) == 1,
                    "throwing did not commit exactly one recoverable trident: " + totalTridents(helper, player));
            helper.assertTrue(helper.getLevel()
                            .getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(24),
                                    e -> e.getItem().is(Items.TRIDENT))
                            .size() == 1,
                    "expected exactly one dropped trident in the world");

            // A second identical request must not create a second item.
            var state = BattleManager.snapshot(player);
            BattleManager.request(player, new BattleNetwork.Request(state.battle(), state.revision(), "use",
                    4, "mineturn:throw_trident", mob.getId(), Vec3.ZERO, -1));
            helper.assertTrue(totalTridents(helper, player) == 1,
                    "a repeated throw request duplicated the trident: " + totalTridents(helper, player));
        } finally {
            cleanup(player);
        }
        helper.succeed();
    }

    /**
     * Closing the battle while a return is still pending recovers the committed drop through the exit
     * path. Whatever branch it takes, the total number of tridents must stay at exactly one: the item
     * moves, it never duplicates and it is never lost.
     */
    @GameTest(template = "empty")
    public static void battleCloseRecoversTheDropWithoutDuplicating(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemEntity committed = null;
        try {
            var mob = startBattle(helper, player);
            var weapon = loyalTrident(helper, 3);
            player.getInventory().setItem(4, weapon);

            throwTrident(player, 4, mob);

            var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(24), e -> e.getItem().is(Items.TRIDENT));
            helper.assertTrue(drops.size() == 1, "expected one committed drop before closing, found " + drops.size());
            committed = drops.getFirst();
            helper.assertTrue(totalTridents(helper, player) == 1,
                    "fixture did not start with exactly one trident: " + totalTridents(helper, player));

            helper.assertTrue(command(player, "mineturn abort") > 0, "abort command was rejected");
            helper.assertTrue(!BattleManager.locked(player), "battle did not actually close");

            // The vacated slot is empty, so the committed drop is recovered into it and the entity goes away.
            helper.assertTrue(player.getInventory().getItem(4).is(Items.TRIDENT),
                    "exit recovery did not return the trident to its original slot");
            helper.assertTrue(committed.isRemoved(),
                    "the recovered drop entity was left in the world as a duplicate");
            helper.assertTrue(totalTridents(helper, player) == 1,
                    "closing the battle lost or duplicated the trident: " + totalTridents(helper, player));
        } finally {
            if (committed != null) committed.discard();
            cleanup(player);
        }
        helper.succeed();
    }

    /**
     * When the original slot is taken but the inventory has room elsewhere, recovery moves the item into
     * the bag, drops the entity, and still leaves exactly one trident. The existing suites assert the
     * component and durability survival here; this asserts the total.
     */
    @GameTest(template = "empty")
    public static void recoveryMovesToFreeBagSlotWithoutDuplicating(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemEntity committed = null;
        try {
            var mob = startBattle(helper, player);
            player.getInventory().setItem(4, loyalTrident(helper, 3));

            throwTrident(player, 4, mob);

            // Occupy the original slot so recovery cannot use it, while leaving other slots free.
            player.getInventory().setItem(4, new ItemStack(Items.DIRT, 64));
            var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(24), e -> e.getItem().is(Items.TRIDENT));
            helper.assertTrue(drops.size() == 1, "expected one committed drop, found " + drops.size());
            committed = drops.getFirst();

            helper.assertTrue(command(player, "mineturn abort") > 0, "abort command was rejected");

            helper.assertTrue(player.getInventory().getItem(4).is(Items.DIRT),
                    "recovery overwrote the occupied slot");
            helper.assertTrue(player.getInventory().countItem(Items.TRIDENT) == 1,
                    "recovery did not move the trident into a free bag slot");
            helper.assertTrue(committed.isRemoved(), "the recovered drop entity was left behind");
            helper.assertTrue(totalTridents(helper, player) == 1,
                    "recovery into the bag lost or duplicated the trident: " + totalTridents(helper, player));
        } finally {
            if (committed != null) committed.discard();
            cleanup(player);
        }
        helper.succeed();
    }
}
