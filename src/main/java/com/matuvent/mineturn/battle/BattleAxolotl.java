package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.effect.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

final class BattleAxolotl {
    static void begin(BattleSession b,Axolotl axolotl,int ticks){
        axolotl.getBrain().setMemory(MemoryModuleType.PLAY_DEAD_TICKS,ticks);
        axolotl.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        axolotl.setPlayingDead(true);b.member(axolotl).axolotlPlaying=true;
        axolotl.addEffect(new MobEffectInstance(MobEffects.REGENERATION,ticks,0),axolotl);
        b.message("美西螈装死，"+(ticks*5)+" AV 后苏醒；期间不能行动。");b.revision++;
    }
    static void enter(BattleSession b,LivingEntity entity){
        if(entity instanceof Axolotl a){int remaining=a.getBrain().getMemory(MemoryModuleType.PLAY_DEAD_TICKS).orElse(0);if(remaining>0)begin(b,a,remaining);}
    }
    static void tick(BattleSession b,BattleSession.Member member){
        if(!(member.entity instanceof Axolotl a))return;
        if(!member.axolotlPlaying){a.getBrain().eraseMemory(MemoryModuleType.PLAY_DEAD_TICKS);return;}
        int left=a.getBrain().getMemory(MemoryModuleType.PLAY_DEAD_TICKS).orElse(0)-1;
        if(left<=0 || !AquaticPath.inWater(b.level,a.position())){
            a.getBrain().eraseMemory(MemoryModuleType.PLAY_DEAD_TICKS);a.setPlayingDead(false);member.axolotlPlaying=false;
            b.message("美西螈苏醒，可以重新行动。");b.revision++;
        }else a.getBrain().setMemory(MemoryModuleType.PLAY_DEAD_TICKS,left);
    }
    static void damage(LivingDamageEvent.Post event){
        if(event.getNewDamage()<=0 || event.getEntity().level().isClientSide)return;
        var victim=event.getEntity();var b=BattleManager.ACTIVE.get(victim.getUUID());if(b==null)return;
        var source=event.getSource().getEntity();
        if(victim instanceof Axolotl a && a.isAlive() && source instanceof LivingEntity attacker && b.members.containsKey(attacker.getUUID()) && b.enemy(attacker,a)
                && !b.member(a).axolotlPlaying && AquaticPath.inWater(b.level,a.position())
                && (a.getHealth()<=a.getMaxHealth()/2 || a.getBrain().getMemory(MemoryModuleType.PLAY_DEAD_TICKS).isPresent()))begin(b,a,200);
        if(source instanceof Axolotl a && b.members.containsKey(a.getUUID()) && b.enemy(a,victim))b.member(a).assistedTargets.add(victim.getUUID());
        if(!victim.isDeadOrDying() || !(source instanceof ServerPlayer player) || !b.members.containsKey(player.getUUID()))return;
        for(var member:b.members.values())if(member.entity instanceof Axolotl a && member.assistedTargets.remove(victim.getUUID())
                && a.isAlive() && player.getUUID().equals(member.allyOwner) && a.distanceToSqr(player)<=400 && b.enemy(a,victim))a.applySupportingEffects(player);
    }
    private BattleAxolotl(){}
}
