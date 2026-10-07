package com.matuvent.mineturn.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class BattleRaid {
    static boolean canSummon(BattleSession b){return !b.closed && b.members.size()<BattleSession.MAX_MEMBERS && b.definitions.mobs().containsKey("minecraft:vex")
            && b.members.values().stream().noneMatch(m->m.entity instanceof Vex && m.entity.isAlive());}
    static void summon(BattleSession b,Evoker source){
        if(!canSummon(b))return;int created=0;
        for(int i=0;i<8 && created<3 && b.members.size()<BattleSession.MAX_MEMBERS && !b.closed;i++){
            double angle=i*Math.PI/4;Vec3 p=source.position().add(Math.cos(angle)*2,1,Math.sin(angle)*2);
            var vex=EntityType.VEX.create(b.level);if(vex==null)continue;
            vex.moveTo(p.x,p.y,p.z,source.getYRot(),0);
            if(!b.inRegion(vex) || !b.level.hasChunkAt(vex.blockPosition()) || !b.level.getWorldBorder().isWithinBounds(vex.getBoundingBox()) || !b.level.noCollision(vex,vex.getBoundingBox())){vex.discard();continue;}
            vex.finalizeSpawn(b.level,b.level.getCurrentDifficultyAt(vex.blockPosition()),MobSpawnType.MOB_SUMMONED,null);
            vex.setOwner(source);vex.setBoundOrigin(source.blockPosition());vex.setLimitedLife(20*(30+source.getRandom().nextInt(90)));
            if(b.level.addFreshEntity(vex) && vex.isAlive() && !b.closed && b.members.size()<BattleSession.MAX_MEMBERS && !BattleManager.locked(vex)){
                b.add(vex);b.member(vex).allyOwner=b.member(source).allyOwner;created++;b.revision++;
            }else vex.discard();
        }
        source.playSound(net.minecraft.sounds.SoundEvents.EVOKER_CAST_SPELL,1,1);
    }
    static void fangs(BattleSession b,LivingEntity source,LivingEntity target,boolean circle,float damage){
        b.fangs.removeIf(Entity::isRemoved);
        var hits=new HashSet<UUID>();float angle=(float)Math.atan2(target.getZ()-source.getZ(),target.getX()-source.getX());
        for(int i=0;i<(circle?13:12) && b.fangs.size()<96;i++){
            double a=circle?angle+(i<5?i*Math.PI*2/5:(i-5)*Math.PI/4):angle;
            double d=circle?(i<5?1.5:2.5):(i+1)*1.25;
            double x=source.getX()+Math.cos(a)*d,z=source.getZ()+Math.sin(a)*d;
            int top=(int)Math.floor(Math.max(source.getY(),target.getY())+1),bottom=(int)Math.floor(Math.min(source.getY(),target.getY())-1);
            for(int y=top;y>=bottom;y--){
                var pos=BlockPos.containing(x,y,z);
                if(!b.level.hasChunkAt(pos)||!b.level.getWorldBorder().isWithinBounds(pos))break;
                if(!b.level.getBlockState(pos.below()).isFaceSturdy(b.level,pos.below(),Direction.UP))continue;
                var shape=b.level.getBlockState(pos).getCollisionShape(b.level,pos);
                double rise=shape.isEmpty()?0:shape.max(Direction.Axis.Y);
                var fang=new BattleFang(b,source,x,y+rise,z,(float)a);
                if(!b.inRegion(fang) || !b.level.addFreshEntity(fang)){fang.discard();break;}
                b.fangs.add(fang);
                for(var victim:b.enemies(source))if(!hits.contains(victim.getUUID()) && fang.getBoundingBox().inflate(0.2,0,0.2).intersects(victim.getBoundingBox()) && source.hasLineOfSight(victim)){
                    hits.add(victim.getUUID());victim.invulnerableTime=0;
                    victim.hurt(source.damageSources().indirectMagic(source,source),damage);victim.setDeltaMovement(Vec3.ZERO);
                }
                break;
            }
        }
        source.playSound(net.minecraft.sounds.SoundEvents.EVOKER_CAST_SPELL,1,1);
    }
    static void shieldClock(BattleSession b,BattleSession.Member m){
        m.shieldDisabledUntil=BattleItemCooldowns.sync(b,m,net.minecraft.world.item.Items.SHIELD,m.shieldDisabledUntil);
    }
    static void endCharge(BattleSession.Member member){
        member.chargeTarget=null;member.chargeUntil=0;
        if(member.entity instanceof Pillager pillager)pillager.setChargingCrossbow(false);
    }
    private BattleRaid(){}
}
