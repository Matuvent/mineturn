package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;

/** Stationary target, excluded from the action clock and owned by a field. */
public final class BattleDevice extends FlyingMob {
    public BattleDevice(EntityType<? extends BattleDevice> type,Level level){super(type,level);setNoGravity(true);setNoAi(true);}
    public static AttributeSupplier.Builder attributes(){return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,10).add(Attributes.MOVEMENT_SPEED,0);}
    @Override public boolean shouldBeSaved(){return false;}
    @Override public void tick(){if(!level().isClientSide&&!BattleManager.locked(this)){discard();return;}super.tick();}
}
