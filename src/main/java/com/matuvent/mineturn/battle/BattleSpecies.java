package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.api.GuardianBeamAccess;
import com.matuvent.mineturn.api.RavagerBattleAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/** Species state shares the AV clock; no native world AI or unfiltered area attack is run. */
final class BattleSpecies {
    static boolean busy(LivingEntity entity){return entity instanceof Ravager r && (r.getStunnedTick()>0 || r.getRoarTick()>0)
            || entity instanceof Guardian g && g.hasActiveAttackTarget();}
    static void stun(BattleSession battle,Ravager ravager){
        if(busy(ravager))return;
        ((RavagerBattleAccess)ravager).mineturn$timers(40,0);
        ravager.playSound(SoundEvents.RAVAGER_STUNNED,1,1);
        battle.message("劫掠兽被盾牌格挡，眩晕 200 AV，恢复后吼叫。");battle.revision++;
    }
    static void tick(BattleSession battle,BattleSession.Member member){
        if(member.entity instanceof Vex vex){
            var life=(com.matuvent.mineturn.mixin.VexLifeAccess)vex;
            if(life.mineturn$limited()){
                int remaining=life.mineturn$life()-1;
                if(remaining<=0){remaining=20;vex.invulnerableTime=0;vex.hurt(vex.damageSources().starve(),1);}
                life.mineturn$life(remaining);
            }
        }
        if(member.entity instanceof Ravager r){
            int stun=r.getStunnedTick(),roar=r.getRoarTick();
            if(stun>0){
                stun--;
                if(stun==0){roar=20;r.playSound(SoundEvents.RAVAGER_ROAR,1,1);}
            }else if(roar>0){
                roar--;
                if(roar==10)roar(battle,r);
            }
            ((RavagerBattleAccess)r).mineturn$timers(stun,roar);
        }
        updateBeam(battle,member);
    }
    static void roar(BattleSession battle,Ravager source){
        BattleManager.authorized(()->{
            for(var target:battle.enemies(source)){
                if(target instanceof AbstractIllager || target instanceof Ravager
                        || !source.getBoundingBox().inflate(4).intersects(target.getBoundingBox())
                        || battle.level.clip(new net.minecraft.world.level.ClipContext(source.getEyePosition(),target.getEyePosition(),
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,source)).getType()!=net.minecraft.world.phys.HitResult.Type.MISS)continue;
                target.invulnerableTime=0;target.hurt(source.damageSources().mobAttack(source),6);target.setDeltaMovement(Vec3.ZERO);
            }
        });
        var point=source.getBoundingBox().getCenter();
        battle.level.sendParticles(ParticleTypes.POOF,point.x,point.y,point.z,40,1,0.5,1,0.05);
        battle.message("劫掠兽吼叫！");battle.revision++;
    }
    static void beginBeam(BattleSession battle,LivingEntity source,LivingEntity target,double duration){
        var member=battle.member(source);
        if(!(source instanceof Guardian guardian))throw new IllegalArgumentException("Beam requires guardian");
        member.beamTarget=target.getUUID();member.lastBeamTarget=target.getUUID();member.beamStart=battle.clock.time();member.beamEnd=member.beamStart+duration;
        guardian.setTarget(target);((GuardianBeamAccess)guardian).mineturn$beam(target.getId(),0);
        guardian.playSound(SoundEvents.GUARDIAN_ATTACK,1,1);
    }
    static void updateSpines(BattleSession battle,BattleSession.Member member){
        if(member.entity instanceof Guardian guardian)((GuardianBeamAccess)guardian).mineturn$spines(!guardian.hasActiveAttackTarget() && (battle.motion==null || battle.motion.player!=guardian));
    }
    static void updateBeam(BattleSession battle,BattleSession.Member member){
        if(member.beamTarget==null)return;
        var target=battle.members.get(member.beamTarget);
        var guardian=(Guardian)member.entity;
        if(target==null || !target.entity.isAlive() || !guardian.hasLineOfSight(target.entity)
                || guardian.distanceTo(target.entity)>member.beamRange)clearBeam(member);
        else ((GuardianBeamAccess)guardian).mineturn$beam(target.entity.getId(),(int)((battle.clock.time()-member.beamStart)/BattleStatus.AV_PER_TICK));
    }
    static void clearBeam(BattleSession.Member member){
        if(member.entity instanceof Guardian guardian){((GuardianBeamAccess)guardian).mineturn$beam(0,-1);guardian.setTarget(null);}
        member.beamTarget=null;
    }
    static void clear(BattleSession battle,LivingEntity entity){
        for(var member:battle.members.values())if(member.entity==entity || entity.getUUID().equals(member.beamTarget))clearBeam(member);
        if(entity instanceof Ravager r)((RavagerBattleAccess)r).mineturn$timers(-1,-1);
    }
    private BattleSpecies(){}
}
