package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.mixin.FluidAccess;
import com.matuvent.mineturn.mixin.StatusAccess;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

/** Native status ticks on the AV clock. Does not run entity AI, movement, or the full base tick. */
public final class BattleStatus {
    public static final double AV_PER_TICK=5;
    private record Frame(LivingEntity entity,int tick) {}
    private static final ThreadLocal<Frame> CURRENT=new ThreadLocal<>();
    public static boolean running(){return CURRENT.get()!=null;}
    public static int effectTick(LivingEntity entity){var frame=CURRENT.get();return frame!=null && frame.entity==entity ? frame.tick : entity.tickCount;}
    public static void applyClientFire(LivingEntity entity,int fireTicks) {
        if(!entity.level().isClientSide)return;
        Frame previous=CURRENT.get();CURRENT.set(new Frame(entity,entity.tickCount));
        try{entity.setRemainingFireTicks(fireTicks);}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    static void sync(LivingEntity entity,boolean locked) {
        var level=(net.minecraft.server.level.ServerLevel)entity.level();
        for(var effect:entity.getActiveEffects())level.getChunkSource().broadcastAndSend(entity,
                new net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket(entity.getId(),effect,false));
        var payload=new com.matuvent.mineturn.network.BattleNetwork.StatusClock(entity.getId(),entity.getUUID(),locked,entity.getRemainingFireTicks());
        for(var player:level.players())if(player==entity || player.distanceToSqr(entity)<128*128)
            com.matuvent.mineturn.network.BattleNetwork.send(player,payload);
    }
    static void tick(BattleSession.Member member) {
        LivingEntity entity=member.entity;
        Frame previous=CURRENT.get();CURRENT.set(new Frame(entity,++member.statusTicks));
        try { BattleManager.authorized(() -> {
            var battle=BattleManager.ACTIVE.get(entity.getUUID());
            if(battle!=null){BattleSpecies.tick(battle,member);BattleRealm.tick(battle,member);}
            if(battle!=null){BattleItemCooldowns.clock(battle,member);}
            BattleAnger.tick(entity);
            if(!entity.isAlive())return;
            // Native poison/wither/fire may otherwise share invulnerability left by a previous logical tick.
            entity.invulnerableTime=0;
            // Native repeated suffocation hits are separated by its ten-tick hurt window.
            // Track that interval explicitly because each logical status tick clears invulnerability.
            if(entity.isInWall()){
                if(member.suffocationTicks++%10==0)entity.hurt(entity.damageSources().inWall(),1);
                if(member.suffocationTicks>=10)member.suffocationTicks=0;
            }else member.suffocationTicks=0;
            if(!entity.isAlive())return;
            ((FluidAccess)entity).mineturn$updateFluid();((FluidAccess)entity).mineturn$updateEyes();
            BattleCompanions.tick(entity);if(battle!=null)BattleAxolotl.tick(battle,member);if(!entity.isAlive())return;
            entity.setDeltaMovement(Vec3.ZERO);
            // Locked mobs do not run block callbacks; inspect their actual occupied blocks.
            entity.wasInPowderSnow=entity.isInPowderSnow;
            entity.isInPowderSnow=entity.level().getBlockStatesIfLoaded(entity.getBoundingBox().deflate(1e-6))
                    .anyMatch(state->state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW));
            if(entity.getRemainingFireTicks()>0)entity.setTicksFrozen(0);
            boolean extinguished=entity.isInWaterRainOrBubble() || entity.isInPowderSnow
                    || entity.isInFluidType((type,height)->entity.canFluidExtinguish(type));
            if(extinguished || entity.fireImmune())entity.clearFire();
            else {
                boolean fire=entity.level().getBlockStatesIfLoaded(entity.getBoundingBox().deflate(1e-6)).anyMatch(state->state.is(BlockTags.FIRE));
                if(fire || entity.isInLava())entity.igniteForSeconds(entity.isInLava()?15:8);
                int remaining=entity.getRemainingFireTicks();
                if(remaining>0){
                    if(entity.isInLava() ? member.statusTicks%10==0 : fire ? member.statusTicks%20==0 : remaining%20==0)
                        entity.hurt(entity.isInLava()?entity.damageSources().lava():entity.damageSources().onFire(),entity.isInLava()?4:1);
                    entity.setRemainingFireTicks(remaining-1);
                }
            }
            BattleSunlight.tick(entity);
            entity.setSharedFlagOnFire(entity.getRemainingFireTicks()>0);
            if(!entity.isAlive())return;
            var access=(StatusAccess)entity;
            entity.setTicksFrozen(entity.isInPowderSnow && entity.canFreeze()
                    ? Math.min(entity.getTicksRequiredToFreeze(),entity.getTicksFrozen()+1)
                    : Math.max(0,entity.getTicksFrozen()-2));
            access.mineturn$removeFrost();access.mineturn$addFrost();
            if(member.statusTicks%40==0 && entity.isFullyFrozen() && entity.canFreeze())
                entity.hurt(entity.damageSources().freeze(),1);
            if(!entity.isAlive())return;
            int air=entity.getAirSupply();
            if(entity instanceof net.minecraft.world.entity.animal.axolotl.Axolotl)((com.matuvent.mineturn.mixin.AxolotlAirAccess)entity).mineturn$air(air);
            else CommonHooks.onLivingBreathe(entity,air-access.mineturn$decreaseAir(air),access.mineturn$increaseAir(air)-air);
            if(entity.isAlive())access.mineturn$tickEffects();
            if(entity instanceof net.minecraft.server.level.ServerPlayer player){
                ((com.matuvent.mineturn.mixin.PlayerEquipmentAccess)player).mineturn$turtleHelmet();
                // Keep vanilla exhaustion processing without re-enabling its real-time natural regeneration.
                var food=player.getFoodData();
                if(food.getExhaustionLevel()>4){
                    food.setExhaustion(food.getExhaustionLevel()-4);
                    if(food.getSaturationLevel()>0)food.setSaturation(Math.max(0,food.getSaturationLevel()-1));
                    else if(entity.level().getDifficulty()!=net.minecraft.world.Difficulty.PEACEFUL)food.setFoodLevel(Math.max(0,food.getFoodLevel()-1));
                }
                var timer=(com.matuvent.mineturn.mixin.FoodClockAccess)food;
                if(food.getFoodLevel()<=0){
                    int elapsed=timer.mineturn$getTimer()+1;
                    if(elapsed>=80){
                        var difficulty=entity.level().getDifficulty();
                        if(player.getHealth()>10 || difficulty==net.minecraft.world.Difficulty.HARD
                                || player.getHealth()>1 && difficulty==net.minecraft.world.Difficulty.NORMAL)
                            player.hurt(player.damageSources().starve(),1);
                        elapsed=0;
                    }
                    timer.mineturn$setTimer(elapsed);
                }else timer.mineturn$setTimer(0);
            }
            entity.setDeltaMovement(Vec3.ZERO);
        }); }
        finally {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    private BattleStatus(){}
}
