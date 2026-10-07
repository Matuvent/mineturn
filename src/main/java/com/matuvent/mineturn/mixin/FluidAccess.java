package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface FluidAccess {
    @Invoker("updateFluidOnEyes") void mineturn$updateEyes();
    @Invoker("updateInWaterStateAndDoFluidPushing") boolean mineturn$updateFluid();
}
