package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.client.BattleClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class BattleClientMovementMixin {
    @Inject(method = {"travel", "travelRidden"}, at = @At("HEAD"), cancellable = true)
    private void mineturn$stopClientTravel(CallbackInfo ci) {
        if (BattleClient.local((Entity) (Object) this) || BattleClient.localVehicle((Entity)(Object)this)) {
            // travel normally decays this animation. Cancelling without updating it leaves hurt-induced
            // speed at 1.5 forever, causing interpolation to repeatedly twitch between render frames.
            ((LivingEntity) (Object) this).walkAnimation.update(0, 0.4f);
            ci.cancel();
        }
    }
}
