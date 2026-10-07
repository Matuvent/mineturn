package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Zombie.class)
public interface ZombieSunAccess {
    @Invoker("isSunSensitive") boolean mineturn$sunSensitive();
}
