package com.matuvent.mineturn.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Temporary, single-unit summons. Calls belong to the server-thread effect context. */
public final class CombatSummons {
    public record Request(ResourceLocation entity,Vec3 position,double lifetimeAv){
        public Request {
            java.util.Objects.requireNonNull(entity);java.util.Objects.requireNonNull(position);
            if(!Double.isFinite(position.x)||!Double.isFinite(position.y)||!Double.isFinite(position.z)
                    ||!Double.isFinite(lifetimeAv)||lifetimeAv<1||lifetimeAv>100000)throw new IllegalArgumentException("Invalid summon position/lifetime");
        }
    }
    public record Result(UUID entity,String reason){
        public boolean accepted(){return entity!=null;}
        public static Result rejected(String reason){return new Result(null,reason);}
    }
    private CombatSummons(){}
}
