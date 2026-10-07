package com.matuvent.mineturn.battle;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.*;

/** Gate native terrain effects without replacing their predicates or attribute formulas. */
public final class BattleTerrainEnchantments {
    private record Stamp(java.util.UUID battle,net.minecraft.core.BlockPos position,
                         net.minecraft.world.level.block.state.BlockState support,
                         net.minecraft.world.item.ItemStack stack,int level,boolean passenger){}
    private static final java.util.Map<LivingEntity,Stamp> SEEN=new java.util.WeakHashMap<>();
    public static void stopped(Enchantment enchantment,LivingEntity entity){
        if(entity.level() instanceof ServerLevel level && net.minecraft.resources.ResourceLocation.withDefaultNamespace("soul_speed").equals(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getKey(enchantment)))SEEN.remove(entity);
    }
    public static boolean allow(Enchantment enchantment,ServerLevel level,int rank,EnchantedItemInUse item,LivingEntity entity){
        var battle=BattleManager.ACTIVE.get(entity.getUUID());
        if(battle==null){SEEN.remove(entity);return true;}
        var id=level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getKey(enchantment);
        if(net.minecraft.resources.ResourceLocation.withDefaultNamespace("frost_walker").equals(id))return false;
        if(!net.minecraft.resources.ResourceLocation.withDefaultNamespace("soul_speed").equals(id))return true;
        var stamp=new Stamp(battle.id,entity.blockPosition(),level.getBlockState(entity.getBlockPosBelowThatAffectsMyMovement()),item.itemStack(),rank,entity.isPassenger());
        if(stamp.equals(SEEN.get(entity)))return false;
        SEEN.put(entity,stamp);return true;
    }
    static void refresh(LivingEntity entity){
        var level=(ServerLevel)entity.level();var stack=entity.getItemBySlot(EquipmentSlot.FEET);
        var enchantment=level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SOUL_SPEED);
        int rank=EnchantmentHelper.getItemEnchantmentLevel(enchantment,stack);
        if(rank>0)enchantment.value().runLocationChangedEffects(level,rank,new EnchantedItemInUse(stack,EquipmentSlot.FEET,entity),entity);
    }
    private BattleTerrainEnchantments(){}
}
