package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** One server-owned attempt. The client sends only its token, never a hit result or cursor position. */
final class RangedShot {
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
        startNanos=now+1_000_000_000L;
        latencyCompensationNanos=Math.clamp(shooter.connection.latency(),0,250)*1_000_000L;
        double width=action.ranged().width(ground==null?shooter.distanceTo(target):shooter.position().distanceTo(ground));
        double center=0.5; low=center-width/2; high=center+width/2;
    }
    double elapsedMs(long now) { return (now-startNanos)/1_000_000.0; }
    boolean hit(long now) { double progress=elapsedMs(now)/action.ranged().durationMs(); return progress>=low && progress<=high; }
    boolean expired(long now) { return elapsedMs(now)>action.ranged().durationMs(); }
}
