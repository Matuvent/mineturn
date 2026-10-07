package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mob.class)
public interface SunlightAccess {
    @Invoker("isSunBurnTick") boolean mineturn$sunBurnTick();
}
