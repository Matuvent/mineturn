package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import net.minecraft.core.component.DataComponents;

final class RaidEffects {
    static double parameter(CombatData.Action action,String key,double fallback){var p=action.parameters();return p.has(key)?p.get(key).getAsDouble():fallback;}
    static void plain(CombatData.Action a,boolean self){if(a.self()!=self || a.consume()!=0 || a.ranged()!=null)throw new IllegalArgumentException("Invalid mob action target/cost");}
    static void duration(CombatData.Action a,String key,double fallback){double value=parameter(a,key,fallback);if(!Double.isFinite(value)||value<1||value>10000)throw new IllegalArgumentException("Invalid "+key);}
    static void register(){
        CombatEffects.register(ResourceLocation.parse("mineturn:pillager_charge"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){plain(a,false);duration(a,"charge_av",100);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Pillager && !CombatProjectiles.mobCrossbow(c.source()).isEmpty()
                    && c.battle().canChargeCrossbow()?null:"需要持弩，且当前不能重复蓄力。";}
            public void execute(CombatEffects.Context c){
                double ratio=net.minecraft.world.item.enchantment.EnchantmentHelper.modifyCrossbowChargingTime(CombatProjectiles.mobCrossbow(c.source()),c.source(),1.25f)/1.25;
                double delay=parameter(c.action(),"charge_av",100)*Math.clamp(ratio,0.01,1);c.battle().chargeCrossbow(delay);
                c.source().playSound(net.minecraft.sounds.SoundEvents.CROSSBOW_LOADING_START.value(),1,1);
                c.battle().after(delay,shot->{
                    shot.battle().endCrossbowCharge();
                    if(CombatProjectiles.mobCrossbow(shot.source()).isEmpty() || !shot.source().hasLineOfSight(shot.target())
                            || shot.source().distanceTo(shot.target())>shot.action().range() || !shot.battle().enemies().contains(shot.target()))return;
                    CombatProjectiles.crossbow(shot);
                });
            }
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:vindicator_strike"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){plain(a,false);duration(a,"shield_cooldown_av",100);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Vindicator?null:"需要卫道士。";}
            public void execute(CombatEffects.Context c){VanillaCombatItems.mobMelee(c);c.battle().disableShield(parameter(c.action(),"shield_cooldown_av",100));}
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:evoker_summon"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){plain(a,true);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Evoker && c.battle().canSummonVex()?null:"同场已有恼鬼、人数已满或缺少恼鬼注册。";}
            public void execute(CombatEffects.Context c){c.battle().summonVex();}
        });
        for(boolean circle:new boolean[]{false,true})CombatEffects.register(ResourceLocation.parse(circle?"mineturn:fangs_circle":"mineturn:fangs_line"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){plain(a,false);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Evoker?null:"需要唤魔者。";}
            public void execute(CombatEffects.Context c){c.battle().fangs(circle);}
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:witch_potion"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){
                String kind=a.parameters().get("potion").getAsString();
                if(!java.util.Set.of("healing","slowness","harming","poison").contains(kind))throw new IllegalArgumentException("Unknown witch potion");
                plain(a,kind.equals("healing"));
            }
            public String validate(CombatEffects.Context c){return c.source() instanceof Witch?null:"需要女巫。";}
            public void execute(CombatEffects.Context c){
                var potion=switch(c.action().parameters().get("potion").getAsString()){
                    case "healing"->Potions.HEALING;case "slowness"->Potions.SLOWNESS;case "harming"->Potions.HARMING;default->Potions.POISON;};
                var stack=new ItemStack(c.action().self()?Items.POTION:Items.SPLASH_POTION);
                stack.set(DataComponents.POTION_CONTENTS,new PotionContents(potion));
                if(c.action().self())stack.finishUsingItem(c.source().level(),c.source());
                else new CombatSplash(false).execute(new CombatEffects.Context(c.source(),c.target(),stack,c.action(),c.battleTime(),c.battle()));
                c.source().playSound(c.action().self()?net.minecraft.sounds.SoundEvents.WITCH_DRINK:net.minecraft.sounds.SoundEvents.WITCH_THROW,1,1);
                ((net.minecraft.server.level.ServerLevel)c.source().level()).levelEvent(2002,c.target().blockPosition(),stack.get(DataComponents.POTION_CONTENTS).getColor());
            }
        });
    }
    private RaidEffects(){}
}
