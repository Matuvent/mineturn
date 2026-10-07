package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface StatusAccess {
    @org.spongepowered.asm.mixin.gen.Accessor("useItemRemaining") void mineturn$useRemaining(int ticks);
    @Invoker("detectEquipmentUpdates") void mineturn$equipment();
    @Invoker("removeFrost") void mineturn$removeFrost();
    @Invoker("tryAddFrost") void mineturn$addFrost();
    @Invoker("tickEffects") void mineturn$tickEffects();
    @Invoker("decreaseAirSupply") int mineturn$decreaseAir(int air);
    @Invoker("increaseAirSupply") int mineturn$increaseAir(int air);
}
