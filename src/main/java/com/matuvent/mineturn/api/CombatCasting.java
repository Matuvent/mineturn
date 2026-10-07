package com.matuvent.mineturn.api;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Entry points for adapters; native spell packets and cooldown ticks must be intercepted by the adapter. */
public final class CombatCasting {
    public record Result(boolean accepted,String reason) {}
    /** Call from a native spell's server-side entry point. Authorized combat effects may invoke that spell. */
    public static boolean allowsOriginalCast(Entity caster){return !BattleManager.locked(caster)||BattleManager.authorized();}
    /** Freeze the external mod's real-time cooldown clock while this returns true; use remainingAv instead. */
    public static boolean usesBattleClock(Entity caster){return BattleManager.locked(caster);}
    public static Result cast(ServerPlayer caster,ResourceLocation grant,int targetId){return BattleManager.castGranted(caster,grant.toString(),targetId);}
    public static double remainingAv(ServerPlayer caster,ResourceLocation action){return BattleManager.remainingCooldown(caster,action.toString());}
    private CombatCasting(){}
}
