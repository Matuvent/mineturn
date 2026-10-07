package com.matuvent.mineturn.mixin;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.item.enchantment.Enchantment.class)
public abstract class TerrainEnchantmentMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method="stopLocationBasedEffects",at=@org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void mineturn$forget(int rank,net.minecraft.world.item.enchantment.EnchantedItemInUse item,
            net.minecraft.world.entity.LivingEntity entity,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
        com.matuvent.mineturn.battle.BattleTerrainEnchantments.stopped((net.minecraft.world.item.enchantment.Enchantment)(Object)this,entity);
    }
    @org.spongepowered.asm.mixin.injection.Inject(method="runLocationChangedEffects",at=@org.spongepowered.asm.mixin.injection.At("HEAD"),cancellable=true)
    private void mineturn$terrain(net.minecraft.server.level.ServerLevel level,int rank,
            net.minecraft.world.item.enchantment.EnchantedItemInUse item,net.minecraft.world.entity.LivingEntity entity,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
        if(!com.matuvent.mineturn.battle.BattleTerrainEnchantments.allow((net.minecraft.world.item.enchantment.Enchantment)(Object)this,level,rank,item,entity))ci.cancel();
    }
}
