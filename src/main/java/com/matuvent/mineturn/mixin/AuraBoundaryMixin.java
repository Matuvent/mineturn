package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Block auras have no entity source; keep their real-time refresh outside combat. */
@Mixin({BeaconBlockEntity.class,ConduitBlockEntity.class})
public abstract class AuraBoundaryMixin {
    @Redirect(method="applyEffects",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
    private static boolean mineturn$refresh(Player player,MobEffectInstance effect){
        return !BattleManager.locked(player) && player.addEffect(effect);
    }
}
