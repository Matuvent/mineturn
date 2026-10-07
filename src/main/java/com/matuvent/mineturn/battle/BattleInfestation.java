package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.neoforged.neoforge.event.EventHooks;
import java.util.*;

final class BattleInfestation {
    static int parameter(CombatData.Action action,String name,int fallback) {
        var json=action.parameters();return json.has(name)?json.get(name).getAsInt():fallback;
    }
    static List<BlockPos> candidates(BattleSession battle,LivingEntity source,CombatData.Action action) {
        if(!(source instanceof Silverfish) || battle.closed || battle.members.size()>=BattleSession.MAX_MEMBERS
                || !battle.definitions.mobs().containsKey("minecraft:silverfish"))return List.of();
        int radius=parameter(action,"search_radius",4);var origin=source.blockPosition();var result=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(origin.offset(-radius,-radius,-radius),origin.offset(radius,radius,radius))) {
            var point=net.minecraft.world.phys.Vec3.atBottomCenterOf(pos);
            if(pos.distSqr(origin)>radius*radius || !battle.level.hasChunkAt(pos)
                    || !battle.level.getWorldBorder().isWithinBounds(pos) || Math.abs(point.y-battle.center.y)>6
                    || Math.hypot(point.x-battle.center.x,point.z-battle.center.z)>BattleSession.RADIUS)continue;
            if(battle.level.getBlockState(pos).getBlock() instanceof InfestedBlock)result.add(pos.immutable());
        }
        result.sort(Comparator.comparingDouble(pos->pos.distSqr(origin)));return result;
    }
    static void summon(BattleSession battle,LivingEntity source,CombatData.Action action) {
        if(!EventHooks.canEntityGrief(battle.level,source))return;
        int limit=parameter(action,"max_summons",3),created=0;
        UUID side=battle.member(source).allyOwner;
        for(var pos:candidates(battle,source,action)) {
            if(battle.closed || !source.isAlive() || created>=limit || battle.members.size()>=BattleSession.MAX_MEMBERS)break;
            var state=battle.level.getBlockState(pos);
            if(!(state.getBlock() instanceof InfestedBlock) || !state.canEntityDestroy(battle.level,pos,source)
                    || !EventHooks.onEntityDestroyBlock(source,pos,state) || !battle.level.getBlockState(pos).equals(state))continue;
            var child=EntityType.SILVERFISH.create(battle.level);if(child==null)continue;
            child.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,source.getYRot(),0);
            if(!battle.level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)){child.discard();continue;}
            boolean accepted=false;
            try {
                if(battle.level.noCollision(child,child.getBoundingBox()) && battle.level.addFreshEntity(child)
                        && child.isAlive() && !battle.closed && battle.members.size()<BattleSession.MAX_MEMBERS
                        && !BattleManager.locked(child) && battle.inRegion(child)) {
                    battle.add(child);battle.member(child).allyOwner=side;created++;accepted=true;
                    child.spawnAnim();battle.revision++;
                }
            }finally {
                if(!accepted) {
                    child.discard();
                    // Do not overwrite a replacement installed by another event handler.
                    if(battle.level.getBlockState(pos).isAir())battle.level.setBlock(pos,state,3);
                }
            }
        }
        if(created>0)battle.message("蠹虫唤醒了 "+created+" 只同类，按完整行动间隔入列。");
    }
    private BattleInfestation() {}
}
