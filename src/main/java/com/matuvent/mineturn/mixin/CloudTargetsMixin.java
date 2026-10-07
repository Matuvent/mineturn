package com.matuvent.mineturn.mixin;

import com.matuvent.mineturn.battle.BattleCloud;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.List;

@Mixin(AreaEffectCloud.class)
public abstract class CloudTargetsMixin {
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> mineturn$participants(Level level,Class<LivingEntity> type,AABB bounds){
        var nearby=level.getEntitiesOfClass(type,bounds);
        return (Object)this instanceof BattleCloud cloud?cloud.participants(nearby):nearby;
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;isAffectedByPotions()Z"))
    private boolean mineturn$target(LivingEntity target){
        return ((Object)this instanceof BattleCloud || com.matuvent.mineturn.battle.BattleManager.allowsRealtimeEffect((AreaEffectCloud)(Object)this,target)) && target.isAffectedByPotions();
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/effect/MobEffect;applyInstantenousEffect(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;ID)V"))
    private void mineturn$instant(net.minecraft.world.effect.MobEffect effect,net.minecraft.world.entity.Entity direct,net.minecraft.world.entity.Entity owner,LivingEntity target,int amplifier,double scale){
        if((Object)this instanceof BattleCloud || com.matuvent.mineturn.battle.BattleManager.allowsRealtimeEffect((AreaEffectCloud)(Object)this,target))effect.applyInstantenousEffect(direct,owner,target,amplifier,scale);
    }
}
