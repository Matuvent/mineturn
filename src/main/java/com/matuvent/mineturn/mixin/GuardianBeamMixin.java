package com.matuvent.mineturn.mixin;
import com.matuvent.mineturn.api.GuardianBeamAccess;

import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Native continuous beam geometry, with charge colour driven by AV rather than wall time. */
@Mixin(Guardian.class)
public abstract class GuardianBeamMixin implements GuardianBeamAccess {
    @Unique private static final EntityDataAccessor<Integer> MINETURN_CHARGE=SynchedEntityData.defineId(Guardian.class,EntityDataSerializers.INT);
    @Shadow abstract void setActiveAttackTarget(int target);
    @Shadow abstract void setMoving(boolean moving);
    @Override public void mineturn$spines(boolean extended){setMoving(!extended);}
    @Inject(method="defineSynchedData",at=@At("TAIL"))
    private void mineturn$define(SynchedEntityData.Builder builder,CallbackInfo ci){builder.define(MINETURN_CHARGE,-1);}
    @Override public void mineturn$beam(int target,int elapsed){
        setActiveAttackTarget(target);
        mineturn$spines(target==0);
        ((Guardian)(Object)this).getEntityData().set(MINETURN_CHARGE,elapsed);
    }
    @Inject(method="getAttackAnimationScale",at=@At("HEAD"),cancellable=true)
    private void mineturn$charge(float partial,CallbackInfoReturnable<Float> cir){
        Guardian self=(Guardian)(Object)this;int elapsed=self.getEntityData().get(MINETURN_CHARGE);
        if(elapsed>=0)cir.setReturnValue(Math.min(1f,elapsed/(float)self.getAttackDuration()));
    }
}
