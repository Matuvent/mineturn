package com.matuvent.mineturn.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A mount is a movement carrier, never an extra timeline actor. */
final class BattleRiding {
    private static final ConcurrentHashMap<UUID,ServerPlayer> RIDERS=new ConcurrentHashMap<>();
    static LivingEntity vehicle(LivingEntity rider) {
        if(!(rider instanceof ServerPlayer player) || !(rider.getVehicle() instanceof Mob mount)
                || !mount.isAlive() || mount.isPassenger() || mount.getPassengers().size()!=1)return null;
        // Swapping away from a steering item in battle must not discard the carrier.
        return mount.getControllingPassenger()==player || RIDERS.get(mount.getUUID())==player?mount:null;
    }
    static ServerPlayer rider(Entity mount){return RIDERS.get(mount.getUUID());}
    static boolean locked(Entity mount){return RIDERS.containsKey(mount.getUUID());}
    static void clear(){RIDERS.clear();}
    static void forget(BattleSession.Member member){RIDERS.entrySet().removeIf(entry->entry.getValue()==member.entity);}
    static Vec3 seat(LivingEntity rider){var mount=vehicle(rider);return mount==null?Vec3.ZERO:rider.position().subtract(mount.position());}
    static AABB body(LivingEntity entity) {
        var mount=vehicle(entity);
        AABB box=entity.getBoundingBox();
        if(mount!=null)box=box.minmax(mount.getBoundingBox());
        if(entity.getFirstPassenger() instanceof LivingEntity passenger && vehicle(passenger)==entity)box=box.minmax(passenger.getBoundingBox());
        return box;
    }
    static void enter(BattleSession.Member member) {
        var mount=vehicle(member.entity);if(mount==null)return;
        member.mountState=new BattleSession.Member(mount,null);
        member.mountState.statusTicks=mount.tickCount;
        RIDERS.put(mount.getUUID(),(ServerPlayer)member.entity);
        ((Mob)mount).getNavigation().stop();mount.setDeltaMovement(Vec3.ZERO);
        BattleStatus.sync(mount,true);
    }
    static void release(BattleSession.Member member) {
        if(member.mountState==null)return;
        var mount=member.mountState.entity;
        RIDERS.remove(mount.getUUID(),member.entity);BattleStatus.sync(mount,false);member.mountState=null;
    }
    static void maintain(BattleSession battle,BattleSession.Member member) {
        if(member.mountState==null)return;
        var mount=member.mountState.entity;
        if(vehicle(member.entity)!=mount || mount.level()!=battle.level) {
            if(member.entity.getVehicle()==mount)member.entity.stopRiding();
            release(member);member.anchor=member.entity.position();
            if(battle.motion!=null && battle.motion.player==member.entity)battle.cancelRidingMotion(member.entity);
            if(battle.actor==member.entity)battle.budget.limitMovement(battle.movement(member.entity));
            battle.revision++;
        }else {mount.setDeltaMovement(Vec3.ZERO);mount.fallDistance=0;}
    }
    static boolean position(LivingEntity rider,Vec3 point) {
        var mount=vehicle(rider);if(mount==null)return false;
        mount.setPos(mount.position().add(point.subtract(rider.position())));
        mount.positionRider(rider);mount.setDeltaMovement(Vec3.ZERO);mount.fallDistance=0;
        return true;
    }
    static TerrainPath.Result translate(TerrainPath.Result route,Vec3 offset) {
        return new TerrainPath.Result(route.destination().add(offset),route.cost(),
                route.landings().stream().map(l->new TerrainPath.Landing(l.position().add(offset),l.block(),l.distance())).toList(),
                route.samples().stream().map(p->p.add(offset)).toList());
    }
    private BattleRiding(){}
}
