package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.world.phys.Vec3;

/** Explicit skill displacement, independent of ordinary attacks and movement budgets. */
final class Repulse implements CombatEffects.Effect {
    private static double parameter(CombatData.Action action,String key,double fallback){
        var value=action.parameters().get(key);
        if(value==null)return fallback;
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Repulse parameter must be numeric: "+key);
        double number=value.getAsDouble();
        if(!Double.isFinite(number))throw new IllegalArgumentException("Invalid repulse parameter: "+key);
        return number;
    }
    @Override public void validateDefinition(CombatData.Action action){
        if(action.self() || action.allied() || action.consume()!=0 || action.amount()!=0)
            throw new IllegalArgumentException("Repulse requires enemy target, consume=0 and amount=0");
        double distance=parameter(action,"distance",3),rise=parameter(action,"rise",0.5);
        if(distance<=0 || distance>8 || rise<0 || rise>2)throw new IllegalArgumentException("Repulse distance must be (0,8], rise [0,2]");
    }
    @Override public void execute(CombatEffects.Context context){
        Vec3 direction=context.target().position().subtract(context.source().position()).multiply(1,0,1);
        if(direction.lengthSqr()<1e-8)direction=new Vec3(1,0,0);
        context.battle().displace(context.target(),direction.normalize().scale(parameter(context.action(),"distance",3))
                .add(0,parameter(context.action(),"rise",0.5),0));
    }
}
