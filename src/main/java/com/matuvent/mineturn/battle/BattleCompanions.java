package com.matuvent.mineturn.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Bee;
import com.matuvent.mineturn.mixin.BeeLifeAccess;

final class BattleCompanions {
    static boolean hostileTo(BattleSession b,Bee bee,LivingEntity other){
        var member=b.members.get(bee.getUUID());
        var target=member!=null && member.beeTarget!=null?member.beeTarget:bee.getPersistentAngerTarget();
        return target!=null && (bee.hasStung() || bee.getRemainingPersistentAngerTime()>0)
                && (target.equals(other.getUUID()) || target.equals(b.side(other)));
    }
    static void provoke(Bee source,ServerPlayer player){
        if(player.isCreative() || player.isSpectator())return;
        var b=BattleManager.ACTIVE.get(source.getUUID());int count=0;
        if(!source.hasStung()){
            source.setPersistentAngerTarget(player.getUUID());source.startPersistentAngerTimer();source.setTarget(player);
            if(b!=null)b.member(source).beeTarget=player.getUUID();
        }
        var bees=source.level().getEntitiesOfClass(Bee.class,source.getBoundingBox().inflate(8)).stream()
                .filter(bee->bee.isAlive() && !bee.hasStung() && bee.distanceToSqr(source)<=64)
                .sorted(java.util.Comparator.comparingDouble(source::distanceToSqr)).toList();
        for(var bee:bees){
            if(bee==source)continue;
            var occupied=BattleManager.ACTIVE.get(bee.getUUID());
            if(occupied!=null && occupied!=b || bee!=source && bee.getPersistentAngerTarget()!=null && !bee.getPersistentAngerTarget().equals(player.getUUID()))continue;
            if(count++>=15)break;
            bee.setPersistentAngerTarget(player.getUUID());bee.startPersistentAngerTimer();bee.setTarget(player);
            if(occupied!=null)occupied.member(bee).beeTarget=player.getUUID();
        }
        if(b!=null)b.recruit();
    }
    static void tick(LivingEntity entity){
        if(entity instanceof Bee bee){
            var access=(BeeLifeAccess)bee;
            if(bee.getRemainingPersistentAngerTime()>0){bee.setRemainingPersistentAngerTime(bee.getRemainingPersistentAngerTime()-1);if(bee.getRemainingPersistentAngerTime()==0)bee.stopBeingAngry();}
            int wet=bee.isInWaterOrBubble()?access.mineturn$wetTicks()+1:0;access.mineturn$wetTicks(wet);
            if(wet>20 && wet%10==1)bee.hurt(bee.damageSources().drown(),1);
            if(bee.hasStung()){
                int age=access.mineturn$stingAge()+1;access.mineturn$stingAge(age);
                if(age%5==0 && bee.getRandom().nextInt(Math.clamp(1200-age,1,1200))==0)bee.hurt(bee.damageSources().generic(),bee.getHealth());
            }
        }
        if(entity instanceof net.minecraft.world.entity.animal.SnowGolem && BattleStatus.effectTick(entity)%10==0 && entity.isInWaterRainOrBubble())entity.hurt(entity.damageSources().drown(),1);
        if(entity instanceof net.minecraft.world.entity.animal.SnowGolem && BattleStatus.effectTick(entity)%10==0 && entity.level().getBiome(entity.blockPosition()).is(net.minecraft.tags.BiomeTags.SNOW_GOLEM_MELTS))
            entity.hurt(entity.damageSources().onFire(),1);
    }
    private BattleCompanions(){}
}
