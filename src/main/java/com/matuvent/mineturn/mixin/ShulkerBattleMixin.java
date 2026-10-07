package com.matuvent.mineturn.mixin;
import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Shulker.class)
public abstract class ShulkerBattleMixin {
    @Inject(method="teleportSomewhere",at=@At("HEAD"),cancellable=true)
    private void mineturn$noFreeTeleport(CallbackInfoReturnable<Boolean> cir){
        if(BattleManager.locked((Shulker)(Object)this))cir.setReturnValue(false);
    }
}
