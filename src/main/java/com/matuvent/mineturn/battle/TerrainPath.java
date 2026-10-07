package com.matuvent.mineturn.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Ground-following straight path. Each rise is at most one block; drops stop at the first solid support. */
public final class TerrainPath {
    public record Landing(Vec3 position, BlockPos block, float distance) {}
    public record Result(Vec3 destination, double cost, List<Landing> landings, List<Vec3> samples) {
        public Result(Vec3 destination,double cost,List<Landing> landings){this(destination,cost,landings,List.of(destination));}
        public Result {landings=List.copyOf(landings);samples=List.copyOf(samples);}
    }
    private record Surface(double y, BlockPos block) {}
    private static final double EPS = 1e-6;

    public static Result trace(LivingEntity entity, Vec3 horizontal, Predicate<Vec3> control) {
        return traceFrom(entity,entity.position(),horizontal,control);
    }
    /** Pure geometry query from a hypothetical position; never teleports the entity during search. */
    public static Result traceFrom(LivingEntity entity,Vec3 start,Vec3 horizontal,Predicate<Vec3> control) {
        var mount=BattleRiding.vehicle(entity);
        if(mount!=null){var seat=BattleRiding.seat(entity);return BattleRiding.translate(traceFrom(mount,start.subtract(seat),horizontal,p->control.test(p.add(seat))),seat);}
        ServerLevel level = (ServerLevel) entity.level();
        Vec3 last = start;
        double length = MovementDistance.ground(horizontal);
        int steps = Math.max(1, (int) Math.ceil(length / 0.1));
        List<Landing> landings = new ArrayList<>();
        List<Vec3> samples = new ArrayList<>();
        double cost = 0;
        for (int step = 1; step <= steps; step++) {
            Vec3 horizontalPoint = start.add(horizontal.scale(step / (double) steps));
            Vec3 base = new Vec3(horizontalPoint.x, last.y, horizontalPoint.z);
            AABB atBase = box(entity, base);
            // Do not load terrain on behalf of a movement/preview packet.
            if (!loaded(level, atBase)) break;
            Surface support = support(level, entity, atBase, last.y + 1);
            if (support == null) break;
            double surface=support.y;boolean water=false;
            var fluidHit=level.clip(new ClipContext(base.add(0,EPS,0),new Vec3(base.x,surface,base.z),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,entity));
            if(AquaticPath.immersed(level,base) && support.y<=last.y+EPS){water=true;surface=last.y;}
            else if(fluidHit.getType()==HitResult.Type.BLOCK && fluidHit.getLocation().y>support.y+EPS && level.getFluidState(fluidHit.getBlockPos()).is(net.minecraft.tags.FluidTags.WATER)){
                water=true;surface=com.matuvent.mineturn.api.CombatMovement.walksOnWater(entity)?fluidHit.getLocation().y:Math.floor(fluidHit.getLocation().y-EPS);
            }
            Vec3 point = new Vec3(base.x, surface, base.z);
            AABB target = box(entity, point);
            double dy = point.y - last.y;
            if (!clear(level, entity, target)) break;
            // Step up vertically before moving forward; move off an edge before dropping vertically.
            AABB swept = dy > EPS ? box(entity, last).expandTowards(0, dy, 0)
                    : target.expandTowards(0, -dy, 0);
            if (!clear(level, entity, swept)) break;
            Vec3 upperStart = new Vec3(last.x, Math.max(last.y, point.y), last.z);
            if (!clear(level, entity, box(entity, upperStart).expandTowards(point.x - last.x, 0, point.z - last.z))) break;
            boolean legal = true;
            var segment = new ArrayList<Vec3>();
            Vec3 corner = dy > EPS ? new Vec3(last.x, point.y, last.z) : base;
            Vec3 previous = last;
            for (Vec3 end : List.of(corner, point)) {
                int count = Math.max(1, (int)Math.ceil(previous.distanceTo(end) / 0.1));
                for (int i = 1; i <= count; i++) {
                    Vec3 checked = previous.lerp(end, i / (double)count);
                    if (!control.test(checked)) { legal = false; break; }
                    segment.add(checked);
                }
                if (!legal) break;
                previous = end;
            }
            if (!legal) break;
            samples.addAll(segment);
            if (dy < -EPS) landings.add(new Landing(point, support.block, water?0:(float) -dy));
            last = point; cost = length * step / steps;
        }
        return new Result(last, cost, landings,samples);
    }
    /** Automatic gravity has no movement cost. Preserve the distance accumulated before battle entry. */
    public static Landing drop(LivingEntity entity) {
        var mount=BattleRiding.vehicle(entity);
        if(mount!=null){var landing=drop(mount);return landing==null?null:new Landing(landing.position().add(BattleRiding.seat(entity)),landing.block(),landing.distance());}
        ServerLevel level = (ServerLevel) entity.level();
        AABB body = entity.getBoundingBox();
        if (!loaded(level, body)) return null;
        Surface support = support(level, entity, body, entity.getY());
        double ground = support == null ? level.getMinBuildHeight() - 65 : support.y;
        BlockPos block = support == null ? BlockPos.containing(entity.getX(), ground - 1, entity.getZ()) : support.block;
        // Water/lava intercept an automatic fall. Tactical movement into liquids remains disabled.
        var fluid = level.clip(new ClipContext(entity.position().add(0, EPS, 0),
                new Vec3(entity.getX(), ground, entity.getZ()), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, entity));
        boolean inFluid = fluid.getType() == HitResult.Type.BLOCK && !level.getFluidState(fluid.getBlockPos()).isEmpty()
                && fluid.getLocation().y >= ground;
        if (inFluid) { ground = fluid.getLocation().y; block = fluid.getBlockPos();
            if(level.getFluidState(block).is(net.minecraft.tags.FluidTags.WATER) && !com.matuvent.mineturn.api.CombatMovement.walksOnWater(entity))ground=Math.floor(ground-EPS);
        }
        double distance = entity.getY() - ground;
        if (distance <= EPS) return null;
        Vec3 target = new Vec3(entity.getX(), ground, entity.getZ());
        if (!level.noCollision(entity, BattleRiding.body(entity).move(target.subtract(entity.position())).expandTowards(0, distance, 0).deflate(EPS))) return null;
        return new Landing(target, block, inFluid ? 0 : (float) (distance + entity.fallDistance));
    }
    private static AABB box(LivingEntity entity, Vec3 point) {
        return entity.getBoundingBox().move(point.subtract(entity.position()));
    }
    private static boolean loaded(ServerLevel level, AABB box) {
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++)
                if (!level.hasChunkAt(new BlockPos(x, (int) box.minY, z))) return false;
        return true;
    }
    private static boolean clear(ServerLevel level, LivingEntity entity, AABB box) {
        if(entity.getFirstPassenger() instanceof LivingEntity passenger && BattleRiding.vehicle(passenger)==entity) {
            var nativeBox=entity.getBoundingBox();var full=BattleRiding.body(entity);
            box=new AABB(box.minX+full.minX-nativeBox.minX,box.minY,box.minZ+full.minZ-nativeBox.minZ,
                    box.maxX+full.maxX-nativeBox.maxX,box.maxY+full.maxY-nativeBox.maxY,box.maxZ+full.maxZ-nativeBox.maxZ);
        }
        AABB interior = box.deflate(EPS);
        if(!level.getWorldBorder().isWithinBounds(box) || !level.noCollision(entity,interior))return false;
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(interior.minX,interior.minY,interior.minZ),BlockPos.containing(interior.maxX,interior.maxY,interior.maxZ))){
            var fluid=level.getFluidState(pos);if(!fluid.isEmpty() && !fluid.is(net.minecraft.tags.FluidTags.WATER))return false;
        }
        return true;
    }
    private static Surface support(ServerLevel level, LivingEntity entity, AABB footprint, double ceiling) {
        AABB column = new AABB(footprint.minX + EPS, level.getMinBuildHeight(), footprint.minZ + EPS,
                footprint.maxX - EPS, ceiling + EPS, footprint.maxZ - EPS);
        double highest = Double.NEGATIVE_INFINITY;
        BlockPos block = null;
        CollisionContext context = CollisionContext.of(entity);
        // Search downwards and stop as soon as lower blocks cannot contain a higher support.
        for (int y = (int) Math.floor(ceiling); y >= level.getMinBuildHeight(); y--) {
        if (highest >= y + 2) break;
        for (int x = (int) Math.floor(column.minX); x <= (int) Math.floor(column.maxX); x++)
        for (int z = (int) Math.floor(column.minZ); z <= (int) Math.floor(column.maxZ); z++) {
        BlockPos pos = new BlockPos(x, y, z);
        var shape = level.getBlockState(pos).getCollisionShape(level, pos, context).move(x, y, z);
        for (AABB solid : shape.toAabbs()) {
            if (solid.maxY > ceiling + EPS || solid.maxY <= highest || solid.maxX <= column.minX || solid.minX >= column.maxX
                    || solid.maxZ <= column.minZ || solid.minZ >= column.maxZ) continue;
            highest = solid.maxY;
            block = pos;
        }
        }
        }
        return block == null ? null : new Surface(highest, block);
    }
}
