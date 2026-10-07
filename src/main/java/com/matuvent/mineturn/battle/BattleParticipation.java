package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.registries.*;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.server.level.ServerPlayer;

/** Admission policy is distinct from having an AI definition. */
final class BattleParticipation {
    static final TagKey<EntityType<?>> RESOURCES=TagKey.create(Registries.ENTITY_TYPE,ResourceLocation.parse("mineturn:non_combatants"));
    static boolean allowed(LivingEntity entity,CombatData.Snapshot data){
        if(entity instanceof ServerPlayer)return true;
        var brain=data.mobs().get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        return brain!=null && !brain.participation().equals("never")
                && (!brain.participation().equals("auto") || !entity.getType().is(RESOURCES));
    }
    static boolean hostile(LivingEntity entity,CombatData.Snapshot data){
        if(!allowed(entity,data))return false;
        var brain=data.mobs().get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        return brain!=null && (brain.participation().equals("hostile") || brain.participation().equals("auto") && entity instanceof net.minecraft.world.entity.monster.Enemy);
    }
    private BattleParticipation(){}
}
