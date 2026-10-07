package com.matuvent.mineturn.api;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;

/** Immediate, battle-scoped firework explosions after a successful timing check. */
final class CombatFireworks implements CombatEffects.Effect {
    @Override public void validateDefinition(com.matuvent.mineturn.data.CombatData.Action action) {
        if(action.ranged()==null || action.consume()!=0 || action.ranged().ammoCount()!=1
                || !action.ranged().ammunition().equals("minecraft:firework_rocket"))
            throw new IllegalArgumentException("firework requires one firework_rocket ammunition");
    }
    @Override public String validate(CombatEffects.Context context){return context.item().is(Items.CROSSBOW)?null:"烟花射击需要弩。";}
    @Override public void execute(CombatEffects.Context context) {
        var ammo=context.battle().ammunition();var fireworks=ammo.get(DataComponents.FIREWORKS);
        if(fireworks==null || fireworks.explosions().isEmpty()){CombatVisuals.send(context.source(),context.target().getBoundingBox().getCenter(),ammo,true,false);return;}
        var source=context.source();var level=(ServerLevel)source.level();
        float base=5+2*fireworks.explosions().size();
        var damage=new LinkedHashMap<LivingEntity,Float>();
        var centers=CombatProjectiles.targets(context,0).stream().map(LivingEntity::position).toList();
        for(Vec3 center:centers) {
            CombatVisuals.send(source,center,ammo,true,true);
            for(var victim:context.battle().participants()) {
                double distance=victim.position().distanceTo(center);
                if(!victim.isAlive() || distance>=5)continue;
                boolean visible=false;
                for(double height:new double[]{0.1,0.5})if(level.clip(new ClipContext(center.add(0,0.1,0),
                        victim.position().add(0,victim.getBbHeight()*height,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,source)).getType()==HitResult.Type.MISS){visible=true;break;}
                if(visible)damage.merge(victim,base*(float)Math.sqrt((5-distance)/5),Math::max);
            }
        }
        Vec3 center=context.target().position();
        var rocket=new FireworkRocketEntity(level,source,center.x,center.y,center.z,ammo);
        try {
            for(var hit:damage.entrySet()) {
                var victim=hit.getKey();if(!victim.isAlive())continue;
                victim.invulnerableTime=0;victim.hurt(source.damageSources().fireworks(rocket,source),hit.getValue());
                victim.setDeltaMovement(Vec3.ZERO);
            }
        }finally{rocket.discard();}
    }
}
