package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Native explosion calculation, restricted before knockback and entity callbacks as well as damage. */
final class BattleExplosion {
    private record Scope(BattleSession battle,LivingEntity source) {}
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    static void filter(ExplosionEvent.Detonate event) {
        var scope=CURRENT.get();
        if(scope!=null && event.getExplosion().getDirectSourceEntity()==scope.source())
            event.getAffectedEntities().removeIf(entity->!allowed(scope,entity));
    }
    private static boolean allowed(Scope scope,Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && scope.battle().members.containsKey(entity.getUUID())
                && scope.battle().enemy(scope.source(),living);
    }
    static void explode(BattleSession battle,Creeper source) {
        var tag=new net.minecraft.nbt.CompoundTag();source.addAdditionalSaveData(tag);
        float power=Math.clamp(tag.getByte("ExplosionRadius"),0,16)*(source.isPowered()?2:1);
        try{at(battle,source,source.position(),power);}finally{source.discard();}
    }
    static void at(BattleSession battle,LivingEntity source,net.minecraft.world.phys.Vec3 point,float power) {
        var scope=new Scope(battle,source);var previous=CURRENT.get();CURRENT.set(scope);
        var targets=battle.enemies(source);
        try {
            for(var target:targets)target.invulnerableTime=0;
            battle.level.explode(source,null,new ExplosionDamageCalculator(){
                @Override public boolean shouldDamageEntity(Explosion explosion,Entity entity){return allowed(scope,entity);}
                @Override public float getKnockbackMultiplier(Entity entity){return 0;}
            },point.x,point.y,point.z,power,false,Level.ExplosionInteraction.NONE,
                    net.minecraft.core.particles.ParticleTypes.EXPLOSION,net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
        }finally {
            for(var target:targets)target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        }
    }
    private BattleExplosion() {}
}
