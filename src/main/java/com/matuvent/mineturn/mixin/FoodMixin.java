package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class FoodMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mineturn$battleFood(Player player, CallbackInfo ci) {
        if (BattleManager.locked(player)) ci.cancel();
    }
}
