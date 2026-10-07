package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

final class SummonEffect implements CombatEffects.Effect {
    private static double number(CombatData.Action a,String key,double fallback){
        var value=a.parameters().get(key);if(value==null)return fallback;
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid summon parameter "+key);
        double n=value.getAsDouble();if(!Double.isFinite(n))throw new IllegalArgumentException("Invalid summon parameter "+key);return n;
    }
    private static ResourceLocation type(CombatData.Action a){
        var value=a.parameters().get("entity");
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Summon requires entity ID");
        var id=ResourceLocation.parse(value.getAsString());
        if(!BuiltInRegistries.ENTITY_TYPE.containsKey(id))throw new IllegalArgumentException("Unknown summon entity");return id;
    }
    private static Vec3 offset(CombatData.Action a){return new Vec3(number(a,"dx",2),number(a,"dy",0),number(a,"dz",0));}
    @Override public void validateDefinition(CombatData.Action a){
        type(a);new CombatSummons.Request(type(a),offset(a),number(a,"lifetime_av",300));
        if(offset(a).lengthSqr()>64 || !a.self() || a.consume()!=0 || a.ranged()!=null || a.amount()!=0)
            throw new IllegalArgumentException("Summon requires self, amount=0, consume=0, no ranged and offset within 8 blocks");
    }
    private static CombatSummons.Request request(CombatEffects.Context c){return new CombatSummons.Request(type(c.action()),c.source().position().add(offset(c.action())),number(c.action(),"lifetime_av",300));}
    @Override public String validate(CombatEffects.Context c){return c.battle().previewSummon(request(c));}
    @Override public void execute(CombatEffects.Context c){
        var result=c.battle().summon(request(c));
        if(!result.accepted() && c.source() instanceof net.minecraft.server.level.ServerPlayer player)
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(result.reason()),false);
    }
}
