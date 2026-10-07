package com.matuvent.mineturn.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Skill displacement ignores movement budgets and engagement, but preserves collision and gravity. */
final class BattleDisplacement {
    static TerrainPath.Result trace(BattleSession battle,LivingEntity entity,Vec3 offset,boolean allowWater,Predicate<Vec3> contact) {
        Predicate<Vec3> inside=p->Math.abs(p.y-battle.center.y)<=6
                && Math.hypot(p.x-battle.center.x,p.z-battle.center.z)<=BattleSession.RADIUS;
        if(!allowWater)return SpatialPath.trace(entity,offset,false,p->inside.test(p) && contact.test(p));
        double distance=MovementDistance.spatial(offset);
        if(!Double.isFinite(distance) || distance>40)throw new IllegalArgumentException("Invalid displacement");
        var start=entity.position();var last=start;var samples=new ArrayList<Vec3>();
        int steps=Math.max(1,(int)Math.ceil(distance/0.08));
        for(int i=1;i<=steps;i++){
            var point=start.add(offset.scale(i/(double)steps));
            if(!inside.test(point) || !contact.test(point) || !AquaticPath.clearStep(entity,last,point))break;
            last=point;samples.add(point);
        }
        return new TerrainPath.Result(last,MovementDistance.between(start,last),List.of(),samples);
    }
    static void apply(BattleSession battle,LivingEntity entity,TerrainPath.Result route) {
        float falling=0;var previous=entity.position();
        for(var point:route.samples()){
            if(AquaticPath.immersed(battle.level,point))falling=0;
            else if(point.y<previous.y)falling+=(float)(previous.y-point.y);
            previous=point;
        }
        Vec3 direction=route.destination().subtract(entity.position());
        if(direction.horizontalDistanceSqr()>1e-8)BattleFacing.along(entity,direction);
        var origin=entity.position();battle.place(entity,route.destination(),false);
        for(var point:route.samples()){
            BattleFields.contact(battle,entity,origin,point,true);origin=point;
            if(!entity.isAlive() || !battle.members.containsKey(entity.getUUID()))return;
        }
        if(entity instanceof net.minecraft.server.level.ServerPlayer || battle.movementMode(entity).equals("ground")) {
            entity.fallDistance=falling;
            if(!AquaticPath.immersed(battle.level,entity.position())){
                var landing=TerrainPath.drop(entity);
                if(landing!=null){battle.place(entity,landing.position());battle.fall(entity,landing);}
                else if(falling>0 && !battle.level.noCollision(entity,entity.getBoundingBox().move(0,-0.06,0)))
                    battle.fall(entity,new TerrainPath.Landing(entity.position(),BlockPos.containing(entity.position().add(0,-0.01,0)),falling));
            }
            entity.fallDistance=0;
        }
    }
    private BattleDisplacement() {}
}
