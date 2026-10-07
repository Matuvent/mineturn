package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.world.phys.Vec3;

final class HazardField implements CombatEffects.Effect {
    private static double number(CombatData.Action a,String key,double fallback){
        var value=a.parameters().get(key);if(value==null)return fallback;
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid field parameter "+key);
        return value.getAsDouble();
    }
    private static boolean enter(CombatData.Action a){
        var value=a.parameters().get("trigger");if(value==null)return false;
        String trigger=value.getAsString();if(!trigger.equals("periodic")&&!trigger.equals("enter"))throw new IllegalArgumentException("Unknown field trigger");return trigger.equals("enter");
    }
    private static int charges(CombatData.Action a){double n=number(a,"charges",enter(a)?1:0);if(n!=Math.rint(n))throw new IllegalArgumentException("charges must be integer");return (int)n;}
    private static Vec3 offset(CombatData.Action a){return new Vec3(number(a,"dx",2),number(a,"dy",0),number(a,"dz",0));}
    private static CombatFields.Request request(CombatData.Action a,Vec3 origin){return new CombatFields.Request(origin.add(offset(a)),number(a,"radius",2),number(a,"height",2),number(a,"interval_av",25),number(a,"lifetime_av",100),enter(a),charges(a),number(a,"core_health",0));}
    @Override public void validateDefinition(CombatData.Action a){
        request(a,Vec3.ZERO);
        if(!a.self()||a.consume()!=0||a.ranged()!=null||offset(a).lengthSqr()>64)throw new IllegalArgumentException("Field requires self, consume=0, no ranged, offset within 8 blocks");
    }
    @Override public String validate(CombatEffects.Context c){return c.battle().previewField(request(c.action(),c.source().position()));}
    @Override public void execute(CombatEffects.Context c){
        var result=c.battle().createField(request(c.action(),c.source().position()),pulse->pulse.battle().hurt(pulse.target(),(float)pulse.action().amount()));
        if(!result.accepted() && c.source() instanceof net.minecraft.server.level.ServerPlayer p)p.displayClientMessage(net.minecraft.network.chat.Component.literal(result.reason()),false);
    }
}
