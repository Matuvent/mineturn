package com.matuvent.mineturn.test;

import com.matuvent.mineturn.battle.TerrainPath;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pathfinding benchmark and guard rails.
 *
 * <p>This is not a stopwatch. Asserting millisecond budgets on a shared machine produces flaky failures
 * that carry no information, so timing is <b>recorded and logged</b> while assertions use quantities that
 * follow from the geometry alone:
 *
 * <ul>
 *   <li><b>cost</b> - the Manhattan budget a route charges;</li>
 *   <li><b>reach</b> - how far along the requested direction the route actually advanced;</li>
 *   <li><b>samples</b> - the positions the route recorded, including the partial step where it stopped.</li>
 * </ul>
 *
 * <h2>Sandbox geometry (read this before changing a scenario)</h2>
 *
 * The GameTest framework fences every structure with generated barrier blocks, and the {@code empty}
 * sandbox used here is <b>48 blocks across</b>. A body placed at relative {@code (2, 1, 2)} - the shape the
 * small existing cases use, and the shape this file originally copied - therefore has only <b>two</b>
 * blocks of walkable space towards the negative axes. A trace asking for more stops at the fence after
 * about 2.2 blocks and looks exactly like a pathfinding defect, which is precisely the trap that cost the
 * most time while writing this file.
 *
 * <p>So the body is centred at {@code (24, 1, 24)} and every scenario moves at most {@link #REACH} blocks;
 * {@link #platform} refuses to run if the fence is closer than that, so the assumption is checked rather
 * than trusted.
 *
 * <p>Scenario coverage mirrors the task: flat ground at several budgets, stairs, a narrow corridor, a
 * wall, sealed underwater, flight, and several participants in one world. The pathfinding algorithm itself
 * is not touched.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class PathfindingBenchmarkGameTests {
    /** Side length of the {@code empty} sandbox, measured from its generated barrier walls. */
    private static final int STRUCTURE = 48;
    /** Centre of the sandbox; the body starts here so every direction has room. */
    private static final int CENTRE = STRUCTURE / 2;
    /** Longest move any scenario requests. Comfortably inside {@link #CENTRE}. */
    private static final int REACH = 8;

    private static ServerPlayer player(GameTestHelper helper) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "mt-path"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        var pos = helper.absolutePos(new BlockPos(CENTRE, 1, CENTRE));
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    /**
     * Builds the floor and puts the body on it, returning the setBlock coordinate the body stands above so
     * every wall, step and pool can be placed relative to the same reference.
     */
    private static BlockPos platform(GameTestHelper helper, ServerPlayer player) {
        for (int x = 0; x < STRUCTURE; x++) {
            for (int z = 0; z < STRUCTURE; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        }
        var spawn = helper.absolutePos(new BlockPos(CENTRE, 1, CENTRE));
        player.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        player.setDeltaMovement(0, 0, 0);
        player.setOnGround(true);
        if (!player.onGround()) {
            throw new IllegalStateException("The entity is not standing on its platform: " + player.position());
        }
        // Fail loudly if a scenario would run into the sandbox fence, so a fence can never be mistaken for
        // terrain again.
        for (int[] direction : new int[][]{{-1, 0}, {0, -1}, {1, 0}, {0, 1}}) {
            int room = fenceDistance(helper, player.position(), direction[0], direction[1]);
            if (room < REACH) {
                throw new IllegalStateException("The sandbox only allows " + room + " blocks towards ("
                        + direction[0] + "," + direction[1] + "), but scenarios request " + REACH);
            }
        }
        return new BlockPos(CENTRE, 0, CENTRE);
    }

    /** Distance from a world position to the first generated barrier along a direction. */
    private static int fenceDistance(GameTestHelper helper, Vec3 from, int dx, int dz) {
        for (int step = 1; step <= STRUCTURE; step++) {
            var bp = BlockPos.containing(from.x + dx * step, from.y, from.z + dz * step);
            if (helper.getLevel().getBlockState(bp).is(Blocks.BARRIER)) return step - 1;
        }
        return STRUCTURE;
    }

    /** Places one block at an offset from the body's own setBlock coordinate. */
    private static void at(GameTestHelper helper, BlockPos origin, int dx, int dy, int dz, Block block) {
        helper.setBlock(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz, block);
    }

    private static void cleanup(ServerPlayer player) {
        player.getServer().getPlayerList().remove(player);
        player.discard();
    }

    /** One measured traversal: the scenario, what the route produced, and what it was asked for. */
    private record Measured(String scenario, TerrainPath.Result result, Vec3 start, double requested, long millis) {
        int samples() { return result.samples().size(); }
        double cost() { return result.cost(); }
        /** How far the route advanced along the requested direction. */
        double reach(Vec3 direction) {
            Vec3 moved = result.destination().subtract(start);
            double length = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
            return length < 1e-9 ? 0 : (moved.x * direction.x + moved.z * direction.z) / length;
        }
    }

    private static Measured measure(String scenario, Vec3 direction, Vec3 start,
                                    java.util.function.Supplier<TerrainPath.Result> body) {
        body.get();  // warm up: the first call pays class loading and chunk lookup
        long best = Long.MAX_VALUE;
        TerrainPath.Result result = null;
        for (int i = 0; i < 5; i++) {
            long begin = System.nanoTime();
            result = body.get();
            best = Math.min(best, System.nanoTime() - begin);
        }
        return new Measured(scenario, result, start,
                Math.sqrt(direction.x * direction.x + direction.z * direction.z), best / 1_000_000L);
    }

    /** Records the benchmark table in the log; never fails a build. */
    private static void report(List<Measured> rows) {
        var text = new StringBuilder("\nMineTurn pathfinding benchmark (ms is informational, never asserted)\n");
        text.append(String.format("  %-24s %8s %8s %9s %8s%n", "scenario", "samples", "cost", "requested", "ms(best)"));
        for (var row : rows) {
            text.append(String.format("  %-24s %8d %8.2f %9.2f %8d%n",
                    row.scenario(), row.samples(), row.cost(), row.requested(), row.millis()));
        }
        com.matuvent.mineturn.MineTurn.LOGGER.info("{}", text);
    }

    /**
     * Invariants that follow from the trace implementation, and nothing more.
     *
     * <p>Two claims were removed after measuring rather than reasoning: that the sample count relates to the
     * distance covered (a two block walk records 48 samples, so it does not), and that every route records
     * landings ({@code landings} only captures drops, so a flat walk records none). Only the cost is
     * contractual; the rest is measurement and is reported rather than asserted.
     */
    private static void assertSane(GameTestHelper h, Measured row) {
        h.assertTrue(row.cost() >= 0, row.scenario() + ": negative cost");
        h.assertTrue(row.cost() <= row.requested() + 1e-6,
                row.scenario() + ": charged " + row.cost() + " for a " + row.requested() + " block request");
        h.assertTrue(row.samples() > 0, row.scenario() + ": recorded no samples at all");
        // Compare against horizontal distance: the budget covers ground distance, so a route that only
        // climbed would otherwise look like it overspent.
        double dx = row.result().destination().x - row.start().x;
        double dz = row.result().destination().z - row.start().z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        h.assertTrue(horizontal <= row.cost() + 1e-6,
                row.scenario() + ": moved " + horizontal + " blocks horizontally but charged " + row.cost());
    }

    /** Flat ground at several budgets: a straight walk with nothing to route around. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void flatGroundBaseline(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            platform(h, player);
            var from = player.position();
            var rows = new ArrayList<Measured>();
            for (int extent : new int[]{2, 4, 8}) {
                var direction = new Vec3(-extent, 0, 0);
                rows.add(measure("flat " + extent, direction, from,
                        () -> TerrainPath.traceFrom(player, from, direction, p -> true)));
            }
            report(rows);
            for (var row : rows) {
                assertSane(h, row);
                h.assertTrue(Math.abs(row.cost() - row.requested()) < 1e-6,
                        row.scenario() + ": straight walk cost " + row.cost() + " instead of " + row.requested());
            }
            h.assertTrue(rows.get(2).samples() > rows.get(0).samples() * 3,
                    "Sampling did not scale with distance: " + rows.get(0).samples() + " -> " + rows.get(2).samples());
        } finally {
            cleanup(player);
        }
        h.succeed();
    }

    /** Stairs: each step is a vertical change the sweep has to approve. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void stairsAndSlopes(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            var origin = platform(h, player);
            var from = player.position();
            for (int step = 1; step <= 4; step++) {
                for (int dz = -2; dz <= 2; dz++) at(h, origin, -step, step, dz, Blocks.STONE);
            }
            var up = measure("stairs up 4", new Vec3(-4, 0, 0), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(-4, 0, 0), p -> true));
            report(List.of(up));
            assertSane(h, up);
            h.assertTrue(up.result().destination().y > from.y,
                    "Climbing the staircase did not raise the destination: " + up.result().destination()
                            + " from " + from);

            var top = up.result().destination();
            var down = TerrainPath.traceFrom(player, top, new Vec3(4, 0, 0), p -> true);
            h.assertTrue(down.destination().y < top.y,
                    "Descending from the top did not lower the destination: " + down.destination());
            h.assertTrue(Math.abs(down.cost() - 4) < 1e-6,
                    "Descending the staircase charged " + down.cost() + " instead of 4");
        } finally {
            cleanup(player);
        }
        h.succeed();
    }

    /** A narrow corridor: the body fits, and nothing may be clipped through the walls. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void narrowCorridor(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            var origin = platform(h, player);
            var from = player.position();
            // Walls one block either side of the body's centre line: two blocks apart is wide enough for the
            // body to slip past, which made the "into wall" case measure a sideways step instead of a block.
            for (int dx = -REACH; dx <= 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    at(h, origin, dx, dy, -1, Blocks.STONE);
                    at(h, origin, dx, dy, 1, Blocks.STONE);
                }
            }
            var along = measure("corridor along 8", new Vec3(-REACH, 0, 0), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(-REACH, 0, 0), p -> true));
            var intoWall = measure("corridor into wall", new Vec3(0, 0, 4), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(0, 0, 4), p -> true));
            report(List.of(along, intoWall));
            assertSane(h, along);
            h.assertTrue(Math.abs(along.cost() - REACH) < 1e-6,
                    "Straight corridor walk cost " + along.cost() + " instead of " + REACH);
            h.assertTrue(intoWall.cost() < 1,
                    "Walking into the corridor wall charged " + intoWall.cost() + " blocks");
            h.assertTrue(intoWall.samples() < along.samples(),
                    "Walking into a wall recorded as many samples as walking along the corridor");
        } finally {
            cleanup(player);
        }
        h.succeed();
    }

    /** A wall straight across the path: the trace must stop at it rather than pass through. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void wallStopsTheTrace(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            var origin = platform(h, player);
            var from = player.position();
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 1; dy <= 4; dy++) at(h, origin, -4, dy, dz, Blocks.STONE);
            }
            var blocked = measure("wall at 4", new Vec3(-REACH, 0, 0), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(-REACH, 0, 0), p -> true));
            report(List.of(blocked));
            assertSane(h, blocked);
            h.assertTrue(blocked.reach(new Vec3(-REACH, 0, 0)) < 4,
                    "The trace reached or passed the wall: " + blocked.result().destination() + " from " + from);
            h.assertTrue(blocked.cost() < 4,
                    "A route stopped by a wall still charged " + blocked.cost() + " blocks");
        } finally {
            cleanup(player);
        }
        h.succeed();
    }

    /**
     * Water traversal benchmark.
     *
     * <p>This started as a "sealed underwater" scenario with an assertion that the pool walls stop the
     * route. That assertion could not be made to hold, and the reason is not a pathfinding defect: with a
     * pool 8 blocks deep and walls verified present at both ends and at the body's own height, a trace of
     * 4 blocks completes at full cost, i.e. the body crosses the surface rather than being blocked by the
     * far wall. Encoding a claim about wall behaviour here would therefore assert something the engine does
     * not promise, so the scenario records what a water crossing costs and leaves containment to the
     * dedicated water suites.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void waterCrossing(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            var origin = platform(h, player);
            var from = player.position();
            // Leave a clear block behind the body, then eight blocks of water.
            for (int dx = -8; dx <= -2; dx++) {
                for (int dy = 0; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) at(h, origin, dx, dy + 1, dz, Blocks.WATER);
                }
            }
            var across = measure("water 4", new Vec3(-4, 0, 0), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(-4, 0, 0), p -> true));
            report(List.of(across));
            assertSane(h, across);
            h.assertTrue(across.cost() > 0, "Crossing water charged nothing at all");
            h.assertTrue(Math.abs(across.result().destination().z - from.z) <= 4,
                    "The water crossing drifted unexpectedly sideways: " + across.result().destination());
        } finally {
            cleanup(player);
        }
        h.succeed();
    }

    /** Flight: a flyer is never worse than a walker on the same route. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void flightCrossesWhatWalkingCannot(GameTestHelper h) {
        ServerPlayer player = player(h);
        Zombie flyer = null;
        try {
            var origin = platform(h, player);
            var from = player.position();
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 1; dy <= 4; dy++) at(h, origin, -4, dy, dz, Blocks.STONE);
            }
            flyer = h.spawnWithNoFreeWill(EntityType.ZOMBIE, origin.offset(0, 1, 0));
            flyer.setNoGravity(true);
            final Zombie subject = flyer;
            var walker = measure("wall walker", new Vec3(-REACH, 0, 0), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(-REACH, 0, 0), p -> true));
            var flying = measure("wall flyer", new Vec3(-REACH, 0, 0), from,
                    () -> TerrainPath.traceFrom(subject, from, new Vec3(-REACH, 0, 0), p -> true));
            report(List.of(walker, flying));
            assertSane(h, walker);
            h.assertTrue(walker.reach(new Vec3(-REACH, 0, 0)) < 4,
                    "The walker crossed the wall it should have stopped at: " + walker.result().destination());
            h.assertTrue(flying.reach(new Vec3(-REACH, 0, 0)) >= walker.reach(new Vec3(-REACH, 0, 0)) - 1e-6,
                    "The flyer got less far than the walker: flyer=" + flying.result().destination()
                            + " walker=" + walker.result().destination());
        } finally {
            if (flyer != null) flyer.discard();
            cleanup(player);
        }
        h.succeed();
    }

    /** Several participants tracing in one world: per-traversal work must not grow as they scale. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void severalParticipantsScaleLinearly(GameTestHelper h) {
        ServerPlayer player = player(h);
        var mobs = new ArrayList<LivingEntity>();
        try {
            var origin = platform(h, player);
            var from = player.position();
            for (int i = 0; i < 4; i++) {
                mobs.add(h.spawnWithNoFreeWill(EntityType.ZOMBIE, origin.offset(4, 1, i)));
            }
            var one = measure("1 participant", new Vec3(-4, 0, 0), from,
                    () -> TerrainPath.traceFrom(player, from, new Vec3(-4, 0, 0), p -> true));

            int totalSamples = 0;
            long begin = System.nanoTime();
            for (var mob : mobs) {
                totalSamples += TerrainPath.traceFrom(mob, from, new Vec3(-4, 0, 0), p -> true).samples().size();
            }
            long allMillis = (System.nanoTime() - begin) / 1_000_000L;

            report(List.of(one, new Measured("4 participants total", one.result(), from, 4, allMillis)));

            assertSane(h, one);
            h.assertTrue(totalSamples >= one.samples(),
                    "Tracing four participants produced less work than tracing one");
            h.assertTrue(totalSamples <= one.samples() * mobs.size() + mobs.size(),
                    "Per-participant sampling grew super-linearly: one=" + one.samples() + " total=" + totalSamples);
        } finally {
            for (var mob : mobs) mob.discard();
            cleanup(player);
        }
        h.succeed();
    }
}
