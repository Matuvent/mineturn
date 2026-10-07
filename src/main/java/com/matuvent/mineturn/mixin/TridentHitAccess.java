package com.matuvent.mineturn.mixin;

import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThrownTrident.class)
public interface TridentHitAccess {
    @Invoker("onHitEntity") void mineturn$hit(EntityHitResult hit);
}
