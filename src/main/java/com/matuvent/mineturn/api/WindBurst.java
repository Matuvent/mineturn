package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.phys.Vec3;

/** One paid hit; only battle enemies receive the explicit, collision-checked displacement. */
final class WindBurst implements CombatEffects.Effect {
    private final boolean player;
    WindBurst(boolean player){this.player=player;}
    @Override public void validateDefinition(CombatData.Action a){
        if(a.self() || a.consume()!=(player?1:0) || player && a.ranged()==null || !player && a.ranged()!=null)
            throw new IllegalArgumentException("Invalid wind burst cost/target");
        double distance=RaidEffects.parameter(a,"push_distance",3);
        if(!Double.isFinite(distance)||distance<0||distance>8)throw new IllegalArgumentException("Invalid wind displacement");
    }
    @Override public String validate(CombatEffects.Context c){
        if(!player && !(c.source() instanceof Breeze))return "需要旋风人。";
        if(player && !c.item().is(net.minecraft.world.item.Items.WIND_CHARGE))return "需要风弹。";
        return null;
    }
    @Override public void execute(CombatEffects.Context c){
        Vec3 point=c.target().getBoundingBox().getCenter();
        var level=(ServerLevel)c.source().level();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_SMALL,point.x,point.y,point.z,1,0,0,0,0);
        c.source().playSound(net.minecraft.sounds.SoundEvents.WIND_CHARGE_BURST.value(),1,1);
        for(var victim:c.battle().enemies()){
            if(!victim.isAlive() || victim.getBoundingBox().getCenter().distanceToSqr(point)>9 || !c.source().hasLineOfSight(victim)
                    || level.clip(new net.minecraft.world.level.ClipContext(point,victim.getEyePosition(),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,c.source())).getType()!=net.minecraft.world.phys.HitResult.Type.MISS)continue;
            if(victim==c.target()){
                var projectile=new net.minecraft.world.entity.projectile.windcharge.WindCharge(net.minecraft.world.entity.EntityType.WIND_CHARGE,level);
                projectile.setOwner(c.source());victim.invulnerableTime=0;
                try{victim.hurt(c.source().damageSources().windCharge(projectile,c.source()),(float)c.action().amount());}
                finally{projectile.discard();victim.setDeltaMovement(Vec3.ZERO);}
            }
            if(!victim.isAlive())continue;
            Vec3 direction=victim.position().subtract(c.source().position()).multiply(1,0,1).normalize();
            if(direction.lengthSqr()<1e-6)direction=new Vec3(1,0,0);
            c.battle().displace(victim,direction.scale(RaidEffects.parameter(c.action(),"push_distance",3)).add(0,0.7,0));
        }
    }
}
