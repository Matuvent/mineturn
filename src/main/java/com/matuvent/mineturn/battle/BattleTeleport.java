package com.matuvent.mineturn.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Position validation shared by battle teleport items. Never traces a walking route or loads chunks. */
final class BattleTeleport {
    static Vec3 point(BattleSession battle,net.minecraft.world.entity.LivingEntity player,Vec3 requested,double range,boolean visible) {
        if(battle.closed || player==null || !player.isAlive() || player.level()!=battle.level || !battle.members.containsKey(player.getUUID()))throw new IllegalArgumentException("传送目标不在当前战斗中。");
        if(player instanceof BattleDevice)throw new IllegalArgumentException("机关核心不能传送。");
        if(player.isPassenger() || player.isVehicle() || player.isSleeping())throw new IllegalArgumentException("骑乘、载客或睡眠时不能传送。");
        if(!Double.isFinite(range) || range<=0 || range>32)throw new IllegalArgumentException("无效传送范围。");
        if(requested==null || !Double.isFinite(requested.x) || !Double.isFinite(requested.y) || !Double.isFinite(requested.z))throw new IllegalArgumentException("无效传送坐标。");
        Vec3 point=new Vec3(Math.floor(requested.x)+0.5,requested.y,Math.floor(requested.z)+0.5);
        if(!battle.level.hasChunkAt(BlockPos.containing(point)))throw new IllegalArgumentException("落点区块尚未加载。");
        point=AquaticPath.settleSurface(player,point);
        if(Math.abs(point.y-battle.center.y)>6 || Math.hypot(point.x-battle.center.x,point.z-battle.center.z)>BattleSession.RADIUS
                || MovementDistance.between(player.position(),point)>range+1e-6 || point.distanceToSqr(player.position())<0.01)
            throw new IllegalArgumentException("落点超出传送范围或战场，或距离过短。");
        var body=player.getBoundingBox().move(point.subtract(player.position())).deflate(1e-6);
        if(body.minY<battle.level.getMinBuildHeight() || body.maxY>battle.level.getMaxBuildHeight() || !battle.level.getWorldBorder().isWithinBounds(body))throw new IllegalArgumentException("落点超出世界边界。");
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),BlockPos.containing(body.maxX,body.maxY,body.maxZ))){
            if(!battle.level.hasChunkAt(pos))throw new IllegalArgumentException("落点区块尚未加载。");
            var fluid=battle.level.getFluidState(pos);
            if(!fluid.isEmpty() && !fluid.is(net.minecraft.tags.FluidTags.WATER))throw new IllegalArgumentException("落点处于危险流体中。");
        }
        if(!battle.level.noCollision(player,body) || battle.members.values().stream().anyMatch(m->m.entity!=player && m.entity.isAlive() && m.entity.getBoundingBox().intersects(body)))throw new IllegalArgumentException("落点被方块或参与者占据。");
        if(!AquaticPath.immersed(battle.level,point) && battle.level.noCollision(player,body.move(0,-0.06,0)))throw new IllegalArgumentException("请选择有支撑的落点或水中位置。");
        if(visible && battle.level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(),point.add(0,0.1,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player)).getType()!=net.minecraft.world.phys.HitResult.Type.MISS)
            throw new IllegalArgumentException("传送落点被遮挡。");
        return point;
    }
    static com.matuvent.mineturn.api.CombatTeleport.Result preview(BattleSession battle,net.minecraft.world.entity.LivingEntity target,Vec3 requested,com.matuvent.mineturn.api.CombatTeleport.Rules rules){
        if(rules==null)return com.matuvent.mineturn.api.CombatTeleport.Result.rejected("缺少传送规则。");
        if(battle.motion!=null || battle.shot!=null)return com.matuvent.mineturn.api.CombatTeleport.Result.rejected("请等待当前移动或射击结算完成。");
        try{
            var destination=point(battle,target,requested,rules.range(),rules.requireSight());
            if(!rules.allowWater() && battle.level.containsAnyLiquid(target.getBoundingBox().move(destination.subtract(target.position()))))
                return com.matuvent.mineturn.api.CombatTeleport.Result.rejected("该传送不允许水中落点。");
            return com.matuvent.mineturn.api.CombatTeleport.Result.accepted(destination);
        }catch(IllegalArgumentException ex){return com.matuvent.mineturn.api.CombatTeleport.Result.rejected(ex.getMessage());}
    }
    static com.matuvent.mineturn.api.CombatTeleport.Result apply(BattleSession battle,net.minecraft.world.entity.LivingEntity target,Vec3 requested,com.matuvent.mineturn.api.CombatTeleport.Rules rules){
        var result=preview(battle,target,requested,rules);if(!result.success())return result;
        var origin=target.position();battle.place(target,result.destination());target.resetFallDistance();
        if(target instanceof ServerPlayer player)player.resetCurrentImpulseContext();
        battle.level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.TELEPORT,origin,net.minecraft.world.level.gameevent.GameEvent.Context.of(target));
        battle.revision++;battle.syncAll();return result;
    }
    static void clock(BattleSession battle,BattleSession.Member member){BattleItemCooldowns.clock(battle,member);}
    static void pearl(BattleSession battle,ServerPlayer player,int slot,String id,Vec3 requested){
        var action=battle.definitions.actions().get(id);var point=point(battle,player,requested,action.range(),true);
        var pearl=new net.minecraft.world.entity.projectile.ThrownEnderpearl(battle.level,player);
        try{
            pearl.setPos(point);
            var hit=new net.minecraft.world.phys.BlockHitResult(point,net.minecraft.core.Direction.UP,BlockPos.containing(point.add(0,-0.01,0)),false);
            var event=net.neoforged.neoforge.event.EventHooks.onEnderPearlLand(player,point.x,point.y,point.z,pearl,5,hit);
            if(event.isCanceled())throw new IllegalArgumentException("末影珍珠传送被事件取消，未消耗物品或行动。");
            point=point(battle,player,event.getTarget(),action.range(),true);
            float damage=event.getAttackDamage();
            if(!Float.isFinite(damage) || damage<0 || damage>1000)throw new IllegalArgumentException("传送事件返回无效伤害。");
            // Event listeners may mutate the inventory or battle; validate again before paying.
            battle.validatePotionPoint(player,slot,id,point);
            var item=player.getInventory().getItem(slot);var origin=player.position();
            battle.budget.spend(action.cost());item.consume(action.consume(),player);
            battle.member(player).cooldowns.put(id,battle.clock.time()+action.cooldown());
            battle.member(player).pearlUntil=battle.clock.time()+action.cooldown();clock(battle,battle.member(player));
            battle.place(player,point);player.resetFallDistance();player.resetCurrentImpulseContext();
            BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().fall(),damage);});
            battle.level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,origin.x,origin.y+1,origin.z,24,0.3,0.5,0.3,0.1);
            battle.level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,point.x,point.y+1,point.z,24,0.3,0.5,0.3,0.1);
            battle.level.playSound(null,player,net.minecraft.sounds.SoundEvents.PLAYER_TELEPORT,net.minecraft.sounds.SoundSource.PLAYERS,1,1);
            player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(Items.ENDER_PEARL));player.inventoryMenu.broadcastChanges();
            battle.revision++;battle.prune();
        }finally{pearl.discard();}
    }
    static void chorus(BattleSession battle,ServerPlayer player,com.matuvent.mineturn.data.CombatData.Action action){
        var member=battle.member(player);
        member.chorusUntil=battle.clock.time()+action.cooldown();clock(battle,member);
        var origin=player.position();
        for(int attempt=0;attempt<16;attempt++){
            var random=player.getRandom();
            double range=Math.min(16,action.range());
            var event=net.neoforged.neoforge.event.EventHooks.onChorusFruitTeleport(player,
                    origin.x+(random.nextDouble()*2-1)*range,origin.y+random.nextInt(16)-8,
                    origin.z+(random.nextDouble()*2-1)*range);
            if(event.isCanceled())break;
            if(battle.closed || !player.isAlive() || battle.members.get(player.getUUID())!=member)return;
            Vec3 destination=chorusPoint(battle,player,event.getTarget(),range);
            if(destination==null)continue;
            battle.place(player,destination);player.resetFallDistance();player.resetCurrentImpulseContext();
            battle.level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.TELEPORT,origin,net.minecraft.world.level.gameevent.GameEvent.Context.of(player));
            battle.level.playSound(null,player,net.minecraft.sounds.SoundEvents.CHORUS_FRUIT_TELEPORT,net.minecraft.sounds.SoundSource.PLAYERS,1,1);
            battle.level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,destination.x,destination.y+1,destination.z,24,0.3,0.5,0.3,0.1);
            battle.revision++;return;
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("紫颂果已食用，但未能传送；行动与冷却已消耗。"),false);
    }
    static Vec3 chorusPoint(BattleSession battle,ServerPlayer player,Vec3 requested,double range){
        if(!Double.isFinite(requested.x) || !Double.isFinite(requested.y) || !Double.isFinite(requested.z)
                || Math.abs(requested.x-battle.center.x)>BattleSession.RADIUS || Math.abs(requested.z-battle.center.z)>BattleSession.RADIUS)return null;
        int top=(int)Math.floor(Math.min(requested.y,battle.center.y+6));
        int bottom=(int)Math.floor(Math.max(battle.level.getMinBuildHeight(),battle.center.y-6));
        for(int y=top;y>=bottom;y--){
            var floor=BlockPos.containing(requested.x,y-1,requested.z);
            if(!battle.level.hasChunkAt(floor))return null;
            var shape=battle.level.getBlockState(floor).getCollisionShape(battle.level,floor);
            if(shape.isEmpty())continue;
            try{
                var destination=point(battle,player,new Vec3(requested.x,floor.getY()+shape.max(net.minecraft.core.Direction.Axis.Y),requested.z),range,false);
                var box=player.getBoundingBox().move(destination.subtract(player.position()));
                if(!battle.level.containsAnyLiquid(box))return destination;
            }catch(IllegalArgumentException ignored){}
            return null;
        }
        return null;
    }
    private BattleTeleport(){}
}
