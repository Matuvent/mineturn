package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publish JSON only when Minecraft actually installs the matching compiled function library. */
@Mixin(ServerFunctionManager.class)
public abstract class FunctionLibraryMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void mineturn$initial(MinecraftServer server, ServerFunctionLibrary library, CallbackInfo callback) {
        CombatData.activate(library);
    }
    @Inject(method = "replaceLibrary", at = @At("RETURN"))
    private void mineturn$replace(ServerFunctionLibrary library, CallbackInfo callback) {
        CombatData.activate(library);
    }
}
