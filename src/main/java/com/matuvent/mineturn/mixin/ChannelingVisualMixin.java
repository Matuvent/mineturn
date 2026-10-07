package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.api.CombatChanneling;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightningBolt.class)
public abstract class ChannelingVisualMixin {
    @org.spongepowered.asm.mixin.Unique private int mineturn$visualAge;
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void mineturn$visualTick(CallbackInfo ci) {
        var bolt=(LightningBolt)(Object)this;
        if(!bolt.level().isClientSide && bolt.getPersistentData().getBoolean(CombatChanneling.VISUAL)) {
            // visualOnly alone still powers rods and cleans copper in vanilla's server tick.
            if(++mineturn$visualAge>=20)bolt.discard();
            ci.cancel();
        }
    }
}
