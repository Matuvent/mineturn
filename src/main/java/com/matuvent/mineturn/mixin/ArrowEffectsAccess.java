package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractArrow.class)
public interface ArrowEffectsAccess {
    @Invoker("doPostHurtEffects") void mineturn$hitEffects(LivingEntity target);
}
