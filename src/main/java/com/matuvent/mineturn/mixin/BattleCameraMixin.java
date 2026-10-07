package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.client.BattleClient;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class BattleCameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);
    @Shadow protected abstract void move(float zoom, float dy, float dx);
    @Shadow private float getMaxZoom(float distance) { throw new AssertionError(); }
    @Inject(method = "setup", at = @At("RETURN"))
    private void mineturn$orbit(CallbackInfo ci) {
        if (!BattleClient.active()) return;
        detached = true;
        var override = com.matuvent.mineturn.client.ActionAnimations.cameraPose();
        float yaw = override != null ? override.yaw() : BattleClient.yaw;
        float pitch = override != null ? override.pitch() : BattleClient.pitch;
        double distance = override != null ? override.distance() : BattleClient.distance;
        Vec3 focus = override != null ? override.focus() : BattleClient.focus;
        setRotation(yaw, pitch, 0);
        setPosition(focus);
        move(-Math.max(0, getMaxZoom((float) distance) - 0.15f), 0, 0);
    }
}
