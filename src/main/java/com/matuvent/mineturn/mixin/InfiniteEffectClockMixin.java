package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleStatus;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MobEffectInstance.class)
public abstract class InfiniteEffectClockMixin {
    @Redirect(method="tick",at=@At(value="FIELD",target="Lnet/minecraft/world/entity/LivingEntity;tickCount:I"))
    private int mineturn$effectClock(LivingEntity entity) { return BattleStatus.effectTick(entity); }
}
