package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void mineturn$movement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!BattleManager.locked(player)) return;
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
        float yaw = packet.getYRot(player.getYRot());
        float pitch = packet.getXRot(player.getXRot());
        if (Float.isFinite(yaw) && Float.isFinite(pitch)) {
            player.setYRot(yaw);
            player.setXRot(Math.clamp(pitch, -90, 90));
        }
        ci.cancel();
    }

    // Only reads the concurrent membership map when called on a network thread.
    @Inject(method = {"handlePlayerAction", "handleUseItem", "handleUseItemOn", "handleInteract",
            "handleContainerClick", "handleContainerButtonClick", "handlePlaceRecipe", "handleSetCreativeModeSlot",
            "handleSetCarriedItem", "handlePlayerCommand", "handlePlayerAbilities", "handleMoveVehicle",
            "handlePlayerInput", "handlePickItem"}, at = @At("HEAD"), cancellable = true)
    private void mineturn$denyVanilla(CallbackInfo ci) {
        if (BattleManager.locked(player)) ci.cancel();
    }
}
