package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractSkeleton.class)
public interface SkeletonArrowAccess {
    @Invoker("getArrow") AbstractArrow mineturn$arrow(ItemStack ammo,float power,ItemStack weapon);
}
