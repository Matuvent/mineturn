package com.matuvent.mineturn.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public final class Riptide implements CombatEffects.Effect {
    public static boolean action(com.matuvent.mineturn.data.CombatData.Action action){return action.effect().equals("mineturn:riptide");}
    public static double distance(ServerPlayer player,ItemStack stack,com.matuvent.mineturn.data.CombatData.Action action) {
        return Math.min(action.range(),EnchantmentHelper.getTridentSpinAttackStrength(stack,player)
                *CombatEffects.parameter(action,"distance_multiplier",4,0.1,10));
    }
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action action) {
        if(!action.self() || action.ranged()!=null || action.consume()!=0 || !PotionTargets.ground(action))
            throw new IllegalArgumentException("riptide requires self, ground_target, consume=0 and no ranged check");
        CombatEffects.parameter(action,"distance_multiplier",4,0.1,10);
    }
    @Override public String validate(CombatEffects.Context context) {
        if(!(context.source() instanceof ServerPlayer player) || !context.item().is(Items.TRIDENT))return "需要玩家持有激流三叉戟。";
        if(!(EnchantmentHelper.getTridentSpinAttackStrength(context.item(),player)>0))return "三叉戟没有激流效果。";
        if(!wet(player))return "激流需要身处水中或雨中。";
        if(player.isPassenger())return "骑乘时不能使用激流。";
        if(context.item().getCount()!=1 || context.item().isDamageableItem() && context.item().getDamageValue()>=context.item().getMaxDamage()-1)return "需要一把耐久足够的三叉戟。";
        return VanillaCombatItems.slotError(context);
    }
    @Override public void execute(CombatEffects.Context context) {throw new IllegalStateException("Riptide requires a validated destination");}
    private static boolean wet(ServerPlayer player) {
        // Frozen movement can leave vanilla's cached water flag stale; inspect the current body without pushing fluid.
        var level=player.level();var pos=player.blockPosition();var box=player.getBoundingBox().deflate(0.001);
        if(level.isRainingAt(pos) || level.isRainingAt(net.minecraft.core.BlockPos.containing(pos.getX(),player.getBoundingBox().maxY,pos.getZ())))return true;
        for(var point:net.minecraft.core.BlockPos.betweenClosed(net.minecraft.core.BlockPos.containing(box.minX,box.minY,box.minZ),
                net.minecraft.core.BlockPos.containing(box.maxX,box.maxY,box.maxZ))){
            if(!level.hasChunkAt(point))return false;
            var fluid=level.getFluidState(point);
            if(fluid.getFluidType()==net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value() && point.getY()+fluid.getHeight(level,point)>box.minY)return true;
        }
        return false;
    }
    /** One committed contact, using the native spin base damage and weapon snapshot. */
    public static void hit(ServerPlayer player,net.minecraft.world.entity.LivingEntity target,ItemStack weapon) {
        if(!net.neoforged.neoforge.common.CommonHooks.onPlayerAttackTarget(player,target))return;
        var source=new net.minecraft.world.damagesource.DamageSource(player.damageSources().playerAttack(player).typeHolder(),player){
            @Override public ItemStack getWeaponItem(){return weapon;}
        };
        float damage=EnchantmentHelper.modifyDamage(player.serverLevel(),weapon,target,source,8)
                +weapon.getItem().getAttackDamageBonus(target,8,source);
        target.invulnerableTime=0;
        if(target.hurt(source,damage)){
            player.setLastHurtMob(target);
            EnchantmentHelper.doPostAttackEffectsWithItemSource(player.serverLevel(),target,source,weapon);
        }
        target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }
}
