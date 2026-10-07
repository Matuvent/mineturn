package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;

/** Wall contact is required at every sample; this mode cannot fly through open air. */
final class ClimbingPath {
    static boolean supported(LivingEntity entity,Vec3 point){
        var body=entity.getBoundingBox().move(point.subtract(entity.position())).deflate(1e-6);
        return !entity.level().noCollision(entity,body.move(0,-0.08,0))
                || !entity.level().noCollision(entity,body.inflate(0.35,0,0.35).expandTowards(0,-0.55,0));
    }
    static boolean canStep(LivingEntity entity,Vec3 from,Vec3 point){return SpatialPath.canStep(entity,from,point,false) && supported(entity,point);}
    static TerrainPath.Result trace(LivingEntity entity,Vec3 offset,Predicate<Vec3> control){
        Vec3 start=entity.position(),last=start;var samples=new ArrayList<Vec3>();double cost=0;
        int steps=Math.max(1,(int)Math.ceil(MovementDistance.spatial(offset)/0.1));
        for(int i=1;i<=steps;i++){
            Vec3 point=start.add(offset.scale(i/(double)steps));
            if(!canStep(entity,last,point) || !control.test(point))break;
            cost+=MovementDistance.between(last,point);samples.add(point);last=point;
        }
        return new TerrainPath.Result(last,cost,List.of(),samples);
    }
    private ClimbingPath(){}
}
