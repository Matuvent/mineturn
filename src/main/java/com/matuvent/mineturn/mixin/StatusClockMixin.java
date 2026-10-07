package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import com.matuvent.mineturn.battle.BattleStatus;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class StatusClockMixin {
    @Inject(method="move",at=@At("HEAD"),cancellable=true)
    private void mineturn$externalPush(net.minecraft.world.entity.MoverType type,net.minecraft.world.phys.Vec3 movement,CallbackInfo ci){
        if((type==net.minecraft.world.entity.MoverType.PISTON || type==net.minecraft.world.entity.MoverType.SHULKER
                || type==net.minecraft.world.entity.MoverType.SHULKER_BOX) && BattleManager.locked((Entity)(Object)this))ci.cancel();
    }
    @Inject(method="setRemainingFireTicks",at=@At("HEAD"),cancellable=true)
    private void mineturn$fire(int value, CallbackInfo ci) {
        if((BattleManager.locked((Entity)(Object)this) || BattleManager.clientStatusLocked.test((Entity)(Object)this)) && !BattleStatus.running() && !BattleManager.authorized())ci.cancel();
    }
    @Inject(method="setTicksFrozen",at=@At("HEAD"),cancellable=true)
    private void mineturn$frozen(int value, CallbackInfo ci) {
        if(BattleManager.locked((Entity)(Object)this) && !BattleStatus.running() && !BattleManager.authorized())ci.cancel();
    }
    @Inject(method="setAirSupply",at=@At("HEAD"),cancellable=true)
    private void mineturn$air(int value, CallbackInfo ci) {
        if(BattleManager.locked((Entity)(Object)this) && !BattleStatus.running() && !BattleManager.authorized())ci.cancel();
    }
    @Inject(method="extinguishFire",at=@At("HEAD"),cancellable=true)
    private void mineturn$extinguish(CallbackInfo ci) {
        if(BattleManager.locked((Entity)(Object)this) && !BattleStatus.running() && !BattleManager.authorized())ci.cancel();
    }
}
