package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.mixin.SunlightAccess;
import com.matuvent.mineturn.mixin.ZombieSunAccess;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;

/** Vanilla sunlight behavior without running the mob's AI or movement. */
final class BattleSunlight {
    static void tick(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.fireImmune()) return;
        boolean sensitive=mob instanceof AbstractSkeleton || mob instanceof Phantom
                || mob instanceof Zombie && ((ZombieSunAccess)mob).mineturn$sunSensitive();
        if(sensitive && ((SunlightAccess)mob).mineturn$sunBurnTick())expose(mob);
    }
    static void expose(Mob mob) {
        // Phantoms have no vanilla helmet protection logic.
        if(!(mob instanceof Phantom)) {
            ItemStack head=mob.getItemBySlot(EquipmentSlot.HEAD);
            if(!head.isEmpty()) {
                if(head.isDamageableItem()) {
                    var item=head.getItem();
                    head.setDamageValue(head.getDamageValue()+mob.getRandom().nextInt(2));
                    if(head.getDamageValue()>=head.getMaxDamage()) {
                        mob.onEquippedItemBroken(item,EquipmentSlot.HEAD);
                        mob.setItemSlot(EquipmentSlot.HEAD,ItemStack.EMPTY);
                    }
                }
                return;
            }
        }
        mob.igniteForSeconds(8);
    }
    private BattleSunlight(){}
}
