package com.matuvent.mineturn.mixin;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Axolotl.class)
public interface AxolotlAirAccess {
    @Invoker("handleAirSupply") void mineturn$air(int previous);
}
