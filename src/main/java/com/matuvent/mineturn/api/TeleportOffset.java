package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.world.phys.Vec3;

/** Datapack self-teleport in world axes, also callable by function AI through the ordinary action command. */
final class TeleportOffset implements CombatEffects.Effect {
    private static Vec3 offset(CombatData.Action a){return new Vec3(RaidEffects.parameter(a,"dx",0),RaidEffects.parameter(a,"dy",0),RaidEffects.parameter(a,"dz",0));}
    private static boolean flag(CombatData.Action a,String key,boolean fallback){
        var value=a.parameters().get(key);
        if(value==null)return fallback;
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(key+" must be boolean");
        return value.getAsBoolean();
    }
    private static CombatTeleport.Rules rules(CombatData.Action a){return new CombatTeleport.Rules(a.range(),flag(a,"require_sight",true),flag(a,"allow_water",false));}
    @Override public void validateDefinition(CombatData.Action a){
        if(!a.self() || a.ranged()!=null || PotionTargets.ground(a))throw new IllegalArgumentException("teleport_offset requires a self action without ranged/ground targeting");
        var offset=offset(a);var rules=rules(a);double distance=Math.abs(offset.x)+Math.abs(offset.y)+Math.abs(offset.z);
        if(!Double.isFinite(distance) || distance<0.01 || distance>rules.range())throw new IllegalArgumentException("Invalid teleport offset");
    }
    @Override public String validate(CombatEffects.Context c){return c.battle().previewTeleport(c.source(),c.source().position().add(offset(c.action())),rules(c.action())).error();}
    @Override public void execute(CombatEffects.Context c){
        var result=c.battle().teleportTo(c.source(),c.source().position().add(offset(c.action())),rules(c.action()));
        if(!result.success())throw new IllegalArgumentException(result.error());
    }
}
