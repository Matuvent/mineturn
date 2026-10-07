package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import com.matuvent.mineturn.battle.BattleStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingMixin {
    @Inject(method="hurt",at=@At("RETURN"))
    private void mineturn$shieldSpent(net.minecraft.world.damagesource.DamageSource source,float amount,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir){
        BattleManager.finishShieldHit((LivingEntity)(Object)this);
    }
    @Inject(method = "tickEffects", at = @At("HEAD"), cancellable = true)
    private void mineturn$freeze(CallbackInfo ci) {
        Entity entity=(Entity)(Object)this;
        if ((BattleManager.locked(entity) || BattleManager.clientStatusLocked.test(entity)) && !BattleStatus.running()) ci.cancel();
    }
    @Redirect(method="baseTick",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/CommonHooks;onLivingBreathe(Lnet/minecraft/world/entity/LivingEntity;II)V"))
    private void mineturn$breathing(LivingEntity entity,int consume,int refill) {
        if(!BattleManager.locked(entity))net.neoforged.neoforge.common.CommonHooks.onLivingBreathe(entity,consume,refill);
    }
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void mineturn$freezeTravel(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (BattleManager.locked(entity)) {
            entity.walkAnimation.update(0, 0.4f);
            ci.cancel();
        }
    }
}
