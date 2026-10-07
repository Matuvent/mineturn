package com.matuvent.mineturn.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ZombifiedPiglin;

/** Bounded alerts; native anger storage survives leaving the AV clock. */
final class BattleAnger {
    static boolean hostileTo(BattleSession battle, ZombifiedPiglin piglin, LivingEntity other) {
        var owner=battle.side(other);
        return owner!=null && piglin.getRemainingPersistentAngerTime()>0
                && owner.equals(piglin.getPersistentAngerTarget());
    }
    static void provoke(ZombifiedPiglin source, ServerPlayer player) {
        if(player.isCreative() || player.isSpectator())return;
        var battle=BattleManager.ACTIVE.get(source.getUUID());
        anger(source,player);
        for(var other:source.level().getEntitiesOfClass(ZombifiedPiglin.class,source.getBoundingBox().inflate(16,6,16))) {
            if(other==source || !other.isAlive() || other.distanceToSqr(source)>256
                    || other.getRemainingPersistentAngerTime()>0 && other.getPersistentAngerTarget()!=null)continue;
            var occupied=BattleManager.ACTIVE.get(other.getUUID());
            if(occupied!=null && occupied!=battle)continue;
            anger(other,player);
        }
        if(battle!=null)battle.recruit();
    }
    private static void anger(ZombifiedPiglin piglin, ServerPlayer player) {
        piglin.setPersistentAngerTarget(player.getUUID());
        piglin.startPersistentAngerTimer();
        piglin.setTarget(player);
    }
    static void tick(LivingEntity entity) {
        if(entity instanceof ZombifiedPiglin piglin && piglin.getRemainingPersistentAngerTime()>0) {
            piglin.setRemainingPersistentAngerTime(piglin.getRemainingPersistentAngerTime()-1);
            if(piglin.getRemainingPersistentAngerTime()==0)piglin.stopBeingAngry();
        }
    }
    private BattleAnger() {}
}
