package com.matuvent.mineturn.api;

import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Fixed cylindrical fields. First pulse is after intervalAv; expiry wins at equal AV. */
public final class CombatFields {
    public record Request(Vec3 center,double radius,double height,double intervalAv,double lifetimeAv,boolean onEnter,int charges,double coreHealth){
        public Request(Vec3 center,double radius,double height,double intervalAv,double lifetimeAv){this(center,radius,height,intervalAv,lifetimeAv,false,0,0);}
        public Request {
            java.util.Objects.requireNonNull(center);
            if((onEnter ? charges<1||charges>16 : charges!=0) || !Double.isFinite(coreHealth)||coreHealth<0||coreHealth>1000
                    || !Double.isFinite(center.x)||!Double.isFinite(center.y)||!Double.isFinite(center.z)
                    ||!Double.isFinite(radius)||radius<=0||radius>4||!Double.isFinite(height)||height<=0||height>4
                    ||!Double.isFinite(intervalAv)||intervalAv<5||intervalAv>10000
                    ||!Double.isFinite(lifetimeAv)||lifetimeAv<=intervalAv||lifetimeAv>10000)
                throw new IllegalArgumentException("Invalid field geometry or AV timing");
        }
    }
    public record Result(UUID id,String reason){public boolean accepted(){return id!=null;}}
    private CombatFields(){}
}
