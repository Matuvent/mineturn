package com.matuvent.mineturn.mixin;
import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(EnderMan.class)
public abstract class EnderBattleMixin {
    @Inject(method="teleport(DDD)Z",at=@At("HEAD"),cancellable=true)
    private void mineturn$noFreeTeleport(double x,double y,double z,CallbackInfoReturnable<Boolean> cir){
        if(BattleManager.locked((EnderMan)(Object)this))cir.setReturnValue(false);
    }
}
