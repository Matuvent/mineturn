package com.matuvent.mineturn.battle;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Native cloud mechanics, ticked only by its owning battle's AV clock. */
public final class BattleCloud extends AreaEffectCloud {
    private final BattleSession battle;
    double nextAv;
    private int logicalAge;
    public void freezeAge(){tickCount=logicalAge;}
    BattleCloud(BattleSession battle,LivingEntity owner,Vec3 position,PotionContents potion) {
        super(battle.level,position.x,position.y,position.z);this.battle=battle;
        setOwner(owner);setRadius(3);setRadiusOnUse(-0.5f);setWaitTime(10);
        setRadiusPerTick(-getRadius()/getDuration());setPotionContents(potion);
        nextAv=battle.clock.time()+BattleStatus.AV_PER_TICK;
    }
    @Override public boolean shouldBeSaved(){return false;}
    void advance(){nextAv+=BattleStatus.AV_PER_TICK;tickCount=++logicalAge;BattleManager.authorized(this::tick);}
    public List<LivingEntity> participants(List<LivingEntity> nearby) {
        if(battle.closed)return List.of();
        return nearby.stream().filter(entity->entity.isAlive() && battle.members.containsKey(entity.getUUID())
                && level().clip(new net.minecraft.world.level.ClipContext(position().add(0,0.25,0),entity.getEyePosition(),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,this))
                    .getType()==net.minecraft.world.phys.HitResult.Type.MISS).toList();
    }
}
