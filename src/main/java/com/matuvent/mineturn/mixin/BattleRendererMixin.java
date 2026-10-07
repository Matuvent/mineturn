package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.client.BattleClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class BattleRendererMixin {
    @Inject(method = {"bobView", "bobHurt"}, at = @At("HEAD"), cancellable = true)
    private void mineturn$stableCamera(CallbackInfo ci) { if (BattleClient.active()) ci.cancel(); }
    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void mineturn$fixedProjection(CallbackInfoReturnable<Double> ci) {
        if (BattleClient.active()) ci.setReturnValue(Minecraft.getInstance().options.fov().get().doubleValue());
    }
}
