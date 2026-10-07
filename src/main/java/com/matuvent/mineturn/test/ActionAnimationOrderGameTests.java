package com.matuvent.mineturn.test;

import com.matuvent.mineturn.network.ActionAnimationOrder;
import com.matuvent.mineturn.network.ActionAnimationOrder.Action;
import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Action performances share one camera, so their ordering has to be deterministic: attacks interrupt,
 * minor actions wait, and replayed packets never restart an animation. The arbitration is pure logic, so
 * it is exercised here on the server rather than only in the client.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class ActionAnimationOrderGameTests {
    private static final int EAT = ActionAnimationOrder.PRIORITY_EAT;
    private static final int GENERIC = ActionAnimationOrder.PRIORITY_GENERIC;
    private static final int ATTACK = ActionAnimationOrder.PRIORITY_ATTACK;

    @GameTest(template = "empty")
    public static void sequencesAreMonotonicAndDuplicatesDrop(GameTestHelper h) {
        var order = new ActionAnimationOrder();
        h.assertTrue(order.arrive(5, GENERIC) == Action.PLAY, "First arrival did not play");
        h.assertTrue(order.arrive(5, ATTACK) == Action.DROP, "Repeated sequence restarted a performance");
        h.assertTrue(order.arrive(4, ATTACK) == Action.DROP, "Older sequence was accepted");
        h.assertTrue(order.arrive(6, GENERIC) == Action.PLAY, "Newer sequence was not played");
        order.clear();
        h.assertTrue(order.arrive(1, GENERIC) == Action.PLAY, "Clear did not reset the sequence watermark");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void attacksInterruptAndMinorActionsQueue(GameTestHelper h) {
        var order = new ActionAnimationOrder();
        h.assertTrue(order.arrive(1, EAT) == Action.PLAY, "Eating did not start");
        // A stronger performance takes over immediately.
        h.assertTrue(order.arrive(2, ATTACK) == Action.PLAY, "Attack did not interrupt the running cut");
        h.assertTrue(order.runningPriority() == ATTACK, "Running priority was not raised");
        // A weaker one must not fight for the camera.
        h.assertTrue(order.arrive(3, EAT) == Action.QUEUE, "Minor action did not queue behind an attack");
        h.assertTrue(order.hasPending() && order.pendingPriority() == EAT, "Pending slot did not record the minor action");
        // An equal priority replaces the running performance.
        h.assertTrue(order.arrive(4, ATTACK) == Action.PLAY, "Equal priority did not replace the running cut");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pendingWeakestIsKeptAndPromoted(GameTestHelper h) {
        var order = new ActionAnimationOrder();
        h.assertTrue(order.arrive(1, ATTACK) == Action.PLAY, "Attack did not start");
        h.assertTrue(order.arrive(2, GENERIC) == Action.QUEUE, "Generic action did not queue");
        h.assertTrue(order.arrive(3, EAT) == Action.QUEUE, "Eating did not queue");
        // The pending slot keeps the strongest candidate rather than the newest one.
        h.assertTrue(order.pendingPriority() == GENERIC, "Pending slot was overwritten by a weaker action");
        h.assertTrue(order.finishRunning(), "Finishing did not promote the pending performance");
        h.assertTrue(order.running() && order.runningPriority() == GENERIC, "Promoted performance state is wrong");
        h.assertTrue(!order.hasPending(), "Pending slot was not consumed");
        h.assertTrue(!order.finishRunning(), "Finishing without a pending performance reported a promotion");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void clearDropsRunningAndPending(GameTestHelper h) {
        var order = new ActionAnimationOrder();
        order.arrive(1, ATTACK);
        order.arrive(2, EAT);
        order.clear();
        h.assertTrue(!order.running() && !order.hasPending(), "Clear left state behind");
        h.assertTrue(order.arrive(1, EAT) == Action.PLAY, "Clear did not reset the sequence watermark");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void serverPriorityMappingMatchesAnimationIntent(GameTestHelper h) {
        h.assertTrue(BattleNetwork.ActionAnimation.priorityFor(ResourceLocation.parse("mineturn:melee")) == ATTACK,
                "Melee was not tagged as an interrupting attack");
        h.assertTrue(BattleNetwork.ActionAnimation.priorityFor(ResourceLocation.parse("mineturn:ranged")) == ATTACK,
                "Ranged was not tagged as an interrupting attack");
        h.assertTrue(BattleNetwork.ActionAnimation.priorityFor(ResourceLocation.parse("mineturn:eat")) == EAT,
                "Eating was not tagged as a minor action");
        h.assertTrue(BattleNetwork.ActionAnimation.priorityFor(ResourceLocation.parse("mineturn:generic")) == GENERIC,
                "Generic fallback priority is wrong");
        h.assertTrue(BattleNetwork.ActionAnimation.priorityFor(null) == GENERIC,
                "Null animation id did not fall back to the generic priority");
        // Every effect the server maps must resolve to a known id with a sane priority.
        for (String effect : java.util.List.of("mineturn:damage", "mineturn:projectile", "mineturn:food",
                "mineturn:heal", "mineturn:teleport_offset", "mineturn:unknown_effect")) {
            var id = BattleNetwork.ActionAnimation.idFor(effect);
            int priority = BattleNetwork.ActionAnimation.priorityFor(id);
            h.assertTrue(priority >= EAT, "Effect " + effect + " produced an out-of-range priority: " + priority);
        }
        h.succeed();
    }
}
