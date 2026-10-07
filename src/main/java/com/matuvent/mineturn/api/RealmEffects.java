package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.*;

final class RealmEffects {
    static void register(){
        CombatEffects.register(ResourceLocation.parse("mineturn:blaze_charge"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,true);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Blaze && c.battle().canChargeBlaze()?null:"无法充能或仍在冷却。";}
            public void execute(CombatEffects.Context c){c.battle().blazeCharge(true);}
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:blaze_volley"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,false);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Blaze && c.battle().blazeCharged()?null:"需要先充能。";}
            public void execute(CombatEffects.Context c){
                c.battle().blazeCharge(false);c.battle().fireball(false);
                c.battle().after(10,shot->shot.battle().fireball(false));
                c.battle().after(20,shot->shot.battle().fireball(false));
            }
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:ghast_fireball"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,false);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Ghast?null:"需要恶魂。";}
            public void execute(CombatEffects.Context c){c.battle().fireball(true);}
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:enderman_teleport"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,true);RaidEffects.duration(a,"teleport_range",8);}
            public String validate(CombatEffects.Context c){return c.source() instanceof EnderMan && c.battle().canTeleport(RaidEffects.parameter(c.action(),"teleport_range",8))?null:"没有安全瞬移落点。";}
            public void execute(CombatEffects.Context c){c.battle().teleport(RaidEffects.parameter(c.action(),"teleport_range",8));}
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:shulker_shot"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,false);}
            public String validate(CombatEffects.Context c){return c.source() instanceof Shulker && c.battle().canLaunchBullet()?null:"追踪弹未注册或数量已满。";}
            public void execute(CombatEffects.Context c){c.battle().launchBullet();}
        });
    }
    private RealmEffects(){}
}
