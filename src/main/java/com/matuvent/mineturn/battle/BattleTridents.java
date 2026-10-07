package com.matuvent.mineturn.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Commit the real recoverable item before the timing minigame. No ephemeral in-memory ownership. */
final class BattleTridents {
    record Returning(ServerPlayer owner,ItemEntity drop,int slot,double at) {}
    static void launch(BattleSession battle,ServerPlayer player,ItemStack weapon,Vec3 landing,int slot,double baseAv) {
        var thrown=weapon.copy();
        thrown.hurtAndBreak(1,player,EquipmentSlot.MAINHAND);
        if(thrown.isEmpty())throw new IllegalArgumentException("三叉戟无法完成投掷。");
        var projectile=new net.minecraft.world.entity.projectile.ThrownTrident(player.level(),player,thrown.copy());
        int loyalty;
        try {loyalty=net.minecraft.util.Mth.clamp(net.minecraft.world.item.enchantment.EnchantmentHelper
                .getTridentReturnToOwnerAcceleration(player.serverLevel(),thrown,projectile),0,127);}
        finally{projectile.discard();}
        if(!player.hasInfiniteMaterials()) {
            var drop=new ItemEntity(player.level(),landing.x,landing.y,landing.z,thrown);
            drop.setDeltaMovement(Vec3.ZERO);drop.setTarget(player.getUUID());drop.setDefaultPickUpDelay();
            drop.setUnlimitedLifetime();
            if(!player.serverLevel().addFreshEntity(drop))throw new IllegalArgumentException("无法生成可回收的三叉戟，本次未投掷。");
            weapon.shrink(1);
            if(loyalty>0)battle.returningTridents.add(new Returning(player,drop,slot,battle.clock.time()+baseAv/loyalty));
        }
        player.inventoryMenu.broadcastChanges();
    }
    static void advance(BattleSession battle) {
        battle.returningTridents.removeIf(pending->{
            if(pending.at()>battle.clock.time()+1e-7)return false;
            recover(pending);return true;
        });
    }
    static void leave(BattleSession battle,ServerPlayer owner) {
        battle.returningTridents.removeIf(pending->{
            if(pending.owner()!=owner)return false;
            recover(pending);return true;
        });
    }
    private static void recover(Returning pending) {
        var owner=pending.owner();var drop=pending.drop();
        // The real entity is the sole source of the item; destroyed/unloaded drops cannot be recreated.
        if(!drop.isAlive() || drop.level().getEntity(drop.getId())!=drop || !owner.isAlive()
                || owner.isRemoved() || owner.level()!=drop.level() || owner.isSpectator())return;
        var stack=drop.getItem();var visual=stack.copyWithCount(1);var origin=drop.position().add(0,0.25,0);
        if(owner.getInventory().getItem(pending.slot()).isEmpty()) {
            owner.getInventory().setItem(pending.slot(),stack);drop.discard();
        }else {
            owner.getInventory().add(stack);
            if(stack.isEmpty())drop.discard();
            else {drop.setItem(stack);drop.setPos(owner.position());drop.setDeltaMovement(Vec3.ZERO);drop.setDefaultPickUpDelay();}
        }
        com.matuvent.mineturn.api.CombatVisuals.flight(owner.serverLevel(),origin,owner.getEyePosition(),visual,false,false);
        owner.inventoryMenu.broadcastChanges();
    }
    private BattleTridents() {}
}
