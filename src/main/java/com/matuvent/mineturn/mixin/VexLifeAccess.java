package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.monster.Vex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Vex.class)
public interface VexLifeAccess {
    @Accessor("hasLimitedLife") boolean mineturn$limited();
    @Accessor("limitedLifeTicks") int mineturn$life();
    @Accessor("limitedLifeTicks") void mineturn$life(int ticks);
}
