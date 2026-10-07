package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Slime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Slime.class)
public abstract class SlimeSplitMixin {
    @Inject(method="remove",at=@At("TAIL"))
    private void mineturn$children(Entity.RemovalReason reason,CallbackInfo ci){
        BattleManager.finishSplit((Slime)(Object)this);
    }
}
