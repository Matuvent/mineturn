package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.*;

final class RepairEffects {
    private static void definition(CombatData.Action a){
        if(!a.allied() || a.self() || a.consume()!=1 || a.ranged()!=null)throw new IllegalArgumentException("Repair requires ally target and one material");
    }
    static void register(){
        CombatEffects.register(ResourceLocation.parse("mineturn:golem_repair"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){definition(a);}
            public String validate(CombatEffects.Context c){return c.source() instanceof ServerPlayer && c.target() instanceof IronGolem g && g.getHealth()<g.getMaxHealth() && c.item().is(Items.IRON_INGOT)?null:"需要受伤的友军铁傀儡和铁锭。";}
            public void execute(CombatEffects.Context c){
                // Same heal amount as IronGolem.mobInteract; the battle owns material payment.
                c.target().heal(25);c.target().playSound(net.minecraft.sounds.SoundEvents.IRON_GOLEM_REPAIR,1,1);
            }
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:wolf_armor_repair"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){definition(a);}
            public String validate(CombatEffects.Context c){return c.source() instanceof ServerPlayer p && c.target() instanceof Wolf w && w.isOwnedBy(p)
                    && w.getBodyArmorItem().is(Items.WOLF_ARMOR) && w.getBodyArmorItem().isDamaged()
                    && ArmorMaterials.ARMADILLO.value().repairIngredient().get().test(c.item())?null:"需要自己穿戴受损狼铠的狼和修复材料。";}
            public void execute(CombatEffects.Context c){
                var armor=((Wolf)c.target()).getBodyArmorItem();
                // Native repair fraction, applied to the stack's actual MAX_DAMAGE component.
                armor.setDamageValue(Math.max(0,armor.getDamageValue()-(int)(armor.getMaxDamage()*0.125F)));
                c.target().playSound(net.minecraft.sounds.SoundEvents.WOLF_ARMOR_REPAIR,1,1);
            }
        });
    }
    private RepairEffects(){}
}
