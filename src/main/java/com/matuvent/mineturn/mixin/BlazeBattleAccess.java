package com.matuvent.mineturn.mixin;
import net.minecraft.world.entity.monster.Blaze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Blaze.class)
public interface BlazeBattleAccess {
    @Invoker("setCharged") void mineturn$charged(boolean charged);
}
