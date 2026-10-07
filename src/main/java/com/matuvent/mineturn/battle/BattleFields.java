package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.api.*;
import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.function.Consumer;

final class BattleFields {
    static final class Field {
        final UUID id=UUID.randomUUID();final BattleSession.Member owner;final CombatFields.Request request;
        final CombatData.Action action;final ItemStack item;final Consumer<CombatEffects.Context> callback;
        final double expires;double next;int charges;BattleDevice core;
        Field(BattleSession b,BattleSession.Member owner,CombatFields.Request r,CombatData.Action action,ItemStack item,Consumer<CombatEffects.Context> callback){
            this.owner=owner;request=r;this.action=action;this.item=item.copy();this.callback=callback;expires=b.clock.time()+r.lifetimeAv();next=b.clock.time()+r.intervalAv();charges=r.charges();
        }
    }
    static String preview(BattleSession b,LivingEntity source,CombatFields.Request r){
        if(!b.level.getServer().isSameThread() || b.closed || !b.members.containsKey(source.getUUID()) || !source.isAlive() || source.level()!=b.level)return "施法者已离场。";
        if(b.fields.size()>=8 || b.fields.values().stream().filter(f->f.owner.entity==source).count()>=4)return "场上区域数量已满。";
        if(r.coreHealth()>0 && b.members.size()>=BattleSession.MAX_MEMBERS)return "战斗人数已满。";
        var p=r.center();
        if(r.coreHealth()>0 && (Math.abs(p.x-Math.floor(p.x)-0.5)>1e-6 || Math.abs(p.z-Math.floor(p.z)-0.5)>1e-6))return "机关核心需要位于方块中心。";
        if(r.coreHealth()>0){var coreBox=new AABB(p.x-0.375,p.y,p.z-0.375,p.x+0.375,p.y+0.75,p.z+0.375);
            if(!b.level.hasChunkAt(BlockPos.containing(p)) || !b.level.noCollision(null,coreBox) || !b.level.getEntitiesOfClass(LivingEntity.class,coreBox,e->e.isAlive()).isEmpty())return "机关核心位置被占用。";}
        var box=new AABB(p.x-r.radius(),p.y,p.z-r.radius(),p.x+r.radius(),p.y+r.height(),p.z+r.radius());
        if(source.position().distanceToSqr(p)>64 || Math.hypot(p.x-b.center.x,p.z-b.center.z)+r.radius()>BattleSession.RADIUS
                || p.y<b.center.y-6 || box.maxY>b.center.y+6 || box.minY<b.level.getMinBuildHeight() || box.maxY>b.level.getMaxBuildHeight()
                || !b.level.getWorldBorder().isWithinBounds(box))return "区域超出范围。";
        for(int x=net.minecraft.util.Mth.floor(box.minX);x<=net.minecraft.util.Mth.floor(box.maxX);x++)
            for(int z=net.minecraft.util.Mth.floor(box.minZ);z<=net.minecraft.util.Mth.floor(box.maxZ);z++)
                if(!b.level.hasChunkAt(new BlockPos(x,(int)p.y,z)))return "区域区块未加载。";
        if(!b.level.getBlockState(BlockPos.containing(p)).getCollisionShape(b.level,BlockPos.containing(p)).isEmpty()
                || b.level.clip(new ClipContext(source.getEyePosition(),p.add(0,0.1,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,source)).getType()!=HitResult.Type.MISS)return "区域中心被遮挡。";
        return null;
    }
    static CombatFields.Result create(BattleSession b,LivingEntity source,CombatFields.Request r,CombatData.Action action,ItemStack item,Consumer<CombatEffects.Context> callback){
        Objects.requireNonNull(callback);String reason=preview(b,source,r);if(reason!=null)return new CombatFields.Result(null,reason);
        var field=new Field(b,b.member(source),r,action,item,callback);
        if(r.coreHealth()>0){
            var core=com.matuvent.mineturn.MineTurn.BATTLE_DEVICE.get().create(b.level);if(core==null)return new CombatFields.Result(null,"无法创建机关核心。");
            boolean accepted=false;
            try{
                core.moveTo(r.center().x,r.center().y,r.center().z,0,0);core.addTag(BattleSummons.TAG);
                core.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(r.coreHealth());core.setHealth((float)r.coreHealth());
                core.setCustomName(net.minecraft.network.chat.Component.literal("机关核心"));core.setCustomNameVisible(true);
                if(!b.level.addFreshEntity(core) || !core.isAlive() || !live(b,field) || b.members.size()>=BattleSession.MAX_MEMBERS)return new CombatFields.Result(null,"机关生成取消或战斗状态改变。");
                if(b.fields.size()>=8 || b.fields.values().stream().filter(f->f.owner.entity==source).count()>=4 || !b.level.noCollision(core,core.getBoundingBox()) || !b.level.getEntitiesOfClass(LivingEntity.class,core.getBoundingBox(),x->x!=core&&x.isAlive()).isEmpty())return new CombatFields.Result(null,"机关生成位置改变。");
                field.core=core;b.add(core);b.clock.remove(core);b.member(core).allyOwner=b.side(source);accepted=true;
            }finally{if(!accepted){if(b.members.containsKey(core.getUUID()))b.remove(core,"机关生成失败。");core.discard();}}
        }
        b.fields.put(field.id,field);b.revision++;return new CombatFields.Result(field.id,null);
    }
    static void delete(BattleSession b,Field f){
        if(b.fields.remove(f.id)!=f)return;
        if(f.core!=null){b.remove(f.core,"机关失效。");f.core.discard();}b.revision++;
    }
    static void leave(BattleSession b,LivingEntity entity){for(var f:List.copyOf(b.fields.values()))if(f.owner.entity==entity || f.core==entity)delete(b,f);}
    static LivingEntity principal(BattleSession b,LivingEntity entity){for(var f:b.fields.values())if(f.core==entity)return f.owner.entity;return entity;}
    static boolean remove(BattleSession b,LivingEntity source,UUID id){
        var f=b.fields.get(id);if(f==null || f.owner.entity!=source)return false;delete(b,f);return true;
    }
    static double next(BattleSession b){return b.fields.values().stream().mapToDouble(f->f.request.onEnter()?f.expires:Math.min(f.next,f.expires)).min().orElse(Double.POSITIVE_INFINITY);}
    static boolean live(BattleSession b,Field f){return !b.closed && b.members.get(f.owner.entity.getUUID())==f.owner && f.owner.entity.isAlive() && f.owner.entity.level()==b.level && (f.core==null || f.core.isAlive() && !f.core.isRemoved());}
    static boolean geometry(Field f,LivingEntity e,Vec3 position){
        var p=f.request.center();var box=e.getBoundingBox().move(position.subtract(e.position()));double dx=position.x-p.x,dz=position.z-p.z;
        return dx*dx+dz*dz<=f.request.radius()*f.request.radius() && box.maxY>p.y && box.minY<p.y+f.request.height();
    }
    static boolean targetAt(BattleSession b,Field f,LivingEntity e,Vec3 position){
        return e.isAlive() && e.level()==b.level && b.enemy(f.owner.entity,e) && geometry(f,e,position)
                && b.level.clip(new ClipContext(f.request.center().add(0,0.1,0),position.add(0,e.getEyeHeight(),0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,f.owner.entity)).getType()==HitResult.Type.MISS;
    }
    static boolean target(BattleSession b,Field f,LivingEntity e){return targetAt(b,f,e,e.position());}
    static void contact(BattleSession b,LivingEntity e,Vec3 from,Vec3 to,boolean swept){
        if(from.distanceToSqr(to)<1e-12 || !b.members.containsKey(e.getUUID()))return;
        for(var f:List.copyOf(b.fields.values())){
            if(!f.request.onEnter() || b.fields.get(f.id)!=f || !live(b,f) || f.expires<=b.clock.time()+1e-7)continue;
            int steps=swept?Math.max(1,(int)Math.ceil(from.distanceTo(to)/0.1)):1;
            boolean inside=geometry(f,e,from);
            for(int i=1;i<=steps;i++){
                var at=from.lerp(to,(double)i/steps);boolean now=geometry(f,e,at),entered=!inside&&now;inside=now;
                if(!entered || !targetAt(b,f,e,at))continue;
                if(--f.charges==0)delete(b,f); // reserve charge before callbacks, including reentrant displacement
                try{b.runEffect(f.owner.entity,e,f.item.copy(),f.action,f.callback);}
                catch(RuntimeException error){delete(b,f);com.matuvent.mineturn.MineTurn.LOGGER.error("Trap callback failed",error);}
                if(!e.isAlive() || !b.members.containsKey(e.getUUID()) || b.fields.get(f.id)!=f)break;
            }
        }
    }
    static void advance(BattleSession b){
        for(var f:List.copyOf(b.fields.values())){
            if(!live(b,f) || f.expires<=b.clock.time()+1e-7){delete(b,f);continue;}
            if(f.request.onEnter() || f.next>b.clock.time()+1e-7)continue;
            f.next+=f.request.intervalAv(); // reserve pulse before callbacks; never replay a failed effect
            for(var member:List.copyOf(b.members.values())){
                if(!live(b,f) || b.fields.get(f.id)!=f)break;
                if(b.members.get(member.entity.getUUID())!=member || !target(b,f,member.entity))continue;
                try{b.runEffect(f.owner.entity,member.entity,f.item.copy(),f.action,f.callback);}
                catch(RuntimeException error){delete(b,f);com.matuvent.mineturn.MineTurn.LOGGER.error("Field callback failed in battle {}",b.id,error);break;}
            }
        }
    }
    static void display(BattleSession b){
        for(var f:b.fields.values())if(live(b,f))for(int i=0;i<24;i++){
            double a=i*Math.PI*2/24;var p=f.request.center();
            b.level.sendParticles(ParticleTypes.END_ROD,p.x+Math.cos(a)*f.request.radius(),p.y+0.08,p.z+Math.sin(a)*f.request.radius(),1,0,0,0,0);
        }
    }
    private BattleFields(){}
}
