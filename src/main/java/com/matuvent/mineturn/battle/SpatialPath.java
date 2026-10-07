package com.matuvent.mineturn.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Swept body checks with Manhattan movement cost; never loads chunks or crosses a fluid boundary. */
public final class SpatialPath {
    public static TerrainPath.Result trace(LivingEntity entity, Vec3 offset, boolean swimming, Predicate<Vec3> control) {
        return trace(entity,offset,swimming,false,control);
    }
    static TerrainPath.Result trace(LivingEntity entity,Vec3 offset,boolean swimming,boolean phasing,Predicate<Vec3> control){
        double distance = MovementDistance.spatial(offset);
        if (!Double.isFinite(distance) || distance > 40) throw new IllegalArgumentException("Invalid spatial movement");
        Vec3 start = entity.position(), last = start;
        int steps = Math.max(1, (int) Math.ceil(distance / 0.1));
        double cost = 0;
        var samples=new ArrayList<Vec3>();
        for (int i = 1; i <= steps; i++) {
            Vec3 point = start.add(offset.scale(i / (double) steps));
            if (!canStep(entity,last,point,swimming,phasing) || !control.test(point)) break;
            last = point; cost = distance * i / steps;
            samples.add(point);
        }
        return new TerrainPath.Result(last, cost, List.of(),samples);
    }
    static boolean canStep(LivingEntity entity,Vec3 from,Vec3 point,boolean swimming) {
        return canStep(entity,from,point,swimming,false);
    }
    static boolean canStep(LivingEntity entity,Vec3 from,Vec3 point,boolean swimming,boolean phasing) {
        var level=entity.level();
        AABB body=BattleRiding.body(entity).move(from.subtract(entity.position())).expandTowards(point.subtract(from)).deflate(1e-6);
        if(body.minY<level.getMinBuildHeight() || body.maxY>level.getMaxBuildHeight() || !level.getWorldBorder().isWithinBounds(body))return false;
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),BlockPos.containing(body.maxX,body.maxY,body.maxZ))) {
            if(!level.hasChunkAt(pos))return false;
            var fluid=level.getFluidState(pos);
            if(!phasing && (swimming ? !fluid.is(FluidTags.WATER) || pos.getY()+fluid.getHeight(level,pos)<Math.min(body.maxY,pos.getY()+1)-1e-6 : !fluid.isEmpty()))return false;
        }
        return phasing || level.noCollision(entity,body);
    }
    private SpatialPath() {}
}
