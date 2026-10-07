package com.matuvent.mineturn.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Shares the native timer so entering/leaving battle preserves starvation progress. */
@Mixin(FoodData.class)
public interface FoodClockAccess {
    @Accessor("tickTimer") int mineturn$getTimer();
    @Accessor("tickTimer") void mineturn$setTimer(int value);
}
