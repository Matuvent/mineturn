package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.resources.ResourceLocation;

final class CompanionEffects {
    static void register(){
        CombatEffects.register(ResourceLocation.parse("mineturn:bee_sting"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,false);}
            public String validate(CombatEffects.Context c){return c.source() instanceof net.minecraft.world.entity.animal.Bee bee && !bee.hasStung()?null:"蜜蜂已经失去蜂刺。";}
            public void execute(CombatEffects.Context c){
                c.target().invulnerableTime=0;((net.minecraft.world.entity.animal.Bee)c.source()).doHurtTarget(c.target());
                c.target().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
        });
        CombatEffects.register(ResourceLocation.parse("mineturn:snowball_support"),new CombatEffects.Effect(){
            public void validateDefinition(CombatData.Action a){RaidEffects.plain(a,false);RaidEffects.duration(a,"slow_ticks",40);}
            public String validate(CombatEffects.Context c){return c.source() instanceof net.minecraft.world.entity.animal.SnowGolem?null:"需要雪傀儡。";}
            public void execute(CombatEffects.Context c){
                var ball=new net.minecraft.world.entity.projectile.Snowball(c.source().level(),c.source());
                try{c.target().invulnerableTime=0;((com.matuvent.mineturn.mixin.SnowballHitAccess)ball).mineturn$hit(new net.minecraft.world.phys.EntityHitResult(c.target()));}
                finally{ball.discard();c.target().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
                if(c.target().isAlive())c.target().addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,(int)RaidEffects.parameter(c.action(),"slow_ticks",40)),c.source());
                var level=(net.minecraft.server.level.ServerLevel)c.source().level();var start=c.source().getEyePosition();var end=c.target().getEyePosition();
                for(int i=0;i<=16;i++){var p=start.lerp(end,i/16.0);level.sendParticles(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE,p.x,p.y,p.z,1,0,0,0,0);}
                c.source().playSound(net.minecraft.sounds.SoundEvents.SNOW_GOLEM_SHOOT,1,1);
            }
        });
    }
    private CompanionEffects(){}
}
