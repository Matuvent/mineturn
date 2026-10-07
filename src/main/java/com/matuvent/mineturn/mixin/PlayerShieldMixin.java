package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class PlayerShieldMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method="turtleHelmetTick",at=@At("HEAD"),cancellable=true)
    private void mineturn$turtleClock(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
        if(BattleManager.locked((Player)(Object)this) && !com.matuvent.mineturn.battle.BattleStatus.running())ci.cancel();
    }
    @Redirect(method="blockUsingShield",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;disableShield()V"))
    private void mineturn$playerShieldCooldown(Player defender,LivingEntity attacker) {
        boolean shield=defender.getUseItem().is(Items.SHIELD);
        // Let native blocking and canDisableShield decide whether this call happens at all.
        defender.disableShield();
        if(shield && defender instanceof ServerPlayer player && attacker instanceof ServerPlayer)
            BattleManager.playerShieldDisabled(player,attacker);
    }
}
