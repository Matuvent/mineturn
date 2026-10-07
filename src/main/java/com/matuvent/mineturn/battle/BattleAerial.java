package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleTypes;
import java.util.UUID;

/** A fixed route, a completed defender turn, and a separate low-altitude recovery window. */
final class BattleAerial {
    record Plan(UUID target,Vec3 start,Vec3 aim,TerrainPath.Result route,long defenderTurns,double readyAv){}
    static boolean canPrepare(BattleSession b,LivingEntity source,LivingEntity target,double range){
        return b.actor==source && b.members.values().stream().noneMatch(m->m.airPlan!=null)
                && !recovering(b,b.member(source)) && b.diveRoute(source,target,range)!=null;
    }
    static void prepare(BattleSession b,LivingEntity source,LivingEntity target,double range){
        var route=b.diveRoute(source,target,range);
        if(route==null || !canPrepare(b,source,target,range))throw new IllegalArgumentException("没有可锁定的俯冲航线。");
        b.member(source).airPlan=new Plan(target.getUUID(),source.position(),target.position(),route,b.member(target).completedTurns,b.clock.time()+100);
        b.message(source.getName().getString()+" 锁定俯冲航线："+target.getName().getString()+" 可在自己的回合侧移或寻找遮挡。");
        b.revision++;preview(b,b.member(source));
    }
    static boolean recovering(BattleSession b,BattleSession.Member m){
        if(m.airRecoveryTarget==null)return false;
        var target=b.members.get(m.airRecoveryTarget);
        if(b.clock.time()<m.airRecoveryUntil || target!=null && target.completedTurns<=m.airRecoveryTurns)return true;
        return false;
    }
    static void recovery(BattleSession b,BattleSession.Member m,UUID target){
        m.airPlan=null;m.airRecoveryTarget=target;m.airRecoveryUntil=b.clock.time()+100;
        var defender=b.members.get(target);m.airRecoveryTurns=defender==null?-1:defender.completedTurns;
        b.message(m.entity.getName().getString()+" 进入俯冲恢复，暂时无法再次移动或攻击。");b.revision++;
    }
    static boolean turn(BattleSession b,BattleSession.Member m){
        if(recovering(b,m))return true;
        m.airRecoveryTarget=null;
        var plan=m.airPlan;if(plan==null)return false;
        var defender=b.members.get(plan.target());
        if(defender==null || !defender.entity.isAlive() || !b.enemy(m.entity,defender.entity)){
            recovery(b,m,plan.target());return true;
        }
        if(defender.completedTurns<=plan.defenderTurns() || b.clock.time()<plan.readyAv())return true;
        if(!b.budget.canAct())return true;
        b.budget.act();
        var definition=b.definitions.actions().get("mineturn:phantom_telegraph");
        if(definition!=null)m.cooldowns.put("mineturn:phantom_telegraph",b.clock.time()+definition.cooldown());
        recovery(b,m,plan.target());
        if(m.entity.position().distanceToSqr(plan.start())>0.01 || plan.route().cost()>b.budget.remaining())return true;
        b.budget.move(plan.route().cost());b.motion=new BattleSession.Movement(m.entity,plan.route(),"flying");
        b.motion.onLanding=()->{
            var target=defender.entity;
            // Never re-aim: only the originally threatened location is struck.
            if(target.isAlive() && b.members.containsKey(target.getUUID()) && b.enemy(m.entity,target)
                    && target.position().distanceToSqr(plan.aim())<=0.36 && m.entity.hasLineOfSight(target)
                    && BattleManager.gap(m.entity.getBoundingBox(),target.getBoundingBox())<=1.5){
                var action=b.definitions.actions().get("mineturn:mob_melee");
                if(action!=null)b.execute(m.entity,target,"mineturn:mob_melee",action,ItemStack.EMPTY);
            }
        };
        m.entity.playSound(net.minecraft.sounds.SoundEvents.PHANTOM_SWOOP,1,1);b.revision++;return true;
    }
    static void preview(BattleSession b,BattleSession.Member m){
        if(m.airPlan!=null){
            var samples=m.airPlan.route().samples();
            for(int i=0;i<samples.size();i+=8){var p=samples.get(i);b.level.sendParticles(ParticleTypes.ELECTRIC_SPARK,p.x,p.y+0.3,p.z,1,0,0,0,0);}
            var p=m.airPlan.aim();b.level.sendParticles(ParticleTypes.CRIT,p.x,p.y+0.2,p.z,6,0.35,0.1,0.35,0);
        }else if(recovering(b,m)){
            var p=m.entity.position();b.level.sendParticles(ParticleTypes.CRIT,p.x,p.y+0.5,p.z,2,0.2,0.1,0.2,0);
        }
    }
    private BattleAerial(){}
}
