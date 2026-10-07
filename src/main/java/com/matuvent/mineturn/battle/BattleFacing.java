package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Shared server/client heading update, including the body direction inherited from a mount. */
public final class BattleFacing {
    public static void along(LivingEntity entity,Vec3 direction) {
        if(direction.horizontalDistanceSqr()<1e-8)return;
        float yaw=(float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
        if(entity.getVehicle() instanceof LivingEntity mount)turn(mount,yaw);
        turn(entity,yaw);
    }
    private static void turn(LivingEntity entity,float yaw){
        float facing=entity.getYRot()+net.minecraft.util.Mth.wrapDegrees(yaw-entity.getYRot());
        entity.setYRot(facing);entity.setYBodyRot(facing);entity.setYHeadRot(facing);
    }
    private BattleFacing(){}
}
