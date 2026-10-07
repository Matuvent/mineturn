package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.effect.MobEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ThrownPotion.class)
public abstract class PotionBoundaryMixin {
    @Redirect(method="applyWater",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean mineturn$waterDamage(LivingEntity target,net.minecraft.world.damagesource.DamageSource source,float amount){
        return BattleManager.allowsRealtimeEffect((Entity)(Object)this,target) && target.hurt(source,amount);
    }
    @Redirect(method="applyWater",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;extinguishFire()V"))
    private void mineturn$waterExtinguish(LivingEntity target){
        if(BattleManager.allowsRealtimeEffect((Entity)(Object)this,target))target.extinguishFire();
    }
    @Redirect(method="applyWater",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/animal/axolotl/Axolotl;rehydrate()V"))
    private void mineturn$waterRehydrate(net.minecraft.world.entity.animal.axolotl.Axolotl target){
        if(BattleManager.allowsRealtimeEffect((Entity)(Object)this,target))target.rehydrate();
    }
    @Redirect(method="applySplash",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;isAffectedByPotions()Z"))
    private boolean mineturn$target(LivingEntity target){
        return BattleManager.allowsRealtimeEffect((Entity)(Object)this,target) && target.isAffectedByPotions();
    }
    @Redirect(method="applySplash",at=@At(value="INVOKE",target="Lnet/minecraft/world/effect/MobEffect;applyInstantenousEffect(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;ID)V"))
    private void mineturn$instant(MobEffect effect,Entity direct,Entity owner,LivingEntity target,int amplifier,double scale){
        if(BattleManager.allowsRealtimeEffect((Entity)(Object)this,target))effect.applyInstantenousEffect(direct,owner,target,amplifier,scale);
    }
}
