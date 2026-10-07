package com.matuvent.mineturn.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;

/** Native equipment neutrality and brain targets, without running trading or distraction AI. */
final class BattlePiglins {
    static LivingEntity target(AbstractPiglin piglin) {
        var target=piglin.getTarget();
        if(target!=null && target.isAlive())return target;
        var angry=piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT).orElse(null);
        return angry!=null && piglin.level() instanceof net.minecraft.server.level.ServerLevel level
                && level.getEntity(angry) instanceof LivingEntity living && living.isAlive()?living:null;
    }
    static boolean hostileTo(BattleSession battle,Piglin piglin,LivingEntity other) {
        var owner=battle.side(other);
        var target=target(piglin);
        if(target!=null && (target==other || target.getUUID().equals(owner)))return true;
        if(piglin.isBaby())return false;
        var brain=battle.definitions.mobs().get("minecraft:piglin");
        boolean forced=brain!=null && brain.participation().equals("hostile");
        var player=other instanceof ServerPlayer p?p:owner==null?null:battle.level.getPlayerByUUID(owner);
        return player!=null && !player.isCreative() && !player.isSpectator() && (forced || !PiglinAi.isWearingGold(player));
    }
    private BattlePiglins(){}
}
