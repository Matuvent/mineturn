package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Mixin(MinecraftServer.class)
public abstract class ServerReloadMixin {
    @WrapMethod(method="reloadResources")
    private CompletableFuture<Void> mineturn$reload(Collection<String> packs,Operation<CompletableFuture<Void>> original){
        return BattleManager.trackReload(()->original.call(packs));
    }
}
