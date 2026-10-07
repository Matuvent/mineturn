package com.matuvent.mineturn.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="net.minecraft.world.item.ItemCooldowns$CooldownInstance")
public interface CooldownEndAccess {
    @Accessor("endTime") int mineturn$end();
}
