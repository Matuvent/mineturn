package com.matuvent.mineturn.mixin;
import com.matuvent.mineturn.api.RavagerBattleAccess;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Ravager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Ravager.class)
public abstract class RavagerBattleMixin extends net.minecraft.world.entity.raid.Raider implements RavagerBattleAccess {
    protected RavagerBattleMixin(net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.raid.Raider> type,net.minecraft.world.level.Level level){super(type,level);}
    @Unique private static final EntityDataAccessor<Integer> MINETURN_STUN=SynchedEntityData.defineId(Ravager.class,EntityDataSerializers.INT);
    @Unique private static final EntityDataAccessor<Integer> MINETURN_ROAR=SynchedEntityData.defineId(Ravager.class,EntityDataSerializers.INT);
    @Shadow private int stunnedTick;
    @Shadow private int roarTick;
    @Shadow private int attackTick;
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){super.defineSynchedData(builder);builder.define(MINETURN_STUN,-1);builder.define(MINETURN_ROAR,-1);}
    @Override public void mineturn$timers(int stun,int roar){
        var self=(Ravager)(Object)this;stunnedTick=Math.max(0,stun);roarTick=Math.max(0,roar);
        self.getEntityData().set(MINETURN_STUN,stun);self.getEntityData().set(MINETURN_ROAR,roar);
    }
    @Inject(method="blockedByShield",at=@At("HEAD"),cancellable=true)
    private void mineturn$shield(LivingEntity target,CallbackInfo ci){
        var self=(Ravager)(Object)this;
        if(BattleManager.locked(self)){BattleManager.ravagerShield(self);ci.cancel();}
    }
    @Inject(method="getStunnedTick",at=@At("HEAD"),cancellable=true)
    private void mineturn$stun(CallbackInfoReturnable<Integer> cir){int n=((Ravager)(Object)this).getEntityData().get(MINETURN_STUN);if(n>=0)cir.setReturnValue(n);}
    @Inject(method="getRoarTick",at=@At("HEAD"),cancellable=true)
    private void mineturn$roar(CallbackInfoReturnable<Integer> cir){int n=((Ravager)(Object)this).getEntityData().get(MINETURN_ROAR);if(n>=0)cir.setReturnValue(n);}
    @Inject(method="aiStep",at=@At("HEAD"),cancellable=true)
    private void mineturn$freeze(CallbackInfo ci){
        var self=(Ravager)(Object)this;
        if(self.getEntityData().get(MINETURN_STUN)>=0){
            // Attack presentation still plays in real time; stun/roar gameplay timers remain on AV.
            if(self.level().isClientSide && attackTick>0)attackTick--;
            ci.cancel();
        }
    }
}
