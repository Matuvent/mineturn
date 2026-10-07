package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** One server-owned attempt. The client sends only its token, never a hit result or cursor position. */
final class RangedShot {
    /**
     * Where the green window may sit, as a fraction of the bar. The position is drawn per shot so a
     * modified client cannot precompute the submit time; the honest client renders whatever the
     * server sends in the Aim packet, so the visible bar stays consistent.
     */
    private static final double WINDOW_MIN = 0.25;
    private static final double WINDOW_MAX = 0.75;
    /** Randomized lead-in before the bar starts, so a client cannot even anchor on the creation time. */
    private static final long LEAD_IN_MIN_NANOS = 1_000_000_000L;
    private static final long LEAD_IN_SPAN_NANOS = 1_000_000_000L;
    String grantId;
    final UUID token = UUID.randomUUID();
    final ServerPlayer shooter;
    final LivingEntity target;
    final String actionId;
    final CombatData.Action action;
    final ItemStack weapon;
    ItemStack ammunition=ItemStack.EMPTY;
    final long startNanos;
    final long latencyCompensationNanos;
    final double low, high;
    final net.minecraft.world.phys.Vec3 ground;
    RangedShot(ServerPlayer shooter, LivingEntity target, String actionId, CombatData.Action action, ItemStack weapon, long now) {
        this(shooter,target,actionId,action,weapon,now,null);
    }
    RangedShot(ServerPlayer shooter, LivingEntity target, String actionId, CombatData.Action action, ItemStack weapon, long now,net.minecraft.world.phys.Vec3 ground) {
        this.ground=ground;
        this.shooter=shooter; this.target=target; this.actionId=actionId; this.action=action; this.weapon=weapon.copy();
        // The server owns both the injected lead-in and the window position; neither reaches the client early.
        startNanos=now+LEAD_IN_MIN_NANOS+(long)(shooter.getRandom().nextDouble()*LEAD_IN_SPAN_NANOS);
        latencyCompensationNanos=Math.clamp(shooter.connection.latency(),0,250)*1_000_000L;
        double width=action.ranged().width(ground==null?shooter.distanceTo(target):shooter.position().distanceTo(ground));
        double center=WINDOW_MIN+shooter.getRandom().nextDouble()*(WINDOW_MAX-WINDOW_MIN);
        if(width>=WINDOW_MAX-WINDOW_MIN)center=0.5;      // A window this wide already spans the range.
        low=center-width/2; high=center+width/2;
    }
    double elapsedMs(long now) { return (now-startNanos)/1_000_000.0; }
    boolean hit(long now) { double progress=elapsedMs(now)/action.ranged().durationMs(); return progress>=low && progress<=high; }
    boolean expired(long now) { return elapsedMs(now)>action.ranged().durationMs(); }
    /**
     * Test/simulation hook: the centre of the randomly placed window, in nanos. Aiming at the centre
     * rather than an edge keeps a caller inside the window despite nano/milli rounding.
     */
    long windowCentreNanos() { return startNanos+(long)(((low+high)/2)*action.ranged().durationMs()*1_000_000.0); }
}
