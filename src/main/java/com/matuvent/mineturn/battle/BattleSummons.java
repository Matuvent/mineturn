package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.api.CombatSummons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Owned lifetime records are separate from cancellable skill tasks. */
final class BattleSummons {
    static final String TAG="mineturn:temporary_summon";
    record Unit(BattleSession.Member owner,Mob entity,double expires){}
    static boolean temporary(Entity entity){return entity.getTags().contains(TAG);}
    static LivingEntity principal(BattleSession b,LivingEntity entity){
        var unit=b.summons.get(entity.getUUID());return unit==null?entity:unit.owner.entity;
    }
    static Vec3 position(CombatSummons.Request r){return new Vec3(Math.floor(r.position().x)+0.5,r.position().y,Math.floor(r.position().z)+0.5);}
    static String preview(BattleSession b,LivingEntity source,CombatSummons.Request r){
        if(!b.level.getServer().isSameThread() || b.closed || !b.members.containsKey(source.getUUID()) || !source.isAlive() || source.level()!=b.level)return "召唤者已离场。";
        if(temporary(source))return "临时召唤物不能再次召唤。";
        if(b.members.size()>=BattleSession.MAX_MEMBERS || b.summons.size()>=8 || b.summons.values().stream().filter(u->u.owner.entity==source).count()>=4)return "召唤单位数量已满。";
        if(!BuiltInRegistries.ENTITY_TYPE.containsKey(r.entity()))return "未知召唤类型。";
        var type=BuiltInRegistries.ENTITY_TYPE.get(r.entity());var brain=b.definitions.mobs().get(r.entity().toString());
        if(type.getCategory()==MobCategory.MISC || brain==null || brain.participation().equals("never")
                || brain.participation().equals("auto") && type.is(BattleParticipation.RESOURCES))return "召唤类型没有可用战斗 AI。";
        Vec3 p=position(r);var box=type.getDimensions().makeBoundingBox(p);
        if(box.getXsize()>16 || box.getYsize()>16 || box.getZsize()>16)return "召唤实体尺寸超出限制。";
        if(source.position().distanceToSqr(p)>64 || Math.abs(p.y-b.center.y)>6 || Math.hypot(p.x-b.center.x,p.z-b.center.z)>BattleSession.RADIUS)return "召唤点超出范围。";
        for(int x=net.minecraft.util.Mth.floor(box.minX);x<=net.minecraft.util.Mth.floor(box.maxX);x+=1)
            for(int z=net.minecraft.util.Mth.floor(box.minZ);z<=net.minecraft.util.Mth.floor(box.maxZ);z+=1)
                if(!b.level.hasChunkAt(new BlockPos(x,(int)p.y,z)))return "召唤点区块未加载。";
        if(box.minY<b.level.getMinBuildHeight() || box.maxY>b.level.getMaxBuildHeight() || !b.level.getWorldBorder().isWithinBounds(box)
                || !b.level.noCollision(null,box) || !b.level.getEntitiesOfClass(LivingEntity.class,box,e->e.isAlive()).isEmpty())return "召唤点被阻挡。";
        boolean water=AquaticPath.immersed(b.level,p);
        if(b.level.getFluidState(BlockPos.containing(p)).is(net.minecraft.tags.FluidTags.LAVA))return "不能在岩浆中召唤。";
        if(brain.movementMode().equals("swimming") && !water)return "水生召唤物需要水体。";
        if((brain.movementMode().equals("ground") || brain.movementMode().equals("climbing")) && !water && b.level.noCollision(null,box.move(0,-0.05,0)))return "召唤点没有支撑。";
        return null;
    }
    static CombatSummons.Result spawn(BattleSession b,LivingEntity source,CombatSummons.Request r){
        String error=preview(b,source,r);if(error!=null)return CombatSummons.Result.rejected(error);
        var owner=b.member(source);
        var entity=BuiltInRegistries.ENTITY_TYPE.get(r.entity()).create(b.level);
        if(!(entity instanceof Mob mob)){if(entity!=null)entity.discard();return CombatSummons.Result.rejected("召唤类型不是生物。");}
        boolean accepted=false;
        try{
            Vec3 p=position(r);mob.moveTo(p.x,p.y,p.z,source.getYRot(),0);mob.addTag(TAG);
            // Deterministic base entity: no random spawn gear or secondary jockey spawns.
            mob.setCanPickUpLoot(false);
            if(!b.level.noCollision(mob,mob.getBoundingBox()) || !b.inRegion(mob) || !b.level.addFreshEntity(mob))return CombatSummons.Result.rejected("召唤被阻挡或生成事件取消。");
            if(b.closed || b.members.get(source.getUUID())!=owner || !source.isAlive() || source.level()!=b.level || !BattleManager.eligible(mob) || BattleManager.locked(mob)
                    || b.members.size()>=BattleSession.MAX_MEMBERS || b.summons.size()>=8
                    || b.summons.values().stream().filter(u->u.owner.entity==source).count()>=4 || !b.inRegion(mob) || !b.level.noCollision(mob,mob.getBoundingBox()) || !b.level.getEntitiesOfClass(LivingEntity.class,mob.getBoundingBox(),e->e!=mob && e.isAlive()).isEmpty())return CombatSummons.Result.rejected("召唤期间战斗状态已改变。");
            b.add(mob);b.member(mob).allyOwner=b.side(source);
            b.summons.put(mob.getUUID(),new Unit(owner,mob,b.clock.time()+r.lifetimeAv()));
            var target=b.preferredEnemy(source);if(target!=null){b.member(mob).nativeTarget=target.getUUID();mob.setTarget(target);}
            b.revision++;accepted=true;
            return new CombatSummons.Result(mob.getUUID(),null);
        }finally{if(!accepted){if(b.members.containsKey(mob.getUUID()))b.remove(mob,"召唤失败。");mob.discard();}}
    }
    static double next(BattleSession b){return b.summons.values().stream().mapToDouble(Unit::expires).min().orElse(Double.POSITIVE_INFINITY);}
    static void advance(BattleSession b){
        for(var unit:List.copyOf(b.summons.values()))if(unit.expires<=b.clock.time()+1e-7)b.remove(unit.entity,"召唤时间结束。");
    }
    static void remove(BattleSession b,LivingEntity entity){
        var own=b.summons.remove(entity.getUUID());
        for(var unit:List.copyOf(b.summons.values()))if(unit.owner.entity==entity)b.remove(unit.entity,"召唤者离场。");
        if(own!=null)entity.discard();
    }
    private BattleSummons(){}
}
