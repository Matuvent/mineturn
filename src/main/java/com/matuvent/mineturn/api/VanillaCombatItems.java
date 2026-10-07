package com.matuvent.mineturn.api;

import com.matuvent.mineturn.mixin.StatusAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Explicit, targeted adapters. Arbitrary item use is never invoked by the battle GUI. */
final class VanillaCombatItems {
    static String slotError(CombatEffects.Context context) {
        if(!(context.source() instanceof ServerPlayer player))return "这个动作仅供玩家使用。";
        return slot(player,context.item())<0 ? "请将物品放入快捷栏。" : null;
    }
    private static int slot(ServerPlayer player,ItemStack item) {
        for(int i=0;i<9;i++)if(player.getInventory().getItem(i)==item)return i;
        return -1;
    }
    static void melee(CombatEffects.Context context) {
        if(!(context.source() instanceof ServerPlayer player)) {
            context.battle().hurt(context.target(),(float)context.action().amount());return;
        }
        withSelected(context,()->{
            var target=context.target();var stack=context.item();
            // Battle mace attacks are ordinary melee; only the initiating vanilla hit may smash.
            if(stack.getItem() instanceof net.minecraft.world.item.MaceItem)player.resetFallDistance();
            if(!net.neoforged.neoforge.common.CommonHooks.onPlayerAttackTarget(player,target))return;
            var damageSource=player.damageSources().playerAttack(player);
            float base=(float)player.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float damage=EnchantmentHelper.modifyDamage(player.serverLevel(),stack,target,damageSource,base)
                    +stack.getItem().getAttackDamageBonus(target,base,damageSource);
            // Snapshot around the selected target; never query arbitrary world entities for collateral damage.
            var sweepTargets=stack.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SWORD_SWEEP)
                    ? context.battle().enemies().stream().filter(other->other!=target && other.isAlive()
                        && stack.getSweepHitBox(player,target).intersects(other.getBoundingBox())
                        && player.distanceToSqr(other)<player.entityInteractionRange()*player.entityInteractionRange()
                        && player.hasLineOfSight(other)).toList()
                    : java.util.List.<net.minecraft.world.entity.LivingEntity>of();
            float sweepBase=1+(float)player.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO)*base;
            target.invulnerableTime=0;
            if(target.hurt(damageSource,damage)) {
                player.setLastHurtMob(target);
                for(var other:sweepTargets) {
                    other.invulnerableTime=0;
                    float sweepDamage=EnchantmentHelper.modifyDamage(player.serverLevel(),stack,other,damageSource,sweepBase);
                    if(other.hurt(damageSource,sweepDamage))
                        EnchantmentHelper.doPostAttackEffects(player.serverLevel(),other,damageSource);
                }
                for(var other:sweepTargets)other.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                if(!sweepTargets.isEmpty())player.sweepAttack();
                boolean durability=stack.hurtEnemy(target,player);
                EnchantmentHelper.doPostAttackEffects(player.serverLevel(),target,damageSource);
                if(durability && !stack.isEmpty())stack.postHurtEnemy(target,player);
                target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                player.causeFoodExhaustion(0.1f);
            }
        });
    }
    static void mobMelee(CombatEffects.Context context) {
        var mob=context.source();var target=context.target();
        ((StatusAccess)mob).mineturn$equipment();
        var stack=mob.getMainHandItem();var source=mob.damageSources().mobAttack(mob);
        var level=(net.minecraft.server.level.ServerLevel)mob.level();
        float base=(float)mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float damage=EnchantmentHelper.modifyDamage(level,stack,target,source,base)
                +stack.getItem().getAttackDamageBonus(target,base,source);
        target.invulnerableTime=0;
        if(target.hurt(source,damage)) {
            mob.setLastHurtMob(target);
            EnchantmentHelper.doPostAttackEffects(level,target,source);
        }
        target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }
    static void drink(CombatEffects.Context context) {
        withSelected(context,()->{
            ServerPlayer player=(ServerPlayer)context.source();
            ItemStack result=context.item().finishUsingItem(player.level(),player);
            player.getInventory().setItem(player.getInventory().selected,result);
        });
    }
    static boolean drinkable(ItemStack stack){return stack.is(Items.POTION)||stack.is(Items.MILK_BUCKET);}
    static void food(CombatEffects.Context context) {
        ServerPlayer player=(ServerPlayer)context.source();
        var stack=context.item();
        for(int slot=0;slot<36;slot++)if(player.getInventory().getItem(slot)==stack) {
            // Player.eat owns consumption and FOOD.using_converts_to, including last-stack replacement.
            var result=player.eat(player.level(),stack,stack.getFoodProperties(player));
            player.getInventory().setItem(slot,result);
            player.inventoryMenu.broadcastChanges();
            return;
        }
        throw new IllegalStateException("Food left player inventory");
    }
    private static void withSelected(CombatEffects.Context context,Runnable action) {
        ServerPlayer player=(ServerPlayer)context.source();int chosen=slot(player,context.item());
        if(chosen<0)throw new IllegalStateException("Battle item left hotbar");
        int previous=player.getInventory().selected;
        var access=(StatusAccess)player;
        try {
            player.getInventory().selected=chosen;access.mineturn$equipment();action.run();
        }finally {
            player.getInventory().selected=previous;access.mineturn$equipment();
            player.inventoryMenu.broadcastChanges();
        }
    }
    private VanillaCombatItems(){}
}
