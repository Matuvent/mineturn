package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Mixin(MinecraftServer.class)
public abstract class ServerReloadMixin {
    @Inject(method = "reloadResources", at = @At("HEAD"))
    private void mineturn$begin(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        BattleManager.beginReload();
    }
    @Inject(method = "reloadResources", at = @At("RETURN"))
    private void mineturn$finish(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        var server = (MinecraftServer) (Object) this;
        callback.getReturnValue().whenComplete((value, error) -> server.execute(BattleManager::finishReload));
    }
}
