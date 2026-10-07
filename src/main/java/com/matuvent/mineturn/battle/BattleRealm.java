package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.mixin.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.phys.*;
import java.util.*;

final class BattleRealm {
    static void charged(BattleSession b,LivingEntity source,boolean value){
        b.member(source).blazeCharged=value;((BlazeBattleAccess)source).mineturn$charged(value);
    }
    static void fireball(BattleSession b,LivingEntity source,LivingEntity target,boolean large){
        if(!source.isAlive() || !target.isAlive() || !b.members.containsKey(target.getUUID()) || !b.enemy(source,target) || !source.hasLineOfSight(target))return;
        Vec3 start=source.getEyePosition(),end=target.getBoundingBox().getCenter();
        for(int i=0;i<=16;i++){var p=start.lerp(end,i/16.0);b.level.sendParticles(large?ParticleTypes.FLAME:ParticleTypes.SMALL_FLAME,p.x,p.y,p.z,1,0,0,0,0);}
        target.invulnerableTime=0;
        if(large){
            // Native ghast fireballs combine a direct hit with an explosion. Keep both battle-scoped.
            var projectile=new net.minecraft.world.entity.projectile.LargeFireball(b.level,source,Vec3.ZERO,1);
            try{target.hurt(source.damageSources().fireball(projectile,source),6);}finally{projectile.discard();}
            int power=source instanceof Ghast ghast?Math.clamp(ghast.getExplosionPower(),0,8):1;
            BattleExplosion.at(b,source,end,power);
            source.playSound(net.minecraft.sounds.SoundEvents.GHAST_SHOOT,1,1);
        }else{
            var projectile=new net.minecraft.world.entity.projectile.SmallFireball(b.level,source,Vec3.ZERO);
            try{((SmallFireballAccess)projectile).mineturn$hit(new EntityHitResult(target));}finally{projectile.discard();}
            source.playSound(net.minecraft.sounds.SoundEvents.BLAZE_SHOOT,1,1);
        }
        target.setDeltaMovement(Vec3.ZERO);
    }
    static Vec3 teleportPoint(BattleSession b,LivingEntity source,double range){
        var target=b.nearestEnemy(source);if(target==null)return null;
        var candidates=new ArrayList<Vec3>();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(x==0 && z==0)continue;
            for(int dy=2;dy>=-3;dy--){
                var pos=target.blockPosition().offset(x,dy,z);Vec3 p=Vec3.atBottomCenterOf(pos);
                if(MovementDistance.between(source.position(),p)>range || p.distanceToSqr(source.position())<0.25 || Math.abs(p.y-b.center.y)>6
                        || Math.hypot(p.x-b.center.x,p.z-b.center.z)>BattleSession.RADIUS || !b.level.hasChunkAt(pos))continue;
                var body=source.getBoundingBox().move(p.subtract(source.position()));
                boolean loaded=true;for(var occupied:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),BlockPos.containing(body.maxX,body.maxY,body.maxZ)))
                    if(!b.level.hasChunkAt(occupied)){loaded=false;break;}
                if(loaded && b.level.getWorldBorder().isWithinBounds(body) && b.level.noCollision(source,body) && !b.level.containsAnyLiquid(body)
                        && !b.level.noCollision(source,body.move(0,-0.06,0)))candidates.add(p);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(p->p.distanceToSqr(target.position()))).orElse(null);
    }
    static void teleport(BattleSession b,LivingEntity source,double range){
        Vec3 p=teleportPoint(b,source,range);if(p==null)throw new IllegalArgumentException("没有合法瞬移落点。");
        Vec3 old=source.position();b.place(source,p);
        b.level.sendParticles(ParticleTypes.PORTAL,old.x,old.y+1,old.z,32,0.3,0.8,0.3,0.1);
        b.level.sendParticles(ParticleTypes.PORTAL,p.x,p.y+1,p.z,32,0.3,0.8,0.3,0.1);
        source.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,1,1);
    }
    static boolean canBullet(BattleSession b){return !b.closed && b.members.size()<BattleSession.MAX_MEMBERS
            && b.definitions.mobs().containsKey("mineturn:shulker_bullet_unit")
            && b.members.values().stream().filter(m->m.entity instanceof BattleBullet).count()<16;}
    static void bullet(BattleSession b,LivingEntity source,LivingEntity target){
        if(!canBullet(b))return;
        var unit=MineTurn.BATTLE_BULLET.get().create(b.level);if(unit==null)return;
        Vec3 toward=target.getBoundingBox().getCenter().subtract(source.getEyePosition()).normalize();
        Vec3 start=source.getEyePosition().add(toward.scale(0.8));unit.moveTo(start.x,start.y,start.z,0,0);
        if(!b.inRegion(unit) || !clear(unit,start,start)){unit.discard();return;}
        unit.caster=source.getUUID();unit.target=target.getUUID();unit.setCustomName(net.minecraft.network.chat.Component.literal("潜影追踪弹"));
        if(b.level.addFreshEntity(unit) && unit.isAlive() && !b.closed && b.members.size()<BattleSession.MAX_MEMBERS){
            b.add(unit);b.member(unit).allyOwner=b.member(source).allyOwner;
            var member=b.member(source);member.shulkerCloseAv=b.clock.time()+100;
            ((ShulkerBattleAccess)source).mineturn$peek(100);source.playSound(net.minecraft.sounds.SoundEvents.SHULKER_SHOOT,1,1);b.revision++;
        }else unit.discard();
    }
    static boolean clear(LivingEntity entity,Vec3 from,Vec3 to){
        var box=entity.getBoundingBox().move(from.subtract(entity.position())).expandTowards(to.subtract(from)).deflate(1e-6);var level=entity.level();
        if(box.minY<level.getMinBuildHeight() || box.maxY>level.getMaxBuildHeight() || !level.getWorldBorder().isWithinBounds(box))return false;
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
            if(!level.hasChunkAt(pos)||!level.getFluidState(pos).isEmpty())return false;
        return !level.getBlockCollisions(entity,box).iterator().hasNext();
    }
    static boolean impact(BattleSession b,BattleBullet unit,LivingEntity target,LivingEntity caster){
        if(!unit.getBoundingBox().inflate(0.2).intersects(target.getBoundingBox()))return false;
        hit(b,unit,target,caster);return true;
    }
    private static void hit(BattleSession b,BattleBullet unit,LivingEntity target,LivingEntity caster){
        BattleManager.authorized(()->{
            target.invulnerableTime=0;
            if(target.hurt(caster.damageSources().mobProjectile(unit,caster),4))target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.LEVITATION,200),caster);
            target.setDeltaMovement(Vec3.ZERO);
        });unit.discard();
    }
    static void contact(BattleSession b,LivingEntity mover,Vec3 from,Vec3 to){
        if(mover instanceof BattleBullet)return;
        var sweep=mover.getBoundingBox().move(from.subtract(mover.position())).expandTowards(to.subtract(from));
        for(var member:List.copyOf(b.members.values()))if(member.entity instanceof BattleBullet bullet && bullet.isAlive() && !bullet.isRemoved()
                && sweep.intersects(bullet.getBoundingBox().inflate(0.2))){
            var caster=b.members.get(bullet.caster);
            if(caster!=null && caster.entity.isAlive() && b.enemy(caster.entity,mover)){
                hit(b,bullet,mover,caster.entity);b.remove(bullet,"追踪弹接触目标。");
                if(!mover.isAlive())break;
            }
        }
    }
    static void bulletTurn(BattleSession b,BattleBullet unit){
        var owner=b.members.get(unit.caster);var aim=b.members.get(unit.target);
        if(owner==null || aim==null || !owner.entity.isAlive() || !aim.entity.isAlive() || !b.enemy(owner.entity,aim.entity) || ++unit.turns>12){unit.discard();return;}
        var target=aim.entity;var caster=owner.entity;
        if(impact(b,unit,target,caster))return;
        Vec3 difference=target.getBoundingBox().getCenter().subtract(unit.getBoundingBox().getCenter());
        var directions=new ArrayList<>(List.of(new Vec3(difference.x,0,0),new Vec3(0,difference.y,0),new Vec3(0,0,difference.z)));
        directions.sort(Comparator.comparingDouble(Vec3::lengthSqr).reversed());
        // When the direct axes are blocked, try one-cell perpendicular steps, still without diagonal travel.
        directions.addAll(List.of(new Vec3(0,1,0),new Vec3(1,0,0),new Vec3(0,0,1),new Vec3(-1,0,0),new Vec3(0,0,-1),new Vec3(0,-1,0)));
        for(var delta:directions){
            if(delta.lengthSqr()<1e-6)continue;
            Vec3 offset=delta.normalize().scale(Math.min(b.budget.remaining(),delta.length()));if(offset.length()<0.01)break;
            var samples=new ArrayList<Vec3>();Vec3 start=unit.position(),last=start;double cost=0;
            int steps=Math.max(1,(int)Math.ceil(offset.length()/0.1));
            for(int i=1;i<=steps;i++){
                Vec3 p=start.add(offset.scale(i/(double)steps));
                if(Math.abs(p.y-b.center.y)>6 || Math.hypot(p.x-b.center.x,p.z-b.center.z)>BattleSession.RADIUS || !clear(unit,last,p))break;
                samples.add(p);last=p;cost=MovementDistance.between(start,p);
                if(unit.getBoundingBox().move(p.subtract(start)).inflate(0.2).intersects(target.getBoundingBox()))break;
            }
            if(cost<0.01)continue;
            b.budget.act();b.budget.move(cost);b.motion=new BattleSession.Movement(unit,new TerrainPath.Result(last,cost,List.of(),samples),"bullet");
            b.motion.onLanding=()->{if(caster.isAlive() && target.isAlive() && b.members.containsKey(caster.getUUID()) && b.members.containsKey(target.getUUID()) && b.enemy(caster,target))impact(b,unit,target,caster);};
            b.revision++;return;
        }
    }
    static void tick(BattleSession b,BattleSession.Member m){
        if(m.entity instanceof Shulker && m.shulkerCloseAv>0 && m.shulkerCloseAv<=b.clock.time()){
            ((ShulkerBattleAccess)m.entity).mineturn$peek(0);m.shulkerCloseAv=0;
        }
    }
    private BattleRealm(){}
}
