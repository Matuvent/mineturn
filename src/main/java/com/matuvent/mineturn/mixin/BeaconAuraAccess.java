package com.matuvent.mineturn.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BeaconBlockEntity.class)
public interface BeaconAuraAccess {
    @Invoker("applyEffects")
    static void mineturn$apply(Level level,BlockPos pos,int tiers,Holder<MobEffect> primary,Holder<MobEffect> secondary){throw new AssertionError();}
}
