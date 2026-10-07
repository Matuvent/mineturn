package com.matuvent.mineturn.mixin;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Shulker.class)
public interface ShulkerBattleAccess {
    @Invoker("setRawPeekAmount") void mineturn$peek(int amount);
    @Invoker("getRawPeekAmount") int mineturn$peek();
}
