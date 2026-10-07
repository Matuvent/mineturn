package com.matuvent.mineturn.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Player swimming permits a head above water and supported banks, never open-air flight. */
public final class AquaticPath {
    public static boolean immersed(Level level,Vec3 point) {
        BlockPos pos=BlockPos.containing(point.add(0,1e-5,0));
        if(!level.hasChunkAt(pos))return false;var fluid=level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && point.y<pos.getY()+fluid.getHeight(level,pos)-1e-6;
    }
    public static Vec3 settleSurface(LivingEntity entity,Vec3 point){
        if(immersed(entity.level(),point) || !inWater(entity.level(),point) || com.matuvent.mineturn.api.CombatMovement.walksOnWater(entity))return point;
        var body=entity.getBoundingBox().move(point.subtract(entity.position())).deflate(1e-6);
        if(!entity.level().noCollision(entity,body.move(0,-0.06,0)))return point;
        var below=BlockPos.containing(point.add(0,-0.2,0));
        return new Vec3(point.x,below.getY(),point.z);
    }
    public static boolean inWater(Level level,Vec3 point) {
        for(double dy:new double[]{0.05,-0.2}){
            Vec3 checked=point.add(0,dy,0);BlockPos pos=BlockPos.containing(checked);
            if(!level.hasChunkAt(pos))continue;
            var fluid=level.getFluidState(pos);
            if(fluid.is(FluidTags.WATER) && checked.y<pos.getY()+fluid.getHeight(level,pos)+1e-6)return true;
        }
        return false;
    }
    public static TerrainPath.Result trace(LivingEntity entity,Vec3 offset,Predicate<Vec3> control) {
        double length=MovementDistance.spatial(offset);
        if(!Double.isFinite(length)||length>40)throw new IllegalArgumentException("Invalid swimming distance");
        Vec3 start=entity.position(),last=start;
        var samples=new ArrayList<Vec3>();
        int steps=Math.max(1,(int)Math.ceil(length/0.1));double cost=0;
        for(int i=1;i<=steps;i++){
            Vec3 point=start.add(offset.scale(i/(double)steps));
            if(!canStep(entity,last,point))break;
            if(!control.test(point))break;
            last=point;cost=length*i/steps;
            samples.add(point);
        }
        return new TerrainPath.Result(last,cost,List.of(),samples);
    }
    /** Shared by preview and animated movement so changing fluids cannot turn swimming into flight. */
    static boolean canStep(LivingEntity entity,Vec3 from,Vec3 point) {return check(entity,from,point,false);}
    static boolean clearStep(LivingEntity entity,Vec3 from,Vec3 point){return check(entity,from,point,true);}
    private static boolean check(LivingEntity entity,Vec3 from,Vec3 point,boolean groundRoute) {
        var level=entity.level();
        var body=entity.getBoundingBox().move(point.subtract(entity.position())).deflate(1e-6);
        var sweep=entity.getBoundingBox().move(from.subtract(entity.position())).expandTowards(point.subtract(from)).deflate(1e-6);
        if(body.minY<level.getMinBuildHeight() || body.maxY>level.getMaxBuildHeight() || !level.getWorldBorder().isWithinBounds(sweep))return false;
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(sweep.minX,sweep.minY,sweep.minZ),BlockPos.containing(sweep.maxX,sweep.maxY,sweep.maxZ)))
            if(!level.hasChunkAt(pos)||level.getFluidState(pos).is(FluidTags.LAVA))return false;
        return level.noCollision(entity,sweep) && (groundRoute || immersed(level,point) || com.matuvent.mineturn.api.CombatMovement.walksOnWater(entity) && inWater(level,point) || !level.noCollision(entity,body.move(0,-0.06,0)));
    }
    private AquaticPath(){}
}
