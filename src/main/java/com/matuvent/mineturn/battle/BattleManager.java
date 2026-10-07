package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.data.CombatData;
import com.matuvent.mineturn.network.BattleNetwork;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server membership and input boundary. Session state is confined to the server thread. */
public final class BattleManager {
    static final Map<UUID, BattleSession> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> EXECUTING = ThreadLocal.withInitial(() -> false);
    private static final java.util.concurrent.atomic.AtomicInteger reloads=new java.util.concurrent.atomic.AtomicInteger();
    private static final Set<net.minecraft.server.MinecraftServer> STOPPING = ConcurrentHashMap.newKeySet();
    public static java.util.function.Predicate<Entity> clientStatusLocked=entity->false;
    public static void beginReload() {
        reloads.incrementAndGet();
        for (var battle : new HashSet<>(ACTIVE.values())) closeSafely(battle,"数据包重载，已结束战斗。");
    }
    public static void finishReload() { reloads.updateAndGet(count->Math.max(0,count-1)); }
    static boolean reloading(){return reloads.get()>0;}
    public static java.util.concurrent.CompletableFuture<Void> trackReload(java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> operation){
        beginReload();
        try{
            var future=java.util.Objects.requireNonNull(operation.get());
            future.whenComplete((value,error)->finishReload());
            return future;
        }catch(RuntimeException | Error error){finishReload();throw error;}
    }
    static void closeSafely(BattleSession battle,String reason){
        try{battle.close(reason);}
        catch(RuntimeException error){
            MineTurn.LOGGER.error("Battle {} emergency cleanup failed",battle.id,error);
            battle.closed=true;
            for(var member:List.copyOf(battle.members.values()))BattleRiding.forget(member);
            ACTIVE.entrySet().removeIf(entry->entry.getValue()==battle);
        }
    }
    private BattleManager() {}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void explosion(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event){BattleExplosion.filter(event);}
    public static boolean locked(Entity entity) { return !entity.level().isClientSide && (ACTIVE.containsKey(entity.getUUID()) || BattleRiding.locked(entity)); }
    public static boolean authorized() { return EXECUTING.get(); }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void split(net.neoforged.neoforge.event.entity.living.MobSplitEvent event){
        if(BattleSummons.temporary(event.getParent())){event.setCanceled(true);return;}
        var battle=ACTIVE.get(event.getParent().getUUID());
        if(battle!=null && !battle.closed)battle.member(event.getParent()).split=event;
    }
    @SubscribeEvent
    public static void temporarySummonDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event){if(BattleSummons.temporary(event.getEntity()))event.setCanceled(true);}
    @SubscribeEvent
    public static void temporarySummonExperience(net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent event){if(BattleSummons.temporary(event.getEntity()))event.setDroppedExperience(0);}
    @SubscribeEvent
    public static void loadedTemporarySummon(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(!event.getLevel().isClientSide && event.loadedFromDisk() && BattleSummons.temporary(event.getEntity()))event.setCanceled(true);
    }
    public static void finishSplit(net.minecraft.world.entity.monster.Slime parent){
        var battle=ACTIVE.get(parent.getUUID());
        if(battle!=null && !battle.closed)battle.acceptSplit(parent);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void shieldBlocked(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event){
        var battle=ACTIVE.get(event.getEntity().getUUID());
        if(battle!=null && battle.member(event.getEntity()).guarding && event.getBlocked() && event.getBlockedDamage()>0)
            battle.member(event.getEntity()).guardConsumed=true;
    }
    public static void finishShieldHit(LivingEntity entity){
        var battle=ACTIVE.get(entity.getUUID());if(battle==null)return;
        var member=battle.member(entity);
        if(member.guardConsumed){
            member.guardConsumed=false;member.guarding=false;entity.stopUsingItem();
            battle.message(entity.getName().getString()+" 已消耗本次举盾格挡。");battle.revision++;
        }
    }
    public static void playerShieldDisabled(ServerPlayer defender,LivingEntity attacker) {
        var battle=ACTIVE.get(defender.getUUID());
        if(battle==null || battle.closed || ACTIVE.get(attacker.getUUID())!=battle)return;
        var member=battle.member(defender);
        member.shieldDisabledUntil=Math.max(member.shieldDisabledUntil,battle.clock.time()+100*BattleStatus.AV_PER_TICK);
        BattleRaid.shieldClock(battle,member);
    }
    public static void ravagerShield(net.minecraft.world.entity.monster.Ravager ravager){
        var battle=ACTIVE.get(ravager.getUUID());
        if(battle!=null && ravager.getRandom().nextDouble()<0.5)BattleSpecies.stun(battle,ravager);
    }
    static void authorized(Runnable action) {
        boolean previous = EXECUTING.get(); EXECUTING.set(true);
        try { action.run(); } finally { EXECUTING.set(previous); }
    }
    public static double battleTime(Entity entity) {
        var rider=BattleRiding.rider(entity);
        var battle = ACTIVE.get(rider==null?entity.getUUID():rider.getUUID());
        return battle == null ? Double.NaN : battle.clock.time();
    }
    public static BattleNetwork.State snapshot(ServerPlayer player) {
        var battle = ACTIVE.get(player.getUUID());
        return battle == null ? null : battle.snapshot(player);
    }
    public static double remainingCooldown(ServerPlayer player,String action) {
        if(!player.getServer().isSameThread())throw new IllegalStateException("Cooldown queries require the server thread");
        var battle=ACTIVE.get(player.getUUID());return battle==null?0:battle.remainingCooldown(player,action);
    }
    public static com.matuvent.mineturn.api.CombatCasting.Result castGranted(ServerPlayer player,String grant,int targetId) {
        if(!player.getServer().isSameThread())throw new IllegalStateException("Combat casting requires the server thread");
        var battle=ACTIVE.get(player.getUUID());
        if(battle==null)return new com.matuvent.mineturn.api.CombatCasting.Result(false,"当前不在战斗中。");
        try {
            battle.useGrant(player,grant,targetId);battle.revision++;if(!battle.closed)battle.syncAll();
            return new com.matuvent.mineturn.api.CombatCasting.Result(true,"");
        }catch(IllegalArgumentException|IllegalStateException error){
            return new com.matuvent.mineturn.api.CombatCasting.Result(false,error.getMessage()==null?"无法施放。":error.getMessage());
        }
    }
    public static void request(ServerPlayer player, BattleNetwork.Request request) {
        request(player,request,null);
    }
    public static void request(ServerPlayer player, BattleNetwork.Request request, BattleNetwork.AmmoUse ammo) {
        var battle = ACTIVE.get(player.getUUID());
        if (battle == null) {
            BattleNetwork.send(player, BattleNetwork.State.closed(request.battle(), request.revision() + 1, "战斗已经结束。")); return;
        }
        if (!battle.id.equals(request.battle())) { battle.sync(player); return; }
        if(request.operation().equals("potion_preview")) {
            var member=battle.member(player);if(member.previewTick==battle.ticks)return;member.previewTick=battle.ticks;
            String reason="";boolean valid=false;List<Vec3> points=new ArrayList<>();
            try{
                if(request.revision()!=battle.revision)throw new IllegalArgumentException("战斗状态已变化，请重新选择落点。");
                battle.validatePotionPoint(player,request.slot(),request.action(),request.destination());valid=true;
                var action=battle.definitions.actions().get(request.action());
                if(com.matuvent.mineturn.api.Riptide.action(action)){
                    var route=BattleRiptide.route(battle,player,player.getInventory().getItem(request.slot()),action,request.destination()).path();
                    var samples=route.samples();int count=Math.min(32,samples.size());
                    for(int i=1;i<=count;i++)points.add(samples.get((int)((long)i*samples.size()/count)-1));
                    if(route.destination().distanceToSqr(request.destination())>0.01)reason="激流会在路线末端提前停步。";
                }
            }catch(IllegalArgumentException|IllegalStateException ex){reason=ex.getMessage();}
            BattleNetwork.send(player,new BattleNetwork.Preview(battle.id,battle.revision,request.previewId(),request.destination(),valid,reason,points));return;
        }
        if (request.operation().equals("preview")) {
            var member = battle.member(player);
            if (member.previewTick == battle.ticks) return;
            member.previewTick = battle.ticks;
            String reason = "";
            boolean valid = false;
            List<Vec3> previewPath = new ArrayList<>();
            try {
                if (request.revision() != battle.revision) throw new IllegalArgumentException("战斗状态已变化，请重新选点。");
                var route = battle.validateDestination(player, request.destination());
                valid = true;
                var samples = route.samples();
                if (samples.size() == 1) {
                    int count = Math.min(256, Math.max(1, (int)Math.ceil(player.position().distanceTo(route.destination()) / 0.2)));
                    for (int i = 1; i <= count; i++) previewPath.add(player.position().lerp(route.destination(), i / (double)count));
                } else {
                    int count = Math.min(256, samples.size());
                    for (int i = 1; i <= count; i++) previewPath.add(samples.get((int)((long)i * samples.size() / count) - 1));
                }
                reason=String.format(Locale.ROOT,"可到达；消耗 %.2f 格移动距离。",route.cost());
                double drop = route.landings().stream().mapToDouble(landing -> landing.distance()).max().orElse(0);
                if (drop > 3) reason = String.format(Locale.ROOT, "可到达；最大单次下落 %.1f 格，会按原版规则结算摔伤。", drop);
            } catch (IllegalArgumentException | IllegalStateException ex) { reason = ex.getMessage(); }
            BattleNetwork.send(player, new BattleNetwork.Preview(battle.id, battle.revision, request.previewId(), request.destination(), valid, reason, previewPath));
            return;
        }
        if (request.revision() != battle.revision) { battle.sync(player); return; }
        try {
            battle.requireTurn(player);
            if(ammo!=null && !request.operation().equals("use"))throw new IllegalArgumentException("弹药选择只能用于物品行动。");
            if (!Set.of("use", "potion_ground", "grant", "move", "end", "retreat", "flee", "sprint").contains(request.operation())) throw new IllegalArgumentException("无效动作。");
            if (request.operation().equals("use") && (request.slot() < 0 || request.slot() > 8 && request.slot()!=40)) throw new IllegalArgumentException("无效快捷栏槽位。");
            if (request.operation().equals("move")) {
                battle.beginMoveTo(player, request.destination());
                battle.revision++;
            } else if(request.operation().equals("potion_ground")) {
                battle.usePotionPoint(player,request.slot(),request.action(),request.destination());battle.revision++;
            } else if(ammo!=null) {
                battle.use(player,request.slot(),request.action(),request.target(),ammo.ammoSlot(),ammo.expected());battle.revision++;
            } else battle.action(player, request.operation(), request.slot(), request.action(), request.target(), 0, 0);
            battle.syncAll();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            battle.message("无法执行：" + ex.getMessage()); battle.sync(player);
        } catch (RuntimeException ex) {
            MineTurn.LOGGER.error("Battle action failed", ex); closeSafely(battle,"战斗异常，已恢复控制。");
        }
    }
    public static void submitAim(ServerPlayer player, BattleNetwork.AimSubmit request) {
        var battle=ACTIVE.get(player.getUUID());
        if(battle==null || !battle.id.equals(request.battle()))return;
        try { battle.submitShot(player,request.token(),System.nanoTime()-(battle.shot==null ? 0 : battle.shot.latencyCompensationNanos)); }
        catch(RuntimeException error){MineTurn.LOGGER.error("Ranged action failed",error);closeSafely(battle,"远程动作异常，已结束战斗。");}
    }
    static Entity damageOwner(net.minecraft.world.damagesource.DamageSource source) {
        Entity owner=source.getEntity();
        if(owner instanceof LivingEntity)return owner;
        Entity direct=source.getDirectEntity();
        for(int i=0;i<4 && direct instanceof net.minecraft.world.entity.projectile.Projectile projectile;i++){
            var next=projectile.getOwner();if(next==null || next==direct)break;
            if(next instanceof LivingEntity)return next;direct=next;
        }
        return owner;
    }
    public static boolean allowsRealtimeEffect(Entity source,LivingEntity target){
        if(locked(target))return false;
        for(int i=0;source!=null && i<5;i++){
            if(locked(source))return false;
            if(source instanceof net.minecraft.world.entity.projectile.Projectile projectile)source=projectile.getOwner();
            else if(source instanceof net.minecraft.world.entity.AreaEffectCloud cloud)source=cloud.getOwner();
            else break;
        }
        return true;
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void externalEffect(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable event){
        if(!event.getEntity().level().isClientSide && !authorized() && event.getEffectSource()!=null
                && !allowsRealtimeEffect(event.getEffectSource(),event.getEntity()))
            event.setResult(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }
    @SubscribeEvent public static void knockback(net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent event){
        if(locked(event.getEntity()) || BattleRiding.rider(event.getEntity())!=null)event.setCanceled(true);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void explosionKnockback(net.neoforged.neoforge.event.level.ExplosionKnockbackEvent event){
        var source=event.getExplosion().getIndirectSourceEntity();
        if(locked(event.getAffectedEntity()) || source!=null && locked(source))event.setKnockbackVelocity(Vec3.ZERO);
    }
    @SubscribeEvent public static void incoming(LivingIncomingDamageEvent event) {
        if(event.getEntity() instanceof net.minecraft.world.entity.monster.breeze.Breeze
                && event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)){event.setCanceled(true);return;}
        if (authorized()) return;
        Entity source = damageOwner(event.getSource());
        if(event.getEntity().getFirstPassenger() instanceof ServerPlayer rider && BattleRiding.vehicle(rider)==event.getEntity()
                && source instanceof LivingEntity && source!=rider && !locked(event.getEntity())) {
            event.setCanceled(true);rider.hurt(event.getSource(),event.getAmount());return;
        }
        var riding=BattleRiding.rider(event.getEntity());
        var session = ACTIVE.get(riding==null?event.getEntity().getUUID():riding.getUUID());
        if (session != null || source != null && locked(source)) {
            if (session != null && source instanceof LivingEntity living && !locked(living)) session.joinAttacker(living,riding==null?event.getEntity():riding);
            event.setCanceled(true);
        }
    }
    @SubscribeEvent public static void damaged(LivingDamageEvent.Post event) {
        BattleAxolotl.damage(event);
        if(!event.getEntity().level().isClientSide && event.getNewDamage()>0 && event.getEntity() instanceof net.minecraft.world.entity.animal.Bee bee && event.getSource().getEntity() instanceof LivingEntity source){
            var current=ACTIVE.get(source.getUUID());
            var owner=source instanceof ServerPlayer player?player:current!=null && current.side(source)!=null?current.level.getPlayerByUUID(current.side(source)):null;
            if(owner instanceof ServerPlayer player)BattleCompanions.provoke(bee,player);
        }
        if(!event.getEntity().level().isClientSide && event.getNewDamage()>0
                && event.getEntity() instanceof net.minecraft.world.entity.monster.ZombifiedPiglin piglin
                && event.getSource().getEntity() instanceof LivingEntity source) {
            var current=ACTIVE.get(source.getUUID());
            var owner=source instanceof ServerPlayer player?player
                    :current!=null && current.side(source)!=null?current.level.getPlayerByUUID(current.side(source)):null;
            if(owner instanceof ServerPlayer player)BattleAnger.provoke(piglin,player);
        }
        if(!event.getEntity().level().isClientSide && event.getNewDamage()>0){
            var current=ACTIVE.get(event.getEntity().getUUID());
            if(current!=null){
                current.member(event.getEntity()).lastDamageAv=current.clock.time();
                if(damageOwner(event.getSource()) instanceof LivingEntity attacker && attacker!=event.getEntity() && current.members.containsKey(attacker.getUUID())
                        && current.neutral(event.getEntity()) && current.side(event.getEntity())==null)
                    current.directHostilities.add(Set.of(attacker.getUUID(),event.getEntity().getUUID()));
            }
        }
        if (reloading() || authorized() || event.getEntity().level().isClientSide || event.getNewDamage() <= 0
                || STOPPING.contains(event.getEntity().getServer())) return;
        LivingEntity victim = event.getEntity();
        if (!(damageOwner(event.getSource()) instanceof LivingEntity attacker) || !victim.isAlive() || !attacker.isAlive()) return;
        if(attacker==victim)return;
        ServerPlayer player; LivingEntity mob;
        if(attacker instanceof ServerPlayer p && victim instanceof ServerPlayer other){player=p;mob=other;}
        else if (attacker instanceof ServerPlayer p && victim instanceof Mob m) { player = p; mob = m; }
        else if (victim instanceof ServerPlayer p && attacker instanceof Mob m) { player = p; mob = m; }
        else return;
        if (!eligible(player) || !eligible(mob) || locked(player) || locked(mob)) return;
        var definitions = CombatData.current;
        if (!BattleParticipation.allowed(mob,definitions)) return;
        var battle = new BattleSession(player, mob, definitions, attacker);
        battle.stabilizePlayers();
        if (battle.closed) return;
        battle.message("战斗开始！发起者获得先手行动。");
        battle.syncAll();
    }
    static boolean eligible(LivingEntity entity) {
        return entity.isAlive() && !entity.isRemoved() && (!entity.isPassenger() || BattleRiding.vehicle(entity)!=null) && !entity.isVehicle()
                && (!(entity instanceof ServerPlayer player) || !player.isCreative() && !player.isSpectator());
    }
    static void centerForBattle(LivingEntity entity) {
        var mount=BattleRiding.vehicle(entity);
        if(mount!=null){
            mount.positionRider(entity);
            var start=mount.position();var delta=new Vec3(Math.floor(start.x)+0.5-start.x,0,Math.floor(start.z)+0.5-start.z);
            var swept=BattleRiding.body(entity).expandTowards(delta).deflate(1e-6);
            if(entity.level().getWorldBorder().isWithinBounds(swept) && entity.level().noCollision(entity,swept)){
                float fall=mount.fallDistance;BattleRiding.position(entity,entity.position().add(delta));mount.fallDistance=fall;
            }
            return;
        }
        var start=entity.position();
        double y=AquaticPath.inWater(entity.level(),start) && !entity.level().getFluidState(entity.blockPosition()).isEmpty()?Math.floor(start.y):start.y;
        var destination=new net.minecraft.world.phys.Vec3(Math.floor(start.x)+0.5,y,Math.floor(start.z)+0.5);
        var sweep=entity.getBoundingBox().expandTowards(destination.subtract(start)).deflate(1e-6);
        if(!entity.level().getWorldBorder().isWithinBounds(sweep) || !entity.level().noCollision(entity,sweep))return;
        float fall=entity.fallDistance;
        if(entity instanceof ServerPlayer player)player.connection.teleport(destination.x,destination.y,destination.z,player.getYRot(),player.getXRot());
        else entity.teleportTo(destination.x,destination.y,destination.z);
        entity.fallDistance=fall;
    }
    @SubscribeEvent public static void entityTick(EntityTickEvent.Pre event) {
        if(event.getEntity() instanceof BattleCloud cloud){cloud.freezeAge();event.setCanceled(true);return;}
        if (event.getEntity() instanceof Mob mob && mob.isAlive() && locked(mob)) event.setCanceled(true);
    }
    @SubscribeEvent public static void pickup(ItemEntityPickupEvent.Pre event) {
        if (locked(event.getPlayer())) event.setCanPickup(TriState.FALSE);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        for (var battle : new HashSet<>(ACTIVE.values())) {
            try { battle.tick(); }
            catch (RuntimeException ex) { MineTurn.LOGGER.error("Battle failed", ex); closeSafely(battle,"战斗异常，已恢复控制。"); }
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        var battle = ACTIVE.get(event.getEntity().getUUID());
        if (battle != null) battle.leave(event.getEntity(), "玩家离线，已退出当前战斗。");
    }
    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        var battle = ACTIVE.get(event.getEntity().getUUID());
        if (battle != null) battle.leave(event.getEntity(), "跨维度传送，已退出当前战斗。");
    }
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        STOPPING.add(event.getServer());
        // ServerStopping fires before player/world saving; return native clocks and remove temporary units first.
        for (var battle : new HashSet<>(ACTIVE.values()))
            if (battle.level.getServer() == event.getServer()) closeSafely(battle,"服务器关闭，战斗已结束。");
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        ACTIVE.clear(); BattleRiding.clear(); reloads.set(0); STOPPING.remove(event.getServer());
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        FunctionAi.register(event.getDispatcher());
        event.getDispatcher().register(Commands.literal("mineturn")
                .executes(ctx -> command(ctx.getSource().getPlayerOrException(), "status", 0, null, 0, 0))
                .then(Commands.literal("end").executes(ctx -> command(ctx.getSource().getPlayerOrException(), "end", 0, null, 0, 0)))
                .then(Commands.literal("sprint").executes(ctx -> command(ctx.getSource().getPlayerOrException(), "sprint", 0, null, 0, 0)))
                .then(Commands.literal("retreat").executes(ctx -> command(ctx.getSource().getPlayerOrException(), "retreat", 0, null, 0, 0)))
                .then(Commands.literal("flee").executes(ctx -> command(ctx.getSource().getPlayerOrException(), "flee", 0, null, 0, 0)))
                .then(Commands.literal("attack").executes(ctx -> command(ctx.getSource().getPlayerOrException(), "attack", 0, null, 0, 0)))
                .then(Commands.literal("use").then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                        .then(Commands.argument("action", StringArgumentType.greedyString()).executes(ctx -> command(ctx.getSource().getPlayerOrException(),
                                "use", IntegerArgumentType.getInteger(ctx, "slot"), StringArgumentType.getString(ctx, "action"), 0, 0)))))
                .then(Commands.literal("move").then(Commands.argument("dx", DoubleArgumentType.doubleArg(-24, 24))
                        .then(Commands.argument("dz", DoubleArgumentType.doubleArg(-24, 24)).executes(ctx -> command(ctx.getSource().getPlayerOrException(),
                                "move", 0, null, DoubleArgumentType.getDouble(ctx, "dx"), DoubleArgumentType.getDouble(ctx, "dz"))))))
                .then(Commands.literal("abort").requires(source -> source.hasPermission(2))
                        .executes(ctx -> command(ctx.getSource().getPlayerOrException(), "abort", 0, null, 0, 0))));
    }
    private static int command(ServerPlayer player, String operation, int slot, String action, double dx, double dz) {
        var battle = ACTIVE.get(player.getUUID());
        if (battle == null) { player.sendSystemMessage(Component.literal("当前未参战。生存模式攻击未被秒杀的已配置怪物即可测试。")); return 0; }
        try {
            if (operation.equals("status")) { battle.sync(player); player.sendSystemMessage(Component.literal("/mineturn move <dx> <dz> | attack | sprint | retreat | flee | end")); return 1; }
            if (operation.equals("abort")) { closeSafely(battle,"管理员结束战斗。"); return 1; }
            LivingEntity enemy = battle.nearestEnemy(player);
            var definition = action == null ? null : battle.definitions.actions().get(action);
            int target = definition != null && definition.self() ? player.getId() : enemy == null ? -1 : enemy.getId();
            battle.action(player, operation, slot, action, target, dx, dz);
            battle.syncAll(); return 1;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            battle.message("无法执行：" + ex.getMessage()); battle.sync(player); return 0;
        }
    }
    public static double gap(AABB a, AABB b) {
        double x = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double y = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double z = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(x * x + y * y + z * z);
    }
}
