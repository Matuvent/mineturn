package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Targetable, non-persistent battle unit; movement and lifetime are owned by its AV turns. */
public final class BattleBullet extends FlyingMob {
    UUID caster,target;
    int turns;
    public BattleBullet(EntityType<? extends BattleBullet> type,Level level){super(type,level);setNoGravity(true);setNoAi(true);}
    public static AttributeSupplier.Builder attributes(){return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,1).add(Attributes.MOVEMENT_SPEED,0.23);}
    @Override public void tick(){
        if(!level().isClientSide && !BattleManager.locked(this)){discard();return;}
        super.tick();
    }
}
