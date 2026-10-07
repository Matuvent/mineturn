package com.matuvent.mineturn.api;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.effect.MobEffectInstance;

/** Battle-scoped splash; no real-time projectile or world/block edits. */
final class CombatSplash implements CombatEffects.Effect {
    private final boolean lingering;
    CombatSplash(boolean lingering){this.lingering=lingering;}
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action action) {
        if(PotionTargets.ground(action) && (action.self() || action.ranged()==null))throw new IllegalArgumentException("ground potion requires ranged non-self action");
        if(action.consume()!=1 || (action.self()?action.ranged()!=null:action.ranged()==null || action.ranged().ammoCount()!=0))
            throw new IllegalArgumentException("splash requires consume=1 and either self or ranged with ammo_count=0");
    }
    @Override public String validate(CombatEffects.Context context) {
        if(lingering && context.battle().lingeringCount()>=32)return "当前战斗的药水云已达上限。";
        return context.item().is(lingering?Items.LINGERING_POTION:Items.SPLASH_POTION)?null:"药水类型与动作不匹配。";
    }
    @Override public void execute(CombatEffects.Context context) {
        var explicit=context.battle().impactPosition();
        var center=explicit==null?context.target().position():explicit;
        var potion=context.item().getOrDefault(DataComponents.POTION_CONTENTS,PotionContents.EMPTY);
        if(lingering){context.battle().lingering(potion);return;}
        for(var victim:context.battle().participants()) {
            double distance=victim.position().distanceTo(center);
            if(!victim.isAlive() || distance>=4 || Math.abs(victim.getY()-center.y)>2
                    || !victim.isAffectedByPotions())continue;
            boolean visible=explicit==null?context.target().hasLineOfSight(victim):context.source().level().clip(
                    new net.minecraft.world.level.ClipContext(center.add(0,0.05,0),victim.getEyePosition(),
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,context.source())).getType()==net.minecraft.world.phys.HitResult.Type.MISS;
            if(!visible)continue;
            double strength=explicit==null && victim==context.target()?1:1-distance/4;
            if(potion.is(Potions.WATER))victim.clearFire();
            for(var effect:potion.getAllEffects()) {
                var type=effect.getEffect();
                if(type.value().isInstantenous()) {
                    victim.invulnerableTime=0;
                    type.value().applyInstantenousEffect(context.source(),context.source(),victim,effect.getAmplifier(),strength);
                }else {
                    int duration=effect.mapDuration(ticks->(int)(ticks*strength+0.5));
                    var applied=new MobEffectInstance(type,duration,effect.getAmplifier(),effect.isAmbient(),effect.isVisible());
                    if(!applied.endsWithin(20))victim.addEffect(applied,context.source());
                }
            }
            victim.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
    }
}
