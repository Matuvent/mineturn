package com.matuvent.mineturn.test;

import com.matuvent.mineturn.battle.TerrainPath;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Investigates why some large mobs cannot move at all in battle.
 *
 * <p>A ravager was reported as unable to move. It uses the same {@code animated_melee} template as an iron
 * golem and a hoglin, so the template is not the cause; the suspicion is that its collision box is wide
 * enough that the path trace refuses every sample. This compares several body sizes on identical ground so
 * the threshold, if there is one, becomes visible.
 *
 * <p>Read-only: it only asks the pathfinder for a route and never moves anything.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class LargeMobMovementGameTests {
    private static final int PLATFORM = 48;
    private static final int CENTRE = PLATFORM / 2;

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < PLATFORM; x++) {
            for (int z = 0; z < PLATFORM; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        }
    }

    private static LivingEntity spawn(GameTestHelper helper, EntityType<? extends net.minecraft.world.entity.Mob> type) {
        var at = helper.absolutePos(new BlockPos(CENTRE, 1, CENTRE));
        var entity = helper.spawnWithNoFreeWill(type, new BlockPos(CENTRE, 1, CENTRE));
        entity.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        entity.setDeltaMovement(0, 0, 0);
        return entity;
    }

    /**
     * Traces a straight 4 block walk for bodies of increasing width on identical open ground. If the widest
     * ones refuse to move while the narrow ones do not, the collision box is the cause and the number is the
     * evidence.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void wideBodiesStillTraceOnOpenGround(GameTestHelper h) {
        floor(h);
        record Case(String name, EntityType<? extends net.minecraft.world.entity.Mob> type) {}
        var cases = List.of(
                new Case("zombie", EntityType.ZOMBIE),
                new Case("iron_golem", EntityType.IRON_GOLEM),
                new Case("hoglin", EntityType.HOGLIN),
                new Case("ravager", EntityType.RAVAGER));

        var text = new StringBuilder("\nLarge mob movement probe (open flat ground, straight 4 block walk)\n");
        text.append(String.format("  %-14s %7s %7s %8s %10s%n", "mob", "width", "height", "cost", "samples"));
        var refused = new ArrayList<String>();
        for (var one : cases) {
            LivingEntity mob = spawn(h, one.type());
            try {
                var result = TerrainPath.trace(mob, new Vec3(-4, 0, 0), p -> true);
                text.append(String.format("  %-14s %7.2f %7.2f %8.2f %10d%n",
                        one.name(), mob.getBbWidth(), mob.getBbHeight(), result.cost(), result.samples().size()));
                if (result.cost() <= 0) refused.add(one.name() + " (width " + mob.getBbWidth() + ")");
            } finally {
                mob.discard();
            }
        }
        com.matuvent.mineturn.MineTurn.LOGGER.info("{}", text);

        h.assertTrue(refused.isEmpty(),
                "Bodies that cannot take a single step on flat open ground: " + refused);
        h.succeed();
    }

    /** A wide body must also manage a diagonal and a longer walk, not just one axis. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void wideBodiesManageOtherDirections(GameTestHelper h) {
        floor(h);
        var mob = spawn(h, EntityType.RAVAGER);
        try {
            var text = new StringBuilder("\nRavager directions\n");
            var blocked = new ArrayList<String>();
            for (Vec3 direction : List.of(new Vec3(-4, 0, 0), new Vec3(4, 0, 0), new Vec3(0, 0, -4),
                    new Vec3(0, 0, 4), new Vec3(-3, 0, -3))) {
                var result = TerrainPath.trace(mob, direction, p -> true);
                boolean rejected = result.cost() < 1;
                text.append(String.format("  dir=%-14s cost=%.2f samples=%d%s%n",
                        direction, result.cost(), result.samples().size(), rejected ? "  <-- REJECTED" : ""));
                if (rejected) blocked.add(String.valueOf(direction));
            }
            com.matuvent.mineturn.MineTurn.LOGGER.info("{}", text);
            h.assertTrue(blocked.isEmpty(), "The ravager cannot move in these directions at all: " + blocked);
        } finally {
            mob.discard();
        }
        h.succeed();
    }
}
