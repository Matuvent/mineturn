package com.matuvent.mineturn.battle;

import com.google.gson.JsonParser;
import com.matuvent.mineturn.api.CombatEffects;
import com.matuvent.mineturn.data.CombatData;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class BattleExtensionGameTests {
    @GameTest(template="empty",batch="block_aura_boundaries")
    public static void beaconRefreshSkipsCombatAndResumesOnExit(GameTestHelper h){
        var player=player(h);var outside=player(h);outside.setPos(player.position().add(32,0,0));
        var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var speed=net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED;
        var regen=net.minecraft.world.effect.MobEffects.REGENERATION;
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(speed,200));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            h.assertTrue(!BattleManager.locked(outside),"Beacon outsider was recruited into battle");
            var pos=player.blockPosition();
            for(int i=0;i<3;i++)com.matuvent.mineturn.mixin.BeaconAuraAccess.mineturn$apply(h.getLevel(),pos,4,speed,regen);
            h.assertTrue(player.getEffect(speed).getDuration()==200 && !player.hasEffect(regen),"Beacon refreshed or added a combat effect");
            h.assertTrue(outside.getEffect(speed).getDuration()==340 && outside.hasEffect(regen),"Beacon lost primary/secondary effects on outsider");
            for(int i=0;i<10;i++)BattleStatus.tick(battle.member(player));
            com.matuvent.mineturn.mixin.BeaconAuraAccess.mineturn$apply(h.getLevel(),pos,4,speed,speed);
            h.assertTrue(player.getEffect(speed).getDuration()==190 && player.getEffect(speed).getAmplifier()==0,"Beacon overwrote AV duration or amplifier");
            h.assertTrue(outside.getEffect(speed).getAmplifier()==1,"Beacon lost native level-two effect");
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.LUCK,100));
            h.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.LUCK),"Aura boundary globally blocked source-free command effects");
            battle.close("test");com.matuvent.mineturn.mixin.BeaconAuraAccess.mineturn$apply(h.getLevel(),pos,4,speed,regen);
            h.assertTrue(player.getEffect(speed).getDuration()==340 && player.hasEffect(regen),"Beacon failed to resume on exit");
        }finally{battle.close("test");cleanup(player);cleanup(outside);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",batch="block_aura_boundaries")
    public static void conduitRefreshKeepsWaterAndCombatFilters(GameTestHelper h){
        var player=player(h);var outside=player(h);outside.setPos(player.position().add(32,0,0));
        var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var power=net.minecraft.world.effect.MobEffects.CONDUIT_POWER;
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(power,100));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            h.assertTrue(!BattleManager.locked(outside),"Conduit outsider was recruited into battle");
            for(var entity:List.of(player,outside)){
                flowingWater(h,entity);((com.matuvent.mineturn.mixin.FluidAccess)entity).mineturn$updateFluid();
                h.assertTrue(entity.isInWater(),"Conduit fixture is dry");
            }
            var pos=player.blockPosition();var frame=Collections.nCopies(21,pos);
            for(int i=0;i<3;i++)com.matuvent.mineturn.mixin.ConduitAuraAccess.mineturn$apply(h.getLevel(),pos,frame);
            h.assertTrue(player.getEffect(power).getDuration()==100 && outside.getEffect(power).getDuration()==260,"Conduit ignored combat membership");
            for(int i=0;i<5;i++)BattleStatus.tick(battle.member(player));
            com.matuvent.mineturn.mixin.ConduitAuraAccess.mineturn$apply(h.getLevel(),pos,frame);
            h.assertTrue(player.getEffect(power).getDuration()==95,"Conduit reset AV countdown");
            player.removeEffect(power);com.matuvent.mineturn.mixin.ConduitAuraAccess.mineturn$apply(h.getLevel(),pos,frame);
            h.assertTrue(!player.hasEffect(power),"Conduit granted new effect during combat");
            outside.removeEffect(power);outside.setPos(outside.position().add(0,5,0));
            ((com.matuvent.mineturn.mixin.FluidAccess)outside).mineturn$updateFluid();
            com.matuvent.mineturn.mixin.ConduitAuraAccess.mineturn$apply(h.getLevel(),pos,frame);
            h.assertTrue(!outside.hasEffect(power),"Conduit lost native water requirement");
            battle.close("test");com.matuvent.mineturn.mixin.ConduitAuraAccess.mineturn$apply(h.getLevel(),pos,frame);
            h.assertTrue(player.getEffect(power).getDuration()==260,"Conduit failed to resume on exit");
        }finally{battle.close("test");cleanup(player);cleanup(outside);mob.discard();}h.succeed();
    }
    private static void flowingWater(GameTestHelper h,net.minecraft.world.entity.Entity entity){
        var center=entity.blockPosition();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=2;y++)
            h.getLevel().setBlock(center.offset(x,y,z),Blocks.WATER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,x+3),2);
    }
    @GameTest(template="empty")
    public static void fluidFlowFreezesButContactCachesUpdate(GameTestHelper h){
        var player=player(h);var horse=mount(h,player);
        var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            for(var entity:List.of(horse,player,mob,outside)){
                flowingWater(h,entity);entity.setDeltaMovement(Vec3.ZERO);
                ((com.matuvent.mineturn.mixin.FluidAccess)entity).mineturn$updateFluid();
                ((com.matuvent.mineturn.mixin.FluidAccess)entity).mineturn$updateEyes();
                h.assertTrue(entity.isInWater() && entity.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
                        && entity.getFluidTypeHeight(net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value())>0,"Flow freeze lost water contact cache");
                h.assertTrue(entity==outside?entity.getDeltaMovement().lengthSqr()>0:entity.getDeltaMovement().equals(Vec3.ZERO),"Flow ignored battle membership");
            }
            mob.setDeltaMovement(new Vec3(0.2,0.3,0.4));
            BattleManager.authorized(()->((com.matuvent.mineturn.mixin.FluidAccess)mob).mineturn$updateFluid());
            h.assertTrue(mob.getDeltaMovement().equals(new Vec3(0.2,0.3,0.4)),"Flow altered explicit skill velocity inside authorization");
            battle.close("test");mob.setDeltaMovement(Vec3.ZERO);
            ((com.matuvent.mineturn.mixin.FluidAccess)mob).mineturn$updateFluid();
            h.assertTrue(mob.getDeltaMovement().lengthSqr()>0,"Flow did not resume on exit");
            mob.setPos(mob.position().add(0,5,0));
            ((com.matuvent.mineturn.mixin.FluidAccess)mob).mineturn$updateFluid();
            ((com.matuvent.mineturn.mixin.FluidAccess)mob).mineturn$updateEyes();
            h.assertTrue(!mob.isInWater() && !mob.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
                    && mob.getFluidTypeHeight(net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value())==0,"Water cache remained stale on dry land");
        }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void bubbleColumnsFreezeOnlyBattleVelocity(GameTestHelper h){
        var player=player(h);var horse=mount(h,player);
        var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var velocity=new Vec3(0.2,0.1,0.3);
            for(var entity:List.of(player,horse,mob))for(boolean down:new boolean[]{false,true}){
                entity.setDeltaMovement(velocity);entity.fallDistance=8;
                entity.onAboveBubbleCol(down);
                h.assertTrue(entity.getDeltaMovement().equals(velocity),"Bubble surface pushed combatant");
                entity.onInsideBubbleColumn(down);
                h.assertTrue(entity.getDeltaMovement().equals(velocity) && entity.fallDistance==0,"Bubble freeze lost velocity or fall-distance reset");
            }
            for(boolean down:new boolean[]{false,true}){
                outside.setDeltaMovement(Vec3.ZERO);outside.onInsideBubbleColumn(down);
                h.assertTrue(down?outside.getDeltaMovement().y<0:outside.getDeltaMovement().y>0,"Bubble column stopped affecting outsider");
                outside.setDeltaMovement(Vec3.ZERO);outside.onAboveBubbleCol(down);
                h.assertTrue(down?outside.getDeltaMovement().y<0:outside.getDeltaMovement().y>0,"Bubble surface stopped affecting outsider");
            }
            battle.close("test");mob.setDeltaMovement(Vec3.ZERO);mob.onInsideBubbleColumn(false);
            h.assertTrue(mob.getDeltaMovement().y>0,"Bubble lift did not resume on exit");
        }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void motionSequenceRejectsStalePositionsAndSnapshotRestarts(GameTestHelper h){
        var order=new com.matuvent.mineturn.network.MotionOrder();
        h.assertTrue(order.acceptSnapshot(0) && order.acceptMotion(1) && order.acceptMotion(2),"Ordered movement rejected");
        h.assertTrue(!order.acceptMotion(1) && !order.acceptMotion(2),"Stale/duplicate position accepted");
        h.assertTrue(order.acceptSnapshot(3) && !order.acceptMotion(2),"Late active motion can undo stopped snapshot");
        h.assertTrue(order.acceptMotion(4) && !order.acceptSnapshot(3),"Old stopped snapshot can overwrite next movement");
        h.assertTrue(order.acceptSnapshot(4) && !order.acceptMotion(4),"Snapshot watermark failed to suppress duplicate movement");
        order.reset();h.assertTrue(order.acceptSnapshot(0) && order.acceptMotion(1),"New battle retained previous sequence");h.succeed();
    }
    @GameTest(template="empty")
    public static void movementSnapshotsCarrySequenceAndStopState(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            var start=player.position();battle.beginMoveTo(player,start.add(1,0,0));var first=battle.snapshot(player);
            h.assertTrue(first.moving() && first.motionSequence()>0,"Start snapshot missing motion state");
            battle.tickMotion();var progress=battle.snapshot(player);
            h.assertTrue(progress.moving() && progress.motionSequence()>first.motionSequence(),"Same-revision positions have no sequence progress");
            finishMovement(player);var stop=battle.snapshot(player);
            h.assertTrue(!stop.moving() && stop.motionSequence()>progress.motionSequence(),"Stop snapshot missing watermark");
            com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buffer,stop);
            var restored=com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buffer);
            h.assertTrue(restored.equals(stop) && !buffer.isReadable(),"State codec lost movement watermark");
        }finally{buffer.release();battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void cleanupFailureReleasesEveryMemberAndMount(GameTestHelper h){
        class BrokenPillager extends net.minecraft.world.entity.monster.Pillager {
            boolean fail;
            BrokenPillager(){super(EntityType.PILLAGER,h.getLevel());}
            @Override public void stopUsingItem(){if(fail)throw new IllegalStateException("injected cleanup failure");super.stopUsingItem();}
            @Override public net.minecraft.commands.CommandSourceStack createCommandSourceStack(){
                if(fail)throw new IllegalStateException("injected leave callback failure");return super.createCommandSourceStack();
            }
        }
        var player=player(h);var horse=mount(h,player);var mob=new BrokenPillager();mob.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(9,1,8))));h.getLevel().addFreshEntity(mob);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            battle.member(mob).guarding=true;
            // Independent callback instance survives remove's per-member callback filtering.
            battle.callbacks.add(new FunctionAi.Invocation(new BattleSession.Member(mob,null),ResourceLocation.parse("mineturn_test:leave"),"on_leave","",null));
            battle.scheduled.add(new BattleSession.Scheduled(25,0,battle.member(mob),()->{throw new AssertionError("Closed battle executed timer");}));
            mob.fail=true;BattleManager.closeSafely(battle,"test failure");
            h.assertTrue(battle.closed && battle.members.isEmpty() && !BattleManager.locked(player) && !BattleManager.locked(mob) && !BattleManager.locked(horse),"Failed cleanup stranded member/mount lock");
            h.assertTrue(battle.scheduled.isEmpty() && battle.callbacks.isEmpty() && battle.motion==null && battle.shot==null,"Failed cleanup retained pending work");
            battle.close("again");
            h.assertTrue(BattleManager.ACTIVE.values().stream().noneMatch(b->b==battle),"Repeated close restored membership");
        }finally{mob.fail=false;battle.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
    }
    @GameTest(template="empty",batch="reload_lifecycle")
    public static void reloadTrackingBalancesSyncFailureAndOverlappingFutures(GameTestHelper h){
        h.assertTrue(!BattleManager.reloading(),"Reload fixture started with active reload");
        try{
            BattleManager.trackReload(()->{throw new IllegalStateException("injected synchronous reload failure");});
            throw new AssertionError("Synchronous reload error swallowed");
        }catch(IllegalStateException expected){h.assertTrue(expected.getMessage().equals("injected synchronous reload failure"),"Unexpected reload exception");}
        h.assertTrue(!BattleManager.reloading(),"Synchronous failure leaked reload gate");
        var first=new java.util.concurrent.CompletableFuture<Void>();var second=new java.util.concurrent.CompletableFuture<Void>();
        try{
            h.assertTrue(BattleManager.trackReload(()->first)==first,"Tracking replaced native future");BattleManager.trackReload(()->second);
            first.completeExceptionally(new IllegalArgumentException("injected async failure"));
            h.assertTrue(BattleManager.reloading(),"One completion opened gate while another reload was pending");
            second.complete(null);h.assertTrue(!BattleManager.reloading(),"Completed reloads left gate closed");
            var third=new java.util.concurrent.CompletableFuture<Void>();BattleManager.trackReload(()->third);third.cancel(false);
            h.assertTrue(!BattleManager.reloading(),"Cancelled future leaked reload gate");
        }finally{first.complete(null);second.complete(null);}h.succeed();
    }
    @GameTest(template="empty")
    public static void pistonAndShulkerPushCannotMoveBattleBodies(GameTestHelper h){
        var player=player(h);var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var outside=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(8,1,5));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            for(var entity:List.of(player,horse,mob))for(var type:List.of(net.minecraft.world.entity.MoverType.PISTON,net.minecraft.world.entity.MoverType.SHULKER,net.minecraft.world.entity.MoverType.SHULKER_BOX)){
                var start=entity.position();entity.move(type,new Vec3(0.3,0,0));
                h.assertTrue(entity.position().equals(start),"External push moved locked body: "+type);
            }
            var start=outside.position();outside.move(net.minecraft.world.entity.MoverType.PISTON,new Vec3(0.3,0,0));
            h.assertTrue(outside.getX()>start.x,"External movement freeze affected nonparticipant");
            var riderStart=player.position();battle.beginMoveTo(player,horse.position().add(1,0,0));finishMovement(player);
            h.assertTrue(player.getX()>riderStart.x,"Boundary blocked authorized mounted movement");
            battle.close("test");var mobStart=mob.position();mob.move(net.minecraft.world.entity.MoverType.SHULKER_BOX,new Vec3(0.3,0,0));
            h.assertTrue(mob.getX()>mobStart.x,"External pushing did not resume after exit");
        }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void suffocationUsesAvAndResetsAfterAir(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,4));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var head=BlockPos.containing(mob.getEyePosition());h.getLevel().setBlockAndUpdate(head,Blocks.STONE.defaultBlockState());
            h.assertTrue(mob.isInWall(),"Suffocation fixture has no solid head collision");float hp=mob.getHealth();
            mob.hurt(mob.damageSources().inWall(),1);h.assertTrue(mob.getHealth()==hp,"Real-time suffocation bypassed AV");
            BattleStatus.tick(battle.member(mob));h.assertTrue(mob.getHealth()==hp-1,"First AV suffocation tick missing");
            for(int i=0;i<9;i++)BattleStatus.tick(battle.member(mob));h.assertTrue(mob.getHealth()==hp-1,"Suffocation repeated faster than ten logical ticks");
            BattleStatus.tick(battle.member(mob));h.assertTrue(mob.getHealth()==hp-2,"Suffocation interval missing");
            h.getLevel().setBlockAndUpdate(head,Blocks.AIR.defaultBlockState());BattleStatus.tick(battle.member(mob));
            h.getLevel().setBlockAndUpdate(head,Blocks.STONE.defaultBlockState());BattleStatus.tick(battle.member(mob));
            h.assertTrue(mob.getHealth()==hp-3,"Re-entering solid block retained old interval");
            battle.close("test");mob.invulnerableTime=0;mob.hurt(mob.damageSources().inWall(),1);
            h.assertTrue(mob.getHealth()==hp-4,"Native suffocation did not resume on exit");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    private static net.minecraft.world.item.alchemy.PotionContents boundaryPotion(){
        return new net.minecraft.world.item.alchemy.PotionContents(Optional.empty(),Optional.empty(),List.of(
                new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HEAL,1),
                new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,200)));
    }
    private static void burstNativePotion(GameTestHelper h,net.minecraft.world.entity.LivingEntity owner,net.minecraft.world.entity.LivingEntity target){
        class TestPotion extends net.minecraft.world.entity.projectile.ThrownPotion {
            TestPotion(){super(EntityType.POTION,h.getLevel());}
            void burst(){super.onHit(new net.minecraft.world.phys.EntityHitResult(target));}
        }
        var potion=new TestPotion();potion.setOwner(owner);potion.setPos(target.position());
        var item=new ItemStack(net.minecraft.world.item.Items.SPLASH_POTION);item.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,boundaryPotion());potion.setItem(item);potion.burst();
    }
    @GameTest(template="empty")
    public static void nativeSplashCannotHealOrPoisonBattleParticipants(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,4));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var outside=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(7,1,5));
        try{
            mob.setHealth(10);outside.setHealth(4);burstNativePotion(h,null,mob);
            h.assertTrue(mob.getHealth()==10 && !mob.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Native splash bypassed combat clock");
            h.assertTrue(outside.getHealth()>4 && outside.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Unowned splash no longer affects ordinary outsiders");
            outside.removeAllEffects();outside.setHealth(4);burstNativePotion(h,player,mob);
            h.assertTrue(outside.getHealth()==4 && !outside.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Locked owner's leftover potion affected outsider");
        }finally{battle.close("test");cleanup(player);mob.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeCloudCannotHealOrPoisonBattleParticipants(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,4));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var outside=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(7,1,5));
        var cloud=new net.minecraft.world.entity.AreaEffectCloud(h.getLevel(),mob.getX(),mob.getY(),mob.getZ());
        cloud.setWaitTime(0);cloud.setRadius(3);cloud.setRadiusOnUse(0);cloud.setRadiusPerTick(0);cloud.setPotionContents(boundaryPotion());
        try{
            mob.setHealth(10);outside.setHealth(4);for(int i=1;i<=5;i++){cloud.tickCount=i;cloud.tick();}
            h.assertTrue(mob.getHealth()==10 && !mob.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Native cloud bypassed combat clock");
            h.assertTrue(outside.getHealth()>4 && outside.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Native cloud lost ordinary targets");
            outside.removeAllEffects();outside.setHealth(4);cloud.setOwner(player);
            for(int i=6;i<=30;i++){cloud.tickCount=i;cloud.tick();}
            h.assertTrue(outside.getHealth()==4 && !outside.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Locked owner's old cloud affected outsider");
        }finally{cloud.discard();battle.close("test");cleanup(player);mob.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void separateBattlesRejectDirectDamageAndAttributedEffects(GameTestHelper h){
        var first=player(h);var a=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,4));
        var one=new BattleSession(first,a,definitions("ground"),first);
        var second=player(h);var b=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,6));
        var two=new BattleSession(second,b,definitions("ground"),second);
        try{
            float hp=b.getHealth();b.hurt(b.damageSources().mobAttack(a),3);
            boolean applied=b.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,200),a);
            h.assertTrue(b.getHealth()==hp && !applied && !b.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Cross-battle damage/effect accepted");
            h.assertTrue(BattleManager.ACTIVE.get(a.getUUID())==one && BattleManager.ACTIVE.get(b.getUUID())==two && one.actor==first && two.actor==second,"Cross-battle interaction reassigned members or turns");
            BattleManager.authorized(()->b.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,200),second));
            h.assertTrue(b.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED),"Authorized battle effect blocked");
        }finally{one.close("test");two.close("test");cleanup(first);cleanup(second);a.discard();b.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void externalNeutralProjectileJoinsWithOriginalHostility(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,4));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var wolf=h.spawnWithNoFreeWill(EntityType.WOLF,new BlockPos(8,1,6));
        var arrow=new net.minecraft.world.entity.projectile.Arrow(h.getLevel(),wolf,new ItemStack(net.minecraft.world.item.Items.ARROW),null);
        try{
            float hp=mob.getHealth();double av=battle.clock.time();
            mob.hurt(mob.damageSources().arrow(arrow,wolf),2);
            h.assertTrue(mob.getHealth()==hp && BattleManager.ACTIVE.get(wolf.getUUID())==battle,"External shot damaged participant or failed to recruit owner");
            h.assertTrue(battle.enemy(wolf,mob) && battle.enemy(mob,wolf) && battle.nearestEnemy(wolf)==mob,"Neutral reinforcement lost attack target");
            h.assertTrue(!battle.enemy(wolf,player) && battle.actor==player && battle.clock.time()==av,"Reinforcement attacked bystander or stole turn");
            var member=battle.member(wolf);mob.hurt(mob.damageSources().arrow(arrow,wolf),2);
            h.assertTrue(battle.member(wolf)==member && mob.getHealth()==hp,"Repeated external hit re-added owner or damaged target");
        }finally{battle.close("test");cleanup(player);wolf.discard();mob.discard();arrow.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void externalExplosionCannotPushLockedCombatants(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,4));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var outsider=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(7,1,6));
        outsider.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);outsider.setHealth(100);
        try{
            var point=mob.position().add(0,0,1);float hp=mob.getHealth();
            h.getLevel().explode((net.minecraft.world.entity.Entity)null,point.x,point.y,point.z,1,false,net.minecraft.world.level.Level.ExplosionInteraction.NONE);
            h.assertTrue(mob.getHealth()==hp && mob.getDeltaMovement().lengthSqr()==0,"Cancelled external explosion still hurt or pushed participant");
            h.assertTrue(outsider.getHealth()<100 && outsider.getDeltaMovement().lengthSqr()>0,"Battle protection changed unrelated native explosion");
            outsider.setDeltaMovement(Vec3.ZERO);outsider.invulnerableTime=0;float outsideHp=outsider.getHealth();
            h.getLevel().explode(mob,point.x,point.y,point.z,1,false,net.minecraft.world.level.Level.ExplosionInteraction.NONE);
            h.assertTrue(outsider.getHealth()==outsideHp && outsider.getDeltaMovement().lengthSqr()==0,"Locked source bypassed external damage/push boundary");
        }finally{battle.close("test");cleanup(player);mob.discard();outsider.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void piglinGoldNeutralityAndBruteAdmission(GameTestHelper h){
        var player=player(h);player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET));
        var piglin=h.spawnWithNoFreeWill(EntityType.PIGLIN,new BlockPos(7,1,4));
        var brute=h.spawnWithNoFreeWill(EntityType.PIGLIN_BRUTE,new BlockPos(9,1,4));
        var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,enemy,CombatData.current,player);
        try{
            h.assertTrue(!BattleManager.locked(piglin) && !battle.enemy(piglin,player) && !battle.enemy(player,piglin),"Gold-neutral piglin recruited or hostile");
            h.assertTrue(BattleManager.locked(brute) && battle.enemy(brute,player),"Gold incorrectly pacified brute");
            player.setItemSlot(EquipmentSlot.HEAD,ItemStack.EMPTY);battle.recruit();
            h.assertTrue(BattleManager.locked(piglin) && battle.enemy(piglin,player),"Unarmored player did not recruit hostile adult piglin");
        }finally{battle.close("test");cleanup(player);piglin.discard();brute.discard();enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void piglinAngerPreservesTargetAndGoldBystander(GameTestHelper h){
        var player=player(h);var other=player(h);other.setPos(player.position().add(1,0,3));
        for(var p:List.of(player,other))p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET));
        var piglin=h.spawnWithNoFreeWill(EntityType.PIGLIN,new BlockPos(7,1,4));
        piglin.getBrain().setMemoryWithExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.ANGRY_AT,player.getUUID(),600);
        var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,enemy,CombatData.current,player);
        try{
            if(!battle.members.containsKey(other.getUUID()))battle.add(other);
            h.assertTrue(BattleManager.locked(piglin) && battle.nearestEnemy(piglin)==player,"Anger memory lost at admission");
            h.assertTrue(!battle.enemy(piglin,other) && !battle.enemy(other,piglin),"Anger spread to gold bystander");
            var attack=new CombatData.Action("hit","mineturn:damage",1,16,false,0,0,new com.google.gson.JsonObject());
            battle.execute(other,piglin,"test:provoke_piglin",attack,ItemStack.EMPTY);
            h.assertTrue(battle.enemy(piglin,other) && battle.enemy(other,piglin),"Actual damage failed to override gold neutrality");
        }finally{battle.close("test");cleanup(player);cleanup(other);piglin.discard();enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void piglinNativeHuntAndBabyAdmission(GameTestHelper h){
        var player=player(h);player.setItemSlot(EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));
        var hoglin=h.spawnWithNoFreeWill(EntityType.HOGLIN,new BlockPos(8,1,7));
        var piglin=h.spawnWithNoFreeWill(EntityType.PIGLIN,new BlockPos(7,1,4));
        piglin.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET,hoglin);
        var baby=h.spawnWithNoFreeWill(EntityType.PIGLIN,new BlockPos(6,1,6));baby.setBaby(true);
        var battle=new BattleSession(player,hoglin,CombatData.current,player);
        try{
            h.assertTrue(BattleManager.locked(piglin) && battle.nearestEnemy(piglin)==hoglin && battle.enemy(hoglin,piglin),"Native hunt lost or became asymmetric");
            h.assertTrue(!battle.enemy(piglin,player),"Native hunt redirected to gold player");
            player.setItemSlot(EquipmentSlot.CHEST,ItemStack.EMPTY);battle.recruit();
            h.assertTrue(!BattleManager.locked(baby),"Passive baby auto-recruited");
            battle.joinAttacker(baby,player);
            h.assertTrue(BattleManager.locked(baby) && battle.enemy(baby,player),"Explicit external attack did not establish hostility");
        }finally{battle.close("test");cleanup(player);piglin.discard();baby.discard();hoglin.discard();}h.succeed();
    }
    @GameTest(template="empty",batch="lifecycle")
    public static void shutdownClosesBattleBeforeSave(GameTestHelper h){
        lifecycleExit(h,2);h.succeed();
    }
    @GameTest(template="empty")
    public static void logoutReleasesTemporaryUnitsAndNativeClocks(GameTestHelper h){
        lifecycleExit(h,0);h.succeed();
    }
    @GameTest(template="empty")
    public static void dimensionEventImmediatelyReleasesBattle(GameTestHelper h){
        lifecycleExit(h,1);h.succeed();
    }
    private static void lifecycleExit(GameTestHelper h,int mode){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"trap_actions",4),player);
        try{
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));
            battle.useGrant(player,"trap_example:device",player.getId());var core=battle.fields.values().iterator().next().core;
            var summoned=summonForTest(battle,player,player.position().add(0,0,3),300);
            h.assertTrue(summoned.accepted(),"Lifecycle summon fixture rejected");
            var unit=h.getLevel().getEntity(summoned.entity());
            battle.scheduled.add(new BattleSession.Scheduled(25,99,battle.member(player),()->{throw new AssertionError("Departed skill executed");}));
            battle.member(player).windUntil=battle.clock.time()+75;
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,200));
            if(mode==0)BattleManager.logout(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
            else if(mode==1)BattleManager.changedDimension(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(player,h.getLevel().dimension(),net.minecraft.world.level.Level.NETHER));
            else BattleManager.stopping(new net.neoforged.neoforge.event.server.ServerStoppingEvent(h.getLevel().getServer()));
            h.assertTrue(battle.closed && !BattleManager.locked(player) && !BattleManager.locked(enemy),"Exit retained battle locks");
            h.assertTrue(core.isRemoved() && unit.isRemoved() && battle.fields.isEmpty() && battle.summons.isEmpty() && battle.scheduled.isEmpty(),"Exit retained temporary entities or callbacks");
            var cooldown=(com.matuvent.mineturn.api.RemainingItemCooldown)player.getCooldowns();
            h.assertTrue(cooldown.mineturn$remaining(net.minecraft.world.item.Items.WIND_CHARGE)==15,"Exit lost remaining AV cooldown");
            player.getCooldowns().tick();
            h.assertTrue(cooldown.mineturn$remaining(net.minecraft.world.item.Items.WIND_CHARGE)==14 && player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED).getDuration()==200,"Native cooldown did not resume or effect was erased");
            battle.close("again");h.assertTrue(cooldown.mineturn$remaining(net.minecraft.world.item.Items.WIND_CHARGE)==14,"Duplicate close reset cooldown");
        }finally{
            battle.close("test");
            if(mode==2)BattleManager.stopped(new net.neoforged.neoforge.event.server.ServerStoppedEvent(h.getLevel().getServer()));
            cleanup(player);enemy.discard();
        }
    }
    @GameTest(template="empty")
    public static void mountedHeadingFollowsRouteAndSeatUpdate(GameTestHelper h){
        var player=player(h);var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            Vec3 start=horse.position();battle.beginMoveTo(player,start.add(2,0,0));finishMovement(player);horse.positionRider(player);
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(horse.getYRot()+90))<0.01 && Math.abs(net.minecraft.util.Mth.wrapDegrees(player.yBodyRot+90))<0.01,"East route did not turn mount/rider");
            battle.beginMoveTo(player,horse.position().add(0,0,2));finishMovement(player);horse.positionRider(player);
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(horse.yBodyRot))<0.01 && Math.abs(net.minecraft.util.Mth.wrapDegrees(player.yBodyRot))<0.01,"Corner/seat update restored old heading");
        }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void rangedMobsHoldPositionOnCooldown(GameTestHelper h){
        for(var type:List.of(EntityType.EVOKER,EntityType.BREEZE)){
            var player=player(h);var mob=h.spawnWithNoFreeWill(type,new BlockPos(6,1,2));
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                for(String id:List.of("mineturn:evoker_summon","mineturn:fangs_line","mineturn:fangs_circle","mineturn:breeze_burst"))battle.member(mob).cooldowns.put(id,10000.0);
                Vec3 start=mob.position();battle.ai(mob);
                h.assertTrue(battle.motion==null && mob.position().equals(start),"Ranged mob chased during nearby cooldown: "+type);
                Vec3 far=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(11,1,2)));battle.place(mob,far);battle.actor=mob;battle.budget=new TurnBudget(4);battle.ai(mob);
                h.assertTrue(battle.motion!=null,"Ranged mob failed to chase distant target: "+type);
            }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void elderEntryClearsUnmanagedBeam(GameTestHelper h){
        var player=player(h);var elder=h.spawnWithNoFreeWill(EntityType.ELDER_GUARDIAN,new BlockPos(8,1,6));
        ((com.matuvent.mineturn.api.GuardianBeamAccess)elder).mineturn$beam(player.getId(),20);
        var battle=new BattleSession(player,elder,CombatData.current,elder);
        try{
            h.assertTrue(!elder.hasActiveAttackTarget(),"Entry retained native beam with no AV timer");battle.ai(elder);
            h.assertTrue(battle.member(elder).beamTarget!=null && !battle.scheduled.isEmpty() && !battle.member(elder).aiFailed,"Elder could not start managed beam");
        }finally{battle.close("test");cleanup(player);elder.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void originalAggroSurvivesJoiningMonsterFight(GameTestHelper h){
        var player=player(h);var drowned=h.spawnWithNoFreeWill(EntityType.DROWNED,new BlockPos(4,1,2));
        var golem=h.spawnWithNoFreeWill(EntityType.IRON_GOLEM,new BlockPos(10,1,2));
        golem.setTarget(drowned);golem.setPersistentAngerTarget(drowned.getUUID());drowned.setTarget(golem);
        var wolf=h.spawnWithNoFreeWill(EntityType.WOLF,new BlockPos(9,1,5));wolf.setTarget(drowned);
        var battle=new BattleSession(player,drowned,CombatData.current,player);
        try{
            h.assertTrue(battle.members.containsKey(golem.getUUID()) && battle.members.containsKey(wolf.getUUID()),"Existing neutral combatants did not join");
            h.assertTrue(!battle.enemy(golem,player) && !battle.enemy(wolf,player),"Neutral combatants became hostile to unrelated player");
            h.assertTrue(battle.nearestEnemy(golem)==drowned && battle.nearestEnemy(drowned)==golem && battle.nearestEnemy(wolf)==drowned,"Entry discarded native hate targets");
        }finally{battle.close("test");cleanup(player);drowned.discard();golem.discard();wolf.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void ownedFireballExplosionStartsCombat(GameTestHelper h){
        var player=player(h);h.runAfterDelay(70,()->{
            var ghast=h.spawnWithNoFreeWill(EntityType.GHAST,new BlockPos(9,4,7));
            var ball=new net.minecraft.world.entity.projectile.LargeFireball(h.getLevel(),ghast,Vec3.ZERO,1);
            try{
                Vec3 point=player.position().add(1,1,0);ball.setPos(point);
                h.getLevel().explode(ball,point.x,point.y,point.z,1,false,net.minecraft.world.level.Level.ExplosionInteraction.NONE);
                h.assertTrue(player.getHealth()<20 && BattleManager.locked(player) && BattleManager.locked(ghast),"Native ghast explosion failed to start combat");
                var battle=BattleManager.ACTIVE.get(player.getUUID());h.assertTrue(battle.actor==ghast,"Projectile owner did not receive initiative");battle.close("test");
                var source=new net.minecraft.world.damagesource.DamageSource(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolderOrThrow(net.minecraft.world.damagesource.DamageTypes.FIREBALL),ball);
                h.assertTrue(BattleManager.damageOwner(source)==ghast,"Direct projectile owner fallback failed");
            }finally{var battle=BattleManager.ACTIVE.get(player.getUUID());if(battle!=null)battle.close("test");cleanup(player);ghast.discard();ball.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void breezeRejectsTacticalProjectileDamage(GameTestHelper h){
        var player=player(h);var breeze=h.spawnWithNoFreeWill(EntityType.BREEZE,new BlockPos(8,1,2));
        var battle=new BattleSession(player,breeze,CombatData.current,player);
        try{
            player.getInventory().setItem(1,new ItemStack(net.minecraft.world.item.Items.ARROW,8));float hp=breeze.getHealth();
            battle.execute(player,breeze,"mineturn:shoot",CombatData.current.actions().get("mineturn:shoot"),new ItemStack(net.minecraft.world.item.Items.BOW));
            h.assertTrue(breeze.getHealth()==hp,"Tactical arrow bypassed Breeze deflection immunity");
            BattleManager.authorized(()->{breeze.invulnerableTime=0;breeze.hurt(player.damageSources().playerAttack(player),1);});
            h.assertTrue(breeze.getHealth()<hp,"Projectile immunity incorrectly blocked melee");
        }finally{battle.close("test");cleanup(player);breeze.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void crossingBulletDuringOwnMoveTriggersImpact(GameTestHelper h){
        var player=player(h);h.runAfterDelay(70,()->{
            var shulker=h.spawnWithNoFreeWill(EntityType.SHULKER,new BlockPos(10,1,8));
            var battle=new BattleSession(player,shulker,CombatData.current,player);
            try{
                var bullet=com.matuvent.mineturn.MineTurn.BATTLE_BULLET.get().create(h.getLevel());bullet.setPos(player.position().add(1,0.5,0));
                bullet.caster=shulker.getUUID();bullet.target=player.getUUID();h.getLevel().addFreshEntity(bullet);battle.add(bullet);
                float hp=player.getHealth();battle.beginMoveTo(player,player.position().add(2,0,0));finishMovement(player);
                h.assertTrue(player.getHealth()==hp-4 && player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION) && bullet.isRemoved() && !battle.members.containsKey(bullet.getUUID()),"Walking through a stationary bullet caused no single impact");
            }finally{battle.close("test");cleanup(player);shulker.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void surfaceDropsAndGroundMobEntersWater(GameTestHelper h){
        var player=player(h);
        for(int x=1;x<=3;x++)for(int z=1;z<=4;z++)h.setBlock(x,3,z,Blocks.STONE);
        for(int x=4;x<=8;x++)for(int y=1;y<=3;y++)for(int z=1;z<=4;z++)h.setBlock(x,y,z,Blocks.WATER);
        player.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(7,4,2))));
        var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(2,4,2));
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try{
            double top=player.getY();battle.stabilizePlayers();h.assertTrue(player.getY()<top && AquaticPath.immersed(h.getLevel(),player.position()),"Player still stands on water without accessory");
            var route=battle.pursuitRoute(mob,player);h.assertTrue(route.cost()>0 && AquaticPath.immersed(h.getLevel(),route.destination()),"Ground mob refused to enter water");
            battle.budget=new TurnBudget(10);battle.commitMovement(mob,route);h.assertTrue(AquaticPath.immersed(h.getLevel(),mob.position()),"Ground mob did not reach water");
            var id=ResourceLocation.parse("mineturn_test:water_"+UUID.randomUUID().toString().replace("-",""));
            com.matuvent.mineturn.api.CombatMovement.registerWaterWalking(id,e->e==player && e.getTags().contains("water_accessory"));
            player.addTag("water_accessory");battle.place(player,new Vec3(player.getX(),top,player.getZ()));battle.stabilizePlayers();
            h.assertTrue(player.getY()>=top-0.2 && !AquaticPath.immersed(h.getLevel(),player.position()),"Water walking provider ignored");player.removeTag("water_accessory");battle.stabilizePlayers();
            h.assertTrue(player.getY()<top,"Removing water accessory retained water walking");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }

    private static CombatData.Snapshot actionCostData(CombatData.Snapshot data,String id,String cost) {
        var actions=new HashMap<>(data.actions());var base=actions.get(id);var parameters=base.parameters();parameters.add("action_cost",JsonParser.parseString(cost));
        actions.put(id,new CombatData.Action(base.name(),base.effect(),base.amount(),base.range(),base.self(),base.consume(),base.cooldown(),parameters,base.ranged()));
        return new CombatData.Snapshot(actions,data.mobs(),data.items(),data.sources(),data.grants());
    }
    @GameTest(template="empty")
    public static void actionCostsStrictParsingAndAtomicBudget(GameTestHelper h) {
        for(String invalid:List.of("null","0","true","{}","{\"main\":0,\"bonus\":0}","{\"main\":1.5}","{\"bonus\":-1}","{\"main\":101}","{\"main\":\"1\"}","{\"main\":1,\"typo\":1}")) {
            try{com.matuvent.mineturn.data.ActionCost.parse(JsonParser.parseString(invalid));throw new AssertionError("Accepted invalid cost "+invalid);}catch(IllegalArgumentException expected){}
        }
        var cost=com.matuvent.mineturn.data.ActionCost.parse(JsonParser.parseString("{\"main\":2,\"bonus\":1}"));
        var budget=new TurnBudget(4);budget.setActions(2,0);long activity=budget.activity();
        try{budget.spend(cost);throw new AssertionError("Mixed cost partly accepted");}catch(IllegalArgumentException expected){}
        h.assertTrue(budget.mainActions()==2 && budget.bonusActions()==0 && budget.activity()==activity,"Failed mixed cost partially deducted or reset timeout");
        budget.setActions(3,2);budget.spend(cost);
        h.assertTrue(budget.mainActions()==1 && budget.bonusActions()==1 && budget.activity()==activity+1 && budget.remaining()==4,"Successful mixed cost incorrect");h.succeed();
    }
    @GameTest(template="empty")
    public static void bonusFoodAvailableAfterMainSpent(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
        player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.APPLE,3));
        var battle=new BattleSession(player,mob,actionCostData(CombatData.current,"mineturn:eat","\"bonus\""),player);
        try {
            battle.budget.setActions(0,1);var state=battle.snapshot(player);var food=state.slots().getFirst().actions().stream().filter(a->a.id().equals("mineturn:eat")).findFirst().orElseThrow();
            h.assertTrue(food.unavailable().isEmpty() && food.mainCost()==0 && food.bonusCost()==1,"Bonus item incorrectly disabled after main action");
            var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buf,state);var copy=com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buf);h.assertTrue(copy.slots().equals(state.slots()),"Item cost lost in network codec");}finally{buf.release();}
            battle.use(player,0,"mineturn:eat",player.getId());
            h.assertTrue(battle.budget.mainActions()==0 && battle.budget.bonusActions()==0 && player.getInventory().getItem(0).getCount()==2 && player.getFoodData().getFoodLevel()>10,"Bonus food did not pay once/use native food");
            try{battle.use(player,0,"mineturn:eat",player.getId());throw new AssertionError("Repeated item bypassed cost");}catch(IllegalArgumentException expected){}
            h.assertTrue(player.getInventory().getItem(0).getCount()==2,"Rejected action ate an item");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void mixedGrantPrepaymentAndNetwork(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));player.experienceLevel=5;
        var data=actionCostData(resourceGrant(new com.matuvent.mineturn.api.CombatResources.Cost("mineturn:experience_levels",2)),"grant_example:laser","{\"main\":2,\"bonus\":1}");
        var battle=new BattleSession(player,mob,data,player);String grant="grant_example:chestplate_laser";
        try {
            try{battle.useGrant(player,grant,mob.getId());throw new AssertionError("Insufficient main cost accepted");}catch(IllegalArgumentException expected){}
            h.assertTrue(player.experienceLevel==5 && battle.budget.mainActions()==1 && battle.shot==null,"Cost rejection consumed resources");
            battle.budget.setActions(2,0);
            try{battle.useGrant(player,grant,mob.getId());throw new AssertionError("Insufficient bonus cost accepted");}catch(IllegalArgumentException expected){}
            h.assertTrue(player.experienceLevel==5 && battle.budget.mainActions()==2,"Mixed cost not atomic");
            battle.budget.setActions(2,1);var offers=new com.matuvent.mineturn.network.BattleNetwork.Offers(battle.id,battle.revision,battle.offers(player));
            var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.Offers.CODEC.encode(buf,offers);var copy=com.matuvent.mineturn.network.BattleNetwork.Offers.CODEC.decode(buf);h.assertTrue(copy.equals(offers) && copy.actions().getFirst().mainCost()==2 && copy.actions().getFirst().bonusCost()==1,"Grant costs lost in codec");}finally{buf.release();}
            battle.useGrant(player,grant,mob.getId());var token=battle.shot.token;
            h.assertTrue(battle.budget.mainActions()==0 && battle.budget.bonusActions()==0 && player.experienceLevel==3,"Ranged grant did not prepay mixed cost");
            float health=mob.getHealth();battle.finishShot(false);battle.submitShot(player,token,System.nanoTime());
            h.assertTrue(player.experienceLevel==3 && mob.getHealth()==health && battle.budget.mainActions()==0 && battle.budget.bonusActions()==0,"Miss or replay refunded/recharged costs");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void bothAiModesUseMixedActionCosts(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var data=CombatData.current;var actions=new HashMap<>(data.actions());
        actions.put("mineturn_test:cost_heal",new CombatData.Action("heal","mineturn:heal",1,1,true,0,0,JsonParser.parseString("{\"action_cost\":{\"main\":2,\"bonus\":1}}").getAsJsonObject()));
        var brains=new HashMap<>(data.mobs());
        brains.put("minecraft:husk",new CombatData.Brain(100,1.5,"attack",Map.of("attack",new CombatData.State("weighted_action",List.of(),List.of(new CombatData.Choice("mineturn_test:cost_heal",1)))),null,"ground"));
        var battle=new BattleSession(player,mob,new CombatData.Snapshot(actions,brains,data.items(),data.sources(),data.grants()),mob);
        try {
            mob.setHealth(10);battle.budget.setActions(1,1);battle.ai(mob);
            h.assertTrue(mob.getHealth()==10 && battle.budget.mainActions()==1,"FSM executed unaffordable cost");
            battle.budget.setActions(2,1);battle.ai(mob);
            h.assertTrue(mob.getHealth()==11 && battle.budget.mainActions()==0 && battle.budget.bonusActions()==0,"FSM did not use mixed cost");
        }finally{battle.close("test");}
        var function=ResourceLocation.parse("mineturn_test:action_cost");
        brains.put("minecraft:husk",new CombatData.Brain(100,1.5,"",Map.of(),new CombatData.Functions(Map.of("on_turn",function),new net.minecraft.nbt.CompoundTag()),"ground"));
        battle=new BattleSession(player,mob,new CombatData.Snapshot(actions,brains,data.items(),data.sources(),data.grants()),mob);
        try {
            mob.setHealth(10);battle.budget.setActions(2,1);
            FunctionAi.run(battle,new FunctionAi.Invocation(battle.member(mob),function,"on_turn","",null));
            h.assertTrue(mob.getHealth()==11 && battle.budget.mainActions()==0 && battle.budget.bonusActions()==0 && !battle.member(mob).aiFailed,"mcfunction double used or ignored mixed cost");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }

    private static net.minecraft.world.entity.animal.horse.Horse mount(GameTestHelper h,ServerPlayer player) {
        var horse=h.spawnWithNoFreeWill(EntityType.HORSE,new BlockPos(2,1,2));
        horse.setPos(player.position());horse.setTamed(true);
        horse.equipSaddle(new ItemStack(net.minecraft.world.item.Items.SADDLE),null);
        horse.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0.25);
        player.startRiding(horse,true);horse.positionRider(player);return horse;
    }
    @GameTest(template="empty")
    public static void mountedHitEntersAndMountHasNoTurn(GameTestHelper h) {
        var player=player(h);var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
        try {
            h.assertTrue(BattleManager.eligible(player),"Controllable rider excluded");
            horse.fallDistance=2;BattleManager.centerForBattle(player);h.assertTrue(horse.fallDistance==2,"Centering lost pre-entry fall distance");horse.fallDistance=0;
            mob.hurt(player.damageSources().playerAttack(player),1);
            var battle=BattleManager.ACTIVE.get(player.getUUID());
            h.assertTrue(battle!=null && BattleManager.locked(horse),"Mounted actual hit did not start/lock battle");
            h.assertTrue(!battle.members.containsKey(horse.getUUID()) && battle.members.size()==2,"Mount gained an initiative entry");
            h.assertTrue(battle.target(horse.getId())==player && battle.nearestEnemy(mob)==player,"Attack did not prioritize rider");
            h.assertTrue(Math.abs(battle.budget.remaining()-10)<1e-6 && battle.budget.mainActions()==1 && battle.budget.bonusActions()==1,"Mount did not supply native speed or changed actions");
            Vec3 seat=BattleRiding.seat(player),start=horse.position();
            battle.beginMoveTo(player,start.add(2,0,0));finishMovement(player);
            h.assertTrue(horse.position().distanceToSqr(start.add(2,0,0))<1e-6 && player.position().distanceToSqr(horse.position().add(seat))<1e-6 && player.getVehicle()==horse,"Animated movement separated rider/carrier");
            h.assertTrue(Math.abs(battle.budget.remaining()-8)<1e-6 && battle.budget.mainActions()==1,"Mounted move spent wrong resources");
            battle.close("test");h.assertTrue(!BattleManager.locked(horse) && player.getVehicle()==horse,"Exit leaked lock or dismounted player");
        }finally{var b=BattleManager.ACTIVE.get(player.getUUID());if(b!=null)b.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void mountedStairsAndRiderHeadroom(GameTestHelper h) {
        var player=player(h);var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try {
            Vec3 start=horse.position();
            for(int x=4;x<=8;x++)for(int z=1;z<=4;z++)h.setBlock(x,1,z,Blocks.STONE);
            var route=battle.validateDestination(player,start.add(4,1,0));
            h.assertTrue(horse.position().equals(start),"Preview moved real mount");
            battle.beginMoveTo(player,start.add(4,1,0));finishMovement(player);
            h.assertTrue(horse.position().distanceToSqr(start.add(4,1,0))<1e-6 && Math.abs(route.cost()-4)<1e-6,"Mount failed one-block step or Manhattan cost");
            // A low ceiling above the horse still collides with the rider.
            h.setBlock(7,4,2,Blocks.STONE);
            var blocked=TerrainPath.trace(player,new Vec3(1,0,0),q->true);
            h.assertTrue(blocked.cost()<0.99,"Rider head passed through ceiling");
        }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void mountedStatusesDismountAndCarrierDeath(GameTestHelper h) {
        var player=player(h);var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try {
            horse.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS,200));
            int duration=horse.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS).getDuration();
            for(int i=0;i<3;i++)battle.tick();
            h.assertTrue(horse.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS).getDuration()==duration,"Mount statuses used real time");
            battle.next();h.assertTrue(horse.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS).getDuration()<duration,"Mount statuses did not advance with AV");
            battle.actor=player;battle.budget=new TurnBudget(10);
            battle.beginMoveTo(player,horse.position().add(1,0,0));
            player.stopRiding();battle.prune();
            h.assertTrue(battle.motion==null && !BattleManager.locked(horse) && BattleManager.locked(player) && battle.budget.remaining()<=4+1e-6,"Dismount leaked motion, lock or mounted movement");
            // A fresh riding session also handles removal of the carrier mid-move.
            battle.close("test");player.setPos(horse.position());player.startRiding(horse,true);horse.positionRider(player);
            battle=new BattleSession(player,mob,CombatData.current,player);
            battle.beginMoveTo(player,horse.position().add(1,0,0));horse.setHealth(0);battle.prune();
            h.assertTrue(!player.isPassenger() && battle.motion==null && !BattleManager.locked(horse) && BattleManager.locked(player),"Carrier death ended rider combat or retained relationship");
        }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void mountedFallUsesHorseNativeDamage(GameTestHelper h) {
        var player=player(h);
        h.runAfterDelay(70,()->{
            var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
            var battle=new BattleSession(player,mob,CombatData.current,player);
            try {
                var seat=BattleRiding.seat(player);var ground=horse.position();
                battle.place(player,player.position().add(0,8,0));float hp=horse.getHealth(),pp=player.getHealth();
                battle.stabilizePlayers();
                // Horse safe fall distance=6 and multiplier=.5: ceil((8-6)*.5)=1, also applied to rider.
                h.assertTrue(horse.position().distanceToSqr(ground)<1e-6 && player.position().distanceToSqr(ground.add(seat))<1e-6,"Mounted gravity split the pair");
                h.assertTrue(Math.abs(horse.getHealth()-(hp-1))<1e-6 && Math.abs(player.getHealth()-(pp-1))<1e-6,"Mounted fall was skipped or applied twice");
                h.assertTrue(battle.budget.remaining()==10 && battle.budget.mainActions()==1,"Automatic mounted gravity spent resources");
            }finally{battle.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
        });
    }

    @GameTest(template="empty")
    public static void ridingRequiresControlButAllowsWeaponSwitch(GameTestHelper h) {
        var player=player(h);var pig=h.spawnWithNoFreeWill(EntityType.PIG,new BlockPos(2,1,2));
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
        BattleSession battle=null;
        try {
            pig.setPos(player.position());pig.equipSaddle(new ItemStack(net.minecraft.world.item.Items.SADDLE),null);
            player.startRiding(pig,true);pig.positionRider(player);
            h.assertTrue(!BattleManager.eligible(player),"Uncontrollable passenger admitted");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(net.minecraft.world.item.Items.CARROT_ON_A_STICK));
            h.assertTrue(BattleManager.eligible(player),"Native steering item not recognized");
            battle=new BattleSession(player,mob,CombatData.current,player);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
            battle.prune();
            h.assertTrue(BattleManager.locked(player) && BattleManager.locked(pig) && BattleRiding.vehicle(player)==pig,"Weapon switching discarded carrier");
            var start=pig.position();battle.moveTo(player,start.add(1,0,0));
            h.assertTrue(pig.position().distanceToSqr(start.add(1,0,0))<1e-6 && player.getVehicle()==pig,"Steering-item swap broke battle movement");
        }finally{if(battle!=null)battle.close("test");cleanup(player);pig.discard();mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void hittingMountedCarrierPrioritizesRider(GameTestHelper h) {
        var player=player(h);
        h.runAfterDelay(70,()->{
            var horse=mount(h,player);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,9));
            try {
                float hp=horse.getHealth(),pp=player.getHealth();
                horse.hurt(mob.damageSources().mobAttack(mob),2);
                h.assertTrue(horse.getHealth()==hp && player.getHealth()<pp && BattleManager.locked(player) && BattleManager.locked(horse),"Carrier hit failed to prioritize rider/start combat");
                var battle=BattleManager.ACTIVE.get(player.getUUID());
                h.assertTrue(battle.actor==mob && battle.target(horse.getId())==player,"Mounted defender stole initiative or target resolution failed");
                pp=player.getHealth();horse.invulnerableTime=0;horse.hurt(mob.damageSources().mobAttack(mob),2);
                h.assertTrue(player.getHealth()==pp && horse.getHealth()==hp,"Original repeated attacks bypassed battle lock");
            }finally{var battle=BattleManager.ACTIVE.get(player.getUUID());if(battle!=null)battle.close("test");cleanup(player);horse.discard();mob.discard();}h.succeed();
        });
    }

    private static CombatData.Snapshot participationData(String entity,String policy){
        var data=CombatData.current;var brains=new HashMap<>(data.mobs());var base=data.mobs().get("minecraft:husk");
        brains.put(entity,new CombatData.Brain(base.agility(),base.reach(),base.initial(),base.states(),base.functions(),base.movementMode(),policy));
        return new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources());
    }
    @GameTest(template="empty")
    public static void companionEnvironmentUsesAvAndPreservesExit(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var snow=h.spawnWithNoFreeWill(EntityType.SNOW_GOLEM,new BlockPos(5,1,4));var bee=h.spawnWithNoFreeWill(EntityType.BEE,new BlockPos(7,1,4));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            battle.add(bee);for(int y=1;y<=3;y++)h.setBlock(5,y,4,Blocks.WATER);
            battle.member(snow).statusTicks=0;float hp=snow.getHealth();
            for(int i=0;i<9;i++)BattleStatus.tick(battle.member(snow));
            h.assertTrue(snow.getHealth()==hp,"Snow golem water damage ran faster than AV cadence");
            BattleStatus.tick(battle.member(snow));h.assertTrue(snow.getHealth()==hp-1,"Snow golem water damage missing");
            var life=(com.matuvent.mineturn.mixin.BeeLifeAccess)bee;life.mineturn$stingAge(123);
            battle.remove(bee,"test");h.assertTrue(life.mineturn$stingAge()==123 && !BattleManager.locked(bee),"Exit lost native bee lifetime state");
        }finally{battle.close("test");cleanup(player);mob.discard();snow.discard();bee.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void allyRepairsPayMaterialsAndUseNativeValues(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var golem=h.spawnWithNoFreeWill(EntityType.IRON_GOLEM,new BlockPos(4,1,2));var wolf=h.spawnWithNoFreeWill(EntityType.WOLF,new BlockPos(3,1,4));wolf.tame(player);
        var armor=new ItemStack(net.minecraft.world.item.Items.WOLF_ARMOR);armor.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE,160);armor.setDamageValue(80);wolf.setBodyArmorItem(armor);
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            golem.setHealth(60);player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.IRON_INGOT,2));
            var offered=battle.snapshot(player).slots().getFirst().actions().stream().filter(a->a.id().equals("mineturn:golem_repair")).findFirst().orElseThrow();
            h.assertTrue(offered.allied() && offered.unavailable().isEmpty(),"Ally repair was not offered");
            battle.use(player,0,"mineturn:golem_repair",golem.getId());
            h.assertTrue(golem.getHealth()==85 && player.getInventory().getItem(0).getCount()==1 && battle.budget.mainActions()==0,"Golem repair did not pay one item/action for native 25 HP");
            battle.budget=new TurnBudget(4);golem.setHealth(golem.getMaxHealth());
            try{battle.use(player,0,"mineturn:golem_repair",golem.getId());throw new AssertionError("Full golem accepted repair");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.budget.mainActions()==1 && player.getInventory().getItem(0).getCount()==1,"Rejected repair spent resources");
            player.getInventory().setItem(1,new ItemStack(net.minecraft.world.item.Items.ARMADILLO_SCUTE,2));
            float wolfHp=wolf.getHealth();BattleManager.authorized(()->wolf.hurt(mob.damageSources().mobAttack(mob),4));
            h.assertTrue(wolf.getHealth()==wolfHp && armor.getDamageValue()>80,"Native wolf armor failed to absorb damage with durability cost");
            int beforeRepair=armor.getDamageValue();
            battle.use(player,1,"mineturn:wolf_armor_repair",wolf.getId());
            h.assertTrue(armor.getDamageValue()==beforeRepair-20 && player.getInventory().getItem(1).getCount()==1 && battle.budget.mainActions()==0,"Wolf repair ignored MAX_DAMAGE or costs");
            battle.budget=new TurnBudget(4);battle.member(wolf).allyOwner=UUID.randomUUID();
            try{battle.use(player,1,"mineturn:wolf_armor_repair",wolf.getId());throw new AssertionError("Non-ally repair accepted");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.budget.mainActions()==1,"Rejected non-ally repair spent action");
        }finally{battle.close("test");cleanup(player);mob.discard();golem.discard();wolf.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void allyActionPacketRoundtrip(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(9,1,8));var golem=h.spawnWithNoFreeWill(EntityType.IRON_GOLEM,new BlockPos(4,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            golem.setHealth(50);player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));var packet=battle.snapshot(player);
            var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buf,packet);var decoded=com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buf);h.assertTrue(packet.equals(decoded) && decoded.slots().getFirst().actions().getFirst().allied(),"Ally item flag lost in network codec");}finally{buf.release();}
            var offers=new com.matuvent.mineturn.network.BattleNetwork.Offers(battle.id,0,List.of(new com.matuvent.mineturn.network.BattleNetwork.Offer("test:ally","ally","minecraft:iron_ingot",false,true,"")));
            buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.Offers.CODEC.encode(buf,offers);h.assertTrue(offers.equals(com.matuvent.mineturn.network.BattleNetwork.Offers.CODEC.decode(buf)),"Ally grant flag lost");}finally{buf.release();}
        }finally{battle.close("test");cleanup(player);mob.discard();golem.discard();}h.succeed();
    }
    private static void axolotlWater(GameTestHelper h){for(int x=1;x<=10;x++)for(int y=1;y<=3;y++)for(int z=1;z<=6;z++)h.setBlock(x,y,z,Blocks.WATER);}
    @GameTest(template="empty")
    public static void axolotlPlaysDeadWakesAndKeepsDryClock(GameTestHelper h){
        var player=player(h);axolotlWater(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));var a=h.spawnWithNoFreeWill(EntityType.AXOLOTL,new BlockPos(4,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            h.assertTrue(battle.members.containsKey(a.getUUID()) && player.getUUID().equals(battle.side(a)),"Aquatic ally not recruited");
            a.setHealth(8);BattleManager.authorized(()->a.hurt(mob.damageSources().mobAttack(mob),4));
            h.assertTrue(a.isPlayingDead() && a.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PLAY_DEAD_TICKS).orElse(0)==200,"Actual damage did not start play dead");
            h.assertTrue(battle.nearestEnemy(mob)==player,"AI did not lower sleeping axolotl priority");
            battle.actor=a;battle.budget=new TurnBudget(4);battle.ai(a);h.assertTrue(battle.motion==null && battle.budget.mainActions()==1,"Sleeping axolotl acted");
            for(int i=0;i<199;i++)BattleStatus.tick(battle.member(a));h.assertTrue(a.isPlayingDead(),"Axolotl woke too early");
            BattleStatus.tick(battle.member(a));h.assertTrue(!a.isPlayingDead(),"Axolotl failed to wake on AV boundary");
            battle.place(a,Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(11,1,8))));BattleManager.authorized(()->a.setAirSupply(-19));float hp=a.getHealth();BattleStatus.tick(battle.member(a));
            h.assertTrue(a.getAirSupply()==0 && a.getHealth()==hp-2,"Dry-out did not use native AV air rule");
            battle.place(a,Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4,1,2))));BattleAxolotl.begin(battle,a,50);battle.remove(a,"test");
            h.assertTrue(a.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PLAY_DEAD_TICKS).orElse(0)==50 && !BattleManager.locked(a),"Exit discarded native play-dead state");
        }finally{battle.close("test");cleanup(player);mob.discard();a.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void axolotlSupportRequiresSharedKill(GameTestHelper h){
        var player=player(h);axolotlWater(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));var other=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,5));var a=h.spawnWithNoFreeWill(EntityType.AXOLOTL,new BlockPos(4,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            BattleManager.authorized(()->other.hurt(player.damageSources().playerAttack(player),100));
            h.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION),"Unassisted kill granted free regeneration");
            BattleManager.authorized(()->mob.hurt(a.damageSources().mobAttack(a),2));
            h.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION),"Participation alone granted regeneration");
            mob.invulnerableTime=0;BattleManager.authorized(()->mob.hurt(player.damageSources().playerAttack(player),100));
            var regen=player.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
            h.assertTrue(regen!=null && regen.getDuration()==100 && battle.member(a).assistedTargets.isEmpty(),"Shared kill failed native support or retained reward token");
        }finally{battle.close("test");cleanup(player);mob.discard();other.discard();a.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void resourceAdmissionProtectsEveryCombatEntry(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var cow=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(5,1,2));var original=CombatData.current;
            try{
                CombatData.current=participationData("minecraft:cow","auto");
                h.assertTrue(cow.getType().is(BattleParticipation.RESOURCES),"Resource tag not loaded");
                cow.hurt(player.damageSources().playerAttack(player),1);
                h.assertTrue(!BattleManager.locked(player) && !BattleManager.locked(cow),"Broad AI registration enabled resource combat");
                var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(9,1,2));
                var battle=new BattleSession(player,mob,CombatData.current,player);
                try{
                    h.assertTrue(!battle.canJoin(cow),"Resource joined automatically");battle.joinAttacker(cow,player);
                    h.assertTrue(!battle.members.containsKey(cow.getUUID()),"Resource entered via outside attacker fallback");
                    float hp=cow.getHealth();cow.invulnerableTime=0;cow.hurt(player.damageSources().playerAttack(player),1);
                    h.assertTrue(cow.getHealth()==hp,"Locked player used outside resource to bypass combat restrictions");
                }finally{battle.close("test");mob.discard();}
                var explicit=participationData("minecraft:cow","hostile");h.assertTrue(BattleParticipation.allowed(cow,explicit) && BattleParticipation.hostile(cow,explicit),"Explicit datapack override ignored");
                h.assertTrue(!BattleParticipation.allowed(cow,participationData("minecraft:cow","never")),"Never policy ignored");
            }finally{CombatData.current=original;var active=BattleManager.ACTIVE.get(player.getUUID());if(active!=null)active.close("test");cleanup(player);cow.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void participationPolicyLoadsAndRejectsTypos(GameTestHelper h){
        var id=ResourceLocation.parse("mineturn_test:mobs/cow_policy");
        var json=JsonParser.parseString("{\"entity\":\"minecraft:cow\",\"participation\":\"neutral\",\"ai\":{\"on_turn\":\"mineturn:ai/realm/finish\"}}");
        h.assertTrue(CombatData.parse(Map.of(id,json)).mobs().get("minecraft:cow").participation().equals("neutral"),"Participation JSON not retained");
        json.getAsJsonObject().addProperty("participation","neutarl");
        try{CombatData.parse(Map.of(id,json));throw new AssertionError("Invalid participation accepted");}catch(IllegalArgumentException expected){}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void snowGolemSupportsAndPrioritizesBlaze(GameTestHelper h){
        var player=player(h);var blaze=h.spawnWithNoFreeWill(EntityType.BLAZE,new BlockPos(9,1,6));
        var husk=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(4,1,3));var snow=h.spawnWithNoFreeWill(EntityType.SNOW_GOLEM,new BlockPos(3,1,5));
        var battle=new BattleSession(player,blaze,CombatData.current,player);
        try{
            h.assertTrue(battle.members.containsKey(snow.getUUID()) && player.getUUID().equals(battle.side(snow)),"Snow golem did not join allied side");
            battle.actor=snow;battle.budget=new TurnBudget(4);float hp=blaze.getHealth();battle.ai(snow);
            h.assertTrue(blaze.getHealth()==hp-3 && blaze.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) && husk.getHealth()==husk.getMaxHealth(),"Snowball did not prioritize native Blaze damage");
            h.assertTrue(battle.budget.mainActions()==0 && !battle.ready(snow,"mineturn:snowball_support"),"Snowball cost/cooldown missing");
            battle.member(snow).cooldowns.clear();var action=CombatData.current.actions().get("mineturn:snowball_support");hp=husk.getHealth();battle.execute(snow,husk,"mineturn:snowball_support",action,ItemStack.EMPTY);
            h.assertTrue(husk.getHealth()==hp && husk.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN),"Snowball invented ordinary damage or lost support effect");
            battle.remove(player,"test");battle.prune();h.assertTrue(!BattleManager.locked(snow),"Snow golem stayed after ally owner left");
        }finally{battle.close("test");cleanup(player);blaze.discard();husk.discard();snow.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void beesAlertStingOnceAndAgeByAv(GameTestHelper h){
        var player=player(h);var stranger=player(h);
        h.runAfterDelay(70,()->{
            var bee=h.spawnWithNoFreeWill(EntityType.BEE,new BlockPos(3,1,2));var nearby=h.spawnWithNoFreeWill(EntityType.BEE,new BlockPos(7,2,3));
            var far=h.spawnWithNoFreeWill(EntityType.BEE,new BlockPos(20,2,2));
            var battle=new BattleSession(player,bee,CombatData.current,bee);
            try{
                h.assertTrue(battle.members.containsKey(nearby.getUUID()) && far.getPersistentAngerTarget()==null,"Bee alert did not respect radius");
                h.assertTrue(battle.enemy(bee,player) && !battle.enemy(bee,stranger),"Bee anger leaked to another player");
                battle.ai(bee);h.assertTrue(bee.hasStung() && battle.budget.mainActions()==0,"Bee native sting failed");
                float hp=player.getHealth();battle.budget=new TurnBudget(4);battle.ai(bee);
                h.assertTrue(battle.budget.mainActions()==1 && player.getHealth()==hp,"Spent bee stung twice");
                var life=(com.matuvent.mineturn.mixin.BeeLifeAccess)bee;int age=life.mineturn$stingAge();
                h.assertTrue(age==0,"Sting age advanced without AV");BattleStatus.tick(battle.member(bee));h.assertTrue(life.mineturn$stingAge()==1,"Sting age did not follow logical tick");
                life.mineturn$stingAge(1199);bee.invulnerableTime=0;BattleStatus.tick(battle.member(bee));
                h.assertTrue(!bee.isAlive(),"Native terminal sting lifespan did not expire on AV");
            }finally{battle.close("test");cleanup(player);cleanup(stranger);bee.discard();nearby.discard();far.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void phantomTelegraphAllowsDodgeAndRecovery(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.PHANTOM,new BlockPos(6,5,2));
            var other=h.spawnWithNoFreeWill(EntityType.PHANTOM,new BlockPos(10,5,5));
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                battle.budget=new TurnBudget(10);float hp=player.getHealth();battle.ai(mob);
                var plan=battle.member(mob).airPlan;
                h.assertTrue(plan!=null && battle.motion==null && battle.budget.mainActions()==0 && player.getHealth()==hp,"Phantom attacked without telegraph");
                battle.actor=other;battle.budget=new TurnBudget(10);
                h.assertTrue(!BattleAerial.canPrepare(battle,other,player,8),"Multiple phantoms locked simultaneous dives");
                battle.actor=mob;battle.budget=new TurnBudget(10);battle.ai(mob);
                h.assertTrue(battle.motion==null,"Phantom skipped defender response turn");
                battle.place(player,player.position().add(0,0,3));
                // Advance actual initiative until the defender has finished a turn and 100 AV elapsed.
                for(int i=0;i<12 && (battle.member(player).completedTurns==0 || battle.clock.time()<100);i++)battle.next();
                battle.actor=mob;battle.budget=new TurnBudget(10);battle.ai(mob);
                h.assertTrue(battle.motion!=null && battle.motion.route.destination().equals(plan.route().destination()),"Dive followed moved target instead of fixed route");
                finishMovement(player);
                h.assertTrue(player.getHealth()==hp && battle.member(mob).airRecoveryTarget!=null,"Dodge failed or recovery missing");
                battle.actor=mob;battle.budget=new TurnBudget(10);Vec3 low=mob.position();battle.ai(mob);
                h.assertTrue(battle.motion==null && mob.position().equals(low) && battle.budget.mainActions()==1,"Phantom escaped during recovery window");
            }finally{battle.close("test");cleanup(player);mob.discard();other.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void guardianSpinesFollowBeamWindow(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.GUARDIAN,new BlockPos(4,1,2));
            boolean original=mob.isMoving();var battle=new BattleSession(player,mob,guardianDefinitions(),player);
            try{
                float hp=player.getHealth();
                BattleManager.authorized(()->mob.hurt(player.damageSources().playerAttack(player),1));
                h.assertTrue(player.getHealth()==hp-2 && !mob.isMoving(),"Stationary spines did not apply native thorns");
                player.invulnerableTime=0;mob.invulnerableTime=0;
                var action=CombatData.current.actions().get("mineturn:guardian_beam");battle.execute(mob,player,"mineturn:guardian_beam",action,ItemStack.EMPTY);
                hp=player.getHealth();BattleManager.authorized(()->mob.hurt(player.damageSources().playerAttack(player),1));
                h.assertTrue(player.getHealth()==hp && mob.isMoving(),"Charging guardian did not expose melee window");
                BattleSpecies.clearBeam(battle.member(mob));BattleSpecies.updateSpines(battle,battle.member(mob));
                h.assertTrue(!mob.isMoving(),"Spines did not extend after beam");
            }finally{battle.close("test");h.assertTrue(mob.isMoving()==original,"Exit did not restore moving flag");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void elderGuardianAlternatesLocks(GameTestHelper h){
        var player=player(h);var elder=h.spawnWithNoFreeWill(EntityType.ELDER_GUARDIAN,new BlockPos(8,1,6));
        var ally=h.spawnWithNoFreeWill(EntityType.WOLF,new BlockPos(3,1,5));
        var battle=new BattleSession(player,elder,CombatData.current,elder);
        try{
            battle.add(ally);battle.member(ally).allyOwner=player.getUUID();battle.ai(elder);
            var first=battle.member(elder).beamTarget;h.assertTrue(first!=null,"Elder failed first beam lock");
            BattleSpecies.clearBeam(battle.member(elder));battle.scheduled.clear();battle.member(elder).cooldowns.clear();battle.budget=new TurnBudget(4);battle.ai(elder);
            h.assertTrue(battle.member(elder).beamTarget!=null && !first.equals(battle.member(elder).beamTarget),"Elder repeatedly locked one target");
            h.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN),"Elder added mining fatigue");
        }finally{battle.close("test");cleanup(player);elder.discard();ally.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerWindChargePaysAndRespectsCollision(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(6,1,2));
        var outside=h.spawnWithNoFreeWill(EntityType.PIG,new BlockPos(7,1,4));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try{
            player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.WIND_CHARGE,3));
            Vec3 start=mob.position(),safe=outside.position();double movement=battle.budget.remaining();
            battle.use(player,0,"mineturn:wind_burst",mob.getId());var miss=battle.shot;
            battle.submitShot(player,miss.token,miss.startNanos);
            h.assertTrue(player.getInventory().getItem(0).getCount()==2 && mob.position().equals(start) && battle.budget.mainActions()==0,"Miss did not pay exactly once or displaced target");
            battle.member(player).cooldowns.clear();battle.budget=new TurnBudget(movement);
            boolean rejected=false;
            try{battle.use(player,0,"mineturn:wind_burst",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && player.getInventory().getItem(0).getCount()==2 && battle.budget.canAct(),"Item cooldown bypassed by clearing action cooldown");
            advanceShieldTestClock(battle,100);BattleItemCooldowns.clock(battle,battle.member(player));
            for(int y=1;y<4;y++)h.setBlock(9,y,2,Blocks.STONE);
            battle.use(player,0,"mineturn:wind_burst",mob.getId());var hit=battle.shot;
            battle.submitShot(player,hit.token,hit.windowCentreNanos());
            h.assertTrue(mob.getX()>start.x && mob.getX()<h.absolutePos(new BlockPos(9,1,2)).getX() && mob.getHealth()<mob.getMaxHealth(),"Wind burst failed displacement or crossed wall");
            h.assertTrue(outside.position().equals(safe) && battle.budget.remaining()==movement && player.getInventory().getItem(0).getCount()==1,"Wind burst affected outsider or charged movement/item twice");
        }finally{battle.close("test");cleanup(player);mob.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void breezeWindUsesSharedDisplacement(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.BREEZE,new BlockPos(8,1,2));
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                battle.place(player,Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4,1,2))));Vec3 start=player.position();float hp=player.getHealth();battle.ai(mob);
                h.assertTrue(battle.budget.mainActions()==0 && player.getX()<start.x && player.getHealth()<hp,"Breeze failed paid wind displacement");
                h.assertTrue(player.getDeltaMovement().lengthSqr()==0 && !battle.ready(mob,"mineturn:breeze_burst"),"Wind left realtime velocity or no AV cooldown");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void blazeChargesThenUsesAvVolley(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.BLAZE,new BlockPos(8,1,2));
            var data=CombatData.current;var brains=new HashMap<>(data.mobs());
            var params=new net.minecraft.nbt.CompoundTag();params.putString("action","mineturn:blaze_volley");
            brains.put("minecraft:blaze",new CombatData.Brain(100,1.5,"",Map.of(),new CombatData.Functions(Map.of("on_turn",ResourceLocation.parse("mineturn:ai/blaze/turn")),params),"flying"));
            var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),mob);
            try{
                float hp=player.getHealth();battle.ai(mob);
                h.assertTrue(battle.member(mob).blazeCharged && battle.budget.mainActions()==0 && player.getHealth()==hp,"Charge did not consume exactly one harmless action");
                battle.budget=new TurnBudget(4);battle.ai(mob);
                h.assertTrue(!battle.member(mob).blazeCharged && player.getHealth()<hp && battle.scheduled.size()==2,"Charged volley failed to launch three AV shots");
                h.assertTrue(!battle.ready(mob,"mineturn:blaze_volley"),"Volley cooldown missing");
                battle.budget=new TurnBudget(4);battle.ai(mob);
                h.assertTrue(!battle.member(mob).blazeCharged && battle.budget.mainActions()==1,"Blaze charged during cooldown");
                float afterFirst=player.getHealth();battle.runScheduledBeforeNextTurn();
                h.assertTrue(player.getHealth()<afterFirst && battle.scheduled.isEmpty() && player.getRemainingFireTicks()>0,"Delayed native fireball damage/burning missing");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void ghastWandersAndUsesScopedExplosion(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.GHAST,new BlockPos(8,2,8));
            var bystander=h.spawnWithNoFreeWill(EntityType.PIG,new BlockPos(2,1,3));
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                float hp=player.getHealth(),safe=bystander.getHealth();battle.ai(mob);
                h.assertTrue(player.getHealth()<hp && bystander.getHealth()==safe,"Ghast fireball failed or hit outside battle");
                h.assertTrue(battle.budget.mainActions()==0 && battle.motion!=null && battle.budget.remaining()<battle.movement(mob),"Ghast did not wander using independent movement");
                h.assertTrue(h.getBlockState(new BlockPos(2,0,2)).is(Blocks.STONE),"Ghast destroyed terrain");
            }finally{battle.close("test");cleanup(player);mob.discard();bystander.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void endermanTeleportUsesBonusAndSafeGround(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ENDERMAN,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try{
            Vec3 start=mob.position();double movement=battle.budget.remaining();battle.budget.setActions(0,1);battle.ai(mob);
            h.assertTrue(!mob.position().equals(start) && battle.budget.mainActions()==0 && battle.budget.bonusActions()==0,"Teleport did not use only bonus action");
            h.assertTrue(battle.budget.remaining()==movement && !h.getLevel().containsAnyLiquid(mob.getBoundingBox()) && !h.getLevel().noCollision(mob,mob.getBoundingBox().move(0,-0.06,0)),"Teleport spent movement or landed unsafely");
            Vec3 landed=mob.position();battle.budget=new TurnBudget(4);battle.budget.setActions(0,1);battle.ai(mob);
            h.assertTrue(mob.position().equals(landed) && battle.budget.bonusActions()==1,"Teleport repeated during cooldown");
            var another=h.spawnWithNoFreeWill(EntityType.ENDERMAN,new BlockPos(10,1,8));
            try{h.assertTrue(battle.canJoin(another),"Unprovoked enderman could not auto-join");}finally{another.discard();}
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void netherFactionsAndNativeBruteStats(GameTestHelper h){
        var player=player(h);var brute=h.spawnWithNoFreeWill(EntityType.PIGLIN_BRUTE,new BlockPos(8,1,8));
        var zoglin=h.spawnWithNoFreeWill(EntityType.ZOGLIN,new BlockPos(6,1,6));var hoglin=h.spawnWithNoFreeWill(EntityType.HOGLIN,new BlockPos(8,1,6));
        var battle=new BattleSession(player,brute,CombatData.current,player);
        try{
            h.assertTrue(battle.agility(brute)==90 && brute.getMaxHealth()==50,"Brute lost low agility/native health");
            h.assertTrue(battle.enemy(zoglin,hoglin) && battle.enemy(hoglin,zoglin) && battle.enemy(zoglin,player),"Zoglin independent faction is asymmetric");
            h.assertTrue(!battle.enemy(brute,hoglin),"Ordinary hostile mobs became enemies");
        }finally{battle.close("test");cleanup(player);brute.discard();zoglin.discard();hoglin.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void shulkerBulletTracksOnAxesAndCleansUp(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.SHULKER,new BlockPos(8,1,2));
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                float hp=player.getHealth();battle.ai(mob);
                var bullet=(BattleBullet)battle.members.values().stream().map(m->m.entity).filter(BattleBullet.class::isInstance).findFirst().orElseThrow();
                h.assertTrue(bullet.getMaxHealth()==1 && battle.enemies(player).contains(bullet) && battle.agility(bullet)==200,"Bullet is not a targetable queued unit");
                for(int turn=0;turn<8 && bullet.isAlive();turn++){
                    battle.actor=bullet;battle.budget=new TurnBudget(4);Vec3 start=bullet.position();battle.ai(bullet);
                    if(battle.motion!=null){
                        Vec3 d=battle.motion.route.destination().subtract(start);
                        h.assertTrue((Math.abs(d.x)>1e-6?1:0)+(Math.abs(d.y)>1e-6?1:0)+(Math.abs(d.z)>1e-6?1:0)==1,"Bullet traveled diagonally");
                        finishMovement(player);
                    }
                }
                h.assertTrue(!bullet.isAlive() && player.getHealth()<hp && player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION),"Bullet failed native impact/levitation");
                battle.prune();battle.actor=mob;battle.budget=new TurnBudget(4);battle.member(mob).cooldowns.clear();battle.ai(mob);
                h.assertTrue(battle.members.values().stream().anyMatch(m->m.entity instanceof BattleBullet),"Second bullet missing");
                battle.remove(mob,"test");
                h.assertTrue(battle.members.values().stream().noneMatch(m->m.entity instanceof BattleBullet),"Caster exit left orphan bullet");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void pillagerChargePaysOnceAndRechecksShot(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            try{for(int scenario=0;scenario<3;scenario++){
                var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(net.minecraft.world.item.Items.CROSSBOW));
                var battle=new BattleSession(player,mob,CombatData.current,mob);
                try{
                    player.setHealth(20);float health=player.getHealth();battle.ai(mob);
                    h.assertTrue(battle.budget.mainActions()==0 && player.getHealth()==health && battle.scheduled.size()==1 && mob.isChargingCrossbow(),"Charge did not reserve one delayed shot");
                    battle.budget=new TurnBudget(4);battle.ai(mob);h.assertTrue(battle.scheduled.size()==1,"Charge granted another attack");
                    if(scenario==1)for(int y=1;y<=3;y++)h.setBlock(5,y,2,Blocks.STONE);
                    if(scenario==2)mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,ItemStack.EMPTY);
                    battle.runScheduledBeforeNextTurn();
                    h.assertTrue(scenario==0?player.getHealth()<health:player.getHealth()==health,"Delayed shot failed range/weapon/cover recheck");
                    h.assertTrue(!mob.isChargingCrossbow() && battle.scheduled.isEmpty(),"Completed shot retained charge");
                }finally{battle.close("test");mob.discard();for(int y=1;y<=3;y++)h.setBlock(5,y,2,Blocks.AIR);}
            }}finally{cleanup(player);}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void vindicatorShieldCooldownUsesAv(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.VINDICATOR,new BlockPos(3,1,2));
            var battle=new BattleSession(player,mob,CombatData.current,player);
            try{
                player.getInventory().setItem(3,new ItemStack(net.minecraft.world.item.Items.SHIELD));
                battle.use(player,3,"mineturn:shield_guard",player.getId());
                battle.actor=mob;battle.budget=new TurnBudget(4);battle.ai(mob);
                var guard=CombatData.current.actions().get("mineturn:shield_guard");
                h.assertTrue(!battle.member(player).guarding && battle.member(player).shieldDisabledUntil==100,"Vindicator did not disable shield after attack");
                h.assertTrue(battle.agility(mob)>battle.agility(player),"Vindicator agility not increased");
                h.assertTrue(CombatEffects.get(guard.effect()).validate(battle.effectContext(player,player,player.getInventory().getItem(3),guard,false))!=null,"Disabled shield was usable");
                battle.next();h.assertTrue(battle.member(player).shieldDisabledUntil>battle.clock.time(),"Shield expired at first fast enemy turn");
                battle.next();BattleRaid.shieldClock(battle,battle.member(player));
                h.assertTrue(CombatEffects.get(guard.effect()).validate(battle.effectContext(player,player,player.getInventory().getItem(3),guard,false))==null,"Shield did not recover after AV cooldown");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void evokerSummonsThenChoosesFangGeometry(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.EVOKER,new BlockPos(8,1,2));
            var outsider=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(3,1,3));
            var battle=new BattleSession(player,mob,CombatData.current,mob);var children=new ArrayList<net.minecraft.world.entity.LivingEntity>();
            try{
                battle.ai(mob);children.addAll(battle.members.values().stream().map(m->m.entity).filter(e->e instanceof net.minecraft.world.entity.monster.Vex).toList());
                h.assertTrue(children.size()==3 && battle.budget.mainActions()==0 && !BattleRaid.canSummon(battle),"Evoker did not summon bounded Vex group");
                h.assertTrue(children.stream().allMatch(e->battle.enemy(player,e)),"Vex did not inherit faction");
                float health=player.getHealth();battle.budget=new TurnBudget(4);battle.ai(mob);
                h.assertTrue(!battle.ready(mob,"mineturn:fangs_line") && player.getHealth()==health-6,"Far evoker did not hit once with line fangs");
                battle.place(mob,player.position().add(2.5,0,0));battle.budget=new TurnBudget(4);health=player.getHealth();battle.ai(mob);
                h.assertTrue(!battle.ready(mob,"mineturn:fangs_circle") && player.getHealth()==health-6,"Near evoker did not choose circle fangs");
                float after=player.getHealth();for(var fang:List.copyOf(battle.fangs))for(int i=0;i<25;i++)fang.tick();
                h.assertTrue(player.getHealth()==after && outsider.getHealth()==outsider.getMaxHealth(),"Visual fangs repeated damage or hit outsiders");
                battle.close("test");h.assertTrue(battle.fangs.isEmpty() && children.stream().noneMatch(BattleManager::locked),"Evoker objects retained after close");
            }finally{battle.close("test");cleanup(player);mob.discard();outsider.discard();children.forEach(net.minecraft.world.entity.Entity::discard);}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void witchUsesNativeHealingAndScopedPotions(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var mob=h.spawnWithNoFreeWill(EntityType.WITCH,new BlockPos(8,1,2));
            var outsider=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(2,1,3));
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                mob.setHealth(4);battle.ai(mob);
                h.assertTrue(mob.getHealth()==8 && battle.budget.mainActions()==0,"Low-health witch did not use native healing potion");
                float health=player.getHealth();
                for(String id:List.of("mineturn:witch_harming","mineturn:witch_poison","mineturn:witch_slowness"))battle.execute(mob,player,id,CombatData.current.actions().get(id),ItemStack.EMPTY);
                h.assertTrue(player.getHealth()<health && player.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN),"Witch potion effects missing");
                h.assertTrue(outsider.getHealth()==outsider.getMaxHealth() && outsider.getActiveEffects().isEmpty(),"Witch splash affected outsider");
            }finally{battle.close("test");cleanup(player);mob.discard();outsider.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void creeperExplosionIsNativeFilteredAndTerminal(GameTestHelper h) {
        var player=player(h);
        h.runAfterDelay(70,()->{
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
            float ordinary=0;
            try {
                for(boolean charged:List.of(false,true)) {
                    var creeper=h.spawnWithNoFreeWill(EntityType.CREEPER,new BlockPos(4,1,2));
                    var ally=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(4,1,4));
                    var outsider=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(3,1,3));
                    if(charged){var tag=new net.minecraft.nbt.CompoundTag();creeper.addAdditionalSaveData(tag);tag.putBoolean("powered",true);creeper.readAdditionalSaveData(tag);}
                    h.setBlock(5,1,2,Blocks.DIRT);player.setHealth(200);player.invulnerableTime=0;
                    var battle=new BattleSession(player,creeper,CombatData.current,creeper);
                    try {
                        float friendlyHealth=ally.getHealth(),outsideHealth=outsider.getHealth();
                        battle.ai(creeper);
                        float damage=200-player.getHealth();
                        h.assertTrue(damage>0 && creeper.isRemoved(),"Creeper did not explode and remove itself");
                        h.assertTrue(ally.getHealth()==friendlyHealth && outsider.getHealth()==outsideHealth,"Explosion damaged allies or outsiders");
                        h.assertTrue(player.getDeltaMovement().lengthSqr()==0 && outsider.getDeltaMovement().lengthSqr()==0,"Explosion applied knockback");
                        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(5,1,2))).is(Blocks.DIRT),"Explosion destroyed terrain");
                        if(charged)h.assertTrue(damage>ordinary,"Powered creeper lost native increased power");else ordinary=damage;
                        battle.prune();h.assertTrue(!BattleManager.locked(creeper),"Dead creeper retained membership");
                    }finally{battle.close("test");creeper.discard();ally.discard();outsider.discard();}
                }
            }finally{cleanup(player);}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void silverfishSummonConsumesBlocksAndJoinsOnce(GameTestHelper h) {
        var player=player(h);
        h.runAfterDelay(70,()->{
            var source=h.spawnWithNoFreeWill(EntityType.SILVERFISH,new BlockPos(6,1,2));
            for(int z=2;z<=5;z++)h.setBlock(8,1,z,Blocks.INFESTED_STONE);
            var battle=new BattleSession(player,source,CombatData.current,source);
            var children=new ArrayList<net.minecraft.world.entity.LivingEntity>();
            try {
                double time=battle.clock.time();battle.ai(source);
                children.addAll(battle.members.values().stream().map(m->m.entity).filter(e->e!=source && e instanceof net.minecraft.world.entity.monster.Silverfish).toList());
                h.assertTrue(children.size()==3 && battle.budget.mainActions()==0,"Summoning ignored cap or main action cost");
                h.assertTrue(battle.clock.time()==time && !battle.ready(source,"mineturn:silverfish_summon"),"Summoning advanced AV or omitted cooldown");
                h.assertTrue(children.stream().allMatch(e->battle.enemy(player,e) && BattleManager.ACTIVE.get(e.getUUID())==battle),"Summons lost faction or membership");
                int remaining=0;for(int z=2;z<=5;z++)if(h.getLevel().getBlockState(h.absolutePos(new BlockPos(8,1,z))).is(Blocks.INFESTED_STONE))remaining++;
                h.assertTrue(remaining==1,"Summoning did not consume exactly three blocks");
                battle.close("test");h.assertTrue(children.stream().noneMatch(BattleManager::locked),"Summons remained locked after close");
            }finally{battle.close("test");cleanup(player);source.discard();children.forEach(net.minecraft.world.entity.Entity::discard);}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void silverfishSummonRespectsGriefingAndSpawnCancellation(GameTestHelper h) {
        var player=player(h);var source=h.spawnWithNoFreeWill(EntityType.SILVERFISH,new BlockPos(6,1,2));
        h.setBlock(8,1,2,Blocks.INFESTED_STONE);
        var battle=new BattleSession(player,source,CombatData.current,source);
        var action=CombatData.current.actions().get("mineturn:silverfish_summon");
        var rule=h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);boolean old=rule.get();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> cancel=event->{
            if(event.getEntity() instanceof net.minecraft.world.entity.monster.Silverfish && event.getEntity()!=source
                    && event.getEntity().distanceToSqr(source)<100)event.setCanceled(true);
        };
        try {
            rule.set(false,h.getLevel().getServer());
            h.assertTrue(!battle.effectContext(source,source,ItemStack.EMPTY,action,false).battle().canSummonSilverfish(),"Griefing disabled but summon remained ready");
            BattleManager.authorized(()->battle.execute(source,source,"mineturn:silverfish_summon",action,ItemStack.EMPTY));
            h.assertTrue(battle.members.size()==2 && h.getLevel().getBlockState(h.absolutePos(new BlockPos(8,1,2))).is(Blocks.INFESTED_STONE),"Disabled griefing consumed block");
            rule.set(true,h.getLevel().getServer());net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
            BattleManager.authorized(()->battle.execute(source,source,"mineturn:silverfish_summon",action,ItemStack.EMPTY));
            h.assertTrue(battle.members.size()==2 && h.getLevel().getBlockState(h.absolutePos(new BlockPos(8,1,2))).is(Blocks.INFESTED_STONE),"Canceled spawn consumed block or entered battle");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);rule.set(old,h.getLevel().getServer());battle.close("test");cleanup(player);source.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void piglinAngerRecruitmentIsBoundedAndTargeted(GameTestHelper h) {
        var player=player(h);var neutral=player(h);
        var zombie=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(8,1,2));
        var pig=h.spawnWithNoFreeWill(EntityType.ZOMBIFIED_PIGLIN,new BlockPos(6,1,4));
        var nearby=h.spawnWithNoFreeWill(EntityType.ZOMBIFIED_PIGLIN,new BlockPos(8,1,4));
        var distant=h.spawnWithNoFreeWill(EntityType.ZOMBIFIED_PIGLIN,new BlockPos(30,1,4));
        var battle=new BattleSession(player,zombie,CombatData.current,player);
        try {
            h.assertTrue(!BattleManager.locked(pig) && !BattleManager.locked(nearby),"Neutral piglins auto joined");
            BattleManager.authorized(()->pig.hurt(player.damageSources().playerAttack(player),1));battle.recruit();
            h.assertTrue(BattleManager.ACTIVE.get(pig.getUUID())==battle && BattleManager.ACTIVE.get(nearby.getUUID())==battle,"Alert did not recruit nearby piglins");
            h.assertTrue(!BattleManager.locked(distant) && distant.getPersistentAngerTarget()==null,"Alert spread beyond bounded radius");
            h.assertTrue(battle.enemy(pig,player) && battle.enemy(player,pig) && !battle.enemy(pig,neutral),"Anger targeted unrelated player");
            int remaining=pig.getRemainingPersistentAngerTime();BattleAnger.tick(pig);
            h.assertTrue(pig.getRemainingPersistentAngerTime()==remaining-1,"Anger did not advance one logical tick");
            pig.setRemainingPersistentAngerTime(1);BattleAnger.tick(pig);
            h.assertTrue(!battle.enemy(pig,player) && pig.getPersistentAngerTarget()==null && pig.getTarget()==null,"Expired anger retained target");
            int before=nearby.getRemainingPersistentAngerTime();battle.close("test");
            h.assertTrue(nearby.getRemainingPersistentAngerTime()==before && player.getUUID().equals(nearby.getPersistentAngerTarget()),"Exit lost native anger state");
        } finally {battle.close("test");cleanup(player);cleanup(neutral);zombie.discard();pig.discard();nearby.discard();distant.discard();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void drownedCrossesShoreAndUsesMeleeDuringTridentCooldown(GameTestHelper h) {
        var player=player(h);
        h.runAfterDelay(70,()->{
        for(int x=4;x<=10;x++)for(int z=1;z<=5;z++)h.setBlock(x,1,z,Blocks.WATER);
        var target=h.absolutePos(new BlockPos(9,1,2));player.teleportTo(target.getX()+0.5,target.getY(),target.getZ()+0.5);
        var drowned=h.spawnWithNoFreeWill(EntityType.DROWNED,new BlockPos(2,1,2));
            drowned.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            // The template-resolution fixture overrides Drowned with synchronous heavy melee.
            var data=CombatData.current;var brains=new HashMap<>(data.mobs());
            var parameters=new net.minecraft.nbt.CompoundTag();parameters.putString("action","mineturn:drowned_trident");
            brains.put("minecraft:drowned",new CombatData.Brain(100,1.5,"",Map.of(),new CombatData.Functions(Map.of(
                    "on_turn",ResourceLocation.parse("mineturn:ai/drowned/turn"),
                    "on_move_finished",ResourceLocation.parse("mineturn:ai/animated_melee/finish")),parameters),"ground"));
            var battle=new BattleSession(player,drowned,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),drowned);
            try {
                battle.budget=new TurnBudget(12);battle.ai(drowned);
                h.assertTrue(battle.motion!=null && battle.motion.mode.equals("amphibious"),"Drowned did not start cross-shore pursuit: "+battle.lastMessage+" source="+drowned.position()+" target="+player.position()+" mode="+battle.movementMode(drowned)+" route="+battle.pursuitRoute(drowned,player));
                Vec3 planned=battle.motion.route.destination();finishMovement(player);
                h.assertTrue(AquaticPath.inWater(drowned.level(),drowned.position()),"Drowned animation stopped on water boundary: "+battle.lastMessage+" actual="+drowned.position()+" planned="+planned);
                var bank=h.absolutePos(new BlockPos(0,1,2));battle.place(player,new Vec3(bank.getX()+0.5,bank.getY(),bank.getZ()+0.5));
                battle.actor=drowned;battle.budget=new TurnBudget(12);battle.ai(drowned);finishMovement(player);
                h.assertTrue(battle.movementMode(drowned).equals("ground"),"Drowned could not return to supported bank");
                battle.place(drowned,player.position().add(1.2,0,0));
                drowned.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(net.minecraft.world.item.Items.TRIDENT));
                battle.member(drowned).cooldowns.put("mineturn:drowned_trident",battle.clock.time()+1000);
                battle.actor=drowned;battle.budget=new TurnBudget(4);player.invulnerableTime=0;float health=player.getHealth();
                battle.ai(drowned);
                h.assertTrue(player.getHealth()<health,"Armed drowned idled instead of melee during cooldown");
            }finally{battle.close("test");cleanup(player);drowned.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void phantomDiveAnimatesHitsAndRetreats(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
            var phantom=h.spawnWithNoFreeWill(EntityType.PHANTOM,new BlockPos(6,5,2));phantom.setNoGravity(true);
            var battle=new BattleSession(player,phantom,CombatData.current,phantom);
            try{
                battle.budget=new TurnBudget(20);var route=battle.diveRoute(phantom,player,8);
                h.assertTrue(route!=null,"Open dive rejected: source="+phantom.position()+" target="+player.position()+" alive="+phantom.isAlive()+" engaged="+battle.engaged(phantom)+" sight="+phantom.hasLineOfSight(player)+" budget="+battle.budget.remaining());
                float health=player.getHealth();double av=battle.clock.time();Vec3 start=phantom.position();
                battle.ai(phantom);launchTelegraphedDive(battle,phantom,player,h);av=battle.clock.time();
                h.assertTrue(battle.motion!=null && battle.motion.onLanding!=null && battle.budget.mainActions()==0,"AI did not commit dive");
                h.assertTrue(Math.abs(battle.budget.remaining()-(20-route.cost()))<1e-6,"Dive did not pay Manhattan movement");
                battle.tickMotion();h.assertTrue(phantom.getY()<start.y && player.getHealth()==health && battle.clock.time()==av,"Dive hit early or advanced AV mid-flight");
                finishMovement(player);
                h.assertTrue(player.getHealth()<health && player.getDeltaMovement().lengthSqr()==0 && !phantom.onGround(),"Dive hit or aerial finish failed");
                h.assertTrue(!battle.ready(phantom,"mineturn:phantom_telegraph"),"Dive cooldown missing");
                battle.actor=phantom;battle.budget=new TurnBudget(20);battle.ai(phantom);
                h.assertTrue(battle.motion==null,"Phantom skipped post-hit recovery");
                for(int i=0;i<12 && BattleAerial.recovering(battle,battle.member(phantom));i++)battle.next();
                battle.actor=phantom;battle.budget=new TurnBudget(20);battle.ai(phantom);
                h.assertTrue(battle.motion!=null && battle.budget.disengaged() && battle.budget.mainActions()==0
                        && battle.motion.route.destination().y>phantom.getY(),"Phantom did not pay retreat before climbing");
                finishMovement(player);
            }finally{battle.close("test");cleanup(player);phantom.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void phantomDiveRejectsUnsafeAndInterruptedRoutes(GameTestHelper h){
        var player=player(h);
        h.runAfterDelay(70,()->{
        var phantom=h.spawnWithNoFreeWill(EntityType.PHANTOM,new BlockPos(6,5,2));phantom.setNoGravity(true);
        var battle=new BattleSession(player,phantom,CombatData.current,phantom);
        try{
            battle.budget=new TurnBudget(0.5);h.assertTrue(battle.diveRoute(phantom,player,8)==null,"Dive ignored movement budget");
            battle.budget=new TurnBudget(20);h.assertTrue(battle.diveRoute(phantom,player,1)==null,"Dive ignored action range");
            battle.ai(phantom);launchTelegraphedDive(battle,phantom,player,h);h.assertTrue(battle.motion!=null && battle.motion.onLanding!=null,"Dive never started: source="+phantom.position()+" target="+player.position()+" message="+battle.lastMessage);
            var samples=battle.motion.route.samples();var obstacle=BlockPos.containing(samples.get(samples.size()/2));
            h.getLevel().setBlockAndUpdate(obstacle,Blocks.STONE.defaultBlockState());
            float health=player.getHealth();finishMovement(player);
            h.assertTrue(player.getHealth()==health && !battle.ready(phantom,"mineturn:phantom_telegraph"),"Interrupted dive health="+player.getHealth()+" before="+health+" cooldown="+battle.remainingCooldown(phantom,"mineturn:phantom_dive"));
            h.getLevel().setBlockAndUpdate(obstacle,Blocks.AIR.defaultBlockState());
            for(int i=0;i<12 && BattleAerial.recovering(battle,battle.member(phantom));i++)battle.next();
            battle.place(phantom,player.position().add(4,0,0));battle.actor=phantom;battle.budget=new TurnBudget(20);
            h.assertTrue(battle.diveRoute(phantom,player,8)==null,"Low phantom could dive without height advantage");
            battle.ai(phantom);h.assertTrue(battle.motion!=null && battle.motion.route.destination().y>phantom.getY(),"Low phantom did not climb");
            battle.close("test");h.assertTrue(battle.motion==null,"Battle close retained phantom movement");
        }finally{battle.close("test");cleanup(player);phantom.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void slimeAndMagmaSplitContinueBattle(GameTestHelper h){
        var player=player(h);
        try{
            for(var type:List.of(EntityType.SLIME,EntityType.MAGMA_CUBE)){
                var parent=h.spawnWithNoFreeWill(type,new BlockPos(8,1,3));parent.setSize(4,true);
                parent.setCustomName(net.minecraft.network.chat.Component.literal("split-test"));
                var battle=new BattleSession(player,parent,CombatData.current,player);
                var spawned=new ArrayList<net.minecraft.world.entity.LivingEntity>();
                try{
                    BattleManager.authorized(()->parent.hurt(player.damageSources().playerAttack(player),1000));battle.prune();
                    var children=battle.enemies(player);spawned.addAll(children);
                    h.assertTrue(!battle.closed && parent.isRemoved() && !BattleManager.locked(parent),"Parent death ended battle before split");
                    h.assertTrue(children.size()>=2 && children.size()<=4,"Native split count changed");
                    for(var child:children){
                        h.assertTrue(child.getType()==type && ((net.minecraft.world.entity.monster.Slime)child).getSize()==2
                                && child.getName().getString().equals("split-test"),"Native child type/size/name not preserved");
                        h.assertTrue(BattleManager.locked(child) && battle.clock.remaining(child)==Timeline.LAP,"Child not locked or received instant turn");
                    }
                    battle.prune();h.assertTrue(battle.enemies(player).size()==children.size(),"Repeated prune duplicated children");
                    for(var child:children)BattleManager.authorized(()->child.hurt(player.damageSources().playerAttack(player),1000));
                    battle.prune();var smallest=battle.enemies(player);spawned.addAll(smallest);
                    h.assertTrue(!battle.closed && !smallest.isEmpty() && smallest.stream().allMatch(e->((net.minecraft.world.entity.monster.Slime)e).getSize()==1),"Second generation failed to join");
                    for(var child:smallest)BattleManager.authorized(()->child.hurt(player.damageSources().playerAttack(player),1000));
                    battle.prune();h.assertTrue(battle.closed && !BattleManager.locked(player),"Final small slime death did not end battle");
                }finally{battle.close("test");for(var child:spawned)if(!child.isRemoved())child.discard();if(!parent.isRemoved())parent.discard();}
            }
        }finally{cleanup(player);}h.succeed();
    }
    @GameTest(template="empty")
    public static void cancelledSlimeSplitDoesNotKeepBattle(GameTestHelper h){
        var player=player(h);var parent=h.spawnWithNoFreeWill(EntityType.SLIME,new BlockPos(8,1,3));parent.setSize(4,true);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.MobSplitEvent> listener=event->{if(event.getParent()==parent)event.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        var battle=new BattleSession(player,parent,CombatData.current,player);
        try{
            BattleManager.authorized(()->parent.hurt(player.damageSources().playerAttack(player),1000));battle.prune();
            h.assertTrue(battle.closed && !BattleManager.locked(player),"Cancelled split kept an empty battle alive");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);battle.close("test");cleanup(player);if(!parent.isRemoved())parent.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void cancelledSplitChildSpawnLeavesNoGhost(GameTestHelper h){
        var player=player(h);var parent=h.spawnWithNoFreeWill(EntityType.SLIME,new BlockPos(8,1,3));parent.setSize(4,true);
        var children=new HashSet<UUID>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.MobSplitEvent> split=event->{if(event.getParent()==parent)event.getChildren().forEach(e->children.add(e.getUUID()));};
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> spawn=event->{if(children.contains(event.getEntity().getUUID()))event.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(split);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(spawn);
        var battle=new BattleSession(player,parent,CombatData.current,player);
        try{
            BattleManager.authorized(()->parent.hurt(player.damageSources().playerAttack(player),1000));battle.prune();
            h.assertTrue(!children.isEmpty() && battle.closed && children.stream().noneMatch(BattleManager.ACTIVE::containsKey),"Rejected spawn created ghost participant");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(split);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(spawn);battle.close("test");cleanup(player);if(!parent.isRemoved())parent.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void avDeathSplitsBeforeEncounterEnds(GameTestHelper h){
        var player=player(h);var parent=h.spawnWithNoFreeWill(EntityType.SLIME,new BlockPos(8,1,3));parent.setSize(2,true);parent.setHealth(1);
        var battle=new BattleSession(player,parent,CombatData.current,player);
        try{
            BattleManager.authorized(()->parent.setRemainingFireTicks(20));battle.next();
            h.assertTrue(!battle.closed && parent.isRemoved() && !battle.enemies(player).isEmpty(),"AV fire death lost split reinforcements");
            h.assertTrue(battle.enemies(player).stream().allMatch(e->((net.minecraft.world.entity.monster.Slime)e).getSize()==1),"AV split children wrong size");
        }finally{var children=new ArrayList<>(battle.enemies(player));battle.close("test");cleanup(player);for(var child:children)if(!child.isRemoved())child.discard();if(!parent.isRemoved())parent.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void vexPhasingRespectsBudgetAndBoundary(GameTestHelper h){
        var player=player(h);var vex=h.spawnWithNoFreeWill(EntityType.VEX,new BlockPos(9,1,2));
        var data=CombatData.current;var brains=new HashMap<>(data.mobs());var base=brains.get("minecraft:phantom");
        // The installed spatial example deliberately overrides Vex; use the production function with phasing here.
        var callbacks=new HashMap<>(base.functions().callbacks());callbacks.put("on_turn",ResourceLocation.parse("mineturn:ai/animated_melee/turn"));
        brains.put("minecraft:vex",new CombatData.Brain(base.agility(),base.reach(),base.initial(),base.states(),new CombatData.Functions(Map.copyOf(callbacks),base.functions().parameters()),"phasing"));
        var battle=new BattleSession(player,vex,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),vex);
        try{
            for(int y=1;y<=4;y++)for(int z=0;z<=5;z++)h.setBlock(6,y,z,Blocks.STONE);
            Vec3 start=vex.position();
            var ordinary=SpatialPath.trace(vex,new Vec3(-6,0,0),false,p->true);
            var phase=battle.path(vex,new Vec3(-6,0,0),false);
            h.assertTrue(ordinary.cost()<4 && phase.cost()>5,"Phasing did not cross solid wall");
            var blocked=SpatialPath.trace(vex,new Vec3(-6,0,0),false,true,p->p.x>start.x-1);
            h.assertTrue(blocked.cost()<1.01,"Phasing bypassed movement control");
            var ceiling=new Vec3(start.x,h.getLevel().getMaxBuildHeight(),start.z);
            h.assertTrue(!SpatialPath.canStep(vex,ceiling.add(0,-1,0),ceiling,false,true),"Phasing crossed build boundary");
            battle.budget=new TurnBudget(20);battle.ai(vex);
            h.assertTrue(battle.motion!=null && battle.motion.mode.equals("phasing"),"Vex did not use animated phasing route");
            double cost=battle.motion.route.cost();h.assertTrue(Math.abs(battle.budget.remaining()-(20-cost))<1e-6,"Phasing did not pay distance");
            finishMovement(player);
            h.assertTrue(vex.getX()<h.absolutePos(new BlockPos(6,1,2)).getX() && vex.hasLineOfSight(player),"Vex stopped inside wall instead of emerging");
        }finally{battle.close("test");cleanup(player);vex.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void vexLifetimeUsesAvAndResumesOutside(GameTestHelper h){
        var player=player(h);var vex=h.spawnWithNoFreeWill(EntityType.VEX,new BlockPos(8,1,2));vex.setLimitedLife(3);
        var battle=new BattleSession(player,vex,CombatData.current,player);
        var life=(com.matuvent.mineturn.mixin.VexLifeAccess)vex;
        try{
            float health=vex.getHealth();for(int i=0;i<20;i++)battle.tick();
            h.assertTrue(life.mineturn$life()==3 && vex.getHealth()==health,"Lifetime advanced while player waited");
            for(int i=0;i<3;i++)BattleStatus.tick(battle.member(vex));
            h.assertTrue(life.mineturn$life()==20 && vex.getHealth()==health-1,"Expiry did not apply native starvation damage");
            for(int i=0;i<19;i++)BattleStatus.tick(battle.member(vex));
            h.assertTrue(vex.getHealth()==health-1,"Expired vex damaged too early");
            BattleStatus.tick(battle.member(vex));h.assertTrue(vex.getHealth()==health-2,"Expired vex did not damage every 100 AV");
            battle.close("test");vex.tick();h.assertTrue(life.mineturn$life()==19,"Vex lifetime failed to resume outside battle");
        }finally{battle.close("test");cleanup(player);vex.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void witherSkeletonBowUsesNativeFlamingArrow(GameTestHelper h){
        var player=player(h);var skeleton=h.spawnWithNoFreeWill(EntityType.WITHER_SKELETON,new BlockPos(8,1,2));
        var cow=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(5,1,5));
        var bow=new ItemStack(net.minecraft.world.item.Items.BOW);
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,bow);
        var battle=new BattleSession(player,skeleton,CombatData.current,skeleton);
        try{
            battle.ai(skeleton);
            h.assertTrue(battle.budget.mainActions()==0 && !battle.ready(skeleton,"mineturn:skeleton_shoot"),"Wither skeleton did not shoot offhand bow");
            var action=CombatData.current.actions().get("mineturn:skeleton_shoot");
            var context=new CombatEffects.Context(skeleton,cow,bow,action,0,null);
            BattleManager.authorized(()->CombatEffects.get(action.effect()).execute(context));
            h.assertTrue(cow.getRemainingFireTicks()==100 && !cow.hasEffect(net.minecraft.world.effect.MobEffects.WITHER),"Arrow lost native fire or incorrectly inflicted melee wither");
            skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            battle.place(skeleton,player.position().add(2,0,0));battle.actor=skeleton;battle.budget=new TurnBudget(4);
            battle.ai(skeleton);h.assertTrue(battle.budget.mainActions()==0 && battle.motion==null,"Unarmed wither skeleton did not switch to melee");
        }finally{battle.close("test");cleanup(player);skeleton.discard();cow.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void shieldActionTriggersNativeRavagerStun(GameTestHelper h){
        var player=player(h);var ravager=h.spawnWithNoFreeWill(EntityType.RAVAGER,new BlockPos(5,1,2));
        h.runAfterDelay(70,()->{
            var battle=new BattleSession(player,ravager,CombatData.current,player);
            try{
                var shield=new ItemStack(net.minecraft.world.item.Items.SHIELD);player.getInventory().setItem(3,shield);
                battle.use(player,3,"mineturn:shield_guard",player.getId());
                h.assertTrue(player.isBlocking() && battle.budget.mainActions()==0,"Shield action failed to raise shield");
                BattleManager.authorized(()->player.hurt(player.damageSources().onFire(),1));
                h.assertTrue(player.isBlocking(),"Fire consumed attack guard");player.setHealth(20);
                var action=CombatData.current.actions().get("mineturn:species_melee");
                boolean stunned=false;
                for(int i=0;i<80 && !stunned;i++){
                    if(i>0){battle.budget=new TurnBudget(4);battle.use(player,3,"mineturn:shield_guard",player.getId());}
                    player.invulnerableTime=0;battle.execute(ravager,player,"mineturn:species_melee",action,ItemStack.EMPTY);
                    h.assertTrue(!player.isUsingItem(),"Single-use guard survived a blocked attack");
                    stunned=ravager.getStunnedTick()>0;
                }
                h.assertTrue(stunned && player.getHealth()==20 && shield.getDamageValue()>0,"Native shield block did not stun or consumed no durability");
                battle.execute(ravager,player,"mineturn:species_melee",action,ItemStack.EMPTY);
                h.assertTrue(player.getHealth()<20,"Consumed guard blocked a second hit");
                battle.budget=new TurnBudget(4);battle.use(player,3,"mineturn:shield_guard",player.getId());
                battle.next();h.assertTrue(!player.isUsingItem(),"Shield did not end at next own turn");
                battle.close("test");h.assertTrue(!player.isUsingItem(),"Shield remained active after battle");
            }finally{battle.close("test");cleanup(player);ravager.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void guardianContinuousBeamTracksAndClears(GameTestHelper h){
        var player=player(h);var guardian=h.spawnWithNoFreeWill(EntityType.GUARDIAN,new BlockPos(8,1,2));
        var battle=new BattleSession(player,guardian,guardianDefinitions(),guardian);
        try{
            battle.ai(guardian);
            h.assertTrue(guardian.hasActiveAttackTarget() && guardian.getActiveAttackTarget()==player,"Native beam target not synchronized");
            battle.actor=player;battle.budget=new TurnBudget(4);
            float initial=guardian.getAttackAnimationScale(0);
            for(int i=0;i<20;i++)battle.tick();
            h.assertTrue(guardian.getAttackAnimationScale(0)==initial,"Beam charge advanced while waiting");
            battle.next();h.assertTrue(guardian.getAttackAnimationScale(0)>initial,"Beam charge did not track AV");
            battle.remove(player,"test");
            h.assertTrue(!guardian.hasActiveAttackTarget() && guardian.getTarget()==null,"Beam survived target departure");
        }finally{battle.close("test");cleanup(player);guardian.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void friendlyWolfAndGolemTeams(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(9,1,2));
        var wolf=h.spawnWithNoFreeWill(EntityType.WOLF,new BlockPos(4,1,4));wolf.tame(player);
        var golem=h.spawnWithNoFreeWill(EntityType.IRON_GOLEM,new BlockPos(7,1,5));
        var sitting=h.spawnWithNoFreeWill(EntityType.WOLF,new BlockPos(4,1,7));sitting.tame(player);sitting.setOrderedToSit(true);
        var battle=new BattleSession(player,enemy,CombatData.current,player);
        try{
            h.assertTrue(battle.members.containsKey(wolf.getUUID()) && battle.members.containsKey(golem.getUUID()),"Allies did not join");
            h.assertTrue(!battle.members.containsKey(sitting.getUUID()),"Sitting wolf joined");
            h.assertTrue(!battle.enemy(wolf,player) && !battle.enemy(wolf,golem) && battle.enemy(wolf,enemy) && battle.enemy(golem,enemy),"Ally faction mismatch");
            h.assertTrue(battle.nearestEnemy(wolf)==enemy,"Wolf targeted its owner");
            battle.actor=wolf;battle.budget=new TurnBudget(4);battle.ai(wolf);
            h.assertTrue(battle.motion!=null || battle.budget.activity()>0,"Ally AI did not act");
            battle.close("test");
            var hostile=new BattleSession(player,golem,CombatData.current,player);
            try{h.assertTrue(hostile.enemy(player,golem),"Directly attacked golem became friendly");}finally{hostile.close("test");}
        }finally{battle.close("test");cleanup(player);wolf.discard();golem.discard();enemy.discard();sitting.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void ravagerStunRoarAvAndCleanup(GameTestHelper h){
        var player=player(h);var ravager=h.spawnWithNoFreeWill(EntityType.RAVAGER,new BlockPos(5,1,2));
        h.runAfterDelay(70,()->{
            var battle=new BattleSession(player,ravager,CombatData.current,player);
            try{
                BattleSpecies.stun(battle,ravager);
                for(int i=0;i<20;i++)battle.tick();
                h.assertTrue(ravager.getStunnedTick()==40,"Stun advanced in real time");
                for(int i=0;i<40;i++)BattleSpecies.tick(battle,battle.member(ravager));
                h.assertTrue(ravager.getStunnedTick()==0 && ravager.getRoarTick()==20,"Stun did not transition to roar");
                float before=player.getHealth();
                for(int i=0;i<10;i++)BattleSpecies.tick(battle,battle.member(ravager));
                h.assertTrue(player.getHealth()==before-6 && player.getDeltaMovement().lengthSqr()==0,"Roar damage or knockback incorrect");
                for(int i=0;i<10;i++)BattleSpecies.tick(battle,battle.member(ravager));
                h.assertTrue(player.getHealth()==before-6 && !BattleSpecies.busy(ravager),"Roar repeated or failed to recover");
                BattleSpecies.stun(battle,ravager);battle.close("test");
                h.assertTrue(ravager.getStunnedTick()==0 && ravager.getRoarTick()==0,"Ravager remained frozen on exit");
            }finally{battle.close("test");cleanup(player);ravager.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void spiderClimbRequiresWall(GameTestHelper h){
        var player=player(h);var spider=h.spawnWithNoFreeWill(EntityType.SPIDER,new BlockPos(5,1,5));
        var battle=new BattleSession(player,spider,CombatData.current,spider);
        try{
            h.assertTrue(battle.movementMode(spider).equals("climbing"),"Spider climbing mapping missing");
            h.assertTrue(ClimbingPath.trace(spider,new Vec3(0,3,0),p->true).cost()<1,"Spider flew without wall");
            for(int y=1;y<=5;y++)for(int z=3;z<=7;z++)h.setBlock(7,y,z,Blocks.STONE);
            battle.place(spider,spider.position().add(0.5,0,0));
            var route=ClimbingPath.trace(spider,new Vec3(0,3,0),p->true);
            h.assertTrue(Math.abs(route.cost()-3)<1e-5,"Spider failed vertical climb");
            battle.motion=new BattleSession.Movement(spider,route,"climbing");finishMovement(player);
            h.assertTrue(spider.position().distanceToSqr(route.destination())<1e-6,"Climb animation missed destination");
        }finally{battle.close("test");cleanup(player);spider.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void spiderLeapAnimationAndCollision(GameTestHelper h){
        var player=player(h);var spider=h.spawnWithNoFreeWill(EntityType.SPIDER,new BlockPos(6,1,2));
        h.runAfterDelay(70,()->{
            var battle=new BattleSession(player,spider,CombatData.current,spider);
            try{
                var action=CombatData.current.actions().get("mineturn:spider_leap");
                h.assertTrue(battle.leapRoute(spider,player)!=null,"Safe leap rejected");
                float health=player.getHealth();Vec3 start=spider.position();
                battle.ai(spider);
                h.assertTrue(battle.motion!=null && battle.motion.mode.equals("leap") && battle.budget.mainActions()==0,"Datapack did not commit leap");
                battle.tickMotion();h.assertTrue(spider.getY()>start.y && player.getHealth()==health,"Leap did not animate before damage");
                finishMovement(player);h.assertTrue(player.getHealth()<health,"Leap landing did not attack");
                h.assertTrue(!battle.ready(spider,"mineturn:spider_leap"),"Leap cooldown missing");
                battle.place(spider,start);battle.actor=spider;battle.budget=new TurnBudget(8);
                for(int y=1;y<4;y++)h.setBlock(4,y,2,Blocks.STONE);
                h.assertTrue(battle.leapRoute(spider,player)==null,"Leap crossed solid wall");
            }finally{battle.close("test");cleanup(player);spider.discard();}h.succeed();
        });
    }
    private static void launchTelegraphedDive(BattleSession battle,net.minecraft.world.entity.Mob phantom,ServerPlayer player,GameTestHelper h){
        var plan=battle.member(phantom).airPlan;h.assertTrue(plan!=null,"Phantom did not lock a route");
        for(int i=0;i<16 && (battle.member(player).completedTurns<=plan.defenderTurns() || battle.clock.time()<plan.readyAv());i++)battle.next();
        battle.actor=phantom;battle.budget=new TurnBudget(20);battle.ai(phantom);
    }
    private static CombatData.Snapshot guardianDefinitions(){
        var data=CombatData.current;var brains=new HashMap<>(data.mobs());
        // Spatial example intentionally overrides normal Guardian; test the shared production beam mapping.
        brains.put("minecraft:guardian",data.mobs().get("minecraft:elder_guardian"));
        return new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources());
    }
    @GameTest(template="empty")
    public static void drownedTridentNativeHitAndRequirements(GameTestHelper h) {
        var mob=h.spawnWithNoFreeWill(EntityType.DROWNED,new BlockPos(2,1,2));
        var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(6,1,2));
        try {
            var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,weapon);
            var action=CombatData.current.actions().get("mineturn:drowned_trident");
            var context=new CombatEffects.Context(mob,target,weapon,action,0,null);var effect=CombatEffects.get(action.effect());
            h.assertTrue(effect.validate(context)==null,"Armed drowned trident rejected");
            Vec3 position=target.position();BattleManager.authorized(()->effect.execute(context));
            h.assertTrue(target.getHealth()==2 && target.position().equals(position) && target.getDeltaMovement().lengthSqr()==0,"Native trident damage or no knockback failed");
            h.assertTrue(weapon.getCount()==1 && weapon.getDamageValue()==0,"Drowned consumed equipped trident");
            target.setPos(mob.position().add(17,0,0));h.assertTrue(effect.validate(context)!=null,"Trident ignored range");
            target.setPos(position);mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            h.assertTrue(effect.validate(context)!=null,"Unarmed drowned could throw trident");
        }finally{mob.discard();target.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void guardianChargeUsesAvAndRechecksWalls(GameTestHelper h) {
        var player=player(h);
        h.runAfterDelay(70,()->{
            try {
                for(var type:List.of(EntityType.GUARDIAN,EntityType.ELDER_GUARDIAN)) {
                    player.setHealth(20);player.invulnerableTime=0;
                    var mob=h.spawnWithNoFreeWill(type,new BlockPos(8,1,2));
                    var battle=new BattleSession(player,mob,guardianDefinitions(),mob);
                    try {
                        battle.ai(mob);
                        double delay=mob.getAttackDuration()*BattleStatus.AV_PER_TICK;
                        h.assertTrue(!battle.budget.canAct() && battle.scheduled.size()==1 && battle.scheduled.peek().at()==delay && player.getHealth()==20,"Guardian failed to prepay AV charge");
                        for(int i=0;i<20;i++)battle.tickMotion();
                        h.assertTrue(battle.clock.time()==0 && player.getHealth()==20,"Real time advanced beam charge");
                        for(int i=0;i<20 && !battle.scheduled.isEmpty();i++)battle.next();
                        h.assertTrue(battle.scheduled.isEmpty() && battle.clock.time()==delay && player.getHealth()<20,"Beam did not resolve at native AV duration");
                        player.setHealth(20);battle.actor=mob;battle.budget=new TurnBudget(4);battle.member(mob).cooldowns.clear();
                        battle.ai(mob);
                        for(int y=1;y<=4;y++)h.setBlock(5,y,2,Blocks.STONE);
                        for(int i=0;i<20 && !battle.scheduled.isEmpty();i++)battle.next();
                        h.assertTrue(player.getHealth()==20 && battle.scheduled.isEmpty(),"Beam hit through new wall");
                    }finally{battle.close("test");mob.discard();for(int y=1;y<=4;y++)h.setBlock(5,y,2,Blocks.AIR);}
                }
            }finally{cleanup(player);}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void guardianChargeCancelledOnLeave(GameTestHelper h) {
        var player=player(h);var guardian=h.spawnWithNoFreeWill(EntityType.GUARDIAN,new BlockPos(8,1,2));
        var battle=new BattleSession(player,guardian,guardianDefinitions(),guardian);
        try {
            battle.ai(guardian);h.assertTrue(battle.scheduled.size()==1,"Guardian did not schedule charge");
            battle.remove(guardian,"test leave");battle.runScheduledBeforeNextTurn();
            h.assertTrue(battle.scheduled.isEmpty() && player.getHealth()==20,"Removed guardian retained delayed beam");
        }finally{battle.close("test");cleanup(player);guardian.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void countedActionsTimerAndPacket(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.getAttribute(com.matuvent.mineturn.MineTurn.MAIN_ACTIONS).setBaseValue(3);
        player.getAttribute(com.matuvent.mineturn.MineTurn.BONUS_ACTIONS).setBaseValue(2);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            h.assertTrue(battle.budget.mainActions()==3 && battle.budget.bonusActions()==2,"Turn attributes not applied");
            battle.budget.act();battle.budget.act();battle.budget.bonusAct();
            h.assertTrue(battle.budget.canAct() && battle.budget.mainActions()==1 && battle.budget.bonusActions()==1,"Action counts not independent");
            battle.idleTicks=599;battle.tick();
            h.assertTrue(battle.actor==player && battle.idleTicks==1,"Successful action did not reset timer");
            var packet=battle.snapshot(player);var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buf,packet);
                var decoded=com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buf);
                h.assertTrue(decoded.mainActions()==1 && decoded.bonusActions()==1 && decoded.turnSeconds()==30,"Turn resources packet lost fields");
            }finally{buf.release();}
            battle.idleTicks=599;
            try{battle.action(player,"invalid",0,"",-1,0,0);}catch(IllegalArgumentException expected){}
            battle.tick();h.assertTrue(battle.idleTicks==0 && battle.clock.time()>0,"Idle player did not time out after invalid request");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void skillDisplacementAndActionGrantScope(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(6,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var saved=new java.util.concurrent.atomic.AtomicReference<CombatEffects.Context>();
        var id=ResourceLocation.parse("mineturn_test:displace_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(id,context->{
            saved.set(context);context.battle().changeActions(2,1);context.battle().spendBonusAction();
            context.battle().displace(context.target(),new Vec3(2,4,0));
        });
        var action=new CombatData.Action("test",id.toString(),0,16,false,0,0,new com.google.gson.JsonObject());
        try {
            Vec3 start=mob.position();double movement=battle.budget.remaining();
            var preview=battle.effectContext(player,mob,ItemStack.EMPTY,action,false);
            try{preview.battle().displace(mob,new Vec3(2,0,0));throw new AssertionError("Preview displaced target");}catch(IllegalStateException expected){}
            battle.budget.act();battle.execute(player,mob,id.toString(),action,ItemStack.EMPTY);
            h.assertTrue(mob.position().distanceToSqr(start.add(2,0,0))<1e-6 && mob.getHealth()<mob.getMaxHealth(),"Launch did not move target or apply landing damage");
            h.assertTrue(battle.budget.mainActions()==2 && battle.budget.bonusActions()==1 && battle.budget.remaining()==movement,"Skill grant or free forced movement incorrect");
            try{saved.get().battle().changeActions(1,0);throw new AssertionError("Expired effect granted actions");}catch(IllegalStateException expected){}
            for(int y=1;y<=3;y++)h.setBlock(9,y,2,Blocks.STONE);
            var blocked=SpatialPath.trace(mob,new Vec3(2,0,0),false,p->true);
            h.assertTrue(blocked.cost()<1,"Forced displacement geometry ignored wall");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void slimeSizesUseNativeDamage(GameTestHelper h) {
        for(var type:List.of(EntityType.SLIME,EntityType.MAGMA_CUBE))for(int size:new int[]{1,2,4}) {
            var slime=h.spawnWithNoFreeWill(type,new BlockPos(2,1,2));slime.setSize(size,true);
            var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(6,1,2));
            try {
                var action=CombatData.current.actions().get("mineturn:slime_melee");
                var context=new CombatEffects.Context(slime,target,ItemStack.EMPTY,action,0,null);
                var effect=CombatEffects.get(action.effect());boolean harmless=type==EntityType.SLIME && size==1;
                h.assertTrue((effect.validate(context)!=null)==harmless,"Tiny slime/magma damage eligibility wrong");
                BattleManager.authorized(()->effect.execute(context));
                h.assertTrue(target.getHealth()==10-(harmless?0:size+(type==EntityType.MAGMA_CUBE?2:0)),"Slime damage lost size or magma bonus");
            }finally{slime.discard();target.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void registeredFlyersPursueInAir(GameTestHelper h) {
        for(var type:List.of(EntityType.VEX,EntityType.PHANTOM)) {
            var player=player(h);var mob=h.spawnWithNoFreeWill(type,new BlockPos(8,5,2));
            var data=CombatData.current;var brains=new HashMap<>(data.mobs());
            // The installed spatial example deliberately overrides Vex with the old synchronous template.
            // Both production flyers share the same new mapping; use its unoverridden Phantom definition.
            brains.put(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),data.mobs().get("minecraft:phantom"));
            var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),mob);
            try {
                h.assertTrue(battle.movementMode(mob).equals("flying"),"Flyer registration lost movement mode");
                var route=battle.pursuitRoute(mob,player);
                battle.ai(mob);if(battle.member(mob).airPlan!=null)launchTelegraphedDive(battle,mob,player,h);h.assertTrue(battle.motion!=null && battle.motion.route.samples().stream().anyMatch(p->p.y<mob.getY()),"Flying AI failed: "+type+" budget="+battle.budget.remaining()+" route="+route.cost()+" message="+battle.lastMessage);
                finishMovement(player);h.assertTrue(!mob.onGround(),"Flyer incorrectly grounded");
            }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void mobMultishotPaysEachArrowAndStopsOnBreak(GameTestHelper h){
        var mob=h.spawnWithNoFreeWill(EntityType.PIGLIN,new BlockPos(7,1,4));
        var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        try{
            for(var slot:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND))for(int durability:List.of(1,2,100)){
                var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
                bow.setDamageValue(bow.getMaxDamage()-durability);mob.setItemSlot(slot,bow);
                var snapshot=com.matuvent.mineturn.api.CombatProjectiles.prepareMobCrossbow(mob);
                var launched=snapshot.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES).getItems();
                h.assertTrue(launched.size()==Math.min(3,durability),"Broken mob crossbow launched extra lanes");
                h.assertTrue(durability<=3?mob.getItemBySlot(slot).isEmpty():bow.getDamageValue()==bow.getMaxDamage()-durability+3,"Wrong hand or per-arrow durability");
                h.assertTrue(!snapshot.isEmpty() && snapshot.getEnchantments().getLevel(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT))==1,"Last arrow lost weapon snapshot");
                mob.setItemSlot(slot,ItemStack.EMPTY);
            }
            var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING),3);
            var reference=bow.copy();mob.setItemSlot(EquipmentSlot.OFFHAND,bow);
            h.getLevel().random.setSeed(1907);for(int i=0;i<60;i++)com.matuvent.mineturn.api.CombatProjectiles.prepareMobCrossbow(mob);
            h.getLevel().random.setSeed(1907);for(int i=0;i<180;i++)reference.hurtAndBreak(1,mob,EquipmentSlot.OFFHAND);
            h.assertTrue(bow.getDamageValue()==reference.getDamageValue(),"Mob Unbreaking differs from native per-projectile requests");
        }finally{mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void mobCrossbowMissAndDeferredChargePayOnce(GameTestHelper h){
        for(var type:List.of(EntityType.PIGLIN,EntityType.PILLAGER)){
            var player=player(h);var mob=h.spawnWithNoFreeWill(type,new BlockPos(8,1,2));
            var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);mob.setItemSlot(EquipmentSlot.OFFHAND,bow);
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try{
                if(type==EntityType.PIGLIN){
                    var miss=new CombatData.Action("miss","mineturn:mob_crossbow",0,16,false,0,100,new com.google.gson.JsonObject(),new CombatData.Ranged(1200,0,0,0,"minecraft:arrow",0));
                    battle.execute(mob,player,"test:miss",miss,bow);
                    h.assertTrue(bow.getDamageValue()==3 && player.getHealth()==20,"Miss did not pay all launched arrows or dealt damage");
                }else{
                    battle.ai(mob);h.assertTrue(bow.getDamageValue()==0 && battle.member(mob).chargeTarget!=null,"Loading prematurely damaged bow");
                    battle.runScheduledBeforeNextTurn();
                    h.assertTrue(bow.getDamageValue()==3 && battle.member(mob).chargeTarget==null,"Deferred multishot did not pay exactly once");
                }
            }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void brokenMobCrossbowStillResolvesLastArrow(GameTestHelper h){
        var mob=h.spawnWithNoFreeWill(EntityType.PIGLIN,new BlockPos(8,1,2));var victim=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(5,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);bow.setDamageValue(bow.getMaxDamage()-1);mob.setItemSlot(EquipmentSlot.OFFHAND,bow);
        try{
            var action=CombatData.current.actions().get("mineturn:mob_crossbow");
            var context=new CombatEffects.Context(mob,victim,bow,action,0,null);
            BattleManager.authorized(()->CombatEffects.get(action.effect()).execute(context));
            h.assertTrue(mob.getOffhandItem().isEmpty() && victim.getHealth()==6,"Breaking bow lost final arrow damage");
        }finally{mob.discard();victim.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void crossbowMobWeaponAndCooldown(GameTestHelper h) {
        for(var type:List.of(EntityType.PILLAGER,EntityType.PIGLIN)) {
            var player=player(h);var mob=h.spawnWithNoFreeWill(type,new BlockPos(9,1,2));
            var weapon=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
            weapon.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.QUICK_CHARGE),3);
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,weapon);
            var battle=new BattleSession(player,mob,CombatData.current,mob);
            try {
                battle.ai(mob);
                if(mob instanceof net.minecraft.world.entity.monster.Pillager){
                    h.assertTrue(weapon.getDamageValue()==0 && battle.member(mob).chargeUntil>0 && battle.member(mob).chargeUntil<100,"Offhand Quick Charge did not shorten deferred load");
                    battle.runScheduledBeforeNextTurn();
                }
                h.assertTrue(!battle.budget.canAct() && battle.motion==null && weapon.getDamageValue()==1,"Crossbow mob failed to shoot with offhand weapon");
                String cooldown=mob instanceof net.minecraft.world.entity.monster.Pillager?"mineturn:pillager_charge":"mineturn:mob_crossbow";
                h.assertTrue(battle.remainingCooldown(mob,cooldown)>0 && battle.remainingCooldown(mob,cooldown)<100,"Offhand Quick Charge ignored");
                var victim=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(5,1,5));
                try {
                    var action=CombatData.current.actions().get("mineturn:mob_crossbow");
                    var context=new CombatEffects.Context(mob,victim,weapon,action,0,null);
                    BattleManager.authorized(()->CombatEffects.get(action.effect()).execute(context));
                    h.assertTrue(victim.getHealth()==6 && victim.getDeltaMovement().lengthSqr()==0,"Mob crossbow incorrectly used player critical damage or knockback");
                }finally{victim.discard();}
            }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void neutralSpeciesJoinOnlyOnActualCombat(GameTestHelper h) {
        var player=player(h);
        try {
            for(var type:List.of(EntityType.WOLF,EntityType.POLAR_BEAR)) {
                var mob=h.spawnWithNoFreeWill(type,new BlockPos(7,1,2));
                var enemy=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(10,1,2));
                var battle=new BattleSession(player,enemy,CombatData.current,player);
                try {
                    h.assertTrue(CombatData.current.mobs().containsKey(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),"Neutral species not registered");
                    h.assertTrue(!battle.canJoin(mob),"Neutral animal automatically joined unrelated battle");
                    battle.close("test");
                    mob.hurt(player.damageSources().playerAttack(player),1);
                    h.assertTrue(BattleManager.locked(player) && BattleManager.ACTIVE.get(player.getUUID()).members.containsKey(mob.getUUID()),"Actual player attack failed to start neutral species battle");
                }finally{var active=BattleManager.ACTIVE.get(player.getUUID());if(active!=null)active.close("test");battle.close("test");mob.discard();enemy.discard();}
            }
        }finally{cleanup(player);}h.succeed();
    }
    @GameTest(template="empty")
    public static void huskNativeHungerRequiresUnarmedSuccessfulHit(GameTestHelper h) {
        var old=h.getLevel().getDifficulty();h.getLevel().getServer().setDifficulty(net.minecraft.world.Difficulty.HARD,true);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(2,1,2));
        var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(4,1,2));
        try {
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(2);
            var action=CombatData.current.actions().get("mineturn:species_melee");
            var context=new CombatEffects.Context(mob,target,ItemStack.EMPTY,action,0,null);
            var effect=CombatEffects.get(action.effect());
            int expected=140*(int)h.getLevel().getCurrentDifficultyAt(mob.blockPosition()).getEffectiveDifficulty();
            h.assertTrue(expected>0 && effect.validate(context)==null,"Husk native adapter unavailable");
            BattleManager.authorized(()->effect.execute(context));
            var hunger=target.getEffect(net.minecraft.world.effect.MobEffects.HUNGER);
            h.assertTrue(hunger!=null && hunger.getDuration()==expected && target.getHealth()==8,"Husk hunger did not follow native local difficulty");
            target.removeAllEffects();target.setInvulnerable(true);
            BattleManager.authorized(()->effect.execute(context));
            h.assertTrue(!target.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER),"Rejected hit applied hunger");
            target.setInvulnerable(false);mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(net.minecraft.world.item.Items.STICK));
            BattleManager.authorized(()->effect.execute(context));
            h.assertTrue(!target.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER) && target.getDeltaMovement().lengthSqr()==0,"Armed husk applied hunger or knockback");
        }finally{mob.discard();target.discard();h.getLevel().getServer().setDifficulty(old,true);}h.succeed();
    }
    @GameTest(template="empty")
    public static void heavySpeciesNativeDamageWithoutLaunch(GameTestHelper h) {
        for(var type:List.of(EntityType.IRON_GOLEM,EntityType.HOGLIN,EntityType.RAVAGER)) {
            var mob=h.spawnWithNoFreeWill(type,new BlockPos(2,1,2));
            var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(5,1,2));
            try {
                mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(6);
                target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
                var action=CombatData.current.actions().get("mineturn:species_melee");
                var context=new CombatEffects.Context(mob,target,ItemStack.EMPTY,action,0,null);
                var effect=CombatEffects.get(action.effect());
                h.assertTrue(effect.validate(context)==null && CombatData.current.mobs().containsKey(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),"Heavy species registration missing");
                Vec3 start=target.position();
                for(int i=0;i<8;i++) {
                    target.setHealth(100);BattleManager.authorized(()->effect.execute(context));
                    float damage=100-target.getHealth();
                    h.assertTrue(type==EntityType.RAVAGER?damage==6:damage>=3 && damage<=8,"Native heavy attack ignored attribute/random range");
                    h.assertTrue(target.position().equals(start) && target.getDeltaMovement().lengthSqr()==0,"Heavy attack launched victim");
                }
                if(mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin hoglin) {
                    hoglin.setBaby(true);hoglin.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(2);
                    target.setHealth(100);BattleManager.authorized(()->effect.execute(context));
                    h.assertTrue(target.getHealth()==98,"Baby hoglin did not use native unrandomized damage");
                }
            }finally{mob.discard();target.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeSpeciesMeleeDifficultyEffectsAndImmunity(GameTestHelper h) {
        var previous=h.getLevel().getDifficulty();
        try {
            for(var difficulty:List.of(net.minecraft.world.Difficulty.EASY,net.minecraft.world.Difficulty.NORMAL,net.minecraft.world.Difficulty.HARD)) {
                h.getLevel().getServer().setDifficulty(difficulty,true);
                for(var type:List.of(EntityType.SPIDER,EntityType.CAVE_SPIDER,EntityType.WITHER_SKELETON)) {
                    var mob=h.spawnWithNoFreeWill(type,new BlockPos(2,1,2));
                    var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(4,1,2));
                    try {
                        h.assertTrue(CombatData.current.mobs().containsKey(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),"Species registration missing");
                        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(3);
                        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK).setBaseValue(3);
                        var action=CombatData.current.actions().get("mineturn:species_melee");
                        var context=new CombatEffects.Context(mob,target,ItemStack.EMPTY,action,0,null);
                        var effect=CombatEffects.get(action.effect());
                        h.assertTrue(effect.validate(context)==null,"Species rejected");
                        BattleManager.authorized(()->effect.execute(context));
                        h.assertTrue(target.getHealth()==7 && target.getDeltaMovement().lengthSqr()==0,"Native damage attribute or no-knockback failed");
                        int poison=type==EntityType.CAVE_SPIDER?(difficulty==net.minecraft.world.Difficulty.HARD?300:difficulty==net.minecraft.world.Difficulty.NORMAL?140:0):0;
                        var actual=target.getEffect(net.minecraft.world.effect.MobEffects.POISON);
                        h.assertTrue(poison==0?actual==null:actual!=null && actual.getDuration()==poison,"Cave spider difficulty effect mismatch");
                        var wither=target.getEffect(net.minecraft.world.effect.MobEffects.WITHER);
                        h.assertTrue(type==EntityType.WITHER_SKELETON?wither!=null && wither.getDuration()==200:wither==null,"Wither skeleton effect mismatch");
                        target.removeAllEffects();target.setInvulnerable(true);
                        BattleManager.authorized(()->effect.execute(context));
                        h.assertTrue(target.getActiveEffects().isEmpty() && target.getHealth()==7,"Rejected damage still applied species effect");
                    }finally{mob.discard();target.discard();}
                }
            }
        }finally{h.getLevel().getServer().setDifficulty(previous,true);}h.succeed();
    }
    @GameTest(template="empty")
    public static void attacksFaceTargetBeforeRangedResult(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(7,3,2));
        player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.BOW));
        player.getInventory().setItem(10,new ItemStack(net.minecraft.world.item.Items.ARROW,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position();player.setYRot(90);player.setXRot(40);
            battle.use(player,0,"mineturn:shoot",mob.getId());
            Vec3 direction=mob.getEyePosition().subtract(player.getEyePosition()).normalize();
            h.assertTrue(player.getLookAngle().dot(direction)>0.999 && player.getYHeadRot()==player.getYRot()
                    && player.yBodyRot==player.getYRot() && player.position().equals(start),"Ranged aiming did not face elevated target or moved shooter");
            battle.finishShot(false);
            h.assertTrue(player.getLookAngle().dot(direction)>0.999,"Miss lost facing direction");
            var action=new CombatData.Action("test","mineturn:damage",0,16,false,0,0,new com.google.gson.JsonObject());
            mob.setYRot(90);battle.execute(mob,player,"test:facing",action,ItemStack.EMPTY);
            h.assertTrue(mob.getLookAngle().dot(player.getEyePosition().subtract(mob.getEyePosition()).normalize())>0.999
                    && mob.yBodyRot==mob.getYRot() && mob.getYHeadRot()==mob.getYRot(),"Mob attack did not face target");
            float yaw=player.getYRot(),pitch=player.getXRot();
            var self=new CombatData.Action("self","mineturn:heal",0,1,true,0,0,new com.google.gson.JsonObject());
            battle.execute(player,player,"test:self",self,ItemStack.EMPTY);
            h.assertTrue(player.getYRot()==yaw && player.getXRot()==pitch,"Self action changed facing");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void skeletonNativeArrowsKeepVariantEffects(GameTestHelper h) {
        for(var type:List.of(EntityType.SKELETON,EntityType.STRAY,EntityType.BOGGED)) {
            var mob=h.spawnWithNoFreeWill(type,new BlockPos(2,1,2));
            var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(6,1,2));
            try {
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new ItemStack(net.minecraft.world.item.Items.BOW));
                var action=CombatData.current.actions().get("mineturn:skeleton_shoot");
                var context=new CombatEffects.Context(mob,target,ItemStack.EMPTY,action,0,null);
                var effect=CombatEffects.get(action.effect());
                h.assertTrue(effect.validate(context)==null,"Offhand bow not accepted");
                float before=target.getHealth();BattleManager.authorized(()->effect.execute(context));
                h.assertTrue(target.getHealth()<before && target.getDeltaMovement().lengthSqr()==0,"Native mob arrow failed damage or caused knockback");
                h.assertTrue(target.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)==(type==EntityType.STRAY),"Stray arrow effect mismatch");
                h.assertTrue(target.hasEffect(net.minecraft.world.effect.MobEffects.POISON)==(type==EntityType.BOGGED),"Bogged arrow effect mismatch");
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,ItemStack.EMPTY);
                h.assertTrue(effect.validate(context)!=null,"Unarmed skeleton could shoot");
            }finally{mob.discard();target.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void skeletonFunctionShootsAndRespectsRange(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.SKELETON,new BlockPos(9,1,2));
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(net.minecraft.world.item.Items.BOW));
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try {
            Vec3 start=mob.position();battle.ai(mob);
            h.assertTrue(!battle.budget.canAct() && battle.motion==null && mob.position().equals(start)
                    && !battle.ready(mob,"mineturn:skeleton_shoot"),"Skeleton function failed to shoot or reserve cooldown without inventory arrows");
            var action=CombatData.current.actions().get("mineturn:skeleton_shoot");
            var context=new CombatEffects.Context(mob,player,mob.getMainHandItem(),action,0,null);
            mob.setPos(player.position().add(20,0,0));
            h.assertTrue(CombatEffects.get(action.effect()).validate(context)!=null,"Skeleton shot beyond range");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void vindicatorClimbsTowardHigherNearbyPlayer(GameTestHelper h) {
        var player=player(h);
        for(int x=6;x<=9;x++)for(int z=2;z<=8;z++)for(int y=1;y<=4;y++)h.setBlock(x,y,z,Blocks.STONE);
        for(int z=4;z<=7;z++)for(int y=1;y<=z-3;y++)h.setBlock(5,y,z,Blocks.STONE);
        var top=h.absolutePos(new BlockPos(6,5,2));player.setPos(top.getX()+0.5,top.getY(),top.getZ()+0.5);
        var mob=h.spawnWithNoFreeWill(EntityType.VINDICATOR,new BlockPos(5,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try {
            double base=mob.getY();battle.budget=new TurnBudget(20);battle.ai(mob);
            h.assertTrue(battle.motion!=null,"Vindicator stopped under higher player instead of seeking stairs");
            h.assertTrue(battle.motion.route.samples().stream().anyMatch(p->p.y>base+1),"Vindicator did not route up successive full-block steps");
            finishMovement(player);
            h.assertTrue(mob.getY()>base+1,"Vindicator did not execute ascent");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void mobClimbsFullBlockDuringPursuit(GameTestHelper h) {
        for(var type:List.of(EntityType.ZOMBIE,EntityType.VINDICATOR,EntityType.SILVERFISH)) {
        var player=player(h);
        for(int x=4;x<=10;x++)for(int z=0;z<=5;z++)h.setBlock(x,1,z,Blocks.STONE);
        var top=h.absolutePos(new BlockPos(8,2,2));player.setPos(top.getX()+0.5,top.getY(),top.getZ()+0.5);
        var mob=h.spawnWithNoFreeWill(type,new BlockPos(2,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try {
            double base=mob.getY();battle.budget=new TurnBudget(5);battle.ai(mob);
            h.assertTrue(battle.motion!=null,"Zombie did not start pursuit toward one-block platform");
            h.assertTrue(battle.motion.route.destination().y==base+1,"Pursuit did not plan full-block ascent");
            finishMovement(player);
            h.assertTrue(mob.getY()==base+1 && mob.getX()>h.absolutePos(new BlockPos(4,1,2)).getX(),"Zombie failed to execute full-block ascent");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void vanillaFoodComponentsAndContainers(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var honey=new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE,2);
            honey.set(net.minecraft.core.component.DataComponents.FOOD,new net.minecraft.world.food.FoodProperties.Builder().nutrition(3).saturationModifier(0).build());
            player.getInventory().setItem(1,honey);player.getFoodData().setFoodLevel(10);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,200));
            battle.use(player,1,"mineturn:native_food",player.getId());
            h.assertTrue(honey.getCount()==1 && player.getFoodData().getFoodLevel()==13 && !player.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Honey did not use FOOD component, remove poison or consume exactly once");
            h.assertTrue(player.getInventory().contains(new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE)) && !battle.budget.canAct(),"Honey lost bottle or main action");
            battle.budget=new TurnBudget(4);
            player.getInventory().setItem(1,new ItemStack(net.minecraft.world.item.Items.MUSHROOM_STEW));
            battle.use(player,1,"mineturn:native_food",player.getId());
            h.assertTrue(player.getInventory().getItem(1).is(net.minecraft.world.item.Items.BOWL) && player.getFoodData().getFoodLevel()==19,"Stew nutrition or container incorrect");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void vanillaCatalogAndMobAttributes(GameTestHelper h) {
        for(var item:net.minecraft.core.registries.BuiltInRegistries.ITEM){
            var id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if(id.getNamespace().equals("minecraft"))h.assertTrue(CombatData.current.items().containsKey(id.toString()),"Missing vanilla item: "+id);
        }
        h.assertTrue(CombatData.current.items().get("minecraft:chorus_fruit").enabled() && CombatData.current.items().get("minecraft:chorus_fruit").actions().contains("mineturn:chorus_fruit"),"Missing controlled chorus food action");
        h.assertTrue(CombatData.current.mobs().containsKey("minecraft:silverfish"),"Missing baseline melee registration");
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(2,1,2));
        var target=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(3,1,2));
        try {
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(5);
            var action=CombatData.current.actions().get("mineturn:mob_melee");
            var context=new CombatEffects.Context(mob,target,ItemStack.EMPTY,action,0,null);
            float before=target.getHealth();
            BattleManager.authorized(()->CombatEffects.get(action.effect()).execute(context));
            h.assertTrue(target.getHealth()==before-5 && target.getDeltaMovement().lengthSqr()==0,"Mob ignored native attack attribute or caused knockback");
        }finally{mob.discard();target.discard();}h.succeed();
    }
    private static CombatData.Snapshot resourceGrant(com.matuvent.mineturn.api.CombatResources.Cost cost) {
        var data=CombatData.current;var base=data.grants().get("grant_example:chestplate_laser");
        var grant=new com.matuvent.mineturn.data.GrantedAction(base.action(),base.name(),base.icon(),base.order(),true,base.equipment(),base.effects(),base.conditions(),cost);
        return new CombatData.Snapshot(data.actions(),data.mobs(),data.items(),data.sources(),Map.of("grant_example:chestplate_laser",grant));
    }
    @GameTest(template="empty")
    public static void resourceCastingReservesOnceAndUsesAv(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));
        player.experienceLevel=1;
        var battle=new BattleSession(player,mob,resourceGrant(new com.matuvent.mineturn.api.CombatResources.Cost("mineturn:experience_levels",2)),player);
        var grant=ResourceLocation.parse("grant_example:chestplate_laser");var action=ResourceLocation.parse("grant_example:laser");
        try {
            h.assertTrue(!battle.offers(player).getFirst().unavailable().isEmpty(),"Insufficient resource did not disable offer");
            h.assertTrue(!com.matuvent.mineturn.api.CombatCasting.cast(player,grant,mob.getId()).accepted() && player.experienceLevel==1 && battle.budget.canAct(),"Insufficient resource spent action or levels");
            player.experienceLevel=5;
            battle.offers(player);battle.offers(player);
            h.assertTrue(player.experienceLevel==5,"Preview consumed resource");
            h.assertTrue(!com.matuvent.mineturn.api.CombatCasting.allowsOriginalCast(player),"Native cast bypass allowed during battle");
            BattleManager.authorized(()->h.assertTrue(com.matuvent.mineturn.api.CombatCasting.allowsOriginalCast(player),"Authorized effect could not call original spell"));
            h.assertTrue(com.matuvent.mineturn.api.CombatCasting.cast(player,grant,mob.getId()).accepted() && player.experienceLevel==3,"Adapter cast failed to pay resource");
            h.assertTrue(!com.matuvent.mineturn.api.CombatCasting.cast(player,grant,mob.getId()).accepted() && player.experienceLevel==3,"Duplicate cast charged resource twice");
            float health=mob.getHealth();battle.finishShot(false);
            h.assertTrue(mob.getHealth()==health && player.experienceLevel==3,"Miss refunded cost or hurt target");
            for(int i=0;i<5;i++)battle.tick();
            h.assertTrue(com.matuvent.mineturn.api.CombatCasting.remainingAv(player,action)==200,"Real ticks consumed spell cooldown");
            battle.clock.advance(50,battle::agility);
            h.assertTrue(com.matuvent.mineturn.api.CombatCasting.remainingAv(player,action)==150,"Cooldown did not follow AV clock");
            battle.close("test");
            h.assertTrue(com.matuvent.mineturn.api.CombatCasting.allowsOriginalCast(player) && !com.matuvent.mineturn.api.CombatCasting.usesBattleClock(player)
                    && !com.matuvent.mineturn.api.CombatCasting.cast(player,grant,mob.getId()).accepted(),"Exit left native casting locked or enabled free battle cast");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void resourceAtomicFailureDoesNotSpendTurn(GameTestHelper h) {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var id=ResourceLocation.parse("mineturn_test:resource_"+UUID.randomUUID().toString().replace("-",""));
        com.matuvent.mineturn.api.CombatResources.register(id,new com.matuvent.mineturn.api.CombatResources.Resource(){
            public String check(ServerPlayer p,double amount){return null;}
            public boolean trySpend(ServerPlayer p,double amount){calls.incrementAndGet();return false;}
        });
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));
        var battle=new BattleSession(player,mob,resourceGrant(new com.matuvent.mineturn.api.CombatResources.Cost(id.toString(),4)),player);
        try {
            battle.offers(player);h.assertTrue(calls.get()==0,"Resource provider spent during preview");
            var result=com.matuvent.mineturn.api.CombatCasting.cast(player,ResourceLocation.parse("grant_example:chestplate_laser"),mob.getId());
            h.assertTrue(!result.accepted() && calls.get()==1 && battle.budget.canAct() && battle.shot==null && battle.ready(player,"grant_example:laser"),"Atomic failure reserved turn, shot or cooldown");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void resourceCostJsonValidation(GameTestHelper h) {
        var json=JsonParser.parseString("{\"action\":\"grant_example:laser\",\"name\":\"魔法\",\"icon\":\"minecraft:beacon\",\"cost\":{\"resource\":\"mineturn:experience_levels\",\"amount\":2}}").getAsJsonObject();
        h.assertTrue(com.matuvent.mineturn.data.GrantedAction.parse(json).cost().amount()==2,"Resource JSON not parsed");
        for(double amount:new double[]{0,-1,1.5,Double.NaN,Double.POSITIVE_INFINITY,1000001}) {
            try{new com.matuvent.mineturn.api.CombatResources.Cost("mineturn:experience_levels",amount);throw new AssertionError("Invalid resource cost accepted: "+amount);}catch(IllegalArgumentException expected){
                h.assertTrue(expected.getMessage().equals(amount==1.5?"Experience level cost must be an integer":"Resource cost must be finite and within (0,1000000]"),"Resource cost rejected for wrong reason: "+expected.getMessage());
            }
        }
        try{new com.matuvent.mineturn.api.CombatResources.Cost("absent:mana",1);throw new AssertionError("Missing resource provider accepted");}catch(IllegalArgumentException expected){h.assertTrue(expected.getMessage().equals("Unknown combat resource: absent:mana"),"Missing resource rejected for wrong reason: "+expected.getMessage());}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void manhattanGroundBudgetAndSpatialRoutes(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position();battle.budget=new TurnBudget(4);
            h.assertTrue(Math.abs(TerrainPath.trace(player,new Vec3(-1,0,-1),p->true).cost()-2)<1e-6,"Negative ground diagonal did not cost two");
            try{battle.validateDestination(player,start.add(2.5,0,2.5));throw new AssertionError("Euclidean-short but Manhattan-long move accepted");}catch(IllegalArgumentException expected){}
            h.assertTrue(player.position().equals(start) && battle.budget.remaining()==4,"Rejected diagonal spent budget");
            battle.beginMoveTo(player,start.add(2,0,2));finishMovement(player);
            h.assertTrue(player.position().distanceToSqr(start.add(2,0,2))<1e-8 && battle.budget.remaining()<1e-6,"Diagonal did not use exact Manhattan budget");
            Vec3 offset=new Vec3(-1,1,-1);
            var spatial=SpatialPath.trace(mob,offset,false,p->true);
            h.assertTrue(Math.abs(spatial.cost()-3)<1e-6,"Three-axis flight did not cost three");
            h.assertTrue(SpatialRoutes.find(mob,mob.position().add(offset),2.9,"flying",()->p->true)==null,"3D search used Euclidean heuristic as budget");
            var exact=SpatialRoutes.find(mob,mob.position().add(offset),3,"flying",()->p->true);
            h.assertTrue(exact!=null && Math.abs(exact.cost()-3)<1e-6,"3D route rejected exact Manhattan budget");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void grantedLaserEquipmentActionAndCooldown(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try {
            String id="grant_example:chestplate_laser";
            h.assertTrue(battle.offers(player).isEmpty(),"Equipment grant shown without equipment");
            try{battle.useGrant(player,id,mob.getId());throw new AssertionError("Forged grant accepted");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.budget.canAct(),"Rejected grant spent main action");
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));
            h.assertTrue(battle.offers(player).size()==1 && battle.offers(player).getFirst().unavailable().isEmpty(),"Equipped grant unavailable");
            var packet=new com.matuvent.mineturn.network.BattleNetwork.Offers(battle.id,battle.revision,battle.offers(player));
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.Offers.CODEC.encode(buffer,packet);
                h.assertTrue(packet.equals(com.matuvent.mineturn.network.BattleNetwork.Offers.CODEC.decode(buffer)),"Granted action sync lost fields");
            }finally{buffer.release();}
            battle.useGrant(player,id,mob.getId());var shot=battle.shot;float health=mob.getHealth();
            h.assertTrue(!battle.budget.canAct() && shot!=null && !battle.ready(player,"grant_example:laser"),"Grant failed to reserve action/cooldown");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()<health && battle.shot==null,"Successful granted laser did not hit");
            float after=mob.getHealth();battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(mob.getHealth()==after,"Repeated granted token hit again");
            battle.budget=new TurnBudget(4);
            try{battle.useGrant(player,id,mob.getId());throw new AssertionError("Grant ignored AV cooldown");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.budget.canAct(),"Cooldown failure spent action");
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,ItemStack.EMPTY);
            h.assertTrue(battle.offers(player).isEmpty(),"Unequipped grant remained visible");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void grantedLaserRechecksConditionsAfterAim(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,CombatData.current,player);
        try {
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));
            battle.useGrant(player,"grant_example:chestplate_laser",mob.getId());var shot=battle.shot;float health=mob.getHealth();
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,ItemStack.EMPTY);
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==health && battle.shot==null && !battle.budget.canAct() && !battle.ready(player,"grant_example:laser"),"Revoked grant hit or refunded reservation");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void grantedActionConditionsAndValidation(GameTestHelper h) {
        var player=player(h);var flag=new java.util.concurrent.atomic.AtomicBoolean(false);
        var condition=ResourceLocation.parse("mineturn_test:condition_"+UUID.randomUUID().toString().replace("-",""));
        com.matuvent.mineturn.api.CombatConditions.register(condition,p->flag.get());
        try {
            var json=JsonParser.parseString("{\"action\":\"grant_example:laser\",\"name\":\"测试\",\"icon\":\"minecraft:beacon\",\"effects\":[\"minecraft:speed\"],\"conditions\":[\""+condition+"\"]}").getAsJsonObject();
            var grant=com.matuvent.mineturn.data.GrantedAction.parse(json);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,100));
            h.assertTrue(!grant.available(player),"Custom condition ignored");flag.set(true);
            h.assertTrue(grant.available(player),"Equipment-independent effect/custom grant unavailable");
            player.removeAllEffects();h.assertTrue(!grant.available(player),"Required effect ignored");
            try{CombatData.parse(Map.of(ResourceLocation.parse("mineturn_test:grants/bad"),json));throw new AssertionError("Missing action reference accepted");}catch(IllegalArgumentException expected){}
            json.addProperty("icon","absent:item");
            try{com.matuvent.mineturn.data.GrantedAction.parse(json);throw new AssertionError("Missing icon accepted");}catch(IllegalArgumentException expected){}
        }finally{cleanup(player);}h.succeed();
    }
    @GameTest(template="empty")
    public static void flyingPursuitRoutesAboveWall(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(8,2,2));
        for(int z=0;z<12;z++)for(int y=1;y<=4;y++)h.setBlock(5,y,z,Blocks.STONE);
        var battle=new BattleSession(player,mob,stateMachineMode("flying"),mob);
        try {
            Vec3 start=mob.position();battle.budget=new TurnBudget(12);battle.ai(mob);
            h.assertTrue(battle.motion!=null && mob.position().equals(start),"Flight detour did not start asynchronously");
            var route=battle.motion.route;
            h.assertTrue(route.samples().stream().anyMatch(p->p.y>=start.y+3) && route.destination().x<start.x-3,"Flying route did not pass above wall");
            h.assertTrue(route.cost()>start.distanceTo(route.destination())+1 && route.cost()<=12,"Flight detour did not charge space distance");
            finishMovement(player);h.assertTrue(mob.position().distanceToSqr(route.destination())<1e-8,"Flight detour missed endpoint");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void underwaterClickDetoursAndChargesRoute(GameTestHelper h) {
        var player=player(h);
        for(int x=1;x<=7;x++)for(int y=1;y<=5;y++)for(int z=1;z<=5;z++)h.setBlock(x,y,z,Blocks.WATER);
        for(int y=1;y<=5;y++)h.setBlock(4,y,2,Blocks.STONE);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position(),goal=start.add(4,0,0);
            try{battle.validateDestination(player,goal);throw new AssertionError("Water detour exceeded four-block budget");}catch(IllegalArgumentException expected){}
            battle.budget=new TurnBudget(8);var route=battle.validateDestination(player,goal);
            h.assertTrue(route.cost()>4 && route.samples().stream().anyMatch(p->Math.abs(p.z-start.z)>0.8) && player.position().equals(start),"Water preview did not detour or mutated player");
            battle.beginMoveTo(player,goal);finishMovement(player);
            h.assertTrue(player.position().distanceToSqr(goal)<1e-8 && Math.abs(battle.budget.remaining()-(8-route.cost()))<1e-6,"Water detour endpoint/budget incorrect");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void spatialSearchRespectsWaterAndControl(GameTestHelper h) {
        var player=player(h);
        for(int x=1;x<=9;x++)for(int y=1;y<=5;y++)for(int z=1;z<=5;z++)h.setBlock(x,y,z,Blocks.WATER);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        try {
            Vec3 start=mob.position(),goal=start.add(-5,0,0);
            for(int y=1;y<=5;y++)for(int z=1;z<=5;z++)h.setBlock(5,y,z,Blocks.STONE);
            h.assertTrue(SpatialRoutes.find(mob,goal,12,"swimming",()->p->true)==null,"Strict swimmer escaped water to bypass sealed wall");
            for(int y=1;y<=5;y++)h.setBlock(5,y,4,Blocks.WATER);
            var route=SpatialRoutes.find(mob,goal,12,"swimming",()->p->true);
            h.assertTrue(route!=null && route.samples().stream().anyMatch(p->Math.abs(p.z-start.z)>1),"Swimmer failed to find submerged opening");
            h.assertTrue(SpatialRoutes.find(mob,goal,12,"swimming",()->p->p.x>start.x-2)==null,"Spatial search bypassed control predicate");
            h.assertTrue(mob.position().equals(start),"Spatial search moved entity during preview");
        }finally{cleanup(player);mob.discard();}h.succeed();
    }
    private static CombatData.Snapshot pursuitFunction(String function) {
        var data=CombatData.current;var brains=new HashMap<>(data.mobs());
        brains.put("minecraft:husk",new CombatData.Brain(100,1.5,"",Map.of(),new CombatData.Functions(Map.of(
                "on_turn",ResourceLocation.parse("mineturn_test:"+function),
                "on_move_finished",ResourceLocation.parse("mineturn_test:async_finish")),new net.minecraft.nbt.CompoundTag()),"ground"));
        return new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources());
    }
    @GameTest(template="empty")
    public static void functionPursuitDetoursWithinBudget(GameTestHelper h) {
        for(double distance:List.of(3.0,8.0)) {
            var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(7,1,2));
            for(int y=1;y<=3;y++)h.setBlock(5,y,2,Blocks.STONE);
            var battle=new BattleSession(player,mob,pursuitFunction("async_toward"),mob);
            try {
                Vec3 start=mob.position();battle.budget=new TurnBudget(distance);var budget=battle.budget;battle.ai(mob);
                h.assertTrue(battle.motion!=null && battle.motion.functionMove && mob.position().equals(start),"Function pursuit did not start asynchronous route");
                var route=battle.motion.route;
                h.assertTrue(route.cost()<=distance+1e-6 && route.samples().stream().anyMatch(p->Math.abs(p.z-start.z)>0.8),"Function pursuit failed to detour within budget");
                h.assertTrue(Math.abs(budget.remaining()-(distance-route.cost()))<1e-6 && budget.canAct(),"Pursuit charged incorrect budget or main action");
                finishMovement(player);
                h.assertTrue(mob.position().distanceToSqr(route.destination())<1e-8 && mob.getTags().contains("mt_move_finished") && battle.actor==player,"Detour lost callback or turn completion");
            }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void functionPursuitKeepsLegacyAndRetreatRules(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(7,1,2));
        for(int y=1;y<=3;y++)h.setBlock(5,y,2,Blocks.STONE);
        var battle=new BattleSession(player,mob,pursuitFunction("sync_toward"),mob);
        try {
            Vec3 start=mob.position();battle.ai(mob);
            h.assertTrue(battle.motion==null && mob.getX()<start.x && mob.getX()>start.x-2 && mob.getZ()==start.z,"Legacy synchronous move gained detour or animation");
        }finally{battle.close("test");}
        battle=new BattleSession(player,mob,pursuitFunction("async_toward"),mob);
        try {
            battle.place(mob,player.position().add(1.5,0,0));Vec3 start=mob.position();double remaining=battle.budget.remaining();
            battle.ai(mob);
            h.assertTrue(battle.motion==null && mob.position().equals(start) && battle.budget.remaining()==remaining && battle.budget.canAct(),"Async pursuit bypassed retreat or spent resources on failure");
            h.assertTrue(!mob.getTags().contains("mt_move_finished"),"Rejected pursuit fired completion callback");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void animatedFunctionDefinitionAndCancellation(GameTestHelper h) {
        var parsed=CombatData.parse(Map.of(ResourceLocation.parse("mineturn_test:mobs/async"),JsonParser.parseString("""
                {"entity":"minecraft:husk","ai":{"on_turn":"mineturn:ai/animated_melee/turn",
                "on_move_finished":"mineturn_test:async_finish","parameters":{"action":"mineturn:melee"}}}
                """)));
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var data=CombatData.current;var brains=new HashMap<>(data.mobs());brains.put("minecraft:husk",parsed.mobs().get("minecraft:husk"));
        var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),mob);
        try {
            battle.ai(mob);h.assertTrue(battle.motion!=null,"Example async turn did not start movement");
            battle.tickMotion();Vec3 position=mob.position();battle.close("test");battle.tickMotion();
            h.assertTrue(battle.motion==null && mob.position().equals(position) && !BattleManager.locked(mob)
                    && !mob.getTags().contains("mt_move_finished"),"Cancellation moved entity or fired turn callback after exit");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void asynchronousFunctionMovementCallback(GameTestHelper h) {
        for(boolean blocked:List.of(false,true)) {
            var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
            var data=CombatData.current;var brains=new HashMap<>(data.mobs());
            brains.put("minecraft:husk",new CombatData.Brain(100,1.5,"",Map.of(),new CombatData.Functions(Map.of(
                    "on_turn",ResourceLocation.parse("mineturn_test:async_start"),
                    "on_move_finished",ResourceLocation.parse("mineturn_test:async_finish")),new net.minecraft.nbt.CompoundTag()),"ground"));
            var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),mob);
            try {
                Vec3 start=mob.position();battle.ai(mob);var budget=battle.budget;
                h.assertTrue(battle.motion!=null && mob.position().equals(start) && budget.canAct(),"Async function teleported or allowed action after start");
                h.assertTrue(!mob.getTags().contains("mt_move_finished"),"Completion callback ran before movement");
                if(blocked){h.setBlock(7,1,2,Blocks.STONE);h.setBlock(7,2,2,Blocks.STONE);}
                finishMovement(player);
                h.assertTrue(mob.getTags().contains("mt_move_finished") && !budget.canAct() && battle.actor==player,"Callback did not execute with remaining turn permissions");
                var scoreboard=h.getLevel().getScoreboard();
                h.assertTrue(scoreboard.getOrCreatePlayerScore(mob,scoreboard.getObjective("mt_test")).get()==(blocked?0:1),"Movement success flag incorrect");
                h.assertTrue(scoreboard.getOrCreatePlayerScore(mob,scoreboard.getObjective("mt_first")).get()==1,"Completion callback could not query remaining action");
                h.assertTrue(blocked ? mob.getX()>start.x-2 : mob.position().distanceToSqr(start.add(-2,0,0))<1e-8,"Async endpoint incorrect");
            }finally{battle.close("test");cleanup(player);mob.discard();h.setBlock(7,1,2,Blocks.AIR);h.setBlock(7,2,2,Blocks.AIR);}
        }h.succeed();
    }
    private static CombatData.Snapshot stateMachineMode(String mode) {
        var data=CombatData.current;var brains=new HashMap<>(data.mobs());var old=brains.get("minecraft:zombie");
        brains.put("minecraft:zombie",new CombatData.Brain(old.agility(),old.reach(),old.initial(),old.states(),null,mode));
        return new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources());
    }
    @GameTest(template="empty")
    public static void flyingAiAnimatesAndKeepsAltitude(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(8,5,2));
        var battle=new BattleSession(player,mob,stateMachineMode("flying"),mob);
        try {
            Vec3 start=mob.position();battle.ai(mob);
            h.assertTrue(battle.motion!=null && battle.motion.mode.equals("flying") && battle.motion.route.samples().size()>10,"Flying AI omitted sampled animation");
            Vec3 goal=battle.motion.route.destination();
            h.assertTrue(goal.y>player.getY()+0.5 && goal.y<start.y,"Flight did not follow vertical target offset");
            battle.tickMotion();
            h.assertTrue(mob.getY()<start.y && mob.getY()>goal.y && battle.clock.time()==0 && battle.actor==mob && battle.motion.falling==0,"Flight skipped animation or accumulated fall/AV");
            finishMovement(player);
            h.assertTrue(mob.position().distanceToSqr(goal)<1e-8 && !mob.onGround() && battle.actor==player,"Flight landed automatically or failed to end turn");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void flyingAiStopsAtNewObstacleWithoutFalling(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(8,4,2));
        var battle=new BattleSession(player,mob,stateMachineMode("flying"),mob);
        try {
            battle.place(player,player.position().add(0,3,0));h.setBlock(2,3,2,Blocks.STONE);
            Vec3 start=mob.position();battle.ai(mob);battle.tickMotion();
            h.setBlock(6,4,2,Blocks.STONE);h.setBlock(6,5,2,Blocks.STONE);
            finishMovement(player);
            h.assertTrue(mob.getX()>start.x-1.5 && mob.getY()==start.y && battle.actor==player && battle.motion==null,"Blocked flight fell or kept acting");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void swimmingAiAnimatesAndRechecksWater(GameTestHelper h) {
        for(String mode:List.of("swimming","ground")) {
            var player=player(h);
            for(int x=1;x<=10;x++)for(int y=1;y<=6;y++)for(int z=1;z<=4;z++)h.setBlock(x,y,z,Blocks.WATER);
            var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(8,2,2));
            var battle=new BattleSession(player,mob,stateMachineMode(mode),mob);
            try {
                battle.place(player,player.position().add(0,3,0));Vec3 start=mob.position();battle.ai(mob);
                h.assertTrue(battle.motion!=null && battle.motion.route.samples().size()>10,"Water AI teleported");
                battle.tickMotion();
                h.assertTrue(mob.getY()>start.y && mob.getX()<start.x && battle.actor==mob && battle.clock.time()==0,"Water pursuit did not animate in 3D");
                for(int y=1;y<=6;y++)h.setBlock(6,y,2,Blocks.AIR);
                finishMovement(player);
                h.assertTrue(mob.getX()>start.x-2 && mob.getY()>start.y && battle.actor==player && battle.motion==null && mob.fallDistance==0,"Water AI crossed drained section, sank, or retained turn");
            }finally{battle.close("test");cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void zombieDetoursAndWaitsForMovement(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(6,1,2));
        for(int y=1;y<=3;y++)h.setBlock(4,y,2,Blocks.STONE);
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try {
            Vec3 start=mob.position();battle.budget=new TurnBudget(8);battle.turnTicks=11;battle.tick();
            h.assertTrue(battle.motion!=null && battle.actor==mob && mob.position().equals(start),"Chasing skipped animation or advanced turn early");
            h.assertTrue(battle.motion.route.samples().stream().anyMatch(p->Math.abs(p.z-start.z)>0.8),"Zombie did not route around wall");
            Vec3 goal=battle.motion.route.destination();double remaining=battle.budget.remaining();
            battle.tick();
            h.assertTrue(battle.actor==mob && battle.clock.time()==0 && battle.budget.remaining()==remaining && player.getHealth()==20,"Moving zombie ran AI/AV/attack early");
            finishMovement(player);
            h.assertTrue(mob.position().distanceToSqr(goal)<1e-8 && battle.actor==player && player.getHealth()==20,"Chase completion failed to end exactly one turn");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void zombiePursuitUsesAffordablePrefixAndStops(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(7,1,2));
        for(int y=1;y<=3;y++)h.setBlock(5,y,2,Blocks.STONE);
        var battle=new BattleSession(player,mob,CombatData.current,mob);
        try {
            Vec3 start=mob.position();battle.budget=new TurnBudget(3);battle.ai(mob);
            h.assertTrue(battle.motion!=null && battle.motion.route.cost()<=3+1e-6 && battle.motion.route.cost()>1,"No affordable pursuit prefix");
            h.assertTrue(battle.motion.route.samples().stream().anyMatch(p->Math.abs(p.z-start.z)>0.8),"Affordable prefix merely ran into wall");
            var sample=battle.motion.route.samples().stream().filter(p->p.distanceTo(start)>0.8).findFirst().orElseThrow();
            h.getLevel().setBlockAndUpdate(BlockPos.containing(sample),Blocks.STONE.defaultBlockState());
            finishMovement(player);
            h.assertTrue(battle.motion==null && battle.actor==player && mob.position().distanceToSqr(start)<2,"Dynamic obstacle did not stop AI and release turn");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    public static void finishMovement(ServerPlayer player) {
        var battle=BattleManager.ACTIVE.get(player.getUUID());
        for(int i=0;battle!=null && battle.motion!=null && i<1000;i++)battle.tickMotion();
        if(battle!=null && battle.motion!=null)throw new AssertionError("Movement did not finish");
    }
    @GameTest(template="empty")
    public static void commandMovementAnimatesAndPreservesTurnResources(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var start=player.position();battle.idleTicks=599;
            battle.action(player,"move",0,"",-1,1,1);
            h.assertTrue(battle.motion!=null && player.position().equals(start),"Command still teleported instantly");
            h.assertTrue(Math.abs(battle.budget.remaining()-2)<1e-5 && battle.budget.mainActions()==1 && battle.budget.bonusActions()==1,"Command did not reserve Manhattan movement only");
            try{battle.action(player,"move",0,"",-1,1,0);throw new AssertionError("Repeated command queued during motion");}catch(IllegalArgumentException expected){}
            battle.tick();
            h.assertTrue(battle.actor==player && battle.idleTicks==0 && battle.clock.time()==0 && player.position().distanceTo(start)>0 && player.position().distanceTo(start)<1,"Animation did not pause AV/timeout or move gradually");
            finishMovement(player);
            h.assertTrue(player.position().distanceToSqr(start.add(1,0,1))<1e-8 && battle.budget.canAct(),"Command endpoint or action permission incorrect");
            battle.action(player,"move",0,"",-1,1,0);finishMovement(player);
            h.assertTrue(player.position().distanceToSqr(start.add(2,0,1))<1e-8 && Math.abs(battle.budget.remaining()-1)<1e-5,"Second command did not use remaining movement");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void commandMovementStopsForDynamicWallAndRejectsBlockedStart(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var start=player.position();
            for(double dx:new double[]{0,Double.NaN,5}){
                try{battle.action(player,"move",0,"",-1,dx,0);throw new AssertionError("Invalid movement accepted");}catch(IllegalArgumentException expected){}
            }
            h.assertTrue(battle.motion==null && Math.abs(battle.budget.remaining()-4)<1e-5,"Invalid command consumed distance");
            battle.action(player,"move",0,"",-1,3,0);battle.tickMotion();
            h.setBlock(4,1,2,Blocks.STONE);h.setBlock(4,2,2,Blocks.STONE);finishMovement(player);
            h.assertTrue(player.getX()<start.x+1.3 && battle.motion==null && Math.abs(battle.budget.remaining()-1)<1e-5,"Command passed new wall or double charged");
            battle.place(player,start);h.setBlock(3,1,2,Blocks.STONE);h.setBlock(3,2,2,Blocks.STONE);
            // Move up to the wall first; once touching it, a further command must reject without payment.
            battle.action(player,"move",0,"",-1,1,0);finishMovement(player);
            double before=battle.budget.remaining();
            try{battle.action(player,"move",0,"",-1,Math.min(0.1,before),0);throw new AssertionError("Blocked movement accepted");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.motion==null && battle.budget.remaining()==before,"Blocked start consumed movement");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void animatedMovementLocksActionsAndClock(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var start=player.position(); var goal=start.add(2,0,0);
            battle.beginMoveTo(player,goal);
            h.assertTrue(player.position().equals(start) && !battle.snapshot(player).canAct(),"Motion teleported or left actions enabled");
            try{battle.action(player,"end",0,"",-1,0,0);throw new AssertionError("Ended turn during movement");}catch(IllegalArgumentException expected){}
            try{battle.beginMoveTo(player,goal);throw new AssertionError("Queued repeated movement");}catch(IllegalArgumentException expected){}
            battle.tick();
            h.assertTrue(player.getX()>start.x && player.getX()<goal.x && battle.clock.time()==0,"Movement did not advance gradually with frozen AV");
            finishMovement(player);
            h.assertTrue(player.position().distanceToSqr(goal)<1e-8 && battle.snapshot(player).canAct() && Math.abs(battle.budget.remaining()-2)<1e-5,"Motion endpoint/budget/action incorrect");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void animatedMovementStopsForNewObstacle(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var start=player.position();battle.beginMoveTo(player,start.add(3,0,0));
            battle.tickMotion();h.setBlock(4,1,2,Blocks.STONE);h.setBlock(4,2,2,Blocks.STONE);
            finishMovement(player);
            h.assertTrue(player.getX()<start.x+1.3 && battle.motion==null && battle.budget.canAct(),"Moving player crossed new obstacle or stayed locked");
            h.assertTrue(Math.abs(battle.budget.remaining()-1)<1e-5,"Reserved movement charged more than once");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void routePreviewCodec(GameTestHelper h) {
        var points = new ArrayList<Vec3>();
        for (int i = 0; i < 256; i++) points.add(new Vec3(i / 10.0, i % 3, i % 7));
        var packet = new com.matuvent.mineturn.network.BattleNetwork.Preview(UUID.randomUUID(), 12, 7,
                points.getLast(), true, "绕行路径", points);
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            com.matuvent.mineturn.network.BattleNetwork.Preview.CODEC.encode(buffer, packet);
            h.assertTrue(packet.equals(com.matuvent.mineturn.network.BattleNetwork.Preview.CODEC.decode(buffer)), "Route preview lost points or request identity");
            h.assertTrue(!buffer.isReadable(), "Route preview left unread bytes");
            buffer.clear();
            var motion=new com.matuvent.mineturn.network.BattleNetwork.Motion(packet.battle(),27,new Vec3(3.5,7,8.5),true);
            com.matuvent.mineturn.network.BattleNetwork.Motion.CODEC.encode(buffer,motion);
            h.assertTrue(motion.equals(com.matuvent.mineturn.network.BattleNetwork.Motion.CODEC.decode(buffer)),"Motion codec lost position or activity");
        } finally { buffer.release(); }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void flyingSweepsAndBudget(GameTestHelper h) {
        var player = player(h);
        var mob = h.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(8, 3, 2));
        BattleSession battle = new BattleSession(player, mob, definitions("flying"), mob);
        try {
            Vec3 before = mob.position();
            var path = battle.path(mob, new Vec3(0, 2, 0), false);
            h.assertTrue(Math.abs(path.cost()-2) < 1e-6 && path.landings().isEmpty(), "Vertical flight cost/fall incorrect");
            battle.commitMovement(mob, path);
            h.assertTrue(mob.getY() == before.y + 2 && !mob.onGround(), "Flight did not retain altitude");
            h.assertTrue(Math.abs(battle.budget.remaining()-2) < 1e-5, "Flight did not spend movement");
            h.setBlock(8, 7, 2, Blocks.STONE);
            var blocked = battle.path(mob, new Vec3(0, 2, 0), true);
            h.assertTrue(blocked.cost() < 0.2, "Flight passed through a ceiling");
            battle.place(mob, player.position().add(1, 0, 0));
            var escape = battle.path(mob, new Vec3(0, 4, 0), false);
            h.assertTrue(escape.cost() == 0, "Flight bypassed close-control retreat requirement");
            var retreat = battle.path(mob, new Vec3(0, 4, 0), true);
            h.assertTrue(retreat.cost() > 0, "Retreat did not unlock vertical movement");
        } finally { battle.close("test"); cleanup(player); mob.discard(); }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void swimmingCannotCrossWaterBoundary(GameTestHelper h) {
        for (int x=4;x<=9;x++) for(int y=1;y<=6;y++) for(int z=1;z<=5;z++) h.setBlock(x,y,z,Blocks.WATER);
        var mob = h.spawnWithNoFreeWill(EntityType.GUARDIAN, new BlockPos(6,2,3));
        try {
            var diagonal = SpatialPath.trace(mob, new Vec3(1,1,0), true, p -> true);
            h.assertTrue(Math.abs(diagonal.cost()-2) < 1e-6, "Swimming did not use 3D distance");
            h.assertTrue(SpatialPath.trace(mob, new Vec3(0,7,0), true, p -> true).cost() < 5, "Swimmer left water");
            h.assertTrue(SpatialPath.trace(mob, new Vec3(1,0,0), false, p -> true).cost() == 0, "Flight crossed water");
            h.setBlock(7,2,3,Blocks.STONE);
            h.assertTrue(SpatialPath.trace(mob, new Vec3(3,0,0), true, p -> true).cost() < 1, "Swimming crossed a solid block");
        } finally { mob.discard(); }
        h.succeed();
    }
    @GameTest(template="empty", timeoutTicks=100)
    public static void prepaidSkillScopeAndCancellation(GameTestHelper h) {
        var player = player(h);
        h.runAfterDelay(70, () -> {
        var mob = h.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3,1,2));
        BattleSession battle = new BattleSession(player, mob, definitions("ground"), mob);
        var action = new CombatData.Action("test", "mineturn:burst", 4, 8, false, 0, 150,
                JsonParser.parseString("{\"radius\":3,\"delay_av\":25}").getAsJsonObject());
        try {
            var preview = battle.effectContext(mob, player, ItemStack.EMPTY, action, false);
            try { preview.battle().after(25, ignored -> {}); throw new AssertionError("Validation scheduled a free skill"); }
            catch (IllegalStateException expected) {}
            battle.budget.act(); battle.execute(mob,player,"test:burst",action,ItemStack.EMPTY);
            h.assertTrue(!battle.budget.canAct() && !battle.ready(mob,"test:burst") && player.getHealth()==20, "Cast failed to prepay or damaged too early");
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(battle.clock.time()==25 && player.getHealth()==16, "AV impact not resolved at exact time");
            battle.execute(mob,player,"test:burst",action,ItemStack.EMPTY);
            battle.remove(mob,"test removal");
            h.assertTrue(battle.scheduled.isEmpty(), "Caster removal retained prepaid skill");
        } finally { battle.close("test"); cleanup(player); mob.discard(); }
        h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void invalidMobilityAndSkillParameters(GameTestHelper h) {
        var files = new HashMap<ResourceLocation, com.google.gson.JsonElement>();
        files.put(ResourceLocation.parse("test:mobs/husk"), JsonParser.parseString("{\"entity\":\"minecraft:husk\",\"movement_mode\":\"teleport\",\"ai\":{\"on_turn\":\"test:turn\"}}"));
        try { CombatData.parse(files); throw new AssertionError("Invalid mobility accepted"); } catch(IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("Unknown movement_mode teleport"),"Mobility rejected for wrong reason: "+expected.getMessage());
        }
        files.clear();
        files.put(ResourceLocation.parse("test:actions/burst"), JsonParser.parseString("{\"name\":\"bad\",\"effect\":\"mineturn:burst\",\"amount\":4,\"range\":4,\"parameters\":{\"radius\":-1}}"));
        try { CombatData.parse(files); throw new AssertionError("Invalid skill parameter accepted"); } catch(IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("Invalid action parameter radius"),"Skill rejected for wrong reason: "+expected.getMessage());
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void missingLegacyAiStateSkipsOnlyItsAction(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var data=definitions("ground");var brains=new HashMap<>(data.mobs());
        var state=new CombatData.State("approach",List.of(new CombatData.Transition("out_of_reach","missing")),List.of());
        brains.put("minecraft:pillager",new CombatData.Brain(100,1.5,"start",Map.of("start",state),null,"ground"));
        var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),player);
        try {
            Vec3 before=mob.position();
            battle.member(mob).state="missing";battle.ai(mob);
            h.assertTrue(battle.motion==null && battle.budget.canAct(),"Missing initial state spent an action");
            battle.member(mob).state="start";battle.ai(mob);
            h.assertTrue(battle.member(mob).state.equals("missing") && battle.motion==null && mob.position().equals(before)
                    && battle.budget.canAct() && BattleManager.ACTIVE.get(player.getUUID())==battle,"Missing transition target damaged battle state");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void functionCanMoveVerticallyOnlyOnTurn(GameTestHelper h) {
        var player=player(h); var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,3,2));
        var data=definitions("flying"); var brains=new HashMap<>(data.mobs());
        var function=ResourceLocation.parse("mineturn_test:spatial");
        brains.put("minecraft:husk",new CombatData.Brain(100,1.5,"",Map.of(),new CombatData.Functions(Map.of("on_turn",function),new net.minecraft.nbt.CompoundTag()),"flying"));
        var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),mob);
        try {
            Vec3 start=mob.position();
            FunctionAi.run(battle,new FunctionAi.Invocation(battle.member(mob),function,"on_turn","",null));
            h.assertTrue(mob.position().distanceToSqr(start.add(0,2,0))<1e-6,"move3d failed or moved after wait");
            h.assertTrue(Math.abs(battle.budget.remaining()-2)<1e-6,"move3d cost incorrect");
            FunctionAi.run(battle,new FunctionAi.Invocation(battle.member(mob),function,"scheduled","",null));
            h.assertTrue(mob.position().distanceToSqr(start.add(0,2,0))<1e-6,"Scheduled function got free flight");
        } finally {battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void customSkillContextExpires(GameTestHelper h) {
        var player=player(h); var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),mob);
        var saved=new java.util.concurrent.atomic.AtomicReference<CombatEffects.Context>();
        var effectId=ResourceLocation.fromNamespaceAndPath("mineturn_test","scope_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(effectId, context -> {
            saved.set(context);
            context.battle().after(25, delayed -> {
                h.assertTrue(delayed.battleTime()==25,"Custom skill used stale AV time");
                saved.set(delayed);
            });
        });
        var action=new CombatData.Action("custom",effectId.toString(),0,8,false,0,150,new com.google.gson.JsonObject());
        try {
            battle.budget.act();battle.execute(mob,player,"test:custom",action,ItemStack.EMPTY);
            try {saved.get().battle().after(10,c -> {});throw new AssertionError("Expired context scheduled skill");}catch(IllegalStateException expected){}
            battle.runScheduledBeforeNextTurn();
            try {saved.get().battle().hurt(player,4);throw new AssertionError("Expired delayed context damaged player");}catch(IllegalStateException expected){}
            battle.execute(mob,player,"test:custom",action,ItemStack.EMPTY);
            battle.remove(player,"target leaves");
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(saved.get().battleTime()==25,"Delayed skill executed after target left");
        } finally {battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    private static CombatData.Snapshot definitions(String mode) {
        var old = CombatData.current;
        var brains = new HashMap<>(old.mobs());
        // These fixtures explicitly add selected pillagers; others test nonparticipant protection.
        brains.remove("minecraft:pillager");
        var original = brains.get("minecraft:husk");
        brains.put("minecraft:husk", new CombatData.Brain(100, 1.5, original.initial(), original.states(), original.functions(), mode));
        return new CombatData.Snapshot(old.actions(),Map.copyOf(brains),old.items(),old.sources());
    }
    @GameTest(template="empty",timeoutTicks=110)
    public static void statusesWaitForAv(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,100));
        player.setRemainingFireTicks(100);player.setAirSupply(100);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(80,()->{
            try {
                h.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()==100 && player.getRemainingFireTicks()==100
                        && player.getAirSupply()==100 && player.getHealth()==20,"Real time advanced combat statuses");
                battle.next();
                h.assertTrue(battle.clock.time()==100 && player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()==80,"Effect duration did not advance 20 ticks per 100 AV");
                h.assertTrue(player.getRemainingFireTicks()==80 && player.getAirSupply()==180 && player.getHealth()<20,"Fire/air/damage not advanced by AV");
                battle.close("test");
                int before=player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration();
                ((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$tickEffects();
                h.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()==before-1,"Effect ticking did not resume after exit");
            }finally{battle.close("test");cleanup(player);mob.discard();}
            h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void statusHiddenEffectAndInfiniteClock(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));mob.tickCount=0;
        var speed=net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED;
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(speed,40,0));
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(speed,10,1));
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER,-1,0));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.next();
            h.assertTrue(mob.getEffect(speed).getAmplifier()==0 && mob.getEffect(speed).getDuration()==20,"Native hidden effect did not resume");
            h.assertTrue(mob.getHealth()==20,"Infinite effect used frozen entity tick count");
            battle.next();battle.next();
            h.assertTrue(!mob.hasEffect(speed) && mob.getHealth()==19 && mob.getEffect(net.minecraft.world.effect.MobEffects.WITHER).isInfiniteDuration(),"Infinite periodic effect/expiry incorrect");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void statusDeathPreemptsActionAndTimer(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER,40,5));mob.setHealth(1);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        battle.next();
        try {h.assertTrue(battle.closed && !mob.isAlive() && battle.clock.time()==5 && battle.scheduled.isEmpty(),"Fatal status did not preempt next turn and timer");}
        finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void oxygenUsesNativeBreathingRules(GameTestHelper h) {
        var player=player(h);
        for(int x=6;x<=9;x++)for(int y=1;y<=4;y++)for(int z=1;z<=4;z++)h.setBlock(x,y,z,Blocks.WATER);
        var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));mob.setAirSupply(0);
        float fullHealth=mob.getHealth();
        var data=definitions("swimming");var brains=new HashMap<>(data.mobs());brains.put("minecraft:pillager",brains.get("minecraft:husk"));
        var battle=new BattleSession(player,mob,new CombatData.Snapshot(data.actions(),Map.copyOf(brains),data.items(),data.sources()),player);
        var immune=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,3));immune.setAirSupply(0);battle.add(immune);
        try {
            battle.next();
            h.assertTrue(mob.getHealth()==fullHealth-2 && mob.getAirSupply()==0,"AV drowning did not follow native 20-tick interval: health="+mob.getHealth()+", air="+mob.getAirSupply()+", fluid="+mob.getEyeInFluidType());
            h.assertTrue(immune.getHealth()==20,"Native underwater breathing immunity was lost");
            mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WATER_BREATHING,60));
            battle.next();battle.next();battle.next();
            h.assertTrue(mob.getHealth()==fullHealth-2 && mob.getAirSupply()==0,"Water breathing did not protect in AV time");
            battle.place(mob,player.position().add(3,0,0));
            battle.next();battle.next();battle.next();
            h.assertTrue(mob.getAirSupply()==80,"Air recovery outside water was not 4 per logical tick");
        }finally{battle.close("test");cleanup(player);mob.discard();immune.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void statusFractionalTimeAndJoin(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var speed=net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED;
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(speed,40));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        battle.clock.advance(2.5,battle::agility);
        var extra=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(9,1,4));
        extra.addEffect(new net.minecraft.world.effect.MobEffectInstance(speed,40));battle.add(extra);
        try {
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(battle.clock.time()==25 && mob.getEffect(speed).getDuration()==35 && extra.getEffect(speed).getDuration()==36,"Fractional AV or join time lost status ticks");
        }finally{battle.close("test");cleanup(player);mob.discard();extra.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void rangedHitCostsAndReplay(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(9,1,2));
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(0);
        player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.BOW));
        player.getInventory().setItem(10,new ItemStack(net.minecraft.world.item.Items.ARROW,3));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,0,"mineturn:shoot",mob.getId());
            var shot=battle.shot;
            h.assertTrue(shot!=null && !battle.budget.canAct() && player.getInventory().getItem(10).getCount()==2,"Shot not prepaid");
            h.assertTrue(!battle.ready(player,"mineturn:shoot") && player.getInventory().getItem(0).getDamageValue()==1,"Shot cooldown/durability not paid");
            try{battle.action(player,"end",0,"",0,0,0);throw new AssertionError("Ended turn during aim");}catch(IllegalArgumentException expected){}
            battle.submitShot(player,UUID.randomUUID(),shot.windowCentreNanos());
            h.assertTrue(battle.shot==shot,"Forged token consumed valid attempt");
            float health=mob.getHealth();
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(battle.shot==null && mob.getHealth()==health-6,"Ranged hit failed outside melee range");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==health-6 && player.getInventory().getItem(10).getCount()==2,"Replayed shot applied twice");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void rangedMissTimeoutAndCancellation(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(9,1,2));
        player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.BOW));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            try{battle.use(player,0,"mineturn:shoot",mob.getId());throw new AssertionError("Shot without ammunition");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.budget.canAct() && battle.shot==null,"Failed preflight spent action");
            player.getInventory().setItem(10,new ItemStack(net.minecraft.world.item.Items.ARROW,4));
            battle.use(player,0,"mineturn:shoot",mob.getId());var attempt=battle.shot;float health=mob.getHealth();
            battle.submitShot(player,attempt.token,attempt.startNanos-1);
            h.assertTrue(battle.shot==null && mob.getHealth()==health && !battle.budget.canAct(),"Early input was not a spent miss");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot",mob.getId());attempt=battle.shot;
            battle.submitShot(player,attempt.token,attempt.startNanos+3_000_000_000L);
            h.assertTrue(battle.shot==null && mob.getHealth()==health,"Expired input hit target");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot",mob.getId());attempt=battle.shot;
            battle.expireShot(attempt.startNanos+3_000_000_000L);
            h.assertTrue(battle.shot==null && mob.getHealth()==health,"Timeout did not resolve without submission");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot",mob.getId());battle.remove(mob,"test target leaves");
            h.assertTrue(battle.shot==null && player.getInventory().getItem(10).isEmpty(),"Target exit retained shot/refunded ammo");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void rangedCurveAndPacketRoundtrip(GameTestHelper h) {
        var config=CombatData.current.actions().get("mineturn:shoot").ranged();
        h.assertTrue(config.width(1)>config.width(20) && config.width(10000)==config.minWidth(),"Distance curve incorrect");
        var packet=new com.matuvent.mineturn.network.BattleNetwork.Aim(UUID.randomUUID(),UUID.randomUUID(),true,2000,-1000,0.3,0.7);
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            com.matuvent.mineturn.network.BattleNetwork.Aim.CODEC.encode(buffer,packet);
            h.assertTrue(packet.equals(com.matuvent.mineturn.network.BattleNetwork.Aim.CODEC.decode(buffer)),"Aim codec roundtrip failed");
        }finally{buffer.release();}
        h.succeed();
    }
    private static ServerPlayer player(GameTestHelper h) {
        for(int x=0;x<12;x++) for(int z=0;z<12;z++) h.setBlock(x,0,z,Blocks.STONE);
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"mt-ext"),false);
        var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
        player.setGameMode(GameType.SURVIVAL); player.getFoodData().setFoodLevel(10);
        var pos=h.absolutePos(new BlockPos(2,1,2)); player.teleportTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);
        return player;
    }
    @GameTest(template="empty")
    public static void playerUnderwaterMovementAndSurface(GameTestHelper h) {
        var player=player(h);
        for(int x=1;x<=7;x++)for(int y=1;y<=4;y++)for(int z=1;z<=5;z++)h.setBlock(x,y,z,Blocks.WATER);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position();h.assertTrue(AquaticPath.trace(player,new Vec3(1,1,0),p2->true).destination().distanceToSqr(start.add(1,1,0))<1e-6,"Underwater ascent trace blocked");battle.moveTo(player,start.add(1,1,0));battle.stabilizePlayers();
            h.assertTrue(player.position().distanceToSqr(start.add(1,1,0))<1e-6,"Player swimming was rejected or pulled to bottom");
            h.assertTrue(Math.abs(battle.budget.remaining()-(4-2))<1e-6,"Player swimming did not use 3D budget");
            battle.place(player,start.add(0,4,0));battle.budget=new TurnBudget(4);
            try{battle.moveTo(player,player.position().add(0,1,0));throw new AssertionError("Swimmer flew above water");}catch(IllegalArgumentException expected){}
            h.setBlock(8,4,2,Blocks.STONE);battle.place(player,start.add(5,3,0));
            h.assertTrue(AquaticPath.inWater(player.level(),player.position()),"Surface not recognized as water");battle.moveTo(player,start.add(6,4,0));
            h.assertTrue(battle.movementMode(player).equals("ground"),"Player could not leave water onto supported bank");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void animatedSwimmingUsesSpaceDistanceAndNoFall(GameTestHelper h) {
        var player=player(h);
        for(int x=1;x<=7;x++)for(int y=1;y<=5;y++)for(int z=1;z<=5;z++)h.setBlock(x,y,z,Blocks.WATER);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position();battle.budget=new TurnBudget(10);BattleManager.authorized(()->player.setAirSupply(150));
            battle.beginMoveTo(player,start.add(1,2,0));
            h.assertTrue(player.position().equals(start),"Swimming teleported on submit");
            battle.tick();
            h.assertTrue(player.getY()>start.y && player.getY()<start.y+2 && battle.motion!=null,"Swimming did not advance gradually");
            h.assertTrue(!battle.snapshot(player).canAct() && !battle.snapshot(player).canMove(),"Swimming left actions enabled");
            try{battle.beginMoveTo(player,start);throw new AssertionError("Repeated swim accepted");}catch(IllegalArgumentException expected){}
            finishMovement(player);battle.stabilizePlayers();
            h.assertTrue(player.position().distanceToSqr(start.add(1,2,0))<1e-8,"Swimming was pulled down or missed cell center");
            battle.beginMoveTo(player,start.add(1,0,0));
            while(battle.motion!=null){h.assertTrue(battle.motion.falling==0,"Diving accumulated fall distance");battle.tickMotion();}
            h.assertTrue(Math.abs(battle.budget.remaining()-(10-3-2))<1e-6,"Swimming charged incorrect space distance");
            h.assertTrue(player.getHealth()==20 && player.fallDistance==0 && player.getAirSupply()==150 && battle.clock.time()==0,"Animation advanced AV/oxygen or caused diving damage");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void animatedSwimmingCrossesSupportedBank(GameTestHelper h) {
        var player=player(h);
        for(int x=1;x<=7;x++)for(int y=1;y<=4;y++)for(int z=1;z<=5;z++)h.setBlock(x,y,z,Blocks.WATER);
        h.setBlock(8,4,2,Blocks.STONE);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 surface=player.position().add(5,4,0);battle.place(player,surface.add(0,-1,0));battle.budget=new TurnBudget(6);
            try{battle.beginMoveTo(player,surface.add(0,1,0));throw new AssertionError("Swimming flew into unsupported air");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.motion==null && battle.budget.remaining()==6,"Rejected flight spent movement");
            battle.beginMoveTo(player,surface.add(1,0,0));battle.tickMotion();
            h.assertTrue(battle.motion!=null && player.getX()<surface.x+1,"Bank exit teleported");
            finishMovement(player);battle.stabilizePlayers();
            h.assertTrue(player.position().distanceToSqr(surface.add(1,0,0))<1e-8 && battle.movementMode(player).equals("ground"),"Bank exit failed");
            battle.beginMoveTo(player,surface);finishMovement(player);battle.stabilizePlayers();
            h.assertTrue(player.position().distanceToSqr(surface.add(0,-1,0))<1e-8 && battle.movementMode(player).equals("aquatic") && battle.budget.remaining()==4,"Entering water failed or charged wrong distance");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void animatedSwimmingRechecksWaterAndLava(GameTestHelper h) {
        var player=player(h);
        for(int x=1;x<=7;x++)for(int y=1;y<=5;y++)for(int z=1;z<=5;z++)h.setBlock(x,y,z,Blocks.WATER);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position().add(0,2,0);battle.place(player,start);battle.budget=new TurnBudget(8);
            battle.beginMoveTo(player,start.add(3,0,0));battle.tickMotion();
            for(int y=1;y<=5;y++)h.setBlock(3,y,2,Blocks.AIR);
            finishMovement(player);
            h.assertTrue(player.getX()<Math.floor(start.x)+1 && player.getY()==start.y,"Drained route allowed unsupported swimming");
            for(int y=1;y<=5;y++)h.setBlock(3,y,2,Blocks.WATER);
            battle.place(player,start);battle.beginMoveTo(player,start.add(3,0,0));
            for(int y=1;y<=5;y++)h.setBlock(4,y,2,Blocks.LAVA);
            finishMovement(player);
            h.assertTrue(player.getX()<start.x+1.3 && battle.budget.canAct() && battle.budget.remaining()==2,"Animation entered new lava or failed to unlock");
            battle.beginMoveTo(player,start);battle.close("test");
            h.assertTrue(battle.motion==null && !BattleManager.locked(player),"Exit retained swimming state");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void fullFoodPlayerTakesStatusDamage(GameTestHelper h) {
        var player=player(h);player.getFoodData().setFoodLevel(20);player.getFoodData().setSaturation(20);
        var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.setRemainingFireTicks(100);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(80,()->{
            try {
                for(int i=0;i<80;i++)player.doTick();
                h.assertTrue(player.getRemainingFireTicks()==100 && player.getHealth()==20,"Waiting advanced burning");
                battle.next();h.assertTrue(player.getHealth()==19,"Natural regeneration erased burning damage at full food");
                BattleManager.authorized(()->player.clearFire());player.setHealth(20);
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,100));
                battle.next();battle.next();
                h.assertTrue(player.getHealth()==19,"Full-food poison caused no net health loss");
                battle.close("test");
                int duration=player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration();
                h.runAfterDelay(10,()->{
                    try{for(int i=0;i<10;i++)player.doTick();h.assertTrue(!BattleManager.locked(player) && player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()<duration,"Effect did not resume on real ticks after battle exit");}
                    finally{cleanup(player);mob.discard();}
                    h.succeed();
                });
            }catch(Throwable error){battle.close("test");cleanup(player);mob.discard();throw error;}
        });
    }
    @GameTest(template="empty")
    public static void statusUnlockLeaseAndPacket(GameTestHelper h) {
        var locks=new com.matuvent.mineturn.network.StatusLocks();var id=UUID.randomUUID();
        locks.update(id,true,0);h.assertTrue(locks.locked(id,1),"Status lock missing");
        locks.update(id,false,2);h.assertTrue(!locks.locked(id,3),"Explicit exit did not release status lock");
        locks.update(id,true,4);h.assertTrue(!locks.locked(id,6_000_000_000L),"Lost exit left a permanent freeze");
        var packet=new com.matuvent.mineturn.network.BattleNetwork.StatusClock(7,id,false,123);
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{com.matuvent.mineturn.network.BattleNetwork.StatusClock.CODEC.encode(buffer,packet);
            h.assertTrue(packet.equals(com.matuvent.mineturn.network.BattleNetwork.StatusClock.CODEC.decode(buffer)),"Status clock packet lost burning duration or release");
        }finally{buffer.release();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void avHungerAndFireResistance(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.getFoodData().setFoodLevel(10);player.getFoodData().setSaturation(0);player.getFoodData().setExhaustion(0);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HUNGER,20,99));
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE,20));
        mob.setRemainingFireTicks(20);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.next();
            h.assertTrue(player.getFoodData().getFoodLevel()==8 && !player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER),"Hunger did not consume food on AV clock");
            h.assertTrue(mob.getHealth()==20 && mob.getRemainingFireTicks()==0 && !mob.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),"Fire resistance or burning duration incorrect");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void starvationPreservesNativeClock(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.getFoodData().setFoodLevel(0);player.getFoodData().setSaturation(0);
        var timer=(com.matuvent.mineturn.mixin.FoodClockAccess)player.getFoodData();
        timer.mineturn$setTimer(79);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(80,()->{
        try {
            for(int i=0;i<100;i++)player.doTick();
            h.assertTrue(timer.mineturn$getTimer()==79 && player.getHealth()==20,"Waiting advanced starvation");
            BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getHealth()==19 && timer.mineturn$getTimer()==0,"Starvation lost entry phase or damage");
            for(int i=0;i<79;i++)BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getHealth()==19,"Starvation damaged before 400 AV");
            battle.close("test");player.invulnerableTime=0;player.getFoodData().tick(player);
            h.assertTrue(player.getHealth()==18 && timer.mineturn$getTimer()==0,"Exit did not preserve starvation phase");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void starvationFoodAndDifficultyFloor(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var timer=(com.matuvent.mineturn.mixin.FoodClockAccess)player.getFoodData();
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(80,()->{
        try {
            timer.mineturn$setTimer(79);BattleStatus.tick(battle.member(player));
            h.assertTrue(timer.mineturn$getTimer()==0,"Eating did not reset starvation clock");
            player.getFoodData().setFoodLevel(0);player.getFoodData().setSaturation(0);
            var difficulty=player.level().getDifficulty();
            float health=difficulty==net.minecraft.world.Difficulty.NORMAL?1:10;
            player.setHealth(health);timer.mineturn$setTimer(79);BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getHealth()==(difficulty==net.minecraft.world.Difficulty.HARD?health-1:health),"Starvation ignored native difficulty floor");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void powderSnowClockImmunityAndThaw(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        h.setBlock(2,1,2,Blocks.POWDER_SNOW);h.setBlock(8,1,2,Blocks.POWDER_SNOW);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(80,()->{
        try {
            for(int i=0;i<160;i++)player.doTick();
            h.assertTrue(player.getTicksFrozen()==0 && player.getHealth()==20,"Waiting advanced freezing");
            battle.member(player).statusTicks=0;battle.member(mob).statusTicks=0;
            for(int i=0;i<160;i++){BattleStatus.tick(battle.member(player));BattleStatus.tick(battle.member(mob));}
            h.assertTrue(player.isFullyFrozen() && player.getHealth()==19,"Player freezing did not deal AV damage");
            h.assertTrue(mob.isFullyFrozen() && mob.getHealth()==23,"Locked mob missed powder snow detection");
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_BOOTS));
            int before=player.getTicksFrozen();BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getTicksFrozen()==before-2,"Leather equipment did not prevent freezing");
            for(int i=0;i<80;i++)BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getTicksFrozen()==0 && player.getHealth()==19,"Freeze immunity failed");
            h.setBlock(8,1,2,Blocks.AIR);int mobBefore=mob.getTicksFrozen();BattleStatus.tick(battle.member(mob));
            h.assertTrue(mob.getTicksFrozen()==mobBefore-2,"Leaving powder snow did not thaw");
            BattleManager.authorized(()->player.setTicksFrozen(20));h.setBlock(2,1,2,Blocks.AIR);
            battle.close("test");player.doTick();
            h.assertTrue(player.getTicksFrozen()<20,"Exit did not resume native thawing");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=140)
    public static void sunlightUsesAvAndShelter(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.ZOMBIE,new BlockPos(8,1,2));
        long oldTime=h.getLevel().getDayTime();h.setDayTime(6000);
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)h.setBlock(x,4,z,Blocks.STONE);
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(80,()->{
            try {
                var head=mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                h.assertTrue(head.getDamageValue()==0 && mob.getRemainingFireTicks()<=0,"Waiting consumed sunlight helmet durability or ignited mob");
                mob.getRandom().setSeed(0);
                for(int i=0;i<400;i++)BattleStatus.tick(battle.member(mob));
                h.assertTrue(head.getDamageValue()>0 && mob.getRemainingFireTicks()<=0,"AV sunlight did not wear protective helmet");
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,net.minecraft.world.item.ItemStack.EMPTY);
                for(int i=0;i<200 && mob.getRemainingFireTicks()<=0;i++)BattleStatus.tick(battle.member(mob));
                h.assertTrue(mob.getRemainingFireTicks()>0,"Exposed zombie did not ignite on AV");
                var shaded=h.absolutePos(new BlockPos(8,1,8));battle.place(mob,new Vec3(shaded.getX()+0.5,shaded.getY(),shaded.getZ()+0.5));
                h.assertTrue(!h.getLevel().canSeeSky(BlockPos.containing(mob.getEyePosition())),"Test shelter is not ready");
                BattleManager.authorized(mob::clearFire);
                for(int i=0;i<200;i++)BattleStatus.tick(battle.member(mob));
                h.assertTrue(mob.getRemainingFireTicks()<=0,"Roof did not block sunlight");
            }finally{battle.close("test");cleanup(player);mob.discard();h.getLevel().setDayTime(oldTime);}
            h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void sunlightHelmetBreakAndSpeciesRules(GameTestHelper h) {
        var skeleton=h.spawnWithNoFreeWill(EntityType.SKELETON,new BlockPos(3,1,3));
        var phantom=h.spawnWithNoFreeWill(EntityType.PHANTOM,new BlockPos(7,3,3));
        var husk=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(9,1,3));
        try {
            var helmet=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET);
            skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,helmet);
            helmet.setDamageValue(helmet.getMaxDamage()-1);skeleton.getRandom().setSeed(0);
            BattleSunlight.expose(skeleton);
            h.assertTrue(skeleton.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty() && skeleton.getRemainingFireTicks()<=0,"Helmet break failed or ignited on protected tick");
            BattleSunlight.expose(skeleton);
            h.assertTrue(skeleton.getRemainingFireTicks()==160,"Bare skeleton did not ignite for eight seconds worth of AV");
            skeleton.clearFire();skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));
            for(int i=0;i<20;i++)BattleSunlight.expose(skeleton);
            h.assertTrue(skeleton.getRemainingFireTicks()<=0,"Non-damageable head item did not protect skeleton");
            phantom.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            BattleSunlight.expose(phantom);h.assertTrue(phantom.getRemainingFireTicks()==160,"Phantom incorrectly acquired helmet protection");
            h.assertTrue(!((com.matuvent.mineturn.mixin.ZombieSunAccess)husk).mineturn$sunSensitive(),"Invoker ignored Husk sunlight override");
            for(int i=0;i<200;i++)BattleSunlight.tick(husk);
            h.assertTrue(husk.getRemainingFireTicks()<=0,"Husk incorrectly burned in sunlight");
        }finally{skeleton.discard();phantom.discard();husk.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void selectedWeaponAttributesAndEnchantments(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var wooden=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOODEN_SWORD);
        var iron=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        var ench=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        iron.enchant(ench.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS),5);
        iron.enchant(ench.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.FIRE_ASPECT),2);
        player.getInventory().setItem(0,wooden);player.getInventory().setItem(1,iron);player.getInventory().selected=0;
        ((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
        double original=player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,1,"mineturn:melee",mob.getId());
            h.assertTrue(Math.abs(mob.getHealth()-15)<0.001,"Iron sword + Sharpness V did not deal nine damage");
            h.assertTrue(mob.getRemainingFireTicks()>0,"Fire Aspect post-attack effect missing");
            h.assertTrue(iron.getDamageValue()==1 && wooden.getDamageValue()==0,"Wrong weapon durability consumed");
            h.assertTrue(player.getInventory().selected==0 && player.getMainHandItem()==wooden && player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)==original,"Weapon selection or attributes leaked");
            battle.budget=new TurnBudget(4);mob.clearFire();mob.setHealth(24);
            battle.use(player,0,"mineturn:melee",mob.getId());
            h.assertTrue(mob.getHealth()==20,"Immediate second turn used real-time attack cooldown");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void battlePotionAndMilkContainers(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var potion=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
        player.getInventory().setItem(1,potion);player.getInventory().setItem(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MILK_BUCKET));player.setHealth(10);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,1,"mineturn:drink",player.getId());
            h.assertTrue(player.getHealth()==14 && player.getInventory().getItem(1).is(net.minecraft.world.item.Items.GLASS_BOTTLE),"Potion effect/container incorrect");
            try{battle.use(player,2,"mineturn:drink",player.getId());throw new AssertionError("Drank twice in one turn");}catch(IllegalArgumentException|IllegalStateException expected){}
            battle.budget=new TurnBudget(4);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,200));
            battle.use(player,2,"mineturn:drink",player.getId());
            h.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && player.getInventory().getItem(2).is(net.minecraft.world.item.Items.BUCKET),"Milk cure/container incorrect");
            h.assertTrue(player.getInventory().selected==0,"Drinking changed hotbar selection");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void sweepOnlyHitsBattleEnemiesWithoutKnockback(GameTestHelper h) {
        var player=player(h);var ally=player(h);
        var target=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var nearby=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,3));
        var outsider=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,1));
        var far=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var sword=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        sword.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SWEEPING_EDGE),3);
        sword.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK),2);
        player.getInventory().setItem(0,sword);
        var battle=new BattleSession(player,target,definitions("ground"),player);
        battle.add(nearby);battle.add(far);battle.add(ally);
        Vec3 before=target.position(),nearBefore=nearby.position();
        try {
            battle.use(player,0,"mineturn:melee",target.getId());
            h.assertTrue(target.getHealth()==18,"Primary iron sword damage changed");
            h.assertTrue(Math.abs(nearby.getHealth()-18.5)<0.001,"Sweeping Edge III damage missing");
            h.assertTrue(outsider.getHealth()==24 && far.getHealth()==24 && ally.getHealth()==20,"Sweep hit outsider, distant enemy or ally");
            h.assertTrue(target.position().equals(before) && nearby.position().equals(nearBefore)
                    && target.getDeltaMovement().lengthSqr()==0 && nearby.getDeltaMovement().lengthSqr()==0,"Attack retained knockback motion");
            h.assertTrue(sword.getDamageValue()==1 && !battle.budget.canAct(),"Sweep spent durability or main action more than once");
        }finally{battle.close("test");cleanup(player);cleanup(ally);target.discard();nearby.discard();outsider.discard();far.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void sweepRequiresSuccessfulHitAndLineOfSight(GameTestHelper h) {
        var player=player(h);var target=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var nearby=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,3));
        player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        var battle=new BattleSession(player,target,definitions("ground"),player);battle.add(nearby);
        try {
            target.setInvulnerable(true);battle.use(player,0,"mineturn:melee",target.getId());
            h.assertTrue(nearby.getHealth()==24,"Sweep triggered after rejected primary damage");
            target.setInvulnerable(false);battle.budget=new TurnBudget(4);
            h.setBlock(3,1,3,Blocks.STONE);h.setBlock(3,2,3,Blocks.STONE);
            h.assertTrue(!player.hasLineOfSight(nearby),"Test enemy was not behind cover");
            battle.use(player,0,"mineturn:melee",target.getId());
            h.assertTrue(target.getHealth()==18 && nearby.getHealth()==24,"Sweep passed through wall");
        }finally{battle.close("test");cleanup(player);target.discard();nearby.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void enchantedRangedHitSnapshotAndInfinity(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW);
        var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER),5);
        bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.FLAME),1);
        bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY),1);
        bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.PUNCH),2);
        bow.setDamageValue(bow.getMaxDamage()-1);player.getInventory().setItem(0,bow);
        player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var before=mob.position();battle.use(player,0,"mineturn:shoot",mob.getId());var attempt=battle.shot;
            h.assertTrue(bow.isEmpty() && player.getInventory().getItem(10).getCount()==1,"Infinity consumed arrow or weapon did not break");
            battle.submitShot(player,attempt.token,attempt.windowCentreNanos());
            h.assertTrue(mob.getHealth()==10,"Power V lost from broken weapon snapshot: health="+mob.getHealth());
            h.assertTrue(mob.getRemainingFireTicks()==100,"Flame did not ignite for 500 AV");
            h.assertTrue(mob.position().equals(before) && mob.getDeltaMovement().lengthSqr()==0,"Punch caused knockback");
            float health=mob.getHealth();battle.submitShot(player,attempt.token,attempt.windowCentreNanos());
            h.assertTrue(mob.getHealth()==health,"Enchanted shot replayed");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void infinityMissAndCrossbowRestriction(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW);
        var infinity=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY);
        bow.enchant(infinity,1);player.getInventory().setItem(0,bow);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            try{battle.use(player,0,"mineturn:shoot",mob.getId());throw new AssertionError("Infinity allowed no arrows");}catch(IllegalArgumentException expected){}
            h.assertTrue(battle.budget.canAct(),"Missing arrow consumed action");
            player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW));
            battle.use(player,0,"mineturn:shoot",mob.getId());var attempt=battle.shot;
            battle.submitShot(player,attempt.token,attempt.startNanos);
            h.assertTrue(mob.getHealth()==24 && mob.getRemainingFireTicks()<=0 && bow.getDamageValue()==1
                    && player.getInventory().getItem(10).getCount()==1 && !battle.budget.canAct(),"Infinity miss costs incorrect");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            var crossbow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);crossbow.enchant(infinity,1);
            player.getInventory().setItem(0,crossbow);battle.use(player,0,"mineturn:shoot",mob.getId());
            h.assertTrue(player.getInventory().getItem(10).isEmpty(),"Crossbow incorrectly gained Infinity");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void crossbowMultishotLanesAndSingleCost(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var left=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,1));
        var right=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,3));
        var outsider=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,1));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
        player.getInventory().setItem(0,bow);player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW,2));
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(left);battle.add(right);
        try {
            battle.use(player,0,"mineturn:shoot",main.getId());var shot=battle.shot;
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==18 && left.getHealth()==18 && right.getHealth()==18,"Multishot lanes missed or stacked hits");
            h.assertTrue(outsider.getHealth()==24 && player.getInventory().getItem(10).getCount()==1 && bow.getDamageValue()==3 && !battle.budget.canAct(),"Multishot collateral or costs incorrect");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot",main.getId());shot=battle.shot;battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(main.getHealth()==18 && left.getHealth()==18 && right.getHealth()==18,"Miss still fired side lanes");
        }finally{battle.close("test");cleanup(player);main.discard();left.discard();right.discard();outsider.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void crossbowPiercingOrderLimitAndWall(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var near=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var far=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,2));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var piercing=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.PIERCING);
        bow.enchant(piercing,1);player.getInventory().setItem(0,bow);
        player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW,2));
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(far);battle.add(near);
        try {
            battle.use(player,0,"mineturn:shoot",main.getId());var shot=battle.shot;
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==18 && near.getHealth()==18 && far.getHealth()==24,"Piercing did not respect distance order or level limit");
            h.setBlock(9,1,2,Blocks.STONE);h.setBlock(9,2,2,Blocks.STONE);bow.enchant(piercing,4);
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot",main.getId());shot=battle.shot;battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==12 && near.getHealth()==12 && far.getHealth()==24,"Piercing passed through solid wall");
            h.assertTrue(near.getDeltaMovement().lengthSqr()==0,"Piercing generated knockback");
        }finally{battle.close("test");cleanup(player);main.discard();near.discard();far.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void quickChargeUsesAvCooldown(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.QUICK_CHARGE),3);
        player.getInventory().setItem(0,bow);player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            double start=battle.clock.time();battle.use(player,0,"mineturn:shoot",mob.getId());var shot=battle.shot;
            h.assertTrue(Math.abs(battle.member(player).cooldowns.get("mineturn:shoot")-start-40)<0.001,"Quick Charge III did not reduce 100 AV to 40 AV");
            h.assertTrue(shot.action.ranged().durationMs()==1200 && !battle.budget.canAct(),"Quick Charge changed timing check or granted extra action");
            battle.submitShot(player,shot.token,shot.startNanos);battle.budget=new TurnBudget(4);
            h.assertTrue(!battle.ready(player,"mineturn:shoot"),"Quick Charge removed cooldown entirely");
            battle.clock.advance(39,battle::agility);h.assertTrue(!battle.ready(player,"mineturn:shoot"),"Cooldown ended before 40 AV");
            battle.clock.advance(1,battle::agility);h.assertTrue(battle.ready(player,"mineturn:shoot"),"Cooldown did not end at 40 AV");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void tippedArrowSnapshotConsumptionAndAv(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW);
        bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY),1);
        player.getInventory().setItem(0,bow);
        var ammo=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW);
        ammo.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        player.getInventory().setItem(10,ammo);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,0,"mineturn:shoot_tipped",mob.getId());var shot=battle.shot;
            h.assertTrue(ammo.isEmpty(),"Infinity preserved tipped arrow");
            h.assertTrue(!battle.ready(player,"mineturn:shoot") && !battle.ready(player,"mineturn:shoot_spectral"),"Ammo switching bypassed cooldown");
            player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            var poison=mob.getEffect(net.minecraft.world.effect.MobEffects.POISON);
            int expected=net.minecraft.world.item.alchemy.Potions.POISON.value().getEffects().getFirst().getDuration()/8;
            h.assertTrue(poison!=null && poison.getDuration()==expected && mob.getHealth()==18,"Tipped arrow lost potion snapshot or native duration scaling");
            for(int i=0;i<20;i++)BattleStatus.tick(battle.member(mob));
            h.assertTrue(poison.getDuration()==expected-20 && mob.getHealth()<18,"Tipped poison did not tick or damage on AV clock");
            h.assertTrue(!mob.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING),"Replacement ammunition changed pending shot");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void spectralPiercingAndMissEffects(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var next=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.PIERCING),1);
        player.getInventory().setItem(0,bow);player.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW,2));
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(next);
        try {
            battle.use(player,0,"mineturn:shoot_spectral",main.getId());var shot=battle.shot;
            battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(!main.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING) && !next.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING),"Miss applied spectral effect");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot_spectral",main.getId());shot=battle.shot;
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getEffect(net.minecraft.world.effect.MobEffects.GLOWING).getDuration()==200
                    && next.getEffect(net.minecraft.world.effect.MobEffects.GLOWING).getDuration()==200,"Spectral piercing did not apply native glow");
            h.assertTrue(player.getInventory().getItem(10).isEmpty(),"Special arrows were not prepaid exactly once");
        }finally{battle.close("test");cleanup(player);main.discard();next.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void splashRadiusSnapshotAndMiss(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var near=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,3));
        var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,1));
        var far=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,2));
        var splash=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPLASH_POTION,2);
        splash.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        player.getInventory().setItem(0,splash);
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(near);battle.add(far);
        try {
            battle.use(player,0,"mineturn:throw_splash",main.getId());var shot=battle.shot;
            h.assertTrue(splash.getCount()==1 && !battle.budget.canAct(),"Throw did not prepay selected potion");
            splash.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            int duration=net.minecraft.world.item.alchemy.Potions.POISON.value().getEffects().getFirst().getDuration();
            h.assertTrue(main.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()==duration
                    && near.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()==Math.round(duration*0.75),"Splash snapshot or falloff incorrect");
            h.assertTrue(!outside.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && !far.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Splash escaped battle or radius");
            BattleManager.authorized(()->main.igniteForSeconds(5));
            battle.budget=new TurnBudget(4);battle.use(player,0,"mineturn:throw_splash",main.getId());shot=battle.shot;
            battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(splash.isEmpty() && main.getRemainingFireTicks()>0,"Miss did not consume potion or still applied water");
            for(int i=0;i<20;i++)BattleStatus.tick(battle.member(near));
            h.assertTrue(near.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration()==Math.round(duration*0.75)-20,"Splash duration did not use AV");
        }finally{battle.close("test");cleanup(player);main.discard();near.discard();outside.discard();far.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void selfSplashHealsBattleParticipantsOnly(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(3,1,2));
        var blocked=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(2,1,5));
        var outsider=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(2,1,3));
        h.setBlock(2,1,4,Blocks.STONE);h.setBlock(2,2,4,Blocks.STONE);
        player.setHealth(10);mob.setHealth(10);blocked.setHealth(10);outsider.setHealth(10);
        var splash=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPLASH_POTION);
        splash.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
        player.getInventory().setItem(0,splash);
        var battle=new BattleSession(player,mob,definitions("ground"),player);battle.add(blocked);
        try {
            battle.use(player,0,"mineturn:splash_self",player.getId());
            h.assertTrue(player.getHealth()==14 && mob.getHealth()==13,"Self splash did not heal both factions with distance scaling");
            h.assertTrue(blocked.getHealth()==10 && outsider.getHealth()==10,"Splash affected blocked or nonparticipant target");
            h.assertTrue(splash.isEmpty() && battle.shot==null && !battle.budget.canAct(),"Self splash costs or timing mode incorrect");
        }finally{battle.close("test");cleanup(player);mob.discard();blocked.discard();outsider.discard();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void lingeringWaitAvScopeAndCleanup(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,3));
        var potion=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LINGERING_POTION);
        potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        player.getInventory().setItem(0,potion);
        var battle=new BattleSession(player,main,definitions("ground"),player);
        battle.use(player,0,"mineturn:throw_lingering",main.getId());var shot=battle.shot;
        battle.submitShot(player,shot.token,shot.windowCentreNanos());
        var cloud=battle.clouds.getFirst();Vec3 center=cloud.position();
        h.runAfterDelay(40,()->{
            try {
                h.assertTrue(potion.isEmpty() && cloud.tickCount==0 && cloud.getRadius()==3 && !main.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Real time advanced lingering cloud");
                h.assertTrue(!cloud.shouldBeSaved(),"Battle cloud could survive world reload without battle");
                battle.next();
                h.assertTrue(main.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && !outside.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Cloud effect missing or escaped battle");
                h.assertTrue(cloud.tickCount==20 && cloud.getRadius()<3,"Cloud did not advance on AV");
                battle.place(main,main.position().add(4,0,0));
                h.assertTrue(cloud.position().equals(center),"Cloud followed moved target");
                battle.close("test");h.assertTrue(cloud.isRemoved() && battle.clouds.isEmpty(),"Battle exit leaked cloud");
            }finally{battle.close("test");cleanup(player);main.discard();outside.discard();}
            h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void lingeringNativeReapplicationAndExpiry(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        main.setHealth(10);
        var battle=new BattleSession(player,main,definitions("ground"),player);
        var cloud=new BattleCloud(battle,player,main.position(),new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
        h.getLevel().addFreshEntity(cloud);battle.clouds.add(cloud);
        try {
            for(int i=0;i<9;i++)cloud.advance();h.assertTrue(main.getHealth()==10,"Cloud ignored initial waiting period");
            cloud.advance();h.assertTrue(main.getHealth()==12,"Cloud instant effect did not use native half strength");
            for(int i=0;i<19;i++)cloud.advance();h.assertTrue(main.getHealth()==12,"Cloud reapplied before 100 AV delay");
            cloud.advance();h.assertTrue(main.getHealth()==14,"Cloud did not reapply after 100 AV");
            battle.place(main,main.position().add(5,0,0));
            for(int i=0;i<610 && !cloud.isRemoved();i++)cloud.advance();
            h.assertTrue(cloud.isRemoved(),"Cloud did not expire or shrink away");
        }finally{battle.close("test");cleanup(player);main.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void loadedCrossbowMissAndNoInventoryAmmo(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
        bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(new ItemStack(net.minecraft.world.item.Items.ARROW)));
        player.getInventory().setItem(0,bow);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.validateUse(player,0,"mineturn:shoot",mob.getId());
            h.assertTrue(!bow.get(component).isEmpty() && battle.budget.canAct() && bow.getDamageValue()==0,"Preview spent loaded shot");
            battle.use(player,0,"mineturn:shoot",mob.getId());var shot=battle.shot;
            h.assertTrue(bow.get(component).isEmpty() && bow.getDamageValue()==1 && !battle.budget.canAct(),"Loaded shot costs incorrect");
            battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(mob.getHealth()==24,"Miss damaged target");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            boolean rejected=false;try{battle.use(player,0,"mineturn:shoot",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && battle.budget.canAct() && bow.getDamageValue()==1,"Spent loaded shot could fire again");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void loadedCrossbowPotionSnapshotAndWrongAction(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var ammo=new ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW);
        ammo.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
        bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(ammo));
        player.getInventory().setItem(0,bow);
        var spare=new ItemStack(net.minecraft.world.item.Items.ARROW,2);player.getInventory().setItem(10,spare);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            boolean rejected=false;try{battle.use(player,0,"mineturn:shoot",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && battle.budget.canAct() && !bow.get(component).isEmpty() && spare.getCount()==2,"Wrong action discarded loaded potion or spent resources");
            bow.setDamageValue(bow.getMaxDamage()-1);
            battle.use(player,0,"mineturn:shoot_tipped",mob.getId());var shot=battle.shot;
            h.assertTrue(bow.isEmpty() && spare.getCount()==2,"Loaded shot required inventory ammo or did not break weapon");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==18 && mob.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Loaded potion snapshot lost after weapon broke");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void loadedCrossbowLanesAndUnsupportedContents(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var left=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,1));
        var right=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,3));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
        var arrow=new ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW);
        bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(List.of(arrow,arrow,arrow)));
        player.getInventory().setItem(0,bow);
        var spare=new ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW,4);player.getInventory().setItem(10,spare);
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(left);battle.add(right);
        try {
            battle.use(player,0,"mineturn:shoot_spectral",main.getId());var shot=battle.shot;
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==18 && left.getHealth()==18 && right.getHealth()==18
                    && left.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING),"Stored three-shot load lost lanes or effects");
            h.assertTrue(spare.getCount()==4 && bow.get(component).isEmpty(),"Loaded multishot consumed spare arrows");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            var mixed=net.minecraft.world.item.component.ChargedProjectiles.of(List.of(arrow,new ItemStack(net.minecraft.world.item.Items.ARROW),arrow));
            bow.set(component,mixed);
            boolean rejected=false;try{battle.use(player,0,"mineturn:shoot_spectral",main.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && bow.get(component).equals(mixed) && battle.budget.canAct() && spare.getCount()==4,"Unsupported load was silently consumed");
            bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(arrow));
            bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
            battle.use(player,0,"mineturn:shoot_spectral",main.getId());shot=battle.shot;
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==12 && left.getHealth()==18 && right.getHealth()==18,"Enchantment expanded stored single shot into extra lanes");
        }finally{battle.close("test");cleanup(player);main.discard();left.discard();right.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void loadedCrossbowFireworkAndClose(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
        bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(rockets(1,2)));
        player.getInventory().setItem(0,bow);var spare=rockets(2,0);player.getInventory().setItem(10,spare);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,0,"mineturn:shoot_firework",mob.getId());var shot=battle.shot;
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==15 && spare.getCount()==2 && bow.get(component).isEmpty(),"Loaded firework ignored stars or consumed spare rocket");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(rockets(1,1)));
            battle.use(player,0,"mineturn:shoot_firework",mob.getId());
            battle.close("test");
            h.assertTrue(bow.get(component).isEmpty() && spare.getCount()==2 && mob.getHealth()==15,"Closing pending shot refunded or detonated loaded rocket");
        }finally{if(!battle.closed)battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void selectedAmmoStackAndComponentValidation(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.BOW);player.getInventory().setItem(0,bow);
        var poison=new ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW,3);
        poison.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        var slow=new ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW,3);
        slow.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.SLOWNESS));
        player.getInventory().setItem(10,poison);player.getInventory().setItem(20,slow);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var expected=slow.copyWithCount(1);
            player.getInventory().setItem(20,poison.copy());
            boolean rejected=false;try{battle.use(player,0,"mineturn:shoot_tipped",mob.getId(),20,expected);}catch(IllegalArgumentException error){rejected=true;}
            h.assertTrue(rejected && battle.budget.canAct() && bow.getDamageValue()==0 && battle.shot==null,"Changed component selection consumed action");
            player.getInventory().setItem(20,slow);
            battle.use(player,0,"mineturn:shoot_tipped",mob.getId(),20,expected);var shot=battle.shot;
            h.assertTrue(poison.getCount()==3 && slow.getCount()==2,"Selected ammunition consumed first matching stack instead");
            slow.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,poison.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) && !mob.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"Selected ammunition did not retain paid snapshot");
            var packet=battle.snapshot(player);var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buffer,packet);
                h.assertTrue(packet.equals(com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buffer)),"Ammo metadata lost in state codec");
            }finally{buffer.release();}
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void selectedFireworkOffhandAndPacket(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);player.getInventory().setItem(0,bow);
        var empty=rockets(3,0);var charged=rockets(2,2);player.getInventory().setItem(10,empty);player.getInventory().setItem(40,charged);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var request=new com.matuvent.mineturn.network.BattleNetwork.Request(battle.id,battle.revision,"use",0,"mineturn:shoot_firework",mob.getId(),Vec3.ZERO,0);
            var packet=new com.matuvent.mineturn.network.BattleNetwork.AmmoUse(request,40,charged.copyWithCount(1));
            var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
            try{
                com.matuvent.mineturn.network.BattleNetwork.AmmoUse.CODEC.encode(buffer,packet);
                var decoded=com.matuvent.mineturn.network.BattleNetwork.AmmoUse.CODEC.decode(buffer);
                h.assertTrue(decoded.request().equals(request) && decoded.ammoSlot()==40 && ItemStack.isSameItemSameComponents(decoded.expected(),charged),"Selected ammo packet lost components");
                battle.use(player,0,request.action(),mob.getId(),decoded.ammoSlot(),decoded.expected());
            }finally{buffer.release();}
            var shot=battle.shot;battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==15 && charged.getCount()==1 && empty.getCount()==3,"Selected offhand fireworks used wrong stars or consumption");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void selectedAmmoRejectsArmorSlotAndLoadedOverride(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);player.getInventory().setItem(0,bow);
        var arrow=new ItemStack(net.minecraft.world.item.Items.ARROW,3);player.getInventory().setItem(10,arrow);
        var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            for(int slot:new int[]{-2,36,39,41,Integer.MAX_VALUE}) {
                boolean rejected=false;try{battle.use(player,0,"mineturn:shoot",mob.getId(),slot,arrow.copyWithCount(1));}catch(IllegalArgumentException error){rejected=true;}
                h.assertTrue(rejected && battle.budget.canAct() && arrow.getCount()==3,"Invalid ammunition slot accepted");
            }
            bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(arrow.copyWithCount(1)));
            boolean rejected=false;try{battle.use(player,0,"mineturn:shoot",mob.getId(),10,arrow.copyWithCount(1));}catch(IllegalArgumentException error){rejected=true;}
            h.assertTrue(rejected && !bow.get(component).isEmpty() && arrow.getCount()==3 && battle.budget.canAct(),"Selection replaced loaded ammunition");
            bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.EMPTY);
            battle.use(player,0,"mineturn:shoot",mob.getId(),10,arrow.copyWithCount(1));var shot=battle.shot;
            battle.submitShot(player,shot.token,shot.startNanos);
            battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(arrow.getCount()==2 && mob.getHealth()==24 && bow.getDamageValue()==1,"Selected miss refunded or duplicated costs");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void groundPotionPointValidationAndSnapshot(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var outsider=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,3));
        var potion=new ItemStack(net.minecraft.world.item.Items.SPLASH_POTION,3);
        potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        player.getInventory().setItem(0,potion);var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var point=mob.position();String action="mineturn:throw_splash_ground";
            for(var invalid:List.of(point.add(0,2,0),point.add(100,0,0),new Vec3(Double.NaN,point.y,point.z))) {
                boolean rejected=false;try{battle.usePotionPoint(player,0,action,invalid);}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected && potion.getCount()==3 && battle.budget.canAct(),"Invalid ground point spent resources");
            }
            battle.validatePotionPoint(player,0,action,point);
            h.assertTrue(potion.getCount()==3 && battle.budget.canAct(),"Ground preview consumed potion");
            battle.usePotionPoint(player,0,action,point);var shot=battle.shot;
            potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && !outsider.hasEffect(net.minecraft.world.effect.MobEffects.POISON)
                    && potion.getCount()==2 && !battle.budget.canAct(),"Ground potion lost snapshot, scope or cost");
            var packet=battle.snapshot(player);var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buf,packet);var copy=com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buf);
                h.assertTrue(copy.equals(packet) && copy.slots().getFirst().actions().stream().anyMatch(a->a.ground()),"Ground targeting flag lost");
            }finally{buf.release();}
        }finally{battle.close("test");cleanup(player);mob.discard();outsider.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void groundLingeringMissBlockedAndFixedPoint(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var potion=new ItemStack(net.minecraft.world.item.Items.LINGERING_POTION,3);
        potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        player.getInventory().setItem(0,potion);var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var point=mob.position();String action="mineturn:throw_lingering_ground";
            battle.usePotionPoint(player,0,action,point);var shot=battle.shot;battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(battle.clouds.isEmpty() && potion.getCount()==2,"Ground miss created cloud or refunded potion");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.usePotionPoint(player,0,action,point);shot=battle.shot;
            for(int y=1;y<=3;y++)h.setBlock(4,y,2,Blocks.STONE);
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(battle.clouds.isEmpty() && potion.getCount()==1,"New wall did not invalidate ground throw");
            for(int y=1;y<=3;y++)h.setBlock(4,y,2,Blocks.AIR);
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.usePotionPoint(player,0,action,point);shot=battle.shot;
            battle.place(mob,mob.position().add(2,0,0));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(battle.clouds.size()==1 && battle.clouds.getFirst().position().equals(point) && potion.isEmpty(),"Ground cloud followed entity instead of point");
            var cloud=battle.clouds.getFirst();battle.close("test");h.assertTrue(cloud.isRemoved(),"Ground cloud survived battle exit");
        }finally{if(!battle.closed)battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void allyPotionUsesRangedCostAndFaction(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var friend=h.spawnWithNoFreeWill(EntityType.IRON_GOLEM,new BlockPos(5,1,2));friend.setHealth(10);
        var potion=new ItemStack(net.minecraft.world.item.Items.SPLASH_POTION,2);
        potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
        player.getInventory().setItem(0,potion);var battle=new BattleSession(player,mob,CombatData.current,player);if(!battle.members.containsKey(friend.getUUID()))battle.add(friend);
        try {
            h.assertTrue(!battle.enemy(player,friend),"Fixture golem not allied");
            boolean rejected=false;try{battle.use(player,0,"mineturn:throw_splash_ally",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && potion.getCount()==2 && battle.budget.canAct(),"Ally throw accepted enemy target");
            battle.use(player,0,"mineturn:throw_splash_ally",friend.getId());var shot=battle.shot;
            h.assertTrue(potion.getCount()==1 && !battle.budget.canAct() && friend.getHealth()==10,"Ally throw bypassed timing or payment");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(friend.getHealth()>10,"Ally potion did not heal selected ally");
        }finally{battle.close("test");cleanup(player);mob.discard();friend.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void infiniteMaterialsAmmoAndThrownItemsKeepStacks(GameTestHelper h) {
        var player=player(h);player.getAbilities().instabuild=true;
        var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            for(String id:List.of("mineturn:shoot_tipped","mineturn:shoot_spectral","mineturn:shoot_firework")) {
                var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);player.getInventory().setItem(0,bow);
                var ammo=id.endsWith("firework")?rockets(1,1):new ItemStack(id.endsWith("tipped")?net.minecraft.world.item.Items.TIPPED_ARROW:net.minecraft.world.item.Items.SPECTRAL_ARROW);
                player.getInventory().setItem(10,ammo);battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
                battle.use(player,0,id,mob.getId(),10,ammo.copy());var shot=battle.shot;
                battle.submitShot(player,shot.token,shot.startNanos);
                h.assertTrue(ammo.getCount()==1 && bow.getDamageValue()==0 && !battle.budget.canAct() && !battle.ready(player,id),"Creative ammo/durability or action cost incorrect");
            }
            var potion=new ItemStack(net.minecraft.world.item.Items.SPLASH_POTION);player.getInventory().setItem(0,potion);
            battle.budget=new TurnBudget(4);battle.use(player,0,"mineturn:throw_splash",mob.getId());battle.finishShot(false);
            h.assertTrue(potion.getCount()==1 && !battle.budget.canAct(),"Creative target throw consumed potion or waived action");
            battle.budget=new TurnBudget(4);battle.usePotionPoint(player,0,"mineturn:throw_splash_ground",mob.position());battle.finishShot(false);
            h.assertTrue(potion.getCount()==1 && !battle.budget.canAct(),"Creative ground throw consumed potion or waived action");
            var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
            bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(new ItemStack(net.minecraft.world.item.Items.ARROW)));
            player.getInventory().setItem(0,bow);battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot",mob.getId());battle.finishShot(false);
            h.assertTrue(bow.get(component).isEmpty() && bow.getDamageValue()==0,"Creative preloaded crossbow did not clear native charge");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void foodLastContainerAndFullInventory(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(net.minecraft.world.item.Items.STONE,64));
            var food=new ItemStack(net.minecraft.world.item.Items.APPLE);
            food.set(net.minecraft.core.component.DataComponents.FOOD,new net.minecraft.world.food.FoodProperties.Builder().nutrition(3).saturationModifier(0).usingConvertsTo(net.minecraft.world.item.Items.BOWL).build());
            player.getInventory().setItem(3,food);player.getFoodData().setFoodLevel(10);
            battle.validateUse(player,3,"mineturn:eat",player.getId());
            h.assertTrue(food.getCount()==1 && player.getFoodData().getFoodLevel()==10,"Food preview consumed food");
            battle.use(player,3,"mineturn:eat",player.getId());
            h.assertTrue(player.getInventory().getItem(3).is(net.minecraft.world.item.Items.BOWL) && player.getFoodData().getFoodLevel()==13 && !battle.budget.canAct(),"Last food did not become container in original slot");
            var multiple=new ItemStack(net.minecraft.world.item.Items.APPLE,2);
            multiple.set(net.minecraft.core.component.DataComponents.FOOD,new net.minecraft.world.food.FoodProperties.Builder().nutrition(3).saturationModifier(0).usingConvertsTo(net.minecraft.world.item.Items.BOWL).build());
            player.getInventory().setItem(3,multiple);battle.budget=new TurnBudget(4);
            var box=player.getBoundingBox().inflate(3);
            int before=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,box,e->e.getItem().is(net.minecraft.world.item.Items.BOWL)).stream().mapToInt(e->e.getItem().getCount()).sum();
            battle.use(player,3,"mineturn:eat",player.getId());
            int after=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,box,e->e.getItem().is(net.minecraft.world.item.Items.BOWL)).stream().mapToInt(e->e.getItem().getCount()).sum();
            h.assertTrue(multiple.getCount()==1 && after==before+1 && player.getFoodData().getFoodLevel()==16,"Stacked food duplicated consumption or lost full-inventory container");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void infiniteMaterialsFoodAndMilkNativeRemainders(GameTestHelper h) {
        var player=player(h);player.getAbilities().instabuild=true;var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var food=new ItemStack(net.minecraft.world.item.Items.APPLE);
            food.set(net.minecraft.core.component.DataComponents.FOOD,new net.minecraft.world.food.FoodProperties.Builder().nutrition(3).saturationModifier(0).usingConvertsTo(net.minecraft.world.item.Items.BOWL).build());
            player.getInventory().setItem(0,food);player.getFoodData().setFoodLevel(10);
            battle.use(player,0,"mineturn:eat",player.getId());
            h.assertTrue(food.getCount()==1 && player.getFoodData().getFoodLevel()==13 && !player.getInventory().contains(new ItemStack(net.minecraft.world.item.Items.BOWL)) && !battle.budget.canAct(),"Creative food generated free container or skipped action");
            var milk=new ItemStack(net.minecraft.world.item.Items.MILK_BUCKET);player.getInventory().setItem(0,milk);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,200));
            battle.budget=new TurnBudget(4);battle.use(player,0,"mineturn:drink",player.getId());
            h.assertTrue(milk.getCount()==1 && !player.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && player.getInventory().contains(new ItemStack(net.minecraft.world.item.Items.BUCKET)),"Milk did not preserve native infinite-materials bucket return");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void crossbowBreakStopsRemainingLanes(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var left=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,1));
        var right=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,3));
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(left);battle.add(right);
        try {
            for(boolean loaded:new boolean[]{false,true})for(int remaining:new int[]{1,2,100}) {
                main.setHealth(24);left.setHealth(24);right.setHealth(24);
                var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
                bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
                bow.setDamageValue(bow.getMaxDamage()-remaining);player.getInventory().setItem(0,bow);
                var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
                if(loaded){var arrow=new ItemStack(net.minecraft.world.item.Items.ARROW);bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(List.of(arrow,arrow,arrow)));}
                player.getInventory().setItem(10,new ItemStack(net.minecraft.world.item.Items.ARROW,2));
                battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
                battle.use(player,0,"mineturn:shoot",main.getId());var shot=battle.shot;
                int lanes=Math.min(remaining,3);
                h.assertTrue(bow.isEmpty()==(remaining<=2) && (bow.isEmpty() || bow.get(component).isEmpty())
                        && shot.weapon.get(component).getItems().size()==lanes,"Live charge or launched lane snapshot incorrect");
                battle.submitShot(player,shot.token,shot.windowCentreNanos());
                int sides=(left.getHealth()<24?1:0)+(right.getHealth()<24?1:0);
                h.assertTrue(main.getHealth()==18 && sides==lanes-1 && player.getInventory().getItem(10).getCount()==(loaded?2:1),"Crossbow fired extra lanes or consumed extra ammo");
            }
        }finally{battle.close("test");cleanup(player);main.discard();left.discard();right.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void crossbowFireworkDurabilityMissAndLoadedBreak(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
            bow.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
            player.getInventory().setItem(0,bow);var ammo=rockets(2,1);player.getInventory().setItem(10,ammo);
            battle.use(player,0,"mineturn:shoot_firework",mob.getId());battle.finishShot(false);
            h.assertTrue(bow.getDamageValue()==9 && ammo.getCount()==1 && mob.getHealth()==24,"Miss did not pay three native firework durability costs");
            var charged=rockets(1,2);var component=net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES;
            bow.set(component,net.minecraft.world.item.component.ChargedProjectiles.of(List.of(charged,charged,charged)));
            bow.setDamageValue(bow.getMaxDamage()-4);battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot_firework",mob.getId());var shot=battle.shot;
            h.assertTrue(bow.isEmpty() && shot.weapon.get(component).getItems().size()==2 && ammo.getCount()==1,"Loaded rocket break count or inventory cost incorrect");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==15,"Broken rocket weapon lost component snapshot or stacked explosion");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void crossbowUnbreakingMatchesNativePerProjectile(GameTestHelper h) {
        var player=player(h);
        try {
            var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);
            var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
            bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING),3);
            var nativeBow=bow.copy();
            h.getLevel().random.setSeed(5721);
            for(int shot=0;shot<40;shot++)for(int lane=0;lane<3;lane++)nativeBow.hurtAndBreak(3,player,net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            h.getLevel().random.setSeed(5721);
            for(int shot=0;shot<40;shot++)com.matuvent.mineturn.api.CombatProjectiles.payCrossbowDurability(player,bow,bow.copy(),rockets(1,1));
            h.assertTrue(bow.getDamageValue()==nativeBow.getDamageValue() && bow.getDamageValue()>0 && bow.getDamageValue()<360,"Unbreaking diverged from native per-projectile calls");
        }finally{cleanup(player);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void playerAxeShieldNativeConditionsAndAv(GameTestHelper h) {
        var defender=player(h);var attacker=player(h);
        attacker.setPos(defender.position().add(2,0,0));
        h.runAfterDelay(70,()->{
            boolean oldPvp=h.getLevel().getServer().isPvpAllowed();h.getLevel().getServer().setPvpAllowed(true);
            try {
                for(int scenario=0;scenario<3;scenario++) {
                    defender.setHealth(20);defender.invulnerableTime=0;defender.getCooldowns().removeCooldown(net.minecraft.world.item.Items.SHIELD);
                    defender.getInventory().setItem(3,new ItemStack(net.minecraft.world.item.Items.SHIELD));
                    attacker.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.WOODEN_SWORD));attacker.getInventory().selected=0;
                    attacker.getInventory().setItem(4,new ItemStack(scenario==0?net.minecraft.world.item.Items.IRON_SWORD:net.minecraft.world.item.Items.IRON_AXE));
                    var battle=new BattleSession(defender,attacker,CombatData.current,defender);
                    try {
                        battle.use(defender,3,"mineturn:shield_guard",defender.getId());
                        if(scenario==2){defender.setYRot(defender.getYRot()+180);defender.setYHeadRot(defender.getYRot());defender.setYBodyRot(defender.getYRot());}
                        battle.actor=attacker;battle.budget=new TurnBudget(4);
                        battle.use(attacker,4,"mineturn:melee",defender.getId());
                        h.assertTrue(attacker.getInventory().selected==0,"Selected axe was not restored after attack");
                        if(scenario==0) {
                            h.assertTrue(defender.getHealth()==20 && battle.member(defender).shieldDisabledUntil==0 && !battle.member(defender).guarding,"Sword incorrectly disabled shield or bypassed guard");
                        } else if(scenario==2) {
                            h.assertTrue(defender.getHealth()<20 && battle.member(defender).shieldDisabledUntil==0,"Back attack shield mismatch: hp="+defender.getHealth()+", disabled="+battle.member(defender).shieldDisabledUntil);
                        } else {
                            h.assertTrue(defender.getHealth()==20 && battle.member(defender).shieldDisabledUntil==battle.clock.time()+500 && !battle.member(defender).guarding,"Selected axe did not retain native 500 AV shield disable");
                            for(int i=0;i<120;i++){defender.getCooldowns().tick();BattleRaid.shieldClock(battle,battle.member(defender));}
                            h.assertTrue(defender.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD) && battle.member(defender).shieldDisabledUntil-battle.clock.time()==500,"Real time advanced shield AV cooldown");
                            advanceShieldTestClock(battle,250);BattleRaid.shieldClock(battle,battle.member(defender));
                            battle.actor=defender;battle.budget=new TurnBudget(4);
                            boolean rejected=false;try{battle.use(defender,3,"mineturn:shield_guard",defender.getId());}catch(IllegalArgumentException expected){rejected=true;}
                            h.assertTrue(rejected && battle.budget.canAct(),"Shield could guard during AV disable");
                            battle.close("test");
                            for(int i=0;i<49;i++)defender.getCooldowns().tick();
                            h.assertTrue(defender.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD),"Exit lost remaining shield cooldown");
                            defender.getCooldowns().tick();h.assertTrue(!defender.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD),"Exit did not resume native cooldown");
                        }
                    }finally{if(!battle.closed)battle.close("test");}
                }
            }finally{h.getLevel().getServer().setPvpAllowed(oldPvp);cleanup(attacker);cleanup(defender);}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void playerAxeShieldCooldownExpiresOnAv(GameTestHelper h) {
        var defender=player(h);var attacker=player(h);attacker.setPos(defender.position().add(2,0,0));
        h.runAfterDelay(70,()->{
            boolean oldPvp=h.getLevel().getServer().isPvpAllowed();h.getLevel().getServer().setPvpAllowed(true);
            var battle=new BattleSession(defender,attacker,CombatData.current,defender);
            try {
                defender.getInventory().setItem(3,new ItemStack(net.minecraft.world.item.Items.SHIELD));
                attacker.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.IRON_AXE));
                battle.use(defender,3,"mineturn:shield_guard",defender.getId());battle.actor=attacker;battle.budget=new TurnBudget(4);
                battle.use(attacker,0,"mineturn:melee",defender.getId());
                advanceShieldTestClock(battle,500);BattleRaid.shieldClock(battle,battle.member(defender));
                h.assertTrue(battle.member(defender).shieldDisabledUntil==0 && !defender.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD),"Shield cooldown survived 500 AV");
                battle.actor=defender;battle.budget=new TurnBudget(4);battle.use(defender,3,"mineturn:shield_guard",defender.getId());
                h.assertTrue(battle.member(defender).guarding,"Shield unavailable after AV expiration");
            }finally{battle.close("test");h.getLevel().getServer().setPvpAllowed(oldPvp);cleanup(attacker);cleanup(defender);}h.succeed();
        });
    }
    private static void advanceShieldTestClock(BattleSession battle,double elapsed) {
        double end=battle.clock.time()+elapsed;
        while(battle.clock.time()<end-1e-7) {
            double next=battle.clock.nextDelay(battle::agility);
            if(next<1e-7)battle.clock.next(battle::agility);
            else battle.clock.advance(Math.min(next,end-battle.clock.time()),battle::agility);
        }
    }
    @GameTest(template="empty")
    public static void playerTridentMeleeThrowAndRecovery(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
        weapon.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("测试三叉戟"));
        player.getInventory().setItem(4,weapon);var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,4,"mineturn:melee",mob.getId());
            h.assertTrue(mob.getHealth()==15 && weapon.getDamageValue()>0,"Player trident melee did not use native attributes/durability");
            int before=weapon.getDamageValue();var expected=weapon.copy();expected.setDamageValue(before+1);
            battle.budget=new TurnBudget(4);battle.use(player,4,"mineturn:throw_trident",mob.getId());var shot=battle.shot;
            var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mob.getBoundingBox().inflate(2),e->e.getItem().is(net.minecraft.world.item.Items.TRIDENT));
            h.assertTrue(weapon.isEmpty() && drops.size()==1 && ItemStack.matches(expected,drops.getFirst().getItem()) && !battle.budget.canAct(),"Throw lost components/durability or duplicated weapon");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==7 && mob.getDeltaMovement().lengthSqr()==0,"Native player trident hit damage or no-knockback rule incorrect");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==7,"Trident damage replayed");
            battle.close("test");h.assertTrue(drops.getFirst().isAlive(),"Closing battle deleted recoverable weapon");
            drops.getFirst().discard();
        }finally{if(!battle.closed)battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerTridentMissCancelAndUnsupportedEnchantments(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            for(var key:List.of(net.minecraft.world.item.enchantment.Enchantments.RIPTIDE)) {
                var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);weapon.enchant(registry.getHolderOrThrow(key),1);player.getInventory().setItem(0,weapon);
                boolean rejected=false;try{battle.use(player,0,"mineturn:throw_trident",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected && weapon.getCount()==1 && weapon.getDamageValue()==0 && battle.budget.canAct(),"Unsupported special trident throw spent resources");
            }
            var worn=new ItemStack(net.minecraft.world.item.Items.TRIDENT);worn.setDamageValue(worn.getMaxDamage()-1);player.getInventory().setItem(0,worn);
            boolean rejected=false;try{battle.use(player,0,"mineturn:throw_trident",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && worn.getCount()==1 && battle.budget.canAct(),"Native low-durability restriction ignored");
            for(int i=0;i<2;i++) {
                player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.TRIDENT));battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
                battle.use(player,0,"mineturn:throw_trident",mob.getId());
                if(i==0)battle.finishShot(false);else battle.close("test");
            }
            var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mob.getBoundingBox().inflate(2),e->e.getItem().is(net.minecraft.world.item.Items.TRIDENT));
            h.assertTrue(drops.size()==2 && mob.getHealth()==24 && player.getInventory().getItem(0).isEmpty(),"Miss/close refunded, damaged or lost thrown weapon");
            drops.forEach(net.minecraft.world.entity.Entity::discard);
        }finally{if(!battle.closed)battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerTridentSpawnCancellationAndInfiniteMaterials(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);player.getInventory().setItem(0,weapon);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> cancel=event->{
            if(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(net.minecraft.world.item.Items.TRIDENT))event.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        try {
            boolean rejected=false;try{battle.use(player,0,"mineturn:throw_trident",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && weapon.getCount()==1 && weapon.getDamageValue()==0 && battle.budget.canAct() && battle.shot==null && battle.ready(player,"mineturn:throw_trident"),"Cancelled item spawn spent weapon or battle resources");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);player.getAbilities().instabuild=true;
            battle.use(player,0,"mineturn:throw_trident",mob.getId());battle.finishShot(false);
            h.assertTrue(weapon.getCount()==1 && weapon.getDamageValue()==0 && !battle.budget.canAct(),"Infinite-material throw consumed weapon or waived action");
            h.assertTrue(h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mob.getBoundingBox().inflate(2),e->e.getItem().is(net.minecraft.world.item.Items.TRIDENT)).isEmpty(),"Infinite-material throw duplicated recoverable weapon");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    private static CombatData.Snapshot exampleDefinitions(GameTestHelper h,String folder,int count){
        var files=new java.util.HashMap<ResourceLocation,com.google.gson.JsonElement>();
        for(var entry:h.getLevel().getServer().getResourceManager().listResources("fixtures/"+folder,id->id.getPath().endsWith(".json")).entrySet()){
            String path=entry.getKey().getPath().substring(("fixtures/"+folder+"/").length());
            int split=path.indexOf('/');String namespace=path.substring(0,split);
            try(var reader=entry.getValue().openAsReader()){
                files.put(ResourceLocation.fromNamespaceAndPath(namespace,path.substring(split+1+"mineturn/".length(),path.length()-5)),JsonParser.parseReader(reader));
            }catch(java.io.IOException error){throw new RuntimeException(error);}
        }
        h.assertTrue(files.size()==count,"Missing accessory example fixture");
        var parsed=CombatData.parse(files);var base=definitions("ground");
        var actions=new java.util.HashMap<>(base.actions());actions.putAll(parsed.actions());
        return new CombatData.Snapshot(actions,base.mobs(),base.items(),base.sources(),parsed.grants());
    }
    private static CombatData.Snapshot accessoryExample(GameTestHelper h){return exampleDefinitions(h,"accessory_actions",4);}
    @GameTest(template="empty")
    public static void entryTrapIgnoresWaitingAndInitialOccupant(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"trap_actions",4),player);
        try{
            player.setItemSlot(EquipmentSlot.FEET,new ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));float hp=enemy.getHealth();
            battle.useGrant(player,"trap_example:trap",player.getId());
            advanceShieldTestClock(battle,25);BattleFields.advance(battle);
            h.assertTrue(enemy.getHealth()==hp && battle.fields.size()==1,"Trap hit initial occupant or pulsed while waiting");
            battle.place(enemy,enemy.position().add(0,0,3));h.assertTrue(enemy.getHealth()==hp,"Leaving trap triggered damage");
            battle.place(enemy,player.position().add(2,0,0));
            h.assertTrue(enemy.getHealth()==hp-4 && battle.fields.isEmpty(),"Reentry did not trigger once and consume trap");
            battle.place(enemy,enemy.position().add(0,0,3));battle.place(enemy,player.position().add(2,0,0));h.assertTrue(enemy.getHealth()==hp-4,"Consumed trap triggered again");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void entryTrapCatchesDisplacementButNotTeleportPath(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,5));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"trap_actions",4),player);
        var effect=ResourceLocation.parse("mineturn_test:trap_push_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(effect,c->c.battle().displace(enemy,new Vec3(0,0,-5)));
        try{
            player.setItemSlot(EquipmentSlot.FEET,new ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));battle.useGrant(player,"trap_example:trap",player.getId());float hp=enemy.getHealth();
            var start=enemy.position();battle.place(enemy,start.add(0,0,-5));h.assertTrue(enemy.getHealth()==hp && battle.fields.size()==1,"Teleport traced intervening trap");battle.place(enemy,start);
            battle.execute(player,enemy,effect.toString(),new CombatData.Action("push",effect.toString(),0,8,false,0,0,new com.google.gson.JsonObject()),ItemStack.EMPTY);
            h.assertTrue(enemy.getHealth()==hp-4 && battle.fields.isEmpty(),"Swept displacement skipped trap between endpoints");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void fieldCoreIsTargetableHasNoTurnAndDiesWithField(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"trap_actions",4),player);
        try{
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));battle.useGrant(player,"trap_example:device",player.getId());
            var field=battle.fields.values().iterator().next();var core=field.core;
            h.assertTrue(core!=null && core.getHealth()==8 && battle.enemy(enemy,core) && !battle.enemy(player,core),"Core health or faction incorrect");
            var state=battle.snapshot(player);h.assertTrue(state.fighters().stream().anyMatch(f->f.id()==core.getId() && f.nextAv()<0) && state.queue().stream().noneMatch(q->q.id()==core.getId()) && !battle.engaged(enemy),"Core took turns or imposed engagement");
            var hit=new CombatData.Action("hit","mineturn:damage",8,16,false,0,0,new com.google.gson.JsonObject());battle.execute(enemy,core,"test:hit_core",hit,ItemStack.EMPTY);battle.prune();
            h.assertTrue(core.isRemoved() && battle.fields.isEmpty() && !BattleManager.locked(core),"Destroyed core retained field/member");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerWeaponDestroysEnemyFieldCore(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,enemy,definitions("ground"),player);
        var id=ResourceLocation.parse("mineturn_test:enemy_core_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(id,c->c.battle().createField(new com.matuvent.mineturn.api.CombatFields.Request(player.position().add(2,0,0),1,2,25,100,false,0,4),pulse->{}));
        try{
            battle.execute(enemy,enemy,id.toString(),new CombatData.Action("core",id.toString(),0,8,true,0,0,new com.google.gson.JsonObject()),ItemStack.EMPTY);
            var core=battle.fields.values().iterator().next().core;player.getInventory().setItem(0,new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
            h.assertTrue(battle.enemy(player,core) && !battle.engaged(player),"Enemy core target/control incorrect");
            battle.use(player,0,"mineturn:melee",core.getId());
            h.assertTrue(!battle.budget.canAct() && core.isRemoved() && battle.fields.isEmpty(),"Paid weapon action failed to destroy core");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void coreRemovedOnExpiryAndTrapParametersValidated(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,8));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"trap_actions",4),player);
        try{
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));battle.useGrant(player,"trap_example:device",player.getId());var core=battle.fields.values().iterator().next().core;
            advanceShieldTestClock(battle,150);BattleFields.advance(battle);h.assertTrue(core.isRemoved() && battle.fields.isEmpty() && !battle.members.containsKey(core.getUUID()),"Expiry retained core");
            boolean rejected=false;try{new com.matuvent.mineturn.api.CombatFields.Request(player.position(),1,2,25,150,true,0,0);}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"Zero-charge trap accepted");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void hazardExamplePulsesEnemiesOnlyAndExpires(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var ally=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,3));var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,1));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"field_actions",2),player);
        try{
            battle.add(ally);battle.member(ally).allyOwner=player.getUUID();player.experienceLevel=4;
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.CHAINMAIL_HELMET));
            float hp=enemy.getHealth(),friend=ally.getHealth(),other=outside.getHealth();
            battle.useGrant(player,"field_example:hazard",player.getId());
            h.assertTrue(battle.fields.size()==1 && player.experienceLevel==3 && !battle.budget.canAct() && !battle.ready(player,"field_example:hazard") && enemy.getHealth()==hp,"Field payment/initial delay incorrect");
            var field=battle.fields.values().iterator().next();
            for(int i=0;i<10;i++)BattleFields.display(battle);
            h.assertTrue(enemy.getHealth()==hp && field.next==25,"Display advanced logical field");
            advanceShieldTestClock(battle,25);BattleFields.advance(battle);
            h.assertTrue(enemy.getHealth()==hp-2 && ally.getHealth()==friend && outside.getHealth()==other,"Field hit ally/outsider or missed enemy");
            BattleFields.advance(battle);h.assertTrue(enemy.getHealth()==hp-2,"Pulse repeated at same AV");
            advanceShieldTestClock(battle,25);BattleFields.advance(battle);advanceShieldTestClock(battle,25);BattleFields.advance(battle);
            h.assertTrue(enemy.getHealth()==hp-6 && player.experienceLevel==3,"Pulse repaid fees or damage incorrect");
            advanceShieldTestClock(battle,25);BattleFields.advance(battle);
            h.assertTrue(battle.fields.isEmpty() && enemy.getHealth()==hp-6,"Expiry did not win over equal-time pulse");
        }finally{battle.close("test");cleanup(player);enemy.discard();ally.discard();outside.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void hazardRechecksCoverPositionAndOwner(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var battle=new BattleSession(player,enemy,exampleDefinitions(h,"field_actions",2),player);
        try{
            player.experienceLevel=4;player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.CHAINMAIL_HELMET));
            battle.useGrant(player,"field_example:hazard",player.getId());float hp=enemy.getHealth();
            var wall=BlockPos.containing(player.position().add(3,0,0));for(int y=0;y<3;y++)h.getLevel().setBlockAndUpdate(wall.above(y),Blocks.STONE.defaultBlockState());
            advanceShieldTestClock(battle,25);BattleFields.advance(battle);h.assertTrue(enemy.getHealth()==hp,"Field hit through new wall");
            for(int y=0;y<3;y++)h.getLevel().setBlockAndUpdate(wall.above(y),Blocks.AIR.defaultBlockState());
            battle.place(enemy,player.position().add(6,0,0));advanceShieldTestClock(battle,25);BattleFields.advance(battle);
            h.assertTrue(enemy.getHealth()==hp,"Fixed field followed escaped target");
            battle.remove(player,"owner leaves");h.assertTrue(battle.fields.isEmpty(),"Owner departure retained field");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void fieldTimelineUsesFreshContextAndExactExpiry(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var battle=new BattleSession(player,enemy,definitions("ground"),player);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var saved=new java.util.concurrent.atomic.AtomicReference<CombatEffects.Context>();
        var id=ResourceLocation.parse("mineturn_test:field_clock_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(id,c->c.battle().createField(new com.matuvent.mineturn.api.CombatFields.Request(enemy.position(),2,2,25,26),pulse->{saved.set(pulse);calls.incrementAndGet();pulse.battle().hurt(pulse.target(),2);}));
        try{
            float hp=enemy.getHealth();battle.execute(player,player,id.toString(),new CombatData.Action("field",id.toString(),0,8,true,0,0,new com.google.gson.JsonObject()),ItemStack.EMPTY);
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(calls.get()==1 && saved.get().battleTime()==25 && battle.clock.time()==26 && battle.fields.isEmpty() && enemy.getHealth()==hp-2,"Field did not join event timeline or expire exactly");
            boolean rejected=false;try{saved.get().battle().hurt(enemy,2);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Pulse context remained authorized");
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void fieldScopeLimitsAndCancellation(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,enemy,definitions("ground"),player);
        var request=new com.matuvent.mineturn.api.CombatFields.Request(player.position().add(2,0,0),2,2,25,100);
        var saved=new java.util.concurrent.atomic.AtomicReference<CombatEffects.Context>();var ticket=new java.util.concurrent.atomic.AtomicReference<UUID>();
        var id=ResourceLocation.parse("mineturn_test:field_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(id,c->{saved.set(c);for(int i=0;i<4;i++){var result=c.battle().createField(request,p->{});h.assertTrue(result.accepted(),"Field below cap rejected");ticket.set(result.id());}
            h.assertTrue(c.battle().previewField(request)!=null && !c.battle().createField(request,p->{}).accepted(),"Field owner cap bypassed");
            h.assertTrue(c.battle().removeField(ticket.get()) && !c.battle().removeField(ticket.get()),"Field cancellation not unique");});
        var action=new CombatData.Action("test",id.toString(),0,8,true,0,0,new com.google.gson.JsonObject());
        try{
            var preview=battle.effectContext(player,player,ItemStack.EMPTY,action,false);h.assertTrue(preview.battle().previewField(request)==null,"Legal field preview rejected");
            boolean rejected=false;try{preview.battle().createField(request,p->{});}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected && battle.fields.isEmpty(),"Preview created field");
            battle.execute(player,player,id.toString(),action,ItemStack.EMPTY);
            rejected=false;try{saved.get().battle().removeField(ticket.get());}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Expired context cancelled field");
            battle.close("test");h.assertTrue(battle.fields.isEmpty(),"Closed battle retained fields");
            for(double interval:new double[]{0,Double.NaN,4}){boolean invalid=false;try{new com.matuvent.mineturn.api.CombatFields.Request(player.position(),2,2,interval,100);}catch(IllegalArgumentException expected){invalid=true;}h.assertTrue(invalid,"Invalid field timing accepted");}
        }finally{battle.close("test");cleanup(player);enemy.discard();}h.succeed();
    }
    private static com.matuvent.mineturn.api.CombatSummons.Result summonForTest(BattleSession battle,net.minecraft.world.entity.LivingEntity source,Vec3 point,double life){
        var result=new java.util.concurrent.atomic.AtomicReference<com.matuvent.mineturn.api.CombatSummons.Result>();
        var id=ResourceLocation.parse("mineturn_test:summon_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(id,c->result.set(c.battle().summon(new com.matuvent.mineturn.api.CombatSummons.Request(ResourceLocation.parse("minecraft:husk"),point,life))));
        battle.execute(source,source,id.toString(),new CombatData.Action("summon",id.toString(),0,8,true,0,0,new com.google.gson.JsonObject()),ItemStack.EMPTY);
        return result.get();
    }
    @GameTest(template="empty")
    public static void summonExamplePreflightPaymentAndOwnerCleanup(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,exampleDefinitions(h,"summon_actions",2),player);
        try{
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET));player.experienceLevel=3;
            var wall=BlockPos.containing(player.position().add(2,0,0));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
            boolean rejected=false;try{battle.useGrant(player,"summon_example:husk",player.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && player.experienceLevel==3 && battle.budget.canAct() && battle.summons.isEmpty(),"Blocked summon spent resources");
            h.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());
            battle.useGrant(player,"summon_example:husk",player.getId());var unit=battle.summons.values().iterator().next();
            h.assertTrue(player.experienceLevel==2 && !battle.budget.canAct() && !battle.ready(player,"summon_example:husk"),"Summon did not pay once");
            h.assertTrue(BattleManager.locked(unit.entity()) && !battle.enemy(player,unit.entity()) && battle.enemy(unit.entity(),mob) && battle.enemy(mob,unit.entity()),"Summon membership/faction incorrect");
            h.assertTrue(unit.expires()==battle.clock.time()+300 && unit.entity().getDeltaMovement().lengthSqr()==0,"Summon lifetime or velocity incorrect");
            battle.remove(player,"owner leaves");
            h.assertTrue(unit.entity().isRemoved() && battle.summons.isEmpty() && !BattleManager.locked(unit.entity()),"Owner departure left orphan");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void summonAvLifetimeAndHostileOwner(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var result=summonForTest(battle,mob,mob.position().add(0,0,2),25);
            h.assertTrue(result.accepted(),"Summon API rejected legal point: "+result.reason());var unit=battle.summons.get(result.entity()).entity();
            h.assertTrue(battle.enemy(unit,player) && !battle.enemy(unit,mob) && battle.members.containsKey(result.entity()),"Hostile summon did not inherit owner faction");
            h.assertTrue(!unit.isRemoved() && battle.clock.time()==0,"Lifetime advanced in realtime");
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(unit.isRemoved() && battle.summons.isEmpty() && !battle.members.containsKey(result.entity()) && !BattleManager.locked(unit),"AV expiry did not remove unit");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void summonLimitsPreviewScopeAndExit(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var action=new CombatData.Action("preview","mineturn:summon",0,8,true,0,0,new com.google.gson.JsonObject());
            var preview=battle.effectContext(player,player,ItemStack.EMPTY,action,false);
            var request=new com.matuvent.mineturn.api.CombatSummons.Request(ResourceLocation.parse("minecraft:husk"),player.position().add(2,0,0),100);
            h.assertTrue(preview.battle().previewSummon(request)==null && battle.summons.isEmpty(),"Preview mutated state");
            boolean rejected=false;try{preview.battle().summon(request);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Preview spawned free unit");
            for(var offset:List.of(new Vec3(2,0,0),new Vec3(2,0,2),new Vec3(2,0,4),new Vec3(4,0,0)))
                h.assertTrue(summonForTest(battle,player,player.position().add(offset),100).accepted(),"Legal unit rejected below limit");
            h.assertTrue(!summonForTest(battle,player,player.position().add(4,0,2),100).accepted() && battle.summons.size()==4,"Per-owner limit bypassed");
            var units=battle.summons.values().stream().map(BattleSummons.Unit::entity).toList();
            h.assertTrue(!summonForTest(battle,units.getFirst(),player.position().add(4,0,2),100).accepted(),"Temporary unit recursively summoned");
            battle.close("test");h.assertTrue(battle.summons.isEmpty() && units.stream().allMatch(e->e.isRemoved() && !BattleManager.locked(e)),"Battle close retained units");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void summonGlobalLimitAndDeathCleanup(GameTestHelper h){
        var player=player(h);var enemy=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var extra=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,4));
        var battle=new BattleSession(player,enemy,definitions("ground"),player);
        try{
            battle.add(extra);
            for(var offset:List.of(new Vec3(2,0,0),new Vec3(2,0,2),new Vec3(2,0,4),new Vec3(4,0,0)))
                h.assertTrue(summonForTest(battle,player,player.position().add(offset),300).accepted(),"Friendly fixture failed");
            for(var offset:List.of(new Vec3(-2,0,0),new Vec3(-2,0,-2),new Vec3(0,0,-2),new Vec3(-4,0,0)))
                h.assertTrue(summonForTest(battle,enemy,enemy.position().add(offset),300).accepted(),"Hostile fixture failed");
            h.assertTrue(battle.summons.size()==8 && !summonForTest(battle,extra,extra.position().add(-2,0,0),300).accepted(),"Global limit bypassed by third caster");
            var victim=battle.summons.values().iterator().next().entity();
            BattleManager.authorized(()->victim.hurt(player.damageSources().playerAttack(player),1000));battle.prune();
            h.assertTrue(victim.isRemoved() && !BattleManager.locked(victim) && battle.summons.size()==7,"Dead summon retained slot");
            h.assertTrue(summonForTest(battle,extra,extra.position().add(-2,0,0),300).accepted(),"Death did not release global capacity");
        }finally{battle.close("test");cleanup(player);enemy.discard();extra.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void summonCancelledSpawnAndNoLootPolicy(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,exampleDefinitions(h,"summon_actions",2),player);
        var captured=new java.util.concurrent.atomic.AtomicReference<net.minecraft.world.entity.Entity>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener=event->{if(BattleSummons.temporary(event.getEntity())){captured.set(event.getEntity());event.setCanceled(true);}};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try{
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET));player.experienceLevel=3;
            battle.useGrant(player,"summon_example:husk",player.getId());
            h.assertTrue(captured.get()!=null && captured.get().isRemoved() && battle.summons.isEmpty() && player.experienceLevel==2 && !battle.budget.canAct(),"Spawn cancellation leaked entity or refunded committed fees");
            var unit=(net.minecraft.world.entity.LivingEntity)captured.get();
            var drops=new net.neoforged.neoforge.event.entity.living.LivingDropsEvent(unit,unit.damageSources().generic(),new java.util.ArrayList<>(),true);
            BattleManager.temporarySummonDrops(drops);h.assertTrue(drops.isCanceled(),"Temporary unit drops not blocked");
            var xp=new net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent(unit,player,5);BattleManager.temporarySummonExperience(xp);h.assertTrue(xp.getDroppedExperience()==0,"Temporary XP not blocked");
            var loaded=new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(unit,h.getLevel(),true);BattleManager.loadedTemporarySummon(loaded);h.assertTrue(loaded.isCanceled(),"Saved orphan allowed to load");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void delayedStrikeExamplePrepaymentAndRecheck(GameTestHelper h){
        for(int scenario=0;scenario<4;scenario++){
            var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
            var battle=new BattleSession(player,mob,exampleDefinitions(h,"delayed_actions",2),player);
            try{
                player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));player.experienceLevel=3;
                float hp=mob.getHealth();double movement=battle.budget.remaining();
                battle.useGrant(player,"timeline_example:strike",mob.getId());
                h.assertTrue(battle.scheduled.size()==1 && player.experienceLevel==2 && battle.budget.mainActions()==0 && mob.getHealth()==hp,"Delayed strike prepayment scenario="+scenario+" queue="+battle.scheduled.size()+" xp="+player.experienceLevel+" main="+battle.budget.mainActions()+" hp="+mob.getHealth()+" previous="+hp+" closed="+battle.closed);
                h.assertTrue(!battle.ready(player,"timeline_example:strike") && battle.budget.remaining()==movement,"Delayed strike missed cooldown or spent movement");
                if(scenario==1)battle.place(mob,player.position().add(10,0,0));
                if(scenario==2){var wall=BlockPos.containing(player.position().add(2,0,0));for(int y=0;y<3;y++)h.getLevel().setBlockAndUpdate(wall.above(y),Blocks.STONE.defaultBlockState());}
                if(scenario==3){battle.remove(mob,"target leaves");h.assertTrue(battle.scheduled.isEmpty(),"Target departure retained queue slot");}
                battle.runScheduledBeforeNextTurn();
                h.assertTrue(scenario==0?mob.getHealth()<hp:mob.getHealth()==hp,"Delayed strike target recheck failed: "+scenario);
                h.assertTrue(player.experienceLevel==2 && battle.scheduled.isEmpty(),"Continuation charged twice or remained pending");
            }finally{battle.close("test");if(scenario==2){var wall=BlockPos.containing(player.position().add(2,0,0));for(int y=0;y<3;y++)h.getLevel().setBlockAndUpdate(wall.above(y),Blocks.AIR.defaultBlockState());}cleanup(player);mob.discard();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void checkedTimelineCancellationAndReadOnlyPredicate(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var saved=new java.util.concurrent.atomic.AtomicReference<CombatEffects.Context>();var ticket=new java.util.concurrent.atomic.AtomicReference<UUID>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var id=ResourceLocation.parse("mineturn_test:timeline_"+UUID.randomUUID().toString().replace("-",""));
        CombatEffects.register(id,c->{
            saved.set(c);
            ticket.set(c.battle().afterChecked(25,p->true,p->calls.addAndGet(100)));
            h.assertTrue(c.battle().cancelScheduled(ticket.get()) && !c.battle().cancelScheduled(ticket.get()),"Ticket did not cancel exactly once");
            c.battle().afterChecked(25,p->{
                boolean rejected=false;try{p.battle().hurt(p.target(),4);}catch(IllegalStateException expected){rejected=true;}
                h.assertTrue(rejected && p.battleTime()==25,"Settlement predicate was mutable or stale");return true;
            },p->calls.incrementAndGet());
            c.battle().afterChecked(25,p->false,p->calls.addAndGet(1000));
        });
        try{
            var action=new CombatData.Action("test",id.toString(),0,8,false,0,150,new com.google.gson.JsonObject());
            battle.budget.act();battle.execute(player,mob,"test:timeline",action,ItemStack.EMPTY);
            boolean rejected=false;try{saved.get().battle().cancelScheduled(ticket.get());}catch(IllegalStateException expected){rejected=true;}
            h.assertTrue(rejected && calls.get()==0,"Expired cancellation accepted or timer ran in realtime");
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(calls.get()==1 && battle.scheduled.isEmpty(),"Cancelled/rejected task executed");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void delayedStrikeQueueFullCostsNothing(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(6,1,2));
        var battle=new BattleSession(player,mob,exampleDefinitions(h,"delayed_actions",2),player);
        try{
            player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));player.experienceLevel=3;
            for(int i=0;i<128;i++)battle.scheduled.add(new BattleSession.Scheduled(25,i,battle.member(player),()->{}));
            boolean rejected=false;try{battle.useGrant(player,"timeline_example:strike",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && player.experienceLevel==3 && battle.budget.canAct() && battle.ready(player,"timeline_example:strike"),"Full timeline spent fees");
            battle.close("test");h.assertTrue(battle.scheduled.isEmpty(),"Closed battle retained delayed work");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void accessoryExampleLaserPaymentAndRevocation(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(7,1,2));
        var battle=new BattleSession(player,mob,accessoryExample(h),player);
        try{
            h.assertTrue(battle.offers(player).isEmpty(),"Unequipped accessory action shown");
            player.setItemSlot(EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE));
            player.experienceLevel=1;
            h.assertTrue(!battle.offers(player).getFirst().unavailable().isEmpty(),"Unaffordable action enabled");
            boolean rejected=false;try{battle.useGrant(player,"accessory_example:laser",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && player.experienceLevel==1 && battle.budget.canAct(),"Rejected example spent fees");
            player.experienceLevel=5;double movement=battle.budget.remaining();
            battle.useGrant(player,"accessory_example:laser",mob.getId());var shot=battle.shot;float health=mob.getHealth();
            h.assertTrue(player.experienceLevel==3 && battle.budget.mainActions()==0 && battle.budget.bonusActions()==1,"Example laser payment incorrect");
            player.setItemSlot(EquipmentSlot.CHEST,ItemStack.EMPTY);
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(mob.getHealth()==health && player.experienceLevel==3 && !battle.ready(player,"accessory_example:laser") && battle.budget.remaining()==movement,"Revoked example hit/refunded or duplicate charged");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void accessoryExampleRepulseUsesBonusAndCollision(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var battle=new BattleSession(player,mob,accessoryExample(h),player);
        try{
            player.setItemSlot(EquipmentSlot.FEET,new ItemStack(net.minecraft.world.item.Items.GOLDEN_BOOTS));player.experienceLevel=4;
            battle.place(mob,player.position().add(1,0,0));var start=mob.position();double movement=battle.budget.remaining();
            var wall=BlockPos.containing(start.add(2,0,0));for(int y=0;y<4;y++)h.getLevel().setBlockAndUpdate(wall.above(y),Blocks.STONE.defaultBlockState());
            h.assertTrue(battle.engaged(player),"Repulse fixture not engaged");
            battle.useGrant(player,"accessory_example:repulse",mob.getId());
            h.assertTrue(mob.getX()>start.x && mob.getX()<wall.getX() && mob.getDeltaMovement().lengthSqr()==0,"Repulse crossed wall or left free velocity");
            h.assertTrue(player.experienceLevel==3 && battle.budget.mainActions()==1 && battle.budget.bonusActions()==0 && battle.budget.remaining()==movement,"Repulse costs incorrect");
            battle.budget=new TurnBudget(movement);boolean rejected=false;
            try{battle.useGrant(player,"accessory_example:repulse",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && player.experienceLevel==3 && battle.budget.bonusActions()==1,"Cooldown repeat paid twice");
            advanceShieldTestClock(battle,150);
            h.assertTrue(battle.ready(player,"accessory_example:repulse"),"Repulse AV cooldown did not expire");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void repulseRejectsInvalidParameters(GameTestHelper h){
        var effect=CombatEffects.get("mineturn:repulse");
        for(String parameters:List.of("{\"distance\":-1}","{\"distance\":9}","{\"rise\":3}","{\"distance\":\"3\"}")){
            boolean rejected=false;try{effect.validateDefinition(new CombatData.Action("test","mineturn:repulse",0,3,false,0,150,JsonParser.parseString(parameters).getAsJsonObject()));}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"Invalid repulse accepted: "+parameters);
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void itemAdapterExampleParsesWithRealDefinitions(GameTestHelper h){
        var manager=h.getLevel().getServer().getResourceManager();var files=new java.util.HashMap<ResourceLocation,com.google.gson.JsonElement>();
        try{
            for(var entry:manager.listResources("mineturn",id->id.getPath().endsWith(".json")).entrySet()){
                var id=entry.getKey();String path=id.getPath();
                try(var reader=entry.getValue().openAsReader()){files.put(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),path.substring(9,path.length()-5)),com.google.gson.JsonParser.parseReader(reader));}
            }
            int count=0;
            for(var entry:manager.listResources("fixtures/item_adapters",id->id.getPath().endsWith(".json")).entrySet()){
                String path=entry.getKey().getPath().substring("fixtures/item_adapters/".length());int split=path.indexOf('/');String namespace=path.substring(0,split);String definition=path.substring(split+1+"mineturn/".length(),path.length()-5);
                try(var reader=entry.getValue().openAsReader()){files.put(ResourceLocation.fromNamespaceAndPath(namespace,definition),com.google.gson.JsonParser.parseReader(reader));}count++;
            }
            h.assertTrue(count==10,"Example fixture missing files");var snapshot=CombatData.parse(files);
            for(var mapping:java.util.Map.of("iron_sword","weapon","stick","weapon","apple","food","splash_potion","potion","snowball","projectile","ender_pearl","teleport").entrySet())
                h.assertTrue(snapshot.items().get("minecraft:"+mapping.getKey()).actions().contains("item_example:"+mapping.getValue()),"Example override missing: "+mapping.getKey());
            h.assertTrue(snapshot.items().get("minecraft:diamond_sword").actions().contains("mineturn:melee"),"Example unintentionally changed other swords");
            h.assertTrue(snapshot.items().get("minecraft:splash_potion").actions().contains("mineturn:throw_splash_ground"),"Example lost potion ground action");
            h.assertTrue(snapshot.actions().get("item_example:food").consume()==1 && snapshot.actions().get("item_example:teleport").effect().equals("mineturn:ender_pearl"),"Example action contract incorrect");
        }catch(java.io.IOException error){throw new RuntimeException(error);}h.succeed();
    }
    @GameTest(template="empty")
    public static void piercingVisualUsesResolvedTargetsAndWalls(GameTestHelper h){
        var player=player(h);var near=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(5,1,2));var far=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,2));var battle=new BattleSession(player,near,definitions("ground"),player);
        try{
            var bow=new ItemStack(net.minecraft.world.item.Items.CROSSBOW);bow.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,net.minecraft.world.item.component.ChargedProjectiles.of(new ItemStack(net.minecraft.world.item.Items.ARROW)));
            var context=new CombatEffects.Context(player,near,bow,new CombatData.Action("visual","mineturn:projectile",0,16,false,0,0,new com.google.gson.JsonObject()),0,null);
            var single=com.matuvent.mineturn.api.CombatVisuals.arrowEnds(context,java.util.Set.of(near));
            var piercing=com.matuvent.mineturn.api.CombatVisuals.arrowEnds(context,java.util.Set.of(near,far));
            h.assertTrue(single.size()==1 && piercing.size()==1 && piercing.getFirst().x>far.getX() && single.getFirst().x<far.getX(),"Piercing model duplicated or ignored resolved targets");
            var wall=BlockPos.containing(near.position().add(2,1,0));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
            var blocked=com.matuvent.mineturn.api.CombatVisuals.arrowEnds(context,java.util.Set.of(near,far));h.assertTrue(blocked.getFirst().x<=wall.getX()+0.01,"Arrow visual passed wall");
            player.getRandom().setSeed(51);long expected=player.getRandom().nextLong();player.getRandom().setSeed(51);com.matuvent.mineturn.api.CombatVisuals.arrowEnds(context,java.util.Set.of(near,far));h.assertTrue(player.getRandom().nextLong()==expected,"Visual consumed gameplay RNG");
            bow.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,net.minecraft.world.item.component.ChargedProjectiles.of(java.util.List.of(new ItemStack(net.minecraft.world.item.Items.ARROW),new ItemStack(net.minecraft.world.item.Items.ARROW),new ItemStack(net.minecraft.world.item.Items.ARROW))));
            h.assertTrue(com.matuvent.mineturn.api.CombatVisuals.arrowEnds(context,java.util.Set.of(near)).size()==3,"Multishot model lanes lost");
        }finally{battle.close("test");cleanup(player);near.discard();far.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void projectileVisualCodecPreservesFireworkComponents(GameTestHelper h){
        var item=new ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET,12);
        var explosion=new net.minecraft.world.item.component.FireworkExplosion(net.minecraft.world.item.component.FireworkExplosion.Shape.CREEPER,
            it.unimi.dsi.fastutil.ints.IntList.of(0xff0000,0x00ff00),it.unimi.dsi.fastutil.ints.IntList.of(0x0000ff),true,true);
        item.set(net.minecraft.core.component.DataComponents.FIREWORKS,new net.minecraft.world.item.component.Fireworks(2,List.of(explosion)));
        var packet=new com.matuvent.mineturn.network.ProjectileVisual(h.getLevel().dimension().location(),new Vec3(1,2,3),new Vec3(7,2,3),item,12,true,true);
        item.shrink(1);h.assertTrue(packet.item().getCount()==1 && item.getCount()==11,"Visual packet shared live item");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
        try{
            com.matuvent.mineturn.network.ProjectileVisual.CODEC.encode(buffer,packet);var decoded=com.matuvent.mineturn.network.ProjectileVisual.CODEC.decode(buffer);
            h.assertTrue(decoded.valid() && decoded.firework() && decoded.hit() && decoded.from().equals(packet.from()) && decoded.to().equals(packet.to()) && decoded.ticks()==12
                && ItemStack.isSameItemSameComponents(decoded.item(),packet.item()),"Visual codec lost trajectory or fireworks components");
        }finally{buffer.release();}h.succeed();
    }
    @GameTest(template="empty")
    public static void projectileVisualBoundsAndSelection(GameTestHelper h){
        var dimension=h.getLevel().dimension().location();var arrow=new ItemStack(net.minecraft.world.item.Items.TIPPED_ARROW);
        arrow.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.POISON));
        var visual=com.matuvent.mineturn.api.CombatVisuals.projectile("mineturn:projectile",new ItemStack(net.minecraft.world.item.Items.BOW),arrow);
        h.assertTrue(ItemStack.isSameItemSameComponents(visual,arrow),"Visual lost actual tipped-arrow components");
        h.assertTrue(com.matuvent.mineturn.api.CombatVisuals.projectile("mineturn:melee",arrow,arrow).isEmpty(),"Melee spawned projectile visual");
        for(var to:List.of(new Vec3(Double.NaN,0,0),new Vec3(300,0,0)))h.assertTrue(!new com.matuvent.mineturn.network.ProjectileVisual(dimension,Vec3.ZERO,to,arrow,10,true,false).valid(),"Unbounded visual accepted");
        h.assertTrue(!new com.matuvent.mineturn.network.ProjectileVisual(dimension,Vec3.ZERO,new Vec3(2,0,0),arrow,1000,true,false).valid(),"Unbounded visual duration accepted");h.succeed();
    }
    @GameTest(template="empty")
    public static void playerSnowballNativeHitAndSinglePayment(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.BLAZE,new BlockPos(5,1,2));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var snow=new ItemStack(net.minecraft.world.item.Items.SNOWBALL,3);player.getInventory().setItem(4,snow);float hp=mob.getHealth();
            battle.use(player,4,"mineturn:throw_snowball",mob.getId());var shot=battle.shot;
            h.assertTrue(snow.getCount()==2 && !battle.budget.canAct() && mob.getHealth()==hp,"Snowball failed to pay once at launch");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==hp-3 && !mob.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) && snow.getCount()==2,"Snowball native blaze damage or consumption incorrect");
            battle.submitShot(player,shot.token,shot.startNanos);h.assertTrue(mob.getHealth()==hp-3,"Snowball duplicate submission hit twice");
            battle.budget=new TurnBudget(4);battle.use(player,4,"mineturn:throw_snowball",mob.getId());shot=battle.shot;battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(snow.getCount()==1 && mob.getHealth()==hp-3,"Missed snowball refunded or damaged target");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerSnowballOrdinaryTargetAndInfiniteMaterials(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(5,1,2));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var snow=new ItemStack(net.minecraft.world.item.Items.SNOWBALL,2);player.getInventory().setItem(4,snow);player.getAbilities().instabuild=true;float hp=mob.getHealth();
            battle.use(player,4,"mineturn:throw_snowball",mob.getId());var shot=battle.shot;battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(mob.getHealth()==hp && !mob.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) && snow.getCount()==2 && !battle.budget.canAct(),"Ordinary snowball invented damage/debuff or infinite-material costs wrong");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void offhandShieldPreservesHandsAndBlocksOnce(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                var sword=new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);var shield=new ItemStack(net.minecraft.world.item.Items.SHIELD);
                player.getInventory().selected=2;player.getInventory().setItem(2,sword);player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,shield);
                var snapshot=battle.snapshot(player);h.assertTrue(snapshot.slots().size()==10 && snapshot.slots().get(9).actions().getFirst().unavailable().isEmpty(),"Missing offhand action metadata");
                var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());try{com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buffer,snapshot);h.assertTrue(snapshot.equals(com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buffer)),"Offhand metadata roundtrip failed");}finally{buffer.release();}
                battle.use(player,40,"mineturn:shield_guard",player.getId());
                h.assertTrue(player.isBlocking() && player.getUsedItemHand()==net.minecraft.world.InteractionHand.OFF_HAND && player.getMainHandItem()==sword && player.getOffhandItem()==shield && player.getInventory().selected==2 && !battle.budget.canAct(),"Offhand guard swapped equipment or ignored cost");
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),4);});
                h.assertTrue(player.getHealth()==20 && !battle.member(player).guarding && !player.isUsingItem(),"Offhand guard did not block once");
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),4);});
                h.assertTrue(player.getHealth()==16 && player.getMainHandItem()==sword,"Offhand guard blocked twice or changed main hand");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void offhandRejectsOtherItemsAndCooldown(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var sword=new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,sword);
            boolean rejected=false;try{battle.use(player,40,"mineturn:melee",mob.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && battle.budget.canAct() && battle.snapshot(player).slots().get(9).actions().isEmpty(),"Offhand non-shield action accepted");
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new ItemStack(net.minecraft.world.item.Items.SHIELD));battle.member(player).shieldDisabledUntil=battle.clock.time()+100;
            rejected=false;try{battle.use(player,40,"mineturn:shield_guard",player.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && battle.budget.canAct() && !battle.snapshot(player).slots().get(9).actions().getFirst().unavailable().isEmpty(),"Offhand bypassed shield cooldown or spent action on failure");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void soulSpeedTerrainRefreshAndNoRepeatedWear(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var boots=new ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS);boots.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SOUL_SPEED),3);
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,boots);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
            var floor=player.blockPosition().below();h.getLevel().setBlockAndUpdate(floor,Blocks.SOUL_SOIL.defaultBlockState());player.setOnGround(true);BattleTerrainEnchantments.refresh(player);
            h.assertTrue(Math.abs(battle.movement(player)-6.46)<0.001,"Native soul speed III attribute not refreshed");
            int wear=boots.getDamageValue();double remaining=battle.budget.remaining();for(int i=0;i<300;i++)BattleTerrainEnchantments.refresh(player);
            h.assertTrue(boots.getDamageValue()==wear && battle.budget.remaining()==remaining,"Repeated terrain checks wore boots or refilled budget");
            h.getLevel().setBlockAndUpdate(floor,Blocks.STONE.defaultBlockState());BattleTerrainEnchantments.refresh(player);
            h.assertTrue(Math.abs(battle.movement(player)-4)<0.001,"Soul speed persisted after terrain removal");
            h.getLevel().setBlockAndUpdate(floor,Blocks.SOUL_SOIL.defaultBlockState());BattleTerrainEnchantments.refresh(player);
            h.assertTrue(Math.abs(battle.movement(player)-6.46)<0.001,"Soul speed failed to reapply");
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,ItemStack.EMPTY);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,boots);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
            h.assertTrue(Math.abs(battle.movement(player)-6.46)<0.001,"Reequipped boots suppressed by stale terrain cache");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void frostWalkerBattleGateAndSwiftSneakScope(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
                var boots=new ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS);boots.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.FROST_WALKER),2);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,boots);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
                var water=player.blockPosition().below().east();h.getLevel().setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());player.setOnGround(true);
                net.minecraft.world.item.enchantment.EnchantmentHelper.runLocationChangedEffects(h.getLevel(),player);
                h.assertTrue(h.getLevel().getBlockState(water).is(Blocks.WATER),"Frost walker modified blocks in battle");
                float hp=player.getHealth();BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().hotFloor(),1);});h.assertTrue(player.getHealth()==hp,"Frost walker native hot-floor immunity lost");
                var legs=new ItemStack(net.minecraft.world.item.Items.DIAMOND_LEGGINGS);legs.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SWIFT_SNEAK),3);player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,legs);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
                h.assertTrue(Math.abs(battle.movement(player)-4)<0.001 && player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED)>0.7,"Swift sneak altered ordinary budget or lost native attribute");
                battle.close("test");player.setOnGround(true);net.minecraft.world.item.enchantment.EnchantmentHelper.runLocationChangedEffects(h.getLevel(),player);
                h.assertTrue(h.getLevel().getBlockState(water).is(Blocks.FROSTED_ICE),"Frost walker failed to resume outside battle");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void depthStriderBudgetUsesNativeAttribute(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var feet=player.blockPosition();double base=battle.movement(player),remaining=battle.budget.remaining();
            for(int level=0;level<=3;level++){
                var boots=new ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS);
                if(level>0)boots.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.DEPTH_STRIDER),level);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,boots);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
                h.getLevel().setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());h.assertTrue(Math.abs(battle.movement(player)-base)<1e-5,"Depth strider changed dry movement");
                h.getLevel().setBlockAndUpdate(feet,Blocks.WATER.defaultBlockState());h.assertTrue(Math.abs(battle.movement(player)-base*(1+level/3.0))<1e-5,"Depth strider ignored native efficiency");
                h.assertTrue(battle.budget.remaining()==remaining,"Water/equipment change refilled current budget");
            }
            var efficiency=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.WATER_MOVEMENT_EFFICIENCY);
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,ItemStack.EMPTY);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();efficiency.setBaseValue(0.5);
            h.assertTrue(Math.abs(battle.movement(player)-base*1.5)<1e-5,"Custom efficiency attribute ignored");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void armorThornsUsesNativeRetaliation(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(3,1,2));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                var chest=new ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);chest.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.THORNS),3);
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,chest);((com.matuvent.mineturn.mixin.StatusAccess)mob).mineturn$equipment();
                var sword=new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);player.getInventory().setItem(4,sword);var start=player.position();boolean retaliated=false;
                mob.getRandom().setSeed(42);player.getRandom().setSeed(42);
                for(int i=0;i<32 && !retaliated;i++){
                    mob.setHealth(mob.getMaxHealth());player.setHealth(20);player.invulnerableTime=0;player.setDeltaMovement(Vec3.ZERO);battle.budget=new TurnBudget(4);
                    battle.use(player,4,"mineturn:melee",mob.getId());retaliated=player.getHealth()<20;
                }
                h.assertTrue(retaliated && player.getHealth()>=15 && chest.getDamageValue()>0,"Native thorns damage/durability absent");
                h.assertTrue(player.position().equals(start) && player.getDeltaMovement().lengthSqr()<1e-6,"Thorns added displacement");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void turtleHelmetUsesAvAndNativeWaterCheck(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.TURTLE_HELMET));
            var equipment=(com.matuvent.mineturn.mixin.PlayerEquipmentAccess)player;var breathing=net.minecraft.world.effect.MobEffects.WATER_BREATHING;
            equipment.mineturn$turtleHelmet();h.assertTrue(!player.hasEffect(breathing),"Real tick granted turtle effect during frozen time");
            BattleStatus.tick(battle.member(player));h.assertTrue(player.getEffect(breathing).getDuration()==200,"AV turtle refresh missing");
            var feet=player.blockPosition();h.getLevel().setBlockAndUpdate(feet,Blocks.WATER.defaultBlockState());h.getLevel().setBlockAndUpdate(feet.above(),Blocks.WATER.defaultBlockState());
            for(int i=0;i<20;i++)BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getEffect(breathing).getDuration()==180 && player.getAirSupply()==300,"Submerged turtle effect refreshed or failed to protect air");
            for(int i=0;i<20;i++)equipment.mineturn$turtleHelmet();h.assertTrue(player.getEffect(breathing).getDuration()==180,"Real time altered turtle duration");
            h.getLevel().setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());BattleStatus.tick(battle.member(player));
            h.assertTrue(player.getEffect(breathing).getDuration()==200,"Surfacing did not refresh turtle effect");
            battle.close("test");player.removeEffect(breathing);equipment.mineturn$turtleHelmet();h.assertTrue(player.getEffect(breathing).getDuration()==200,"Native turtle refresh did not resume");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void passiveArmorProtectionAndFeatherFalling(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                var chest=new ItemStack(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE);var boots=new ItemStack(net.minecraft.world.item.Items.NETHERITE_BOOTS);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(net.minecraft.world.item.Items.NETHERITE_HELMET));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,chest);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,new ItemStack(net.minecraft.world.item.Items.NETHERITE_LEGGINGS));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,boots);
                ((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),10);});
                h.assertTrue(Math.abs(player.getHealth()-17.2)<0.001 && chest.getDamageValue()==2,"Native armor/toughness/durability changed");
                var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
                chest.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.PROTECTION),4);player.setHealth(20);
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),10);});
                h.assertTrue(Math.abs(player.getHealth()-(20-2.8*0.84))<0.001,"Native protection mitigation incorrect");
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,ItemStack.EMPTY);boots.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.FEATHER_FALLING),4);player.setHealth(20);
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().fall(),12);});
                h.assertTrue(Math.abs(player.getHealth()-(20-12*0.52))<0.001,"Native feather falling mitigation incorrect");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void leatherFreezeAndRespirationNativeRules(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                var feet=player.blockPosition();h.getLevel().setBlockAndUpdate(feet,Blocks.POWDER_SNOW.defaultBlockState());
                BattleManager.authorized(()->player.setTicksFrozen(player.getTicksRequiredToFreeze()));battle.member(player).statusTicks=39;BattleStatus.tick(battle.member(player));
                h.assertTrue(player.getHealth()==19,"Powder snow freeze damage absent");
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,new ItemStack(net.minecraft.world.item.Items.LEATHER_BOOTS));
                for(int i=0;i<40;i++)BattleStatus.tick(battle.member(player));h.assertTrue(player.getHealth()==19 && !player.canFreeze(),"Leather freeze protection missing");
                h.getLevel().setBlockAndUpdate(feet,Blocks.WATER.defaultBlockState());h.getLevel().setBlockAndUpdate(feet.above(),Blocks.WATER.defaultBlockState());
                var helmet=new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET);helmet.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.RESPIRATION),3);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,helmet);((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$equipment();
                player.getRandom().setSeed(42);int expected=300;for(int i=0;i<40;i++)expected=((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$decreaseAir(expected);
                player.getRandom().setSeed(42);BattleManager.authorized(()->player.setAirSupply(300));for(int i=0;i<40;i++)BattleStatus.tick(battle.member(player));
                h.assertTrue(player.getAirSupply()==expected && expected>260,"Respiration did not use native oxygen rule");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=180)
    public static void totemHandsPreserveBattleAndAvEffects(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                for(var hand:net.minecraft.world.InteractionHand.values()){
                    player.removeAllEffects();player.setAbsorptionAmount(0);player.setHealth(2);
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,ItemStack.EMPTY);
                    player.setItemInHand(hand,new ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
                    player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,200));
                    BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),100);});battle.prune();
                    h.assertTrue(player.isAlive() && player.getHealth()==1 && player.getItemInHand(hand).isEmpty(),"Totem did not protect/consume in "+hand);
                    h.assertTrue(!battle.closed && battle.members.containsKey(player.getUUID()) && BattleManager.ACTIVE.get(player.getUUID())==battle,"Totem revival removed participant");
                    h.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && player.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION).getDuration()==900
                        && player.getEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE).getDuration()==800 && player.getEffect(net.minecraft.world.effect.MobEffects.ABSORPTION).getDuration()==100,"Native revival effects changed");
                }
                h.runAfterDelay(20,()->{
                    try{
                        h.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION).getDuration()==900,"Real ticks advanced revival effects");
                        battle.next();
                        h.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION).getDuration()==880 && player.getEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE).getDuration()==780,"AV did not advance revival effects");
                        battle.close("test");((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$tickEffects();
                        h.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION).getDuration()==879,"Revival effects frozen after exit");
                    }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
                });
            }catch(Throwable error){battle.close("test");cleanup(player);mob.discard();throw error;}
        });
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void totemInventoryDoesNotProtect(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            try{
                var totem=new ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);player.getInventory().setItem(12,totem);player.setHealth(2);
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),100);});battle.prune();
                h.assertTrue(!player.isAlive() && totem.getCount()==1 && !BattleManager.ACTIVE.containsKey(player.getUUID()),"Backpack totem protected or dead player remained active");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void totemCancellationUsesNativeEvent(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        h.runAfterDelay(70,()->{
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent> cancel=e->{if(e.getEntity()==player)e.setCanceled(true);};
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
            try{
                var totem=new ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,totem);player.setHealth(2);
                BattleManager.authorized(()->{player.invulnerableTime=0;player.hurt(player.damageSources().mobAttack(mob),100);});battle.prune();
                h.assertTrue(!player.isAlive() && totem.getCount()==1,"Native totem cancellation ignored or consumed item");
            }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void maceBattleUsesOrdinaryMelee(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(3,1,2));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var mace=new ItemStack(net.minecraft.world.item.Items.MACE);player.getInventory().setItem(4,mace);player.fallDistance=30;
            h.assertTrue(CombatData.current.items().get("minecraft:mace").enabled(),"Mace ordinary melee not registered");
            float before=mob.getHealth();battle.use(player,4,"mineturn:melee",mob.getId());
            h.assertTrue(Math.abs(before-mob.getHealth()-6)<0.001 && player.fallDistance==0 && mace.getDamageValue()==1,"Battle mace smashed instead of native ordinary damage/durability");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void teleportApiScopeAndRevalidation(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(6,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var saved=new java.util.concurrent.atomic.AtomicReference<CombatEffects.Context>();
        var rules=new com.matuvent.mineturn.api.CombatTeleport.Rules(8,false,false);
        var goal=mob.position().add(2,0,0);var id=ResourceLocation.parse("mineturn_test:teleport_"+UUID.randomUUID().toString().replace("-",""));
        var blocked=new java.util.concurrent.atomic.AtomicBoolean();
        CombatEffects.register(id,c->{saved.set(c);var result=c.battle().teleportTo(c.target(),goal,rules);h.assertTrue(result.success()!=blocked.get(),"Teleport execution did not revalidate");});
        var action=new CombatData.Action("test",id.toString(),0,16,false,0,0,new com.google.gson.JsonObject());
        try{
            var preview=battle.effectContext(player,mob,ItemStack.EMPTY,action,false);var origin=mob.position();
            h.assertTrue(preview.battle().previewTeleport(mob,goal,rules).success() && mob.position().equals(origin),"Preview moved entity or rejected valid point");
            boolean rejected=false;try{preview.battle().teleportTo(mob,goal,rules);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Preview could execute teleport");
            h.getLevel().setBlockAndUpdate(BlockPos.containing(goal),Blocks.STONE.defaultBlockState());blocked.set(true);
            battle.execute(player,mob,id.toString(),action,ItemStack.EMPTY);h.assertTrue(mob.position().equals(origin),"Stale preview penetrated new block");
            h.getLevel().setBlockAndUpdate(BlockPos.containing(goal),Blocks.AIR.defaultBlockState());blocked.set(false);mob.fallDistance=20;double movement=battle.budget.remaining();
            battle.execute(player,mob,id.toString(),action,ItemStack.EMPTY);
            h.assertTrue(mob.position().distanceToSqr(goal)<0.001 && mob.fallDistance==0 && battle.budget.remaining()==movement,"Teleport moved incorrectly or charged movement");
            rejected=false;try{saved.get().battle().teleportTo(mob,origin,rules);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"Expired callback teleported");
            battle.close("test");h.assertTrue(!preview.battle().previewTeleport(mob,origin,rules).success(),"Closed battle preview accepted");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void teleportApiRulesAndPassengers(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));var outsider=h.spawnWithNoFreeWill(EntityType.COW,new BlockPos(8,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var action=CombatData.current.actions().get("mineturn:chorus_fruit");var api=battle.effectContext(player,player,ItemStack.EMPTY,action,false).battle();
            var rules=new com.matuvent.mineturn.api.CombatTeleport.Rules(8,false,false);var start=player.position();var goal=start.add(3,0,0);
            h.assertTrue(!api.previewTeleport(outsider,goal,rules).success(),"Outsider accepted");
            h.assertTrue(!api.previewTeleport(player,new Vec3(Double.NaN,0,0),rules).success(),"Nonfinite destination accepted");
            h.assertTrue(!api.previewTeleport(player,start.add(9,0,0),rules).success(),"Range ignored");
            h.getLevel().setBlockAndUpdate(BlockPos.containing(start.add(1,1,0)),Blocks.STONE.defaultBlockState());
            h.assertTrue(api.previewTeleport(player,goal,rules).success() && !api.previewTeleport(player,goal,new com.matuvent.mineturn.api.CombatTeleport.Rules(8,true,false)).success(),"Sight policy ignored");
            h.getLevel().setBlockAndUpdate(BlockPos.containing(goal),Blocks.WATER.defaultBlockState());
            h.assertTrue(!api.previewTeleport(player,goal,rules).success() && api.previewTeleport(player,goal,new com.matuvent.mineturn.api.CombatTeleport.Rules(8,false,true)).success(),"Water policy ignored");
            player.startRiding(outsider,true);h.assertTrue(!api.previewTeleport(player,goal,rules).success(),"Passenger accepted");player.stopRiding();
            for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,33}){
                boolean rejected=false;try{new com.matuvent.mineturn.api.CombatTeleport.Rules(invalid,false,false);}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"Bad rules accepted");
            }
        }finally{player.stopRiding();battle.close("test");cleanup(player);mob.discard();outsider.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void datapackTeleportOffsetValidation(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var parameters=new com.google.gson.JsonObject();parameters.addProperty("dx",3);parameters.addProperty("require_sight",false);
            var action=new CombatData.Action("blink","mineturn:teleport_offset",0,8,true,0,100,parameters);var effect=CombatEffects.get(action.effect());effect.validateDefinition(action);
            var start=player.position();h.assertTrue(effect.validate(battle.effectContext(player,player,ItemStack.EMPTY,action,false))==null,"Datapack blink rejected");
            battle.budget.act();battle.execute(player,player,"mineturn_test:blink",action,ItemStack.EMPTY);
            h.assertTrue(player.position().distanceToSqr(start.add(3,0,0))<0.001 && !battle.budget.canAct(),"Datapack blink execution incorrect");
            parameters.addProperty("dx",Double.NaN);var invalid=new CombatData.Action("bad","mineturn:teleport_offset",0,8,true,0,100,parameters);boolean rejected=false;try{effect.validateDefinition(invalid);}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"Nonfinite datapack offset accepted");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void chorusFoodTeleportAndCooldown(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);var start=player.position();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityTeleportEvent.ChorusFruit> listener=e->{
            if(e.getEntity()==player){e.setTargetX(start.x+3);e.setTargetY(start.y+2);e.setTargetZ(start.z);}
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try{
            var fruit=new ItemStack(net.minecraft.world.item.Items.CHORUS_FRUIT,2);player.getInventory().setItem(4,fruit);
            player.getFoodData().setFoodLevel(10);player.fallDistance=15;double movement=battle.budget.remaining();
            battle.use(player,4,"mineturn:chorus_fruit",player.getId());
            h.assertTrue(player.position().distanceTo(start.add(3,0,0))<0.01 && player.fallDistance==0,"Chorus destination or fall reset incorrect");
            h.assertTrue(fruit.getCount()==1 && player.getFoodData().getFoodLevel()==14 && !battle.budget.canAct() && battle.budget.remaining()==movement,"Chorus native food/action costs incorrect");
            battle.budget=new TurnBudget(4);boolean rejected=false;
            try{battle.use(player,4,"mineturn:chorus_fruit",player.getId());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && fruit.getCount()==1,"Chorus cooldown bypassed");
            advanceShieldTestClock(battle,50);BattleTeleport.clock(battle,battle.member(player));
            var access=(com.matuvent.mineturn.api.RemainingItemCooldown)player.getCooldowns();
            h.assertTrue(access.mineturn$remaining(net.minecraft.world.item.Items.CHORUS_FRUIT)==10,"Chorus AV conversion incorrect");
            battle.close("test");player.getCooldowns().tick();h.assertTrue(access.mineturn$remaining(net.minecraft.world.item.Items.CHORUS_FRUIT)==9,"Chorus exit cooldown frozen");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void chorusCancelledAndInvalidStayPaid(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);var start=player.position();var calls=new java.util.concurrent.atomic.AtomicInteger();var cancel=new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityTeleportEvent.ChorusFruit> listener=e->{
            if(e.getEntity()==player){calls.incrementAndGet();if(cancel.get())e.setCanceled(true);else e.setTargetX(Double.NaN);}
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try{
            var fruit=new ItemStack(net.minecraft.world.item.Items.CHORUS_FRUIT,3);player.getInventory().setItem(4,fruit);player.getFoodData().setFoodLevel(20);
            battle.use(player,4,"mineturn:chorus_fruit",player.getId());
            h.assertTrue(calls.get()==1 && fruit.getCount()==2 && !battle.budget.canAct() && player.position().equals(start),"Cancelled chorus payment/full hunger invalid");
            advanceShieldTestClock(battle,100);BattleTeleport.clock(battle,battle.member(player));battle.budget=new TurnBudget(4);cancel.set(false);calls.set(0);
            battle.use(player,4,"mineturn:chorus_fruit",player.getId());
            h.assertTrue(calls.get()==16 && fruit.getCount()==1 && !battle.budget.canAct() && player.position().equals(start),"Invalid chorus attempts not bounded or not paid");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void chorusNativeEntryAndSafeDestinations(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        player.getCooldowns().addCooldown(net.minecraft.world.item.Items.CHORUS_FRUIT,17);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            h.assertTrue(battle.member(player).chorusUntil-battle.clock.time()==85,"Chorus native cooldown lost");
            for(int i=0;i<30;i++){player.getCooldowns().tick();BattleTeleport.clock(battle,battle.member(player));}
            h.assertTrue(((com.matuvent.mineturn.api.RemainingItemCooldown)player.getCooldowns()).mineturn$remaining(net.minecraft.world.item.Items.CHORUS_FRUIT)==17,"Real time shortened chorus cooldown");
            var start=player.position();var goal=start.add(3,2,0);
            h.assertTrue(BattleTeleport.chorusPoint(battle,player,goal,8)!=null,"Valid chorus floor rejected");
            h.getLevel().setBlockAndUpdate(BlockPos.containing(start.add(3,0,0)),Blocks.WATER.defaultBlockState());
            h.assertTrue(BattleTeleport.chorusPoint(battle,player,goal,8)==null,"Chorus accepted water");
            h.assertTrue(BattleTeleport.chorusPoint(battle,player,start.add(30,0,0),8)==null,"Chorus escaped battle");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void pearlPositionValidationAndMetadata(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var pearls=new ItemStack(net.minecraft.world.item.Items.ENDER_PEARL,3);player.getInventory().setItem(4,pearls);
            var start=player.position();var goal=start.add(3,0,0);
            battle.validatePotionPoint(player,4,"mineturn:ender_pearl",goal);
            for(var point:List.of(new Vec3(Double.NaN,0,0),start.add(32,0,0),start.add(2,3,0),mob.position())){
                boolean rejected=false;try{battle.validatePotionPoint(player,4,"mineturn:ender_pearl",point);}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected,"Illegal pearl destination accepted: "+point);
            }
            h.getLevel().setBlockAndUpdate(BlockPos.containing(start.add(1,1,0)),Blocks.STONE.defaultBlockState());
            boolean rejected=false;try{battle.validatePotionPoint(player,4,"mineturn:ender_pearl",goal);}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && pearls.getCount()==3 && battle.budget.canAct() && player.position().equals(start),"Blocked preview spent resources");
            var packet=battle.snapshot(player);var entry=packet.slots().get(4).actions().getFirst();h.assertTrue(entry.teleport() && entry.ground(),"Missing teleport UI metadata");
            var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{com.matuvent.mineturn.network.BattleNetwork.State.CODEC.encode(buf,packet);h.assertTrue(packet.equals(com.matuvent.mineturn.network.BattleNetwork.State.CODEC.decode(buf)),"Teleport metadata lost in codec");}finally{buf.release();}
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void pearlTeleportDamageAndEngagement(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        h.runAfterDelay(70,()->{
            var battle=new BattleSession(player,mob,definitions("ground"),player);
            try{
                var start=player.position();battle.place(mob,start.add(1,0,0));
                var pearls=new ItemStack(net.minecraft.world.item.Items.ENDER_PEARL,2);player.getInventory().setItem(4,pearls);
                double movement=battle.budget.remaining();h.assertTrue(battle.engaged(player),"Pearl fixture not engaged");
                player.fallDistance=20;battle.usePotionPoint(player,4,"mineturn:ender_pearl",start.add(0,0,3));
                h.assertTrue(player.position().distanceTo(start.add(0,0,3))<0.01 && player.getHealth()==15 && player.fallDistance==0,"Pearl teleport or native self damage incorrect");
                h.assertTrue(pearls.getCount()==1 && !battle.budget.canAct() && battle.budget.remaining()==movement && battle.motion==null && battle.shot==null,"Pearl costs incorrect");
                battle.budget=new TurnBudget(4);boolean rejected=false;try{battle.usePotionPoint(player,4,"mineturn:ender_pearl",start);}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected && pearls.getCount()==1 && player.getHealth()==15,"Pearl cooldown allowed repeat use");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void pearlCooldownEntryAvAndExit(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        player.getCooldowns().addCooldown(net.minecraft.world.item.Items.ENDER_PEARL,37);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var access=(com.matuvent.mineturn.api.RemainingItemCooldown)player.getCooldowns();
            h.assertTrue(Math.abs(battle.member(player).pearlUntil-battle.clock.time()-185)<0.001,"Native cooldown not inherited exactly");
            for(int i=0;i<50;i++){player.getCooldowns().tick();BattleTeleport.clock(battle,battle.member(player));}
            h.assertTrue(access.mineturn$remaining(net.minecraft.world.item.Items.ENDER_PEARL)==37,"Real ticks shortened battle cooldown");
            advanceShieldTestClock(battle,50);BattleTeleport.clock(battle,battle.member(player));
            h.assertTrue(access.mineturn$remaining(net.minecraft.world.item.Items.ENDER_PEARL)==27,"AV cooldown conversion incorrect");
            battle.close("test");player.getCooldowns().tick();
            h.assertTrue(access.mineturn$remaining(net.minecraft.world.item.Items.ENDER_PEARL)==26,"Cooldown did not resume on exit");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void supportedItemCooldownsShareAvAndResumeOnExit(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var items=List.of(net.minecraft.world.item.Items.SHIELD,net.minecraft.world.item.Items.ENDER_PEARL,
                net.minecraft.world.item.Items.CHORUS_FRUIT,net.minecraft.world.item.Items.WIND_CHARGE);
        for(int i=0;i<items.size();i++)player.getCooldowns().addCooldown(items.get(i),37+i);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try{
            var access=(com.matuvent.mineturn.api.RemainingItemCooldown)player.getCooldowns();
            for(int tick=0;tick<50;tick++){player.getCooldowns().tick();BattleItemCooldowns.clock(battle,battle.member(player));}
            for(int i=0;i<items.size();i++)h.assertTrue(access.mineturn$remaining(items.get(i))==37+i,"Realtime advanced supported cooldown: "+items.get(i));
            advanceShieldTestClock(battle,50);BattleItemCooldowns.clock(battle,battle.member(player));
            for(int i=0;i<items.size();i++)h.assertTrue(access.mineturn$remaining(items.get(i))==27+i,"AV conversion mismatch: "+items.get(i));
            battle.close("test");player.getCooldowns().tick();
            for(int i=0;i<items.size();i++)h.assertTrue(access.mineturn$remaining(items.get(i))==26+i,"Exit did not restore native clock: "+items.get(i));
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void pearlEventsAndInfiniteMaterials(GameTestHelper h){
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);var mode=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityTeleportEvent.EnderPearl> listener=event->{
            if(event.getEntity()!=player)return;
            if(mode.get()==0)event.setCanceled(true);else if(mode.get()==1)event.setTargetY(Double.NaN);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try{
            var item=new ItemStack(net.minecraft.world.item.Items.ENDER_PEARL,2);player.getInventory().setItem(4,item);var start=player.position();
            for(int i=0;i<2;i++){mode.set(i);boolean rejected=false;try{battle.usePotionPoint(player,4,"mineturn:ender_pearl",start.add(2,0,0));}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected && item.getCount()==2 && battle.budget.canAct() && player.position().equals(start),"Cancelled/invalid event paid or teleported");}
            mode.set(2);player.getAbilities().instabuild=true;
            var wet=BlockPos.containing(start.add(2,0,0));h.getLevel().setBlockAndUpdate(wet,Blocks.WATER.defaultBlockState());
            battle.usePotionPoint(player,4,"mineturn:ender_pearl",start.add(2,1,0));
            h.assertTrue(player.getY()<wet.getY()+1 && item.getCount()==2 && !battle.budget.canAct(),"Pearl floated on water or infinite-material consumption incorrect");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void riptideValidationAndNativeLevels(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var weapon=riptideTrident(h,1);player.getInventory().setItem(4,weapon);var start=player.position();
            boolean rejected=false;try{battle.validatePotionPoint(player,4,"mineturn:riptide",start.add(2,0,0));}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && weapon.getDamageValue()==0 && battle.budget.canAct(),"Dry riptide spent resources");
            wetRiptidePlayer(h,player);
            for(int i=1;i<=3;i++){
                weapon=riptideTrident(h,i);player.getInventory().setItem(4,weapon);
                var action=battle.definitions.actions().get("mineturn:riptide");
                double distance=com.matuvent.mineturn.api.Riptide.distance(player,weapon,action);
                h.assertTrue(Math.abs(distance-(1.5+0.75*(i-1))*4)<1e-6,"Riptide did not use native spin strength");
                battle.validatePotionPoint(player,4,"mineturn:riptide",start.add(distance,0,0));
                rejected=false;try{battle.validatePotionPoint(player,4,"mineturn:riptide",start.add(distance,0,1));}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected,"Diagonal riptide ignored Manhattan range");
            }
            rejected=false;try{battle.validatePotionPoint(player,4,"mineturn:riptide",new Vec3(Double.NaN,0,0));}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && weapon.getDamageValue()==0 && battle.motion==null && battle.budget.canAct(),"Preview consumed resources or accepted NaN");
            var entry=battle.snapshot(player).slots().get(4).actions().stream().filter(a->a.id().equals("mineturn:riptide")).findFirst().orElseThrow();
            h.assertTrue(entry.ground() && entry.self() && entry.unavailable().isEmpty(),"Riptide position action not offered");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void riptideFreeMovementAndFirstContact(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var next=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);battle.add(next);
        try {
            var start=player.position();battle.place(mob,start.add(4,0,0));battle.place(next,start.add(5,0,0));wetRiptidePlayer(h,player);
            var weapon=riptideTrident(h,3);player.getInventory().setItem(4,weapon);battle.budget=new TurnBudget(0);
            double time=battle.clock.time();battle.usePotionPoint(player,4,"mineturn:riptide",start.add(6,0,0));
            h.assertTrue(battle.motion==null && battle.shot==null && weapon.getCount()==1 && weapon.getDamageValue()==1
                    && !battle.budget.canAct() && battle.budget.remaining()==0,"Riptide launch costs or projectile behavior incorrect");
            player.getInventory().setItem(4,new ItemStack(net.minecraft.world.item.Items.STICK));finishMovement(player);
            h.assertTrue(mob.getHealth()==16 && next.getHealth()==24,"Riptide failed first contact or hit multiple targets: "+mob.getHealth()+" / "+next.getHealth()+" gap="+BattleManager.gap(player.getBoundingBox(),mob.getBoundingBox()));
            h.assertTrue(player.getX()<mob.getX() && player.getDeltaMovement().lengthSqr()==0 && mob.getDeltaMovement().lengthSqr()==0
                    && battle.clock.time()==time,"Riptide crossed target, caused knockback or advanced AV");
            h.assertTrue(Math.abs(battle.remainingCooldown(player,"mineturn:riptide")-200)<0.001,"Riptide cooldown incorrect");finishMovement(player);
            h.assertTrue(mob.getHealth()==16,"Riptide contact repeated");
        }finally{battle.close("test");cleanup(player);mob.discard();next.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void riptideRechecksWallAndExit(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            wetRiptidePlayer(h,player);var start=player.position();var weapon=riptideTrident(h,3);player.getInventory().setItem(4,weapon);
            battle.validatePotionPoint(player,4,"mineturn:riptide",start.add(4,0,0));
            var wall=BlockPos.containing(start.add(2,0,0));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
            h.getLevel().setBlockAndUpdate(wall.above(),Blocks.STONE.defaultBlockState());
            battle.usePotionPoint(player,4,"mineturn:riptide",start.add(4,0,0));
            h.assertTrue(battle.motion==null,"Riptide still uses turn movement");
            h.assertTrue(player.getX()<wall.getX() && weapon.getDamageValue()==1 && Math.abs(battle.budget.remaining()-4)<0.001 && mob.getHealth()==24,"Dynamic wall failed or movement budget was spent: "+player.position()+" / "+wall+" / "+battle.budget.remaining());
            battle.place(player,start);wetRiptidePlayer(h,player);battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.usePotionPoint(player,4,"mineturn:riptide",start.add(0,0,3));battle.close("test");
            h.assertTrue(battle.motion==null && weapon.getDamageValue()==2 && mob.getHealth()==24,"Exit refunded riptide or left motion active");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void riptideDisplacementEscapesEngagement(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var start=player.position();battle.place(mob,start.add(1,0,0));wetRiptidePlayer(h,player);
            var weapon=riptideTrident(h,3);player.getInventory().setItem(4,weapon);
            h.assertTrue(battle.engaged(player),"Fixture must be engaged");
            double remaining=battle.budget.remaining();
            battle.usePotionPoint(player,4,"mineturn:riptide",start.add(0,0,3));
            h.assertTrue(player.position().distanceTo(start.add(0,0,3))<0.01 && !battle.budget.canAct()
                    && !battle.budget.disengaged() && battle.budget.remaining()==remaining && battle.motion==null,
                    "Skill displacement required retreat or spent normal movement");

        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void riptideDescentFallAndStaleWaterFlag(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(10,1,8));
        h.runAfterDelay(70,()->{
            var battle=new BattleSession(player,mob,definitions("ground"),player);
            try {
                var ground=player.position();battle.place(player,ground.add(0,4,0));wetRiptidePlayer(h,player);
                var weapon=riptideTrident(h,3);player.getInventory().setItem(4,weapon);
                battle.usePotionPoint(player,4,"mineturn:riptide",ground.add(1,0,0));finishMovement(player);
                h.assertTrue(player.position().distanceTo(ground.add(1,0,0))<0.01 && player.getHealth()<20,"Downward riptide skipped landing damage");
                battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
                boolean rejected=false;try{battle.usePotionPoint(player,4,"mineturn:riptide",player.position().add(1,0,0));}catch(IllegalArgumentException expected){rejected=true;}
                h.assertTrue(rejected && weapon.getDamageValue()==1 && battle.budget.canAct(),"Stale water cache allowed dry riptide");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    private static ItemStack riptideTrident(GameTestHelper h,int level) {
        var item=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
        item.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.RIPTIDE),level);return item;
    }
    private static void wetRiptidePlayer(GameTestHelper h,ServerPlayer player) {
        h.getLevel().setBlockAndUpdate(player.blockPosition(),Blocks.WATER.defaultBlockState());
        ((com.matuvent.mineturn.mixin.FluidAccess)player).mineturn$updateFluid();
    }
    @GameTest(template="empty")
    public static void playerChannelingWeatherRoofAndMiss(GameTestHelper h) {
        var level=h.getLevel();float r=level.getRainLevel(1),t=level.getThunderLevel(1);
        try {
            for(int scenario=0;scenario<4;scenario++) {
                level.setRainLevel(scenario==0?0:1);level.setThunderLevel(scenario>=2?1:0);
                var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
                player.getInventory().setItem(4,channelingTrident(h));
                var battle=new BattleSession(player,mob,definitions("ground"),player);
                try {
                    h.assertTrue(level.canSeeSky(mob.blockPosition()),"Channeling fixture needs open sky");
                    battle.use(player,4,"mineturn:throw_trident",mob.getId());var shot=battle.shot;
                    battle.finishShot(scenario!=3);
                    float expected=scenario==2?11:scenario==3?24:16;
                    h.assertTrue(mob.getHealth()==expected,"Channeling weather/roof/miss incorrect: "+scenario+" health="+mob.getHealth());
                    battle.submitShot(player,shot.token,shot.windowCentreNanos());
                    h.assertTrue(mob.getHealth()==expected,"Channeling replayed after token consumed");
                }finally{battle.close("test");clearChannelingItems(h,mob);cleanup(player);mob.discard();}
            }
        }finally{level.setRainLevel(r);level.setThunderLevel(t);}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerChannelingRoofAfterLightingUpdate(GameTestHelper h) {
        var level=h.getLevel();var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);var roof=mob.blockPosition().above(3);
        level.setBlockAndUpdate(roof,Blocks.STONE.defaultBlockState());
        h.runAfterDelay(20,()->{
            float r=level.getRainLevel(1),t=level.getThunderLevel(1);level.setRainLevel(1);level.setThunderLevel(1);
            try {
                h.assertTrue(!level.canSeeSky(mob.blockPosition()),"Roof lighting did not update");
                player.getInventory().setItem(4,channelingTrident(h));battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(true);
                h.assertTrue(mob.getHealth()==16 && level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,mob.getBoundingBox().inflate(2)).isEmpty(),"Covered target triggered channeling");
            }finally{battle.close("test");clearChannelingItems(h,mob);cleanup(player);mob.discard();level.setBlockAndUpdate(roof,Blocks.AIR.defaultBlockState());level.setRainLevel(r);level.setThunderLevel(t);}h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void playerChannelingParticipantsAndVisualIsolation(GameTestHelper h) {
        var level=h.getLevel();float r=level.getRainLevel(1),t=level.getThunderLevel(1);
        level.setRainLevel(1);level.setThunderLevel(1);
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var near=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,4));
        var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(9,1,2));
        var creeper=h.spawnWithNoFreeWill(EntityType.CREEPER,new BlockPos(8,1,3));
        var battle=new BattleSession(player,mob,definitions("ground"),player);battle.add(near);battle.add(creeper);
        try {
            h.setBlock(8,0,2,Blocks.OXIDIZED_COPPER);player.getInventory().setItem(4,channelingTrident(h));
            battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(true);
            h.assertTrue(mob.getHealth()==11 && near.getHealth()==19 && outside.getHealth()==24,"Lightning escaped battle or failed area damage");
            h.assertTrue(creeper.isPowered() && creeper.getHealth()==15,"Native lightning failed to charge creeper");
            h.assertTrue(near.getRemainingFireTicks()==160 && outside.getRemainingFireTicks()<=0,"Lightning ignition was not scoped to participants");
            var bolts=level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,mob.getBoundingBox().inflate(2),
                    bolt->bolt.getPersistentData().getBoolean(com.matuvent.mineturn.api.CombatChanneling.VISUAL));
            h.assertTrue(bolts.size()==1,"Missing managed lightning visual");
            for(int i=0;i<21;i++)bolts.getFirst().tick();
            h.assertTrue(mob.getHealth()==11 && near.getHealth()==19 && near.getRemainingFireTicks()==160 && outside.getHealth()==24,
                    "Visual lightning repeated damage or advanced fire in real time");
            h.assertTrue(level.getBlockState(h.absolutePos(new BlockPos(8,0,2))).is(Blocks.OXIDIZED_COPPER)
                    && level.getBlockState(h.absolutePos(new BlockPos(8,1,2))).isAir(),"Visual lightning modified blocks");
            h.assertTrue(bolts.getFirst().isRemoved(),"Lightning visual did not expire");
            battle.next();h.assertTrue(near.getRemainingFireTicks()<160,"Lightning fire did not advance with AV");
            if(near.isInWaterRainOrBubble())h.assertTrue(near.getRemainingFireTicks()==0,"Native rain/water did not extinguish lightning fire");
        }finally{battle.close("test");clearChannelingItems(h,mob);cleanup(player);mob.discard();near.discard();outside.discard();creeper.discard();level.setRainLevel(r);level.setThunderLevel(t);}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerChannelingMeleeImmunityAndStrikeCancellation(GameTestHelper h) {
        var level=h.getLevel();float r=level.getRainLevel(1),t=level.getThunderLevel(1);level.setRainLevel(1);level.setThunderLevel(1);
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(4,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent> cancel=event->{if(event.getEntity()==mob)event.setCanceled(true);};
        try {
            player.getInventory().setItem(4,channelingTrident(h));battle.use(player,4,"mineturn:melee",mob.getId());
            h.assertTrue(mob.getHealth()==15 && level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,mob.getBoundingBox().inflate(2)).isEmpty(),"Melee incorrectly triggered channeling");
            mob.setHealth(24);mob.setInvulnerable(true);battle.budget=new TurnBudget(4);
            battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(true);
            h.assertTrue(mob.getHealth()==24 && level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,mob.getBoundingBox().inflate(2)).isEmpty(),"Rejected hit triggered lightning");
            clearChannelingItems(h,mob);mob.setInvulnerable(false);player.getInventory().setItem(4,channelingTrident(h));
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
            battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(true);
            h.assertTrue(mob.getHealth()==16 && mob.getRemainingFireTicks()<=0,"Cancelled native lightning event still damaged or ignited target");
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel);battle.close("test");clearChannelingItems(h,mob);cleanup(player);mob.discard();level.setRainLevel(r);level.setThunderLevel(t);}h.succeed();
    }
    private static ItemStack channelingTrident(GameTestHelper h) {
        var item=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
        item.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.CHANNELING),1);return item;
    }
    private static void clearChannelingItems(GameTestHelper h,net.minecraft.world.entity.LivingEntity target) {
        h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,target.getBoundingBox().inflate(3),e->e.getItem().is(net.minecraft.world.item.Items.TRIDENT)).forEach(net.minecraft.world.entity.Entity::discard);
        h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,target.getBoundingBox().inflate(3),e->e.getPersistentData().getBoolean(com.matuvent.mineturn.api.CombatChanneling.VISUAL)).forEach(net.minecraft.world.entity.Entity::discard);
    }
    @GameTest(template="empty")
    public static void playerTridentImpalingDryTargetsAndLevels(GameTestHelper h) {
        impalingLevels(h,EntityType.GUARDIAN,false,true);
        impalingLevels(h,EntityType.PILLAGER,false,false);
        h.succeed();
    }
    @GameTest(template="empty")
    public static void playerTridentImpalingWetTargetsAndLevels(GameTestHelper h) {
        impalingLevels(h,EntityType.ELDER_GUARDIAN,true,true);
        impalingLevels(h,EntityType.DROWNED,true,false);
        impalingLevels(h,EntityType.PILLAGER,true,false);
        h.succeed();
    }
    private static void impalingLevels(GameTestHelper h,EntityType<? extends net.minecraft.world.entity.Mob> type,boolean wet,boolean sensitive) {
        var player=player(h);var pos=new BlockPos(4,1,2);
        h.setBlock(pos,wet?Blocks.WATER:Blocks.AIR);
        var mob=h.spawnWithNoFreeWill(type,pos);mob.tick();
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(0);
        mob.setHealth(100);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            h.assertTrue(mob.isInWater()==wet,"Impaling fixture water state incorrect");
            h.assertTrue(type.is(net.minecraft.tags.EntityTypeTags.SENSITIVE_TO_IMPALING)==sensitive,"Native impaling target tag changed");
            for(int level=0;level<=5;level++) {
                var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
                if(level>0)weapon.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.IMPALING),level);
                player.getInventory().setItem(4,weapon);player.getInventory().selected=0;
                battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();mob.setHealth(100);
                battle.use(player,4,"mineturn:melee",mob.getId());
                float bonus=sensitive?2.5f*level:0;
                h.assertTrue(Math.abs(mob.getHealth()-(100-9-bonus))<0.001,"Impaling melee target/level or selected slot incorrect: "+type+" / "+level);
                h.assertTrue(player.getInventory().selected==0,"Melee changed selected hotbar slot");
                battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();mob.setHealth(100);
                battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(true);
                h.assertTrue(Math.abs(mob.getHealth()-(100-8-bonus))<0.001,"Impaling throw target/level incorrect: "+type+" / "+level);
                h.assertTrue(mob.getDeltaMovement().lengthSqr()==0,"Impaling introduced knockback");
                h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mob.getBoundingBox().inflate(2),
                        e->e.getItem().is(net.minecraft.world.item.Items.TRIDENT)).forEach(net.minecraft.world.entity.Entity::discard);
            }
        }finally{battle.close("test");cleanup(player);mob.discard();h.setBlock(pos,Blocks.AIR);}
    }
    @GameTest(template="empty")
    public static void playerTridentImpalingArmorAndSnapshot(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.GUARDIAN,new BlockPos(4,1,2));
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);mob.setHealth(100);
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(20);
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS).setBaseValue(8);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
            weapon.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.IMPALING),5);
            player.getInventory().setItem(4,weapon);battle.use(player,4,"mineturn:melee",mob.getId());
            // Native 20 armor / 8 toughness: damage * (1 - (20 - damage / 4) / 25).
            h.assertTrue(Math.abs(mob.getHealth()-(100-21.5f*(1-(20-21.5f/4)/25)))<0.001,"Impaling melee bypassed armor or applied bonus after mitigation");
            mob.setHealth(100);battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,4,"mineturn:throw_trident",mob.getId());var shot=battle.shot;
            var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mob.getBoundingBox().inflate(2),e->e.getItem().is(net.minecraft.world.item.Items.TRIDENT));
            h.assertTrue(drops.size()==1,"Missing real trident recovery item");
            drops.getFirst().getItem().set(net.minecraft.core.component.DataComponents.ENCHANTMENTS,net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
            player.getInventory().setItem(4,new ItemStack(net.minecraft.world.item.Items.STICK));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            float health=100-20.5f*(1-(20-20.5f/4)/25);
            h.assertTrue(Math.abs(mob.getHealth()-health)<0.001,"Impaling throw used live inventory/drop enchantments or bypassed armor");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(Math.abs(mob.getHealth()-health)<0.001,"Impaling hit replayed");drops.getFirst().discard();
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerTridentLoyaltyAvAndUniqueReturn(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
            weapon.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY),3);
            weapon.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("忠诚测试"));
            var expected=weapon.copy();expected.setDamageValue(1);player.getInventory().setItem(4,weapon);
            battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(false);
            var pending=battle.returningTridents.getFirst();
            h.assertTrue(Math.abs(pending.at()-battle.clock.time()-100.0/3)<1e-7,"Loyalty level did not scale AV delay");
            BattleTridents.advance(battle);
            h.assertTrue(player.getInventory().getItem(4).isEmpty() && pending.drop().isAlive(),"Loyalty returned without advancing AV");
            battle.runScheduledBeforeNextTurn();
            h.assertTrue(ItemStack.matches(expected,player.getInventory().getItem(4)) && pending.drop().isRemoved()
                    && battle.returningTridents.isEmpty(),"AV return lost components or left a duplicate drop");
            BattleTridents.advance(battle);battle.close("test");
            h.assertTrue(player.getInventory().countItem(net.minecraft.world.item.Items.TRIDENT)==1,"Return replay/close duplicated trident");
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerTridentLoyaltyExitAndFullInventory(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(net.minecraft.world.item.Items.STONE,64));
            var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
            weapon.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY),1);
            player.getInventory().setItem(4,weapon);battle.use(player,4,"mineturn:throw_trident",mob.getId());
            var pending=battle.returningTridents.getFirst();player.getInventory().setItem(4,new ItemStack(net.minecraft.world.item.Items.DIRT,64));
            battle.close("test");
            h.assertTrue(pending.drop().isAlive() && pending.drop().position().distanceTo(player.position())<0.01
                    && pending.drop().getItem().getDamageValue()==1 && battle.returningTridents.isEmpty(),"Full inventory exit lost trident or left it far away");
            h.assertTrue(player.getInventory().getItem(4).is(net.minecraft.world.item.Items.DIRT),"Return overwrote occupied slot");
            pending.drop().discard();
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    @GameTest(template="empty")
    public static void playerTridentLoyaltyDestroyedAndExitRecovery(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            for(int i=0;i<2;i++) {
                var weapon=new ItemStack(net.minecraft.world.item.Items.TRIDENT);
                weapon.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY),1);
                player.getInventory().setItem(4,weapon);battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
                battle.use(player,4,"mineturn:throw_trident",mob.getId());battle.finishShot(false);
                var pending=battle.returningTridents.getFirst();
                if(i==0){pending.drop().discard();battle.runScheduledBeforeNextTurn();
                    h.assertTrue(player.getInventory().countItem(net.minecraft.world.item.Items.TRIDENT)==0,"Destroyed trident was recreated");
                    // Delay may lie beyond the current turn; clear the dead entry via the exit recovery path.
                    BattleTridents.leave(battle,player);
                }else{battle.close("test");h.assertTrue(player.getInventory().getItem(4).is(net.minecraft.world.item.Items.TRIDENT)
                        && pending.drop().isRemoved(),"Early battle end failed to return live trident");}
            }
        }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
    }
    private static net.minecraft.world.item.ItemStack rockets(int count,int stars) {
        var stack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET,count);
        stack.set(net.minecraft.core.component.DataComponents.FIREWORKS,new net.minecraft.world.item.component.Fireworks(1,
                java.util.Collections.nCopies(stars,net.minecraft.world.item.component.FireworkExplosion.DEFAULT)));
        return stack;
    }
    @GameTest(template="empty")
    public static void fireworkSnapshotFalloffAndWalls(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var near=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,3));
        var outside=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,1));
        var blocked=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,5));
        h.setBlock(8,1,4,Blocks.STONE);h.setBlock(8,2,4,Blocks.STONE);
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        player.getInventory().setItem(0,bow);var ammo=rockets(2,2);player.getInventory().setItem(10,ammo);
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(near);battle.add(blocked);
        try {
            battle.use(player,0,"mineturn:shoot_firework",main.getId());var shot=battle.shot;
            ammo.set(net.minecraft.core.component.DataComponents.FIREWORKS,new net.minecraft.world.item.component.Fireworks(1,List.of()));
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==15 && Math.abs(near.getHealth()-(24-9*Math.sqrt(0.8)))<0.001,"Firework stars or native falloff incorrect");
            h.assertTrue(outside.getHealth()==24 && blocked.getHealth()==24,"Explosion escaped battle or wall");
            h.assertTrue(ammo.getCount()==1 && bow.getDamageValue()==3 && !battle.ready(player,"mineturn:shoot"),"Firework costs or shared cooldown incorrect");
            h.assertTrue(main.getDeltaMovement().lengthSqr()==0 && near.getDeltaMovement().lengthSqr()==0,"Explosion caused knockback");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();
            battle.use(player,0,"mineturn:shoot_firework",main.getId());shot=battle.shot;battle.submitShot(player,shot.token,shot.startNanos);
            h.assertTrue(main.getHealth()==15 && ammo.isEmpty(),"Miss exploded or refunded firework");
        }finally{battle.close("test");cleanup(player);main.discard();near.discard();outside.discard();blocked.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void fireworkMultishotDeduplicatesAndEmptyRocket(GameTestHelper h) {
        var player=player(h);var main=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var left=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,1));
        var right=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,3));
        var bow=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW);
        var registry=h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT),1);
        bow.enchant(registry.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.QUICK_CHARGE),3);
        player.getInventory().setItem(0,bow);player.getInventory().setItem(10,rockets(1,1));
        var battle=new BattleSession(player,main,definitions("ground"),player);battle.add(left);battle.add(right);
        try {
            battle.use(player,0,"mineturn:shoot_firework",main.getId());var shot=battle.shot;
            h.assertTrue(Math.abs(battle.member(player).cooldowns.get("mineturn:shoot")-battle.clock.time()-40)<0.001,"Quick Charge did not apply to firework");
            battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==17 && left.getHealth()==17 && right.getHealth()==17,"Multishot explosions stacked on same target");
            battle.budget=new TurnBudget(4);battle.member(player).cooldowns.clear();player.getInventory().setItem(10,rockets(1,0));
            battle.use(player,0,"mineturn:shoot_firework",main.getId());shot=battle.shot;battle.submitShot(player,shot.token,shot.windowCentreNanos());
            h.assertTrue(main.getHealth()==17 && left.getHealth()==17 && player.getInventory().getItem(10).isEmpty(),"Empty rocket caused damage or was not consumed");
        }finally{battle.close("test");cleanup(player);main.discard();left.discard();right.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void fullFoodAndUnmappedFoodCanBeEaten(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        player.getFoodData().setFoodLevel(20);
        var food=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_CHICKEN,2);player.getInventory().setItem(0,food);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.use(player,0,"mineturn:eat",player.getId());
            h.assertTrue(food.getCount()==1 && !battle.budget.canAct(),"Full hunger or missing item mapping blocked edible food");
            battle.budget=new TurnBudget(4);
            food.set(com.matuvent.mineturn.MineTurn.COMBAT.get(),new com.matuvent.mineturn.data.CombatItem(false,List.of("mineturn:eat"),1));
            try{battle.use(player,0,"mineturn:eat",player.getId());throw new AssertionError("Food fallback bypassed explicit disable");}catch(IllegalArgumentException expected){}
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void entryCentersAndGroundMobSwims(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(8,1,2));
        for(int x=1;x<=10;x++)for(int y=1;y<=4;y++)for(int z=1;z<=4;z++)h.setBlock(x,y,z,Blocks.WATER);
        var start=h.absolutePos(new BlockPos(2,2,2));player.teleportTo(start.getX()+0.12,start.getY()+0.2,start.getZ()+0.83);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            h.assertTrue(player.getX()==start.getX()+0.5 && player.getZ()==start.getZ()+0.5 && player.getY()==start.getY(),"Entry did not center submerged player");
            h.assertTrue(battle.movementMode(mob).equals("aquatic") && battle.towardOffset(mob,player).y>0,"Ground AI ignored water or target height");
            var path=battle.path(mob,new Vec3(-1,1,0),true);
            h.assertTrue(path.cost()>1.4 && path.destination().y>mob.getY(),"Ground mob could not move upward underwater");
            var action=battle.definitions.actions().get("mineturn:shoot");
            h.assertTrue(action.ranged().durationMs()==1200 && action.ranged().width(0)==0.4 && action.ranged().width(10)<0.17,"Ranged difficulty defaults unchanged");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    /** The server, not the client, decides when the ranged window sits on the bar. */
    @GameTest(template="empty")
    public static void rangedWindowPositionIsServerRandomized(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.PILLAGER,new BlockPos(8,1,2));
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        var action=battle.definitions.actions().get("mineturn:shoot");
        try {
            var shots=new java.util.ArrayList<RangedShot>();
            for(int i=0;i<40;i++)shots.add(new RangedShot(player,mob,"mineturn:shoot",action,new ItemStack(net.minecraft.world.item.Items.BOW),System.nanoTime()));
            double minCentre=1,maxCentre=0;boolean varied=false;
            for(int i=0;i<shots.size();i++){
                var s=shots.get(i);double centre=(s.low+s.high)/2;
                minCentre=Math.min(minCentre,centre);maxCentre=Math.max(maxCentre,centre);
                h.assertTrue(centre>=0.25-1e-9 && centre<=0.75+1e-9,"Window centre escaped the randomized range: "+centre);
                h.assertTrue(s.hit(s.windowCentreNanos()),"Centre of the window missed");
                h.assertTrue(!s.hit(s.startNanos-1_000_000L),"Submission before the bar started scored a hit");
                if(i>0 && Math.abs((shots.get(i-1).low+shots.get(i-1).high)/2-centre)>1e-6)varied=true;
            }
            h.assertTrue(varied,"Window centre never moved across 40 shots");
            h.assertTrue(maxCentre-minCentre>0.05,"Window positions were not meaningfully varied: "+minCentre+".."+maxCentre);
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void playerDamageStartsPvpAndKeepsNeutralPlayersFriendly(GameTestHelper h) {
        var attacker=player(h);var victim=player(h);var neutral=player(h);
        var pos=h.absolutePos(new BlockPos(4,1,2));victim.teleportTo(pos.getX()+0.2,pos.getY(),pos.getZ()+0.2);
        h.runAfterDelay(80,()->{
            BattleSession battle=null;boolean oldPvp=h.getLevel().getServer().isPvpAllowed();h.getLevel().getServer().setPvpAllowed(true);
            try {
                h.assertTrue(victim.hurt(attacker.damageSources().playerAttack(attacker),1),"Test PvP damage was rejected");
                battle=BattleManager.ACTIVE.get(attacker.getUUID());
                h.assertTrue(battle!=null && BattleManager.ACTIVE.get(victim.getUUID())==battle,"Player damage did not start shared battle");
                h.assertTrue(battle.enemy(attacker,victim) && battle.enemy(victim,attacker) && !battle.enemy(attacker,neutral),"PvP hostility included unrelated player");
                h.assertTrue(victim.getX()==pos.getX()+0.5 && victim.getZ()==pos.getZ()+0.5,"PvP entry was not centered");
                battle.next();h.assertTrue(!battle.closed,"PvP closed because no monster participated");
                battle.remove(victim,"test");battle.settle();h.assertTrue(battle.closed,"PvP did not close after opponent left");
            }finally{h.getLevel().getServer().setPvpAllowed(oldPvp);if(battle!=null)battle.close("test");cleanup(attacker);cleanup(victim);cleanup(neutral);}
            h.succeed();
        });
    }
    @GameTest(template="empty")
    public static void groundDetourChargesRouteAndDoesNotMoveDuringPreview(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        for(int y=1;y<=3;y++)h.setBlock(4,y,2,Blocks.STONE);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 start=player.position(),goal=start.add(4,0,0);
            try{battle.validateDestination(player,goal);throw new AssertionError("Detour exceeded four-block budget");}catch(IllegalArgumentException expected){}
            h.assertTrue(player.position().equals(start) && Math.abs(battle.budget.remaining()-4)<1e-5,"Failed preview moved or charged player");
            battle.budget=new TurnBudget(8);var route=battle.validateDestination(player,goal);
            h.assertTrue(route.cost()>4.5 && route.cost()<=8 && route.samples().stream().anyMatch(point->Math.abs(point.z-start.z)>0.8),"Route did not detour around wall");
            h.assertTrue(player.position().equals(start),"Search teleported entity while testing edges");
            battle.moveTo(player,goal);
            h.assertTrue(player.position().distanceToSqr(goal)<1e-6 && Math.abs(battle.budget.remaining()-(8-route.cost()))<1e-6,"Detour did not charge actual path length");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void groundDetourCannotCrossControlledChoke(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(4,1,2));
        for(int z=0;z<12;z++)if(z!=2)for(int y=1;y<=3;y++)h.setBlock(4,y,z,Blocks.STONE);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            battle.budget=new TurnBudget(8);Vec3 start=player.position();
            try{battle.validateDestination(player,start.add(4,0,0));throw new AssertionError("Pathfinder crossed controlled choke");}catch(IllegalArgumentException expected){}
            h.assertTrue(player.position().equals(start) && battle.budget.remaining()==8,"Blocked search mutated battle");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty")
    public static void groundDetourPreservesDropLandings(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        h.setBlock(2,5,2,Blocks.STONE);for(int y=1;y<=3;y++)h.setBlock(4,y,2,Blocks.STONE);
        var battle=new BattleSession(player,mob,definitions("ground"),player);
        try {
            Vec3 ground=player.position();battle.place(player,ground.add(0,5,0));battle.budget=new TurnBudget(8);
            var route=battle.validateDestination(player,ground.add(4,0,0));
            h.assertTrue(route.landings().stream().anyMatch(landing->landing.distance()>=5),"Detour erased fall damage landing");
            h.assertTrue(route.destination().distanceToSqr(ground.add(4,0,0))<1e-6,"Detour ended on wrong support");
        }finally{battle.close("test");cleanup(player);mob.discard();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void animatedFallDamagesOnlyOnLanding(GameTestHelper h) {
        var player=player(h);var mob=h.spawnWithNoFreeWill(EntityType.HUSK,new BlockPos(10,1,8));
        h.runAfterDelay(70,()->{
            var battle=new BattleSession(player,mob,definitions("ground"),player);
            try {
                var ground=player.position();h.setBlock(2,6,2,Blocks.STONE);
                battle.place(player,ground.add(0,6,0));battle.beginMoveTo(player,ground.add(2,0,0));
                for(int i=0;i<8;i++)battle.tick();
                h.assertTrue(battle.motion!=null && player.getY()>ground.y && player.getY()<ground.y+6 && player.getHealth()==20,"Fall skipped animation or damaged before landing");
                finishMovement(player);
                h.assertTrue(player.getHealth()==17 && player.position().distanceToSqr(ground.add(2,0,0))<1e-8,"Animated fall damage/endpoint incorrect");
            }finally{battle.close("test");cleanup(player);mob.discard();}h.succeed();
        });
    }
    private static void cleanup(ServerPlayer player) { player.getServer().getPlayerList().remove(player); player.discard(); }
}
