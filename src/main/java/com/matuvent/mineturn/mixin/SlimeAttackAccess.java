package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.monster.Slime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Slime.class)
public interface SlimeAttackAccess {
    @Invoker("getAttackDamage") float mineturn$attackDamage();
}
