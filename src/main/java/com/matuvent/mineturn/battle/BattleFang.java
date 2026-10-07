package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;

/** Damage is committed once by the battle; the native client entity only presents the animation. */
final class BattleFang extends EvokerFangs {
    private final BattleSession battle;
    private final LivingEntity caster;
    private int visualAge;
    BattleFang(BattleSession battle,LivingEntity caster,double x,double y,double z,float angle){
        super(battle.level,x,y,z,angle,0,caster);this.battle=battle;this.caster=caster;
    }
    @Override public void tick(){
        if(battle.closed || !caster.isAlive() || !battle.members.containsKey(caster.getUUID()) || ++visualAge>24){discard();return;}
        if(visualAge==2)level().broadcastEntityEvent(this,(byte)4);
    }
}
