package com.matuvent.mineturn.mixin;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.entity.player.Player.class)
public interface PlayerEquipmentAccess {
    @org.spongepowered.asm.mixin.gen.Invoker("turtleHelmetTick") void mineturn$turtleHelmet();
}
