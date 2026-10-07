package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.api.CombatEffects;
import com.matuvent.mineturn.data.CombatData;
import com.matuvent.mineturn.data.CombatItem;
import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One fixed local PvE encounter. Player allies and configured hostile mobs share one AV clock. */
final class BattleSession {
    static final int MAX_MEMBERS = 32;
    static final double RADIUS = 16;
    final UUID id = UUID.randomUUID();
    final CombatData.Snapshot definitions;
    final Timeline<LivingEntity> clock = new Timeline<>();
    final LinkedHashMap<UUID, Member> members = new LinkedHashMap<>();
    final Set<Set<UUID>> playerHostilities=new HashSet<>();
    final Set<Set<UUID>> directHostilities=new HashSet<>();
    final Set<UUID> escaped = new HashSet<>();
    final ServerLevel level;
    final Vec3 center;
    LivingEntity actor;
    TurnBudget budget;
    int ticks, turnTicks;
    int idleTicks;
    long lastActivity;
    private TurnBudget newBudget(LivingEntity entity){
        BattleTerrainEnchantments.refresh(entity);
        var result=new TurnBudget(movement(entity));
        result.setActions((int)entity.getAttributeValue(MineTurn.MAIN_ACTIONS),(int)entity.getAttributeValue(MineTurn.BONUS_ACTIONS));
        idleTicks=0;lastActivity=0;return result;
    }
    long revision;
    long motionSequence;
    double nextRegen = 100;
    boolean closed;
    String lastMessage = "请选择行动。";
    final ArrayDeque<FunctionAi.Invocation> callbacks = new ArrayDeque<>();
    record Scheduled(double at, long order, Member owner, Runnable task, UUID ticket, Member target) {
        Scheduled(double at,long order,Member owner,Runnable task){this(at,order,owner,task,null,null);}
    }
    final PriorityQueue<Scheduled> scheduled = new PriorityQueue<>(Comparator.comparingDouble(Scheduled::at).thenComparingLong(Scheduled::order));
    long scheduleOrder;
    boolean drainingCallbacks;
    RangedShot shot;
    Movement motion;
    static final class Movement {
        final LivingEntity player;
        final TerrainPath.Result route;
        final String mode;
        int sample, landing;
        float falling;
        boolean functionMove;
        UUID target;
        Runnable onLanding;
        Movement(LivingEntity player, TerrainPath.Result route, String mode) {
            this.player=player; this.route=route; this.mode=mode;
        }
    }
    final Map<UUID,BattleFields.Field> fields=new LinkedHashMap<>();
    final Map<UUID,BattleSummons.Unit> summons=new LinkedHashMap<>();
    final List<BattleCloud> clouds=new ArrayList<>();
    final List<BattleTridents.Returning> returningTridents=new ArrayList<>();
    final List<BattleFang> fangs=new ArrayList<>();

    static final class Member {
        final LivingEntity entity;
        final CombatData.Brain brain;
        final Map<String, Double> cooldowns = new HashMap<>();
        Vec3 anchor;
        Member mountState;
        String state;
        int previewTick = -1;
        UUID aiTarget,beeTarget,nativeTarget;
        UUID allyOwner;
        boolean guarding;
        boolean guardConsumed;
        double shieldDisabledUntil,chargeUntil,shulkerCloseAv,pearlUntil,chorusUntil,windUntil;
        boolean blazeCharged;
        int originalPeek;
        boolean guardianMoving,axolotlPlaying;
        final Set<UUID> assistedTargets=new HashSet<>();
        long completedTurns;
        BattleAerial.Plan airPlan;
        UUID airRecoveryTarget;
        long airRecoveryTurns;
        double airRecoveryUntil;
        UUID chargeTarget;
        net.neoforged.neoforge.event.entity.living.MobSplitEvent split;
        UUID beamTarget,lastBeamTarget;
        double beamStart, beamEnd, beamRange;
        boolean aiFailed;
        double nextStatusAv;
        // Logical status time: seeded on entry, then advanced by AV, never by waiting in the GUI.
        int statusTicks;
        int suffocationTicks;
        double lastDamageAv=Double.NEGATIVE_INFINITY;
        Member(LivingEntity entity, CombatData.Brain brain) {
            this.entity = entity; this.brain = brain; anchor = entity.position();
            state = brain == null ? "" : brain.initial();
        }
    }
    BattleSession(ServerPlayer player, LivingEntity mob, CombatData.Snapshot definitions, LivingEntity first) {
        this.definitions = definitions; level = player.serverLevel();
        center = player.position().add(mob.position()).scale(0.5);
        if(mob instanceof ServerPlayer)playerHostilities.add(Set.of(player.getUUID(),mob.getUUID()));
        directHostilities.add(Set.of(player.getUUID(),mob.getUUID()));
        add(player); add(mob); member(mob).allyOwner=null; actor = first; budget = newBudget(first);
        if(mob instanceof net.minecraft.world.entity.monster.ZombifiedPiglin piglin)BattleAnger.provoke(piglin,player);
        if(mob instanceof net.minecraft.world.entity.animal.Bee bee){member(mob).beeTarget=player.getUUID();BattleCompanions.provoke(bee,player);}
        recruit();
        drainCallbacks();
    }
    Member member(LivingEntity entity) {
        Member result = members.get(entity.getUUID());
        if (result == null) throw new IllegalArgumentException("目标没有参加这场战斗。");
        return result;
    }
    List<ServerPlayer> players() {
        return members.values().stream().map(m -> m.entity).filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).toList();
    }
    boolean enemy(LivingEntity first, LivingEntity second) {
        if(first==second)return false;
        var firstOwner=BattleFields.principal(this,BattleSummons.principal(this,first));var secondOwner=BattleFields.principal(this,BattleSummons.principal(this,second));
        if(firstOwner!=first || secondOwner!=second)return enemy(firstOwner,secondOwner);
        if(first instanceof net.minecraft.world.entity.monster.Zoglin || second instanceof net.minecraft.world.entity.monster.Zoglin)return !(first instanceof net.minecraft.world.entity.monster.Zoglin && second instanceof net.minecraft.world.entity.monster.Zoglin);
        if(first instanceof net.minecraft.world.entity.animal.Bee bee)return BattleCompanions.hostileTo(this,bee,second);
        if(second instanceof net.minecraft.world.entity.animal.Bee bee)return BattleCompanions.hostileTo(this,bee,first);
        if(first instanceof net.minecraft.world.entity.monster.ZombifiedPiglin piglin)return BattleAnger.hostileTo(this,piglin,second);
        if(second instanceof net.minecraft.world.entity.monster.ZombifiedPiglin piglin)return BattleAnger.hostileTo(this,piglin,first);
        if(directHostilities.contains(Set.of(first.getUUID(),second.getUUID())))return true;
        if(nativeEnemy(first,second) || nativeEnemy(second,first))return true;
        UUID a=side(first), b=side(second);
        if(a==null && first instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin)return BattlePiglins.hostileTo(this,piglin,second);
        if(b==null && second instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin)return BattlePiglins.hostileTo(this,piglin,first);
        if(a==null && neutral(first) || b==null && neutral(second))return false;
        if(a==null || b==null)return a!=b;
        return !a.equals(b) && playerHostilities.contains(Set.of(a,b));
    }
    boolean neutral(LivingEntity entity){
        return entity instanceof net.minecraft.world.entity.monster.piglin.Piglin || entity instanceof Mob && !(entity instanceof net.minecraft.world.entity.monster.Enemy) && !(entity instanceof BattleBullet) && !BattleParticipation.hostile(entity,definitions);
    }
    boolean nativeEnemy(LivingEntity source,LivingEntity target){
        var own=members.get(source.getUUID());return own!=null && target.getUUID().equals(own.nativeTarget);
    }
    LivingEntity preferredEnemy(LivingEntity source){
        var own=members.get(source.getUUID());var target=own==null?null:members.get(own.nativeTarget);
        return target!=null && target.entity.isAlive() && enemy(source,target.entity)?target.entity:null;
    }
    UUID side(LivingEntity entity){return entity instanceof ServerPlayer?entity.getUUID():members.containsKey(entity.getUUID())?member(entity).allyOwner:null;}
    UUID assistanceOwner(LivingEntity entity){
        if(entity instanceof net.minecraft.world.entity.animal.axolotl.Axolotl a && AquaticPath.inWater(level,a.position()) && !(a.getLastHurtByMob() instanceof ServerPlayer) && !(a.getTarget() instanceof ServerPlayer))
            return players().stream().filter(p->AquaticPath.inWater(level,p.position())).min(Comparator.comparingDouble(entity::distanceToSqr)).map(Entity::getUUID).orElse(null);
        if(entity instanceof net.minecraft.world.entity.animal.Wolf wolf && wolf.isTame() && !wolf.isOrderedToSit()) {
            UUID owner=wolf.getOwnerUUID();
            return owner!=null && members.get(owner)!=null && members.get(owner).entity instanceof ServerPlayer?owner:null;
        }
        if(entity instanceof net.minecraft.world.entity.animal.SnowGolem snow && !(snow.getTarget() instanceof ServerPlayer) && !(snow.getLastHurtByMob() instanceof ServerPlayer))
            return players().stream().min(Comparator.comparingDouble(entity::distanceToSqr)).map(Entity::getUUID).orElse(null);
        if(entity instanceof net.minecraft.world.entity.animal.IronGolem golem && !(golem.getTarget() instanceof ServerPlayer)
                && !(golem.getLastHurtByMob() instanceof ServerPlayer) && (golem.getPersistentAngerTarget()==null || !(level.getEntity(golem.getPersistentAngerTarget()) instanceof ServerPlayer)))
            return players().stream().min(Comparator.comparingDouble(entity::distanceToSqr)).map(Entity::getUUID).orElse(null);
        return null;
    }
    boolean hasOpponents(){return players().stream().anyMatch(p->!enemies(p).isEmpty());}
    List<LivingEntity> enemies(LivingEntity entity) {
        return members.values().stream().map(m -> m.entity).filter(other -> other.isAlive() && enemy(entity, other)).toList();
    }
    List<LivingEntity> tacticalEnemies(LivingEntity source){
        var all=enemies(source);var awake=all.stream().filter(e->!(e instanceof net.minecraft.world.entity.animal.axolotl.Axolotl a && a.isPlayingDead())).toList();
        return awake.isEmpty()?all:awake;
    }
    boolean actionTarget(LivingEntity source,LivingEntity target,CombatData.Action action){
        return action.self()?source==target:action.allied()?side(source)!=null && side(source).equals(side(target)):enemy(source,target);
    }
    List<LivingEntity> actionTargets(LivingEntity source,CombatData.Action action){
        return members.values().stream().map(m->m.entity).filter(e->e.isAlive() && actionTarget(source,e,action)).toList();
    }
    LivingEntity nearestEnemy(LivingEntity entity) {
        var preferred=preferredEnemy(entity);if(preferred!=null)return preferred;
        return tacticalEnemies(entity).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    boolean inRegion(Entity entity) {
        return entity.level() == level && Math.abs(entity.getY() - center.y) <= 6
                && Math.hypot(entity.getX() - center.x, entity.getZ() - center.z) <= RADIUS;
    }
    boolean canJoin(LivingEntity entity) {
        if(!BattleParticipation.allowed(entity,definitions))return false;
        if(entity instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin
                && members.values().stream().noneMatch(m->enemy(piglin,m.entity)))return false;
        if(entity instanceof net.minecraft.world.entity.monster.ZombifiedPiglin piglin
                && players().stream().noneMatch(player->BattleAnger.hostileTo(this,piglin,player)))return false;
        return !closed && members.size() < MAX_MEMBERS && BattleManager.eligible(entity) && !BattleManager.locked(entity) && inRegion(entity)
                && (entity instanceof ServerPlayer || entity instanceof Mob && (BattleParticipation.hostile(entity,definitions) || entity instanceof net.minecraft.world.entity.animal.Bee bee && players().stream().anyMatch(p->BattleCompanions.hostileTo(this,bee,p)) || assistanceOwner(entity)!=null || entity instanceof Mob mob && mob.getTarget()!=null && members.containsKey(mob.getTarget().getUUID()))
                && definitions.mobs().containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
    }
    void add(LivingEntity entity) {
        if(!(entity instanceof BattleBullet))BattleManager.centerForBattle(entity);
        CombatData.Brain brain = entity instanceof ServerPlayer ? null : definitions.mobs().get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        members.put(entity.getUUID(), new Member(entity, brain));
        member(entity).allyOwner=assistanceOwner(entity);
        if(entity instanceof Mob mob && mob.getTarget()!=null)member(entity).nativeTarget=mob.getTarget().getUUID();
        if(entity instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin piglin && BattlePiglins.target(piglin)!=null)
            member(entity).nativeTarget=BattlePiglins.target(piglin).getUUID();
        if(entity instanceof net.minecraft.world.entity.animal.Bee bee)member(entity).beeTarget=bee.getPersistentAngerTarget();
        if(entity instanceof net.minecraft.world.entity.monster.Guardian g){member(entity).guardianMoving=g.isMoving();((com.matuvent.mineturn.api.GuardianBeamAccess)g).mineturn$beam(0,-1);((com.matuvent.mineturn.api.GuardianBeamAccess)g).mineturn$spines(true);}
        if(entity instanceof net.minecraft.world.entity.monster.Shulker)member(entity).originalPeek=((com.matuvent.mineturn.mixin.ShulkerBattleAccess)entity).mineturn$peek();
        if(entity instanceof net.minecraft.world.entity.monster.Ravager r)((com.matuvent.mineturn.api.RavagerBattleAccess)r).mineturn$timers(r.getStunnedTick(),r.getRoarTick());
        member(entity).nextStatusAv=clock.time()+BattleStatus.AV_PER_TICK;
        member(entity).statusTicks=entity.tickCount;
        BattleManager.ACTIVE.put(entity.getUUID(), this); clock.add(entity);
        BattleRiding.enter(member(entity));
        if(entity instanceof ServerPlayer player)BattleItemCooldowns.enter(this,player);
        BattleAxolotl.enter(this,entity);
        entity.stopUsingItem(); entity.setDeltaMovement(Vec3.ZERO);
        entity.walkAnimation.setSpeed(0); entity.walkAnimation.update(0, 1);
        if (entity instanceof ServerPlayer player) { player.closeContainer(); player.setSprinting(false); }
        if (entity instanceof Mob mob) mob.getNavigation().stop();
        callback(member(entity), "on_enter", "", null);
    }
    void recruit() {
        escaped.removeIf(uuid -> { Entity entity = level.getEntity(uuid); return entity == null || !inRegion(entity); });
        boolean changed = false;
        var region = new AABB(center, center).inflate(RADIUS, 6, RADIUS);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, region)) {
            if (!escaped.contains(entity.getUUID()) && canJoin(entity)) {
                add(entity); revision++; changed = true;
                message(entity.getName().getString() + " 加入战斗，按完整行动间隔入列。");
            }
        }
        if (changed) syncAll();
    }
    void joinAttacker(LivingEntity entity,LivingEntity victim) {
        if(entity==victim || !members.containsKey(victim.getUUID()) || !BattleParticipation.allowed(entity,definitions))return;
        if (!canJoin(entity) && !(entity instanceof Mob && !closed && members.size()<MAX_MEMBERS
                && BattleManager.eligible(entity) && !BattleManager.locked(entity) && inRegion(entity)
                && definitions.mobs().containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))) return;
        if(entity instanceof ServerPlayer && victim instanceof ServerPlayer && entity!=victim)playerHostilities.add(Set.of(entity.getUUID(),victim.getUUID()));
        escaped.remove(entity.getUUID()); add(entity); revision++;
        directHostilities.add(Set.of(entity.getUUID(),victim.getUUID()));
        member(entity).nativeTarget=victim.getUUID();
        if(entity instanceof Mob && side(victim)!=null)member(entity).allyOwner=null;
        if(entity instanceof net.minecraft.world.entity.monster.ZombifiedPiglin piglin && side(victim)!=null
                && level.getPlayerByUUID(side(victim)) instanceof ServerPlayer player)BattleAnger.provoke(piglin,player);
        message(entity.getName().getString() + " 加入战斗。此次外部攻击已拦截，等待其行动。"); syncAll();
    }
    void message(String text) {
        lastMessage = text;
        for (ServerPlayer player : players()) if (!player.connection.hasChannel(BattleNetwork.State.TYPE))
            player.sendSystemMessage(Component.literal("[MineTurn] " + text));
    }
    void sync(ServerPlayer player) {
        if (!closed && members.containsKey(player.getUUID())) {
            BattleNetwork.send(player, snapshot(player));
            BattleNetwork.send(player,new BattleNetwork.Offers(id,revision,offers(player)));
        }
        if (shot != null && shot.shooter == player) sendAim(true);
    }
    void syncAll() { for(Member member:members.values())BattleStatus.sync(member.entity,true); for (ServerPlayer player : players()) sync(player); }
    double agility(LivingEntity entity) {
        Member member = member(entity);
        return entity.getAttributeValue(MineTurn.AGILITY) * (member.brain == null ? 1 : member.brain.agility() / 100.0);
    }
    BattleNetwork.State snapshot(ServerPlayer player) {
        List<BattleNetwork.Slot> slots = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            int slot=index==9?40:index;
            ItemStack stack = player.getInventory().getItem(slot);
            CombatItem config = item(stack);
            List<String> ids = stack.isEmpty() && slot == player.getInventory().selected ? List.of("mineturn:melee")
                    : config != null && config.enabled() ? config.actions() : List.of();
            List<BattleNetwork.ItemAction> actions = new ArrayList<>();
            for (String actionId : ids.stream().limit(32).toList()) {
                var action = definitions.actions().get(actionId);
                if (action == null || slot==40 && (!stack.is(net.minecraft.world.item.Items.SHIELD) || !action.effect().equals("mineturn:shield_guard"))) continue;
                String unavailable = "没有符合条件的目标。";
                List<LivingEntity> targets = actionTargets(player,action);
                for (LivingEntity target : targets) {
                    try { validateUse(player, slot, actionId, target.getId()); unavailable = ""; break; }
                    catch (IllegalArgumentException | IllegalStateException ex) { unavailable = ex.getMessage(); }
                }
                if(com.matuvent.mineturn.api.PotionTargets.ground(action)) {
                    try{validateGroundItem(player,slot,actionId);unavailable="";}catch(IllegalArgumentException|IllegalStateException ex){unavailable=ex.getMessage();}
                }
                boolean selectAmmo=action.ranged()!=null && action.ranged().ammoCount()>0 && LoadedCrossbow.contents(stack,action).isEmpty();
                actions.add(new BattleNetwork.ItemAction(actionId, action.name(), action.self(),action.allied(), unavailable,action.cost().main(),action.cost().bonus(),
                        selectAmmo?action.ranged().ammunition():"",selectAmmo?action.ranged().ammoCount():0,com.matuvent.mineturn.api.PotionTargets.ground(action),com.matuvent.mineturn.api.EnderPearl.action(action)));
            }
            slots.add(new BattleNetwork.Slot(actions));
        }
        List<BattleNetwork.Fighter> fighters = members.values().stream().map(m -> new BattleNetwork.Fighter(m.entity.getId(),
                displayName(m.entity), enemy(player, m.entity), m.anchor, m.entity.getHealth(), m.entity.getMaxHealth(), m.entity instanceof BattleDevice?-1:clock.until(m.entity, this::agility)))
                .sorted(Comparator.comparingDouble(BattleNetwork.Fighter::nextAv)).toList();
        List<BattleNetwork.QueueEntry> queue = clock.forecast(16, this::agility).stream()
                .map(turn -> new BattleNetwork.QueueEntry(turn.actor().getId(), displayName(turn.actor()), turn.inAv())).toList();
        LivingEntity nearest = nearestEnemy(player);
        boolean own = actor == player && motion == null;
        return new BattleNetwork.State(id, revision, true, nearest == null ? -1 : nearest.getId(), actor == null ? -1 : actor.getId(), clock.time(),
                member(player).anchor, center, nearest == null ? 0 : nearest.getHealth(), nearest == null ? 0 : nearest.getMaxHealth(),
                own && shot == null && budget.canMove() && (!engaged(player) || budget.disengaged()), own && budget.canAct(), engaged(player), own && budget.canAct() && canFlee(player),
                own ? budget.remaining() : movement(player), lastMessage, slots, fighters, queue,
                budget.mainActions(),budget.bonusActions(),actor instanceof ServerPlayer?(lastActivity!=budget.activity()?30:(600-idleTicks+19)/20):-1,motionSequence,motion!=null && motion.player==player);
    }
    String displayName(LivingEntity entity) {
        String name = entity.getName().getString();
        if (name.length() > 220) name = name.substring(0, 220);
        return entity instanceof ServerPlayer ? name : name + " #" + entity.getId();
    }
    void requireTurn(ServerPlayer player) {
        if (motion != null) throw new IllegalArgumentException("请等待移动完成。");
        if (actor != player || !player.isAlive()) throw new IllegalArgumentException("还没有轮到你。");
        if (shot != null) throw new IllegalArgumentException("请先完成远程判定。");
    }
    void action(ServerPlayer player, String operation, int slot, String action, int target, double dx, double dz) {
        requireTurn(player);
        switch (operation) {
            case "end" -> next();
            case "sprint" -> { budget.sprint(); message("疾跑：剩余移动距离已翻倍。"); }
            case "retreat" -> { budget.disengage(); message("已撤退：本次可以脱离近身控制，再选择移动。"); }
            case "flee" -> {
                if (!budget.canAct()) throw new IllegalArgumentException("主要行动已用完。");
                if (!canFlee(player)) throw new IllegalArgumentException("逃跑需要距离所有敌人至少 10 格。");
                budget.act(); escaped.add(player.getUUID()); leave(player, "逃跑成功。");
            }
            case "move" -> movePlayer(player, dx, dz);
            case "attack" -> use(player, player.getInventory().selected, "mineturn:melee", target);
            case "use" -> use(player, slot, action, target);
            case "grant" -> useGrant(player,action,target);
            default -> throw new IllegalArgumentException("未知操作。");
        }
        revision++;
    }
    boolean canFlee(ServerPlayer player) {
        return enemies(player).stream().allMatch(other -> BattleManager.gap(player.getBoundingBox(), other.getBoundingBox()) >= 10);
    }
    void tick() {
        if (closed) return;
        drainCallbacks();
        prune(); if (closed) return;
        ticks++; turnTicks++;
        if(ticks%10==0)BattleFields.display(this);
        if(lastActivity!=budget.activity()){idleTicks=0;lastActivity=budget.activity();}
        if(actor instanceof ServerPlayer && motion==null && shot==null && ++idleTicks>=600){
            message("操作超时，自动结束回合。");next();syncAll();return;
        }
        expireShot(System.nanoTime());
        if (motion == null && ticks % 10 == 0) recruit();
        tickMotion(); if (closed) return;
        stabilizePlayers(); if (closed) return;
        for (Member member : members.values()) {
            LivingEntity entity = member.entity;
            BattleItemCooldowns.clock(this,member);
            if(member.chargeTarget!=null && (member.chargeUntil<=clock.time() || !members.containsKey(member.chargeTarget)
                    || !members.get(member.chargeTarget).entity.isAlive()))BattleRaid.endCharge(member);
            BattleSpecies.updateBeam(this,member);
            BattleSpecies.updateSpines(this,member);
            if(ticks%10==0)BattleAerial.preview(this,member);
            if(member.guarding && entity.isUsingItem() && entity.getUseItem().is(net.minecraft.world.item.Items.SHIELD))
                ((com.matuvent.mineturn.mixin.StatusAccess)entity).mineturn$useRemaining(entity.getUseItem().getUseDuration(entity)-5);
            entity.setDeltaMovement(Vec3.ZERO); entity.fallDistance = 0;
            if(member.mountState!=null && ticks%20==0)BattleStatus.sync(member.mountState.entity,true);
            if (entity.position().distanceToSqr(member.anchor) > 0.001) {
                if(BattleRiding.position(entity,member.anchor))continue;
                if (entity instanceof ServerPlayer player) player.connection.teleport(member.anchor.x, member.anchor.y, member.anchor.z, player.getYRot(), player.getXRot());
                else entity.setPos(member.anchor);
            }
        }
        if (motion == null && actor instanceof Mob mob && turnTicks >= 12) {
            long before=budget.activity();ai(mob);drainCallbacks();prune();
            if(!closed && motion==null && actor==mob){if(!hasAiActionBudget(mob) || budget.activity()==before)next();else turnTicks=0;}
        }
        if (ticks % 20 == 0) syncAll();
    }
    private boolean hasAiActionBudget(Mob mob) {
        if(budget.canAct())return true;
        var owner=member(mob);var brain=owner.brain;
        // Function callbacks may spend their bonus actions in the same invocation, as before.
        if(brain==null || brain.functions()!=null || budget.bonusActions()==0)return false;
        var state=brain.states().get(owner.state);
        return state!=null && state.choices().stream().anyMatch(choice->{
            var action=definitions.actions().get(choice.action());
            return action!=null && budget.canPay(action.cost()) && ready(mob,choice.action());
        });
    }
    void remove(LivingEntity entity, String reason) {
        BattleFields.leave(this,entity);
        if(entity instanceof BattleDevice)entity.discard();
        BattleSummons.remove(this,entity);
        if(members.containsKey(entity.getUUID())){
            BattleItemCooldowns.clock(this,member(entity));BattleRaid.endCharge(member(entity));
            if(entity instanceof net.minecraft.world.entity.monster.Blaze)BattleRealm.charged(this,entity,false);
            if(entity instanceof net.minecraft.world.entity.monster.Shulker)((com.matuvent.mineturn.mixin.ShulkerBattleAccess)entity).mineturn$peek(member(entity).originalPeek);
        }
        fangs.removeIf(fang->{if(fang.getOwner()==entity){fang.discard();return true;}return fang.isRemoved();});
        // Complete vanilla splitting before testing whether the last enemy has gone.
        // Loot/XP have already been handled by LivingEntity.die; remove only creates children.
        if(!closed && members.containsKey(entity.getUUID()) && entity instanceof net.minecraft.world.entity.monster.Slime slime
                && !BattleSummons.temporary(slime) && slime.isDeadOrDying() && slime.getSize()>1 && !slime.isRemoved()){
            slime.deathTime=20;level.broadcastEntityEvent(slime,(byte)60);
            slime.remove(Entity.RemovalReason.KILLED);
        }
        BattleSpecies.clear(this,entity);
        if(entity instanceof net.minecraft.world.entity.monster.Guardian && members.containsKey(entity.getUUID()))((com.matuvent.mineturn.api.GuardianBeamAccess)entity).mineturn$spines(!member(entity).guardianMoving);
        if (motion != null && motion.player == entity) { sendMotion(motion.player, false); motion = null; }
        if (shot != null && (shot.shooter == entity || shot.target == entity)) cancelShot();
        Member removed = members.remove(entity.getUUID());
        for(var survivor:members.values())survivor.assistedTargets.remove(entity.getUUID());
        for(var dependent:List.copyOf(members.values()))if(dependent.entity instanceof BattleBullet bullet && (entity.getUUID().equals(bullet.caster)||entity.getUUID().equals(bullet.target))){bullet.discard();remove(bullet,reason);}
        if(entity instanceof BattleBullet)entity.discard();
        if (removed == null) return;
        if(entity instanceof ServerPlayer player)BattleTridents.leave(this,player);
        BattleRiding.release(removed);
        if(removed.guarding)entity.stopUsingItem();
        BattleStatus.sync(entity,false);
        scheduled.removeIf(timer -> timer.owner == removed || timer.target == removed);
        callbacks.removeIf(call -> call.member() == removed);
        callback(removed, "on_leave", "", removed.aiTarget);
        BattleManager.ACTIVE.remove(entity.getUUID(), this); clock.remove(entity);
        entity.setDeltaMovement(Vec3.ZERO);
        if (actor == entity) actor = null;
        revision++;
        if (entity instanceof ServerPlayer player) BattleNetwork.send(player, BattleNetwork.State.closed(id, revision, reason));
    }
    void leave(LivingEntity entity, String reason) {
        remove(entity, reason); settle(); if (!closed) { message(reason); syncAll(); }
    }
    void acceptSplit(net.minecraft.world.entity.monster.Slime parent){
        var previous=member(parent);var split=previous.split;previous.split=null;
        if(split==null || split.isCanceled())return;
        int joined=0;
        for(Mob child:List.copyOf(split.getChildren())){
            // The native addFreshEntity call (including spawn cancellation) must already have succeeded.
            // Reserve the departing parent's slot, but never grow the settled battle above its cap.
            if(child.level()!=level || level.getEntity(child.getUUID())!=child || !BattleManager.eligible(child)
                    || BattleManager.locked(child) || members.size()>=MAX_MEMBERS+1 || !inRegion(child)
                    || !definitions.mobs().containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(child.getType()).toString()))continue;
            add(child);member(child).allyOwner=previous.allyOwner;
            if(movementMode(child).equals("ground")){
                var landing=TerrainPath.drop(child);if(landing!=null){place(child,landing.position());fall(child,landing);}
            }
            if(!child.isAlive())remove(child,"分裂子体落地死亡。");
            joined++;revision++;
        }
        if(joined>0)message(parent.getName().getString()+" 分裂，"+joined+" 个子体按完整行动间隔加入战斗。");
    }
    void prune() {
        for(var m:List.copyOf(members.values()))BattleRiding.maintain(this,m);
        for (Member member : new ArrayList<>(members.values())) {
            if (!BattleManager.eligible(member.entity) || member.entity.level() != level
                    || member.allyOwner!=null && !members.containsKey(member.allyOwner)) remove(member.entity, "已离开战斗。");
        }
        settle();
    }
    void settle() {
        if (closed) return;
        if (!hasOpponents()) close("战斗结束。");
        else if (actor == null) next();
    }
    private void cleanup(String step,Runnable work) {
        try{work.run();}catch(RuntimeException error){MineTurn.LOGGER.error("Battle {} cleanup failed: {}",id,step,error);}
    }
    private void releaseAfterFailure(Member member,String reason) {
        var entity=member.entity;
        // Remove bookkeeping first; third-party callbacks or packet failures must never retain the lock.
        members.remove(entity.getUUID(),member);BattleManager.ACTIVE.remove(entity.getUUID(),this);
        clock.remove(entity);BattleRiding.forget(member);
        cleanup("native cooldowns",()->BattleItemCooldowns.clock(this,member));
        cleanup("crossbow pose",()->BattleRaid.endCharge(member));
        cleanup("mount status",()->BattleRiding.release(member));
        cleanup("guard",()->{if(member.guarding)entity.stopUsingItem();});
        cleanup("status clock",()->BattleStatus.sync(entity,false));
        cleanup("velocity",()->entity.setDeltaMovement(Vec3.ZERO));
        if(entity instanceof BattleDevice || entity instanceof BattleBullet || BattleSummons.temporary(entity))
            cleanup("temporary entity",entity::discard);
        if(entity instanceof ServerPlayer player){
            cleanup("returning tridents",()->BattleTridents.leave(this,player));
            cleanup("close packet",()->BattleNetwork.send(player,BattleNetwork.State.closed(id,++revision,reason)));
        }
    }
    void close(String reason) {
        if (closed) return;
        closed = true;
        var participants=new ArrayList<>(members.values());
        for(var field:List.copyOf(fields.values()))cleanup("field",()->BattleFields.delete(this,field));
        for(var cloud:List.copyOf(clouds))cleanup("cloud",cloud::discard);
        clouds.clear();
        for (Member member : participants) {
            try{remove(member.entity,reason);}
            catch(RuntimeException error){
                MineTurn.LOGGER.error("Battle {} member cleanup failed: {}",id,member.entity.getUUID(),error);
                releaseAfterFailure(member,reason);
            }
        }
        // A failed nested removal must not strand a member outside the original cleanup order.
        for(var member:new ArrayList<>(members.values()))releaseAfterFailure(member,reason);
        for(var fang:List.copyOf(fangs))cleanup("fang",fang::discard);
        fields.clear();summons.clear();fangs.clear();scheduled.clear();returningTridents.clear();
        motion=null;shot=null;actor=null;
        cleanup("leave callbacks",this::drainCallbacks);callbacks.clear();
        BattleManager.ACTIVE.entrySet().removeIf(entry->entry.getValue()==this);
    }
    void next() {
        if (motion != null) throw new IllegalStateException("请等待移动完成。");
        if (closed || members.isEmpty()) return;
        if(actor!=null && members.containsKey(actor.getUUID()))member(actor).completedTurns++;
        advanceEvents(true);
        if (closed || members.isEmpty()) return;
        actor = clock.next(this::agility); revision++; turnTicks = 0; budget = newBudget(actor);
        if(member(actor).guarding){actor.stopUsingItem();member(actor).guarding=false;}
        message(actor.getName().getString() + " 的行动。"); syncAll();
    }
    private void regenerate() {
        while (clock.time() + 1e-7 >= nextRegen) {
            nextRegen += 100;
            for (ServerPlayer player : players()) {
                if (level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION) && player.getFoodData().getFoodLevel() >= 18
                        && player.getHealth() < player.getMaxHealth()) {
                    if(!player.isAlive() || clock.time()-member(player).lastDamageAv<=100)continue;
                    player.heal(1); var food = player.getFoodData();
                    if (food.getSaturationLevel() >= 1) food.setSaturation(food.getSaturationLevel() - 1);
                    else food.setFoodLevel(Math.max(0, food.getFoodLevel() - 1));
                }
            }
        }
    }
    double radius(LivingEntity entity) { var brain = member(entity).brain; return brain == null ? 1.5 : brain.reach(); }
    boolean engaged(LivingEntity entity) {
        return enemies(entity).stream().filter(other->!(other instanceof BattleBullet) && !(other instanceof BattleDevice)).anyMatch(other -> BattleManager.gap(BattleRiding.body(entity), BattleRiding.body(other)) <= radius(other)
                && entity.hasLineOfSight(other));
    }
    double movement(LivingEntity entity) {
        var mount=BattleRiding.vehicle(entity);
        if(mount!=null)return Math.min(20,Math.max(0,4*mount.getAttributeValue(Attributes.MOVEMENT_SPEED)/0.1));
        double distance=4 * entity.getAttributeValue(Attributes.MOVEMENT_SPEED) / (entity instanceof ServerPlayer ? 0.1 : 0.23);
        // Snapshot once when granting a turn, never refill on shore/water transitions.
        if(AquaticPath.inWater(level,entity.position()))distance*=1+Math.clamp(entity.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY),0,1);
        return Math.min(20,Math.max(0,distance));
    }
    String movementMode(LivingEntity entity) {
        if(entity instanceof BattleDevice)return "flying";
        if(BattleRiding.vehicle(entity)!=null)return "ground";
        String mode=member(entity).brain==null?"ground":member(entity).brain.movementMode();
        return (mode.equals("ground") || mode.equals("climbing")) && AquaticPath.inWater(level,entity.position())?"aquatic":mode;
    }
    String animationMode(LivingEntity entity,TerrainPath.Result route) {
        String mode=movementMode(entity);
        if(Set.of("ground","aquatic").contains(mode))return route.samples().stream().anyMatch(p->!AquaticPath.immersed(level,p))?"amphibious":"aquatic";
        return mode;
    }
    Vec3 towardOffset(LivingEntity entity, LivingEntity target) {
        Vec3 offset = target.position().subtract(entity.position());
        return movementMode(entity).equals("ground") ? offset.multiply(1, 0, 1) : offset;
    }
    void requireMovement(ServerPlayer player) {
        requireTurn(player);
        if (!budget.canMove()) throw new IllegalArgumentException("剩余移动距离不足。");
        if (engaged(player) && !budget.disengaged()) throw new IllegalArgumentException("身处敌人周身范围，需要先撤退才能再次移动。");
    }
    void movePlayer(ServerPlayer player, double dx, double dz) {
        requireMovement(player);
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) throw new IllegalArgumentException("无效坐标。");
        double length = Math.abs(dx)+Math.abs(dz);
        if (length < 0.01 || length > budget.remaining() + 1e-6) throw new IllegalArgumentException("超过剩余移动距离或距离为零。");
        beginPlayerMovement(player, path(player, new Vec3(dx, 0, dz), budget.disengaged()));
    }
    void moveTo(ServerPlayer player, Vec3 destination) {
        TerrainPath.Result path = validateDestination(player, destination);
        commitMovement(player, path);
    }
    void beginMoveTo(ServerPlayer player, Vec3 destination) {
        beginPlayerMovement(player, validateDestination(player, destination));
    }
    private void beginPlayerMovement(ServerPlayer player, TerrainPath.Result route) {
        if (route.cost() < 0.01) throw new IllegalArgumentException("路径被阻挡。");
        budget.move(route.cost());
        motion = new Movement(player, route, BattleRiding.vehicle(player)!=null?"ground":animationMode(player,route));
        sendMotion(player,true);
        message("正在沿路线移动…");
    }
    private void sendMotion(LivingEntity entity, boolean active) {
        if(entity instanceof ServerPlayer player)BattleNetwork.send(player,new BattleNetwork.Motion(id,++motionSequence,player.position(),active));
    }
    void cancelRidingMotion(LivingEntity entity) {sendMotion(entity,false);motion=null;}
    void tickMotion() {
        if (motion == null) return;
        BattleRiding.maintain(this,member(motion.player));
        if(motion==null)return;
        var moving=motion; var player=moving.player;
        double distance=0;
        while (moving.sample < moving.route.samples().size() && distance < 0.24) {
            Vec3 point=moving.route.samples().get(moving.sample);
            var box=BattleRiding.body(player).move(point.subtract(player.position())).deflate(1e-6);
            boolean clear=switch(moving.mode) {
                case "phasing" -> SpatialPath.canStep(player,player.position(),point,false,true);
                case "climbing" -> ClimbingPath.canStep(player,player.position(),point);
                case "leap" -> SpatialPath.canStep(player,player.position(),point,false);
                case "aquatic" -> AquaticPath.canStep(player,player.position(),point);
                case "amphibious" -> AquaticPath.clearStep(player,player.position(),point);
                case "bullet" -> BattleRealm.clear(player,player.position(),point);
                case "swimming", "flying" -> SpatialPath.canStep(player,player.position(),point,moving.mode.equals("swimming"));
                default -> level.hasChunkAt(BlockPos.containing(point)) && level.getWorldBorder().isWithinBounds(box)
                        && level.noCollision(player,box) && !level.containsAnyLiquid(box);
            };
            if (!clear) {
                finishMotion("路线发生变化，移动已停止。",false); return;
            }
            distance+=player.position().distanceTo(point);
            if(player instanceof net.minecraft.world.entity.monster.Phantom){
                Vec3 direction=point.subtract(player.position());
                if(direction.lengthSqr()>1e-8)player.setXRot((float)Math.toDegrees(-Math.atan2(direction.y,direction.horizontalDistance())));
            }
            if((player instanceof Mob || BattleRiding.vehicle(player)!=null) && point.subtract(player.position()).horizontalDistanceSqr()>1e-8) {
                Vec3 direction=point.subtract(player.position());
                BattleFacing.along(player,direction);
            }
            if ((moving.mode.equals("ground") || moving.mode.equals("leap") || moving.mode.equals("amphibious") && !AquaticPath.immersed(level,point)) && point.y < player.getY()) moving.falling += (float)(player.getY()-point.y);
            Vec3 previous=player.position();
            if(!BattleRiding.position(player,point))player.setPos(point); member(player).anchor=player.position(); player.fallDistance=0;
            BattleRealm.contact(this,player,previous,player.position());
            BattleFields.contact(this,player,previous,player.position(),true);
            if(!player.isAlive()){prune();return;}
            moving.sample++;
            if (moving.landing < moving.route.landings().size()
                    && point.distanceToSqr(moving.route.landings().get(moving.landing).position()) < 1e-8) {
                fall(player,moving.route.landings().get(moving.landing++));
                moving.falling=0;
                if(motion!=moving){prune();return;}
                if (!player.isAlive()) { prune(); return; }
            }
        }
        sendMotion(player,true);
        if (moving.sample == moving.route.samples().size()) finishMotion("移动完成。",true);
    }
    private void finishMotion(String message, boolean success) {
        var player=motion.player;
        boolean functionMove=motion.functionMove; UUID target=motion.target;
        float falling=motion.falling;
        Runnable onLanding=motion.onLanding;
        boolean grounded=motion.mode.equals("ground") || motion.mode.equals("leap") || motion.mode.equals("amphibious") && !AquaticPath.immersed(level,player.position())
                || motion.mode.equals("climbing") && !ClimbingPath.supported(player,player.position());
        sendMotion(player,false); motion=null;
        place(player,player.position()); player.fallDistance=falling;
        if(player instanceof Mob && grounded) {
            var landing=TerrainPath.drop(player);
            if(landing!=null){place(player,landing.position());fall(player,landing);}
        }
        stabilizePlayers(); if(closed)return;
        if(success && onLanding!=null && player.isAlive())onLanding.run();
        revision++; message(message); syncAll();
        if(functionMove && members.containsKey(player.getUUID()) && player.isAlive() && actor==player) {
            callback(member(player),"on_move_finished","",target,success);drainCallbacks();
        }
        if(!closed && motion==null && player instanceof Mob && actor==player)next();
    }
    TerrainPath.Result validateDestination(ServerPlayer player, Vec3 destination) {
        requireMovement(player);
        Vec3 anchor = member(player).anchor;
        if(BattleRiding.vehicle(player)!=null)destination=destination.add(BattleRiding.seat(player));
        if (!Double.isFinite(destination.x) || !Double.isFinite(destination.y) || !Double.isFinite(destination.z)) throw new IllegalArgumentException("无效目标位置。");
        destination=AquaticPath.settleSurface(player,destination);
        boolean aquatic=BattleRiding.vehicle(player)==null && (AquaticPath.inWater(level,anchor)||AquaticPath.inWater(level,destination));
        Vec3 offset = destination.subtract(anchor).multiply(1, aquatic && movementMode(player).equals("aquatic") ? 1 : 0, 1);
        if (MovementDistance.spatial(offset) < 0.01 || MovementDistance.spatial(offset) > budget.remaining() + 1e-6) throw new IllegalArgumentException("目标超出剩余移动距离。");
        TerrainPath.Result path = path(player, offset, budget.disengaged());
        if(path.destination().distanceToSqr(destination)>0.0025 && Set.of("ground","aquatic").contains(movementMode(player))){
            var route=GroundRoutes.find(player,destination,budget.remaining(),()->movementControl(player,budget.disengaged()));
            if(route!=null)path=route;
        }
        if(path.destination().distanceToSqr(destination)>0.0025 && aquatic) {
            var route=SpatialRoutes.find(player,destination,budget.remaining(),"aquatic",()->movementControl(player,budget.disengaged()));
            if(route!=null)path=route;
        }
        if (path.destination().distanceToSqr(destination) > 0.0025) throw new IllegalArgumentException("路径受阻、台阶超过一格、落点无支撑，或试图穿过周身范围。");
        return path;
    }
    void commitMovement(LivingEntity entity, TerrainPath.Result path) {
        if (path.cost() < 0.01) throw new IllegalArgumentException("路径被阻挡。");
        budget.move(path.cost());
        Vec3 previous=entity.position();
        for(var point:path.samples()){place(entity,point,false);BattleRealm.contact(this,entity,previous,point);BattleFields.contact(this,entity,previous,point,true);previous=point;if(!entity.isAlive()){prune();return;}}
        for (var landing : path.landings()) {
            place(entity, landing.position(),false);
            boolean mounted=BattleRiding.vehicle(entity)!=null;
            fall(entity, landing);
            if(mounted && BattleRiding.vehicle(entity)==null){prune();return;}
            if (!entity.isAlive()) { prune(); return; }
        }
        place(entity, path.destination(),false);
        prune();
    }
    void stabilizePlayers() {
        boolean changed = false;
        for (LivingEntity player : members.values().stream().map(m->m.entity).filter(e->e instanceof ServerPlayer
                || Set.of("ground","aquatic","swimming").contains(movementMode(e))
                || movementMode(e).equals("climbing") && !ClimbingPath.supported(e,e.position())).toList()) {
            if(motion != null && motion.player == player || AquaticPath.immersed(level,player.position()))continue;
            var landing = TerrainPath.drop(player);
            if (landing != null) {
                place(player, landing.position()); fall(player, landing); revision++; changed = true;
            }
        }
        prune(); if (changed && !closed) syncAll();
    }
    void fall(LivingEntity entity, TerrainPath.Landing landing) {
        var mount=BattleRiding.vehicle(entity);
        if(mount!=null){fall(mount,landing);BattleRiding.maintain(this,member(entity));return;}
        BattleManager.authorized(() -> {
            entity.invulnerableTime = 0;
            if (landing.position().y < level.getMinBuildHeight() - 64) {
                entity.hurt(entity.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            } else if (landing.distance() > 0 && !entity.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)
                    && !entity.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)) {
                var block = level.getBlockState(landing.block());
                block.getBlock().fallOn(level, block, landing.block(), entity, landing.distance());
            }
        });
    }
    void place(LivingEntity entity, Vec3 position) {place(entity,position,true);}
    void place(LivingEntity entity, Vec3 position,boolean traps) {
        if(entity instanceof BattleDevice)return;
        Vec3 previous=entity.position();
        member(entity).anchor = position;
        if(BattleRiding.position(entity,position)) {member(entity).anchor=entity.position();}
        else if (entity instanceof ServerPlayer player) player.connection.teleport(position.x, position.y, position.z, player.getYRot(), player.getXRot());
        else entity.teleportTo(position.x, position.y, position.z);
        entity.fallDistance = 0; entity.setDeltaMovement(Vec3.ZERO); entity.setOnGround(movementMode(entity).equals("ground"));
        if(traps)BattleFields.contact(this,entity,previous,entity.position(),false);
    }
    TerrainPath.Result path(LivingEntity entity, Vec3 offset, boolean disengaged) {
        Vec3 start = entity.position();
        if (!disengaged && engaged(entity)) return new TerrainPath.Result(start, 0, List.of());
        var control=movementControl(entity,disengaged);
        if(BattleRiding.vehicle(entity)!=null)return TerrainPath.trace(entity,offset.multiply(1,0,1),control);
        if(movementMode(entity).equals("aquatic"))return AquaticPath.trace(entity,offset,control);
        return switch (movementMode(entity)) {
            case "phasing" -> SpatialPath.trace(entity,offset,false,true,control);
            case "climbing" -> ClimbingPath.trace(entity,offset,control);
            case "flying" -> SpatialPath.trace(entity, offset, false, control);
            case "swimming" -> SpatialPath.trace(entity, offset, true, control);
            default -> {
                if (Math.abs(offset.y) > 1e-6) throw new IllegalArgumentException("地面移动不能指定垂直位移。");
                yield TerrainPath.trace(entity, offset, control);
            }
        };
    }
    java.util.function.Predicate<Vec3> movementControl(LivingEntity entity,boolean disengaged) {
        Vec3 start=entity.position();
        AABB startBox=BattleRiding.body(entity);
        if(!disengaged && engaged(entity))return point->false;
        List<LivingEntity> enemies = enemies(entity).stream().filter(other->!(other instanceof BattleBullet) && !(other instanceof BattleDevice)).toList();
        Map<LivingEntity, ApproachGate> controls = new HashMap<>();
        for (LivingEntity other : enemies) {
            ApproachGate gate = new ApproachGate();
            double gap = BattleManager.gap(BattleRiding.body(entity), BattleRiding.body(other));
            gate.accept(gap, gap <= radius(other) && entity.hasLineOfSight(other));
            controls.put(other, gate);
        }
        java.util.function.Predicate<Vec3> control = point -> {
            if (disengaged) return true;
            AABB box = startBox.move(point.subtract(start));
            for (LivingEntity other : enemies) {
                double gap = BattleManager.gap(box, other.getBoundingBox());
                boolean inside = gap <= radius(other) && level.clip(new ClipContext(point.add(0, entity.getEyeHeight(), 0), other.getEyePosition(),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
                if (!controls.get(other).accept(gap, inside)) return false;
            }
            return true;
        };
        return control;
    }
    CombatItem item(ItemStack stack) {
        CombatItem explicit = stack.get(MineTurn.COMBAT.get());
        if(explicit!=null)return explicit;
        var mapped=definitions.items().get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if(mapped!=null)return mapped;
        return stack.has(net.minecraft.core.component.DataComponents.FOOD)?new CombatItem(true,List.of("mineturn:eat"),1):null;
    }
    private static String cooldownKey(String action){return Set.of("mineturn:shoot","mineturn:shoot_tipped","mineturn:shoot_spectral","mineturn:shoot_firework").contains(action)?"mineturn:shoot":action;}
    boolean ready(LivingEntity entity, String action) { return member(entity).cooldowns.getOrDefault(cooldownKey(action), 0.0) <= clock.time() + 1e-7; }
    double remainingCooldown(LivingEntity entity,String action){return Math.max(0,member(entity).cooldowns.getOrDefault(cooldownKey(action),0.0)-clock.time());}
    LivingEntity target(int id) {
        var clicked=level.getEntity(id);var rider=clicked==null?null:BattleRiding.rider(clicked);
        if(rider!=null && rider.isAlive() && members.containsKey(rider.getUUID()))return rider;
        return members.values().stream().map(m -> m.entity).filter(entity -> entity.getId() == id && entity.isAlive()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标没有参加这场战斗或已经死亡。"));
    }
    void validateUse(ServerPlayer player, int slot, String id, int targetId) {
        requireTurn(player);
        if (slot < 0 || slot > 35 && slot!=40) throw new IllegalArgumentException("无效物品槽位。");
        ItemStack stack = player.getInventory().getItem(slot);
        CombatItem config = item(stack);
        boolean unarmed = stack.isEmpty() && id.equals("mineturn:melee");
        if (!unarmed && (config == null || !config.enabled() || !config.actions().contains(id))) throw new IllegalArgumentException("该物品没有启用这个战斗动作。");
        var action = definitions.actions().get(id);
        if (action == null) throw new IllegalArgumentException("动作不存在。");
        if(slot==40 && (!stack.is(net.minecraft.world.item.Items.SHIELD) || !action.effect().equals("mineturn:shield_guard")))throw new IllegalArgumentException("副手仅开放已装备盾牌的举盾动作。");
        if(com.matuvent.mineturn.api.PotionTargets.ground(action))throw new IllegalArgumentException("请为这个动作选择地面落点。");
        budget.require(action.cost());
        LivingEntity target = target(targetId);
        if (!actionTarget(player,target,action)) throw new IllegalArgumentException("目标阵营不符合该动作。");
        double reach = config == null ? action.range() : config.meleeRange();
        if (!action.self() && ((action.ranged() == null && BattleManager.gap(player.getBoundingBox(), target.getBoundingBox()) > reach) || !player.hasLineOfSight(target)))
            throw new IllegalArgumentException("目标超出范围或被遮挡。");
        if (!ready(player, id)) throw new IllegalArgumentException(String.format(Locale.ROOT,"动作仍在冷却：剩余 %.1f AV。",remainingCooldown(player,id)));
        if(stack.is(net.minecraft.world.item.Items.WIND_CHARGE) && member(player).windUntil>clock.time())throw new IllegalArgumentException("风弹仍在物品冷却中。");
        if (stack.getCount() < action.consume()) throw new IllegalArgumentException("物品数量不足。");
        requireAmmo(player, action, stack);
        String invalid = CombatEffects.get(action.effect()).validate(effectContext(player, target, stack, action, false));
        if (invalid != null) throw new IllegalArgumentException(invalid);
    }
    List<BattleNetwork.Offer> offers(ServerPlayer player) {
        var result=new ArrayList<BattleNetwork.Offer>();
        definitions.grants().entrySet().stream().sorted(Comparator.<Map.Entry<String,com.matuvent.mineturn.data.GrantedAction>>comparingInt(e->e.getValue().order()).thenComparing(Map.Entry::getKey)).forEach(entry->{
            var grant=entry.getValue();if(!grant.available(player))return;
            var action=definitions.actions().get(grant.action());String reason="没有符合条件的目标。";
            for(var target:actionTargets(player,action)) {
                try{validateGrant(player,entry.getKey(),target.getId());reason="";break;}
                catch(IllegalArgumentException|IllegalStateException error){reason=error.getMessage();}
            }
            result.add(new BattleNetwork.Offer(entry.getKey(),grant.name(),grant.icon(),action.self(),action.allied(),reason,action.cost().main(),action.cost().bonus()));
        });
        return result;
    }
    CombatData.Action validateGrant(ServerPlayer player,String grantId,int targetId) {
        requireTurn(player);
        var grant=definitions.grants().get(grantId);
        if(grant==null || !grant.available(player))throw new IllegalArgumentException("此行动的装备或状态条件已失效。");
        var action=definitions.actions().get(grant.action());var target=target(targetId);
        if(com.matuvent.mineturn.api.PotionTargets.ground(action))throw new IllegalArgumentException("地面药水动作需要通过物品落点选择使用。");
        budget.require(action.cost());
        if(!actionTarget(player,target,action))throw new IllegalArgumentException("目标阵营不符合该动作。");
        if(!action.self() && (!player.hasLineOfSight(target) || action.ranged()==null && BattleManager.gap(player.getBoundingBox(),target.getBoundingBox())>action.range()))throw new IllegalArgumentException("目标超出范围或被遮挡。");
        if(!ready(player,grant.action()))throw new IllegalArgumentException("动作仍在冷却。");
        if(grant.cost()!=null){String error=grant.cost().check(player);if(error!=null)throw new IllegalArgumentException(error);}
        requireAmmo(player,action);
        String error=CombatEffects.get(action.effect()).validate(effectContext(player,target,ItemStack.EMPTY,action,false));
        if(error!=null)throw new IllegalArgumentException(error);
        return action;
    }
    void useGrant(ServerPlayer player,String grantId,int targetId) {
        var action=validateGrant(player,grantId,targetId);var grant=definitions.grants().get(grantId);
        if(grant.cost()!=null && !grant.cost().trySpend(player))throw new IllegalArgumentException("资源不足或消耗失败，请重新选择行动。");
        budget.spend(action.cost());
        if(action.ranged()!=null) {
              shot=new RangedShot(player,target(targetId),grant.action(),action,ItemStack.EMPTY,System.nanoTime());
              faceTarget(player,shot.target);
            shot.grantId=grantId;shot.ammunition=ammoSnapshot(player,action);spendAmmo(player,action,ItemStack.EMPTY);
            member(player).cooldowns.put(cooldownKey(grant.action()),clock.time()+action.cooldown());
            message("远程判定：指针进入绿色区域时按空格。");
        } else execute(player,target(targetId),grant.action(),action,ItemStack.EMPTY);
        prune();
    }
    void use(ServerPlayer player, int slot, String id, int targetId) {
        use(player,slot,id,targetId,-1,ItemStack.EMPTY);
    }
    void use(ServerPlayer player, int slot, String id, int targetId,int ammoSlot,ItemStack expected) {
        validateUse(player, slot, id, targetId);
        var action = definitions.actions().get(id);
        ItemStack chosenAmmo=ItemStack.EMPTY;
        if(ammoSlot!=-1 || !expected.isEmpty()) {
            if((ammoSlot<0 || ammoSlot>35) && ammoSlot!=40)throw new IllegalArgumentException("无效弹药槽位。");
            if(action.ranged()==null || action.ranged().ammoCount()<=0 || !LoadedCrossbow.contents(player.getInventory().getItem(slot),action).isEmpty())
                throw new IllegalArgumentException("这个动作不能另选背包弹药。");
            chosenAmmo=player.getInventory().getItem(ammoSlot);
            if(chosenAmmo.isEmpty() || !ItemStack.isSameItemSameComponents(chosenAmmo,expected)
                    || !BuiltInRegistries.ITEM.getKey(chosenAmmo.getItem()).toString().equals(action.ranged().ammunition())
                    || chosenAmmo.getCount()<action.ranged().ammoCount())throw new IllegalArgumentException("所选弹药已变化或数量不足，请重新选择。");
            if(ammoSlot==slot)throw new IllegalArgumentException("武器和弹药不能使用同一个槽位。");
        }
        if(action.effect().equals("mineturn:player_trident")) {
            var stack=player.getInventory().getItem(slot);var target=target(targetId);
            var attempt=new RangedShot(player,target,id,action,stack,System.nanoTime());
            BattleTridents.launch(this,player,stack,target.position(),slot,com.matuvent.mineturn.api.PlayerTrident.returnAv(action));
            budget.spend(action.cost());shot=attempt;faceTarget(player,target);
            member(player).cooldowns.put(cooldownKey(id),clock.time()+action.cooldown());
            message("三叉戟已投出：完成远程判定。忠诚按 AV 返回，无忠诚则战后拾回。");prune();return;
        }
        budget.spend(action.cost());
        if (action.ranged() != null) {
            ItemStack stack=player.getInventory().getItem(slot);
              shot=new RangedShot(player,target(targetId),id,action,stack,System.nanoTime());
              faceTarget(player,shot.target);
            shot.ammunition=chosenAmmo.isEmpty()?ammoSnapshot(player,action,stack):chosenAmmo.copyWithCount(1);
            if(chosenAmmo.isEmpty())spendAmmo(player,action,stack);
            else if(!com.matuvent.mineturn.api.CombatProjectiles.preservesArrow(player,stack,action))chosenAmmo.shrink(action.ranged().ammoCount());
            if(shot.weapon.is(net.minecraft.world.item.Items.WIND_CHARGE)){
                member(player).windUntil=clock.time()+action.cooldown();BattleItemCooldowns.clock(this,member(player));
            }
            stack.consume(action.consume(),player);
            member(player).cooldowns.put(cooldownKey(id),clock.time()+com.matuvent.mineturn.api.CombatProjectiles.cooldown(player,stack,action));
            if(stack.is(net.minecraft.world.item.Items.CROSSBOW) && Set.of("mineturn:projectile","mineturn:firework").contains(action.effect()))
                com.matuvent.mineturn.api.CombatProjectiles.payCrossbowDurability(player,stack,shot.weapon,shot.ammunition);
            else if(stack.isDamageableItem()) stack.hurtAndBreak(1,player,EquipmentSlot.MAINHAND);
            message("远程判定：指针进入绿色区域时按空格。未命中也会消耗本次行动和弹药。");
        } else execute(player, target(targetId), id, action, player.getInventory().getItem(slot));
        prune();
    }
    private CombatData.Action validateGroundItem(ServerPlayer player,int slot,String id) {
        requireTurn(player);
        if(slot<0 || slot>8)throw new IllegalArgumentException("无效快捷栏槽位。");
        var stack=player.getInventory().getItem(slot);var config=item(stack);var action=definitions.actions().get(id);
        if(action!=null && com.matuvent.mineturn.api.EnderPearl.action(action)){
            if(config==null || !config.enabled() || !config.actions().contains(id) || stack.getCount()<action.consume())throw new IllegalArgumentException("该物品没有启用传送或数量不足。");
            budget.require(action.cost());
            if(!ready(player,id) || member(player).pearlUntil>clock.time())throw new IllegalArgumentException("末影珍珠仍在冷却。");
            String error=CombatEffects.get(action.effect()).validate(effectContext(player,player,stack,action,false));
            if(error!=null)throw new IllegalArgumentException(error);return action;
        }
        if(action!=null && com.matuvent.mineturn.api.Riptide.action(action)){
            if(config==null || !config.enabled() || !config.actions().contains(id))throw new IllegalArgumentException("该物品没有启用激流。");
            budget.require(action.cost());if(!ready(player,id))throw new IllegalArgumentException("激流仍在冷却。");
            String error=CombatEffects.get(action.effect()).validate(effectContext(player,player,stack,action,false));
            if(error!=null)throw new IllegalArgumentException(error);
            return action;
        }
        if(action==null || !com.matuvent.mineturn.api.PotionTargets.ground(action)
                || !Set.of("mineturn:splash","mineturn:lingering").contains(action.effect()) || action.self() || action.ranged()==null
                || action.ranged().ammoCount()!=0 || action.consume()!=1
                || config==null || !config.enabled() || !config.actions().contains(id))throw new IllegalArgumentException("该物品没有启用地面药水投掷。");
        budget.require(action.cost());
        if(!ready(player,id))throw new IllegalArgumentException(String.format(Locale.ROOT,"动作仍在冷却：剩余 %.1f AV。",remainingCooldown(player,id)));
        if(stack.getCount()<action.consume())throw new IllegalArgumentException("物品数量不足。");
        var error=CombatEffects.get(action.effect()).validate(effectContext(player,player,stack,action,false));
        if(error!=null)throw new IllegalArgumentException(error);
        return action;
    }
    private void checkPotionPoint(ServerPlayer player,Vec3 point) {
        if(!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || Math.abs(point.y-center.y)>6 || Math.hypot(point.x-center.x,point.z-center.z)>RADIUS)
            throw new IllegalArgumentException("落点超出战斗区域。");
        var pos=net.minecraft.core.BlockPos.containing(point);
        if(!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos))throw new IllegalArgumentException("落点不可用。");
        var surface=level.clip(new ClipContext(point.add(0,0.1,0),point.add(0,-0.15,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,player));
        if(surface.getType()!=HitResult.Type.BLOCK || surface.getDirection()!=net.minecraft.core.Direction.UP
                || surface.getLocation().distanceTo(point)>0.08)throw new IllegalArgumentException("请选择方块或水面的上表面。");
        var sight=level.clip(new ClipContext(player.getEyePosition(),point.add(0,0.05,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(sight.getType()!=HitResult.Type.MISS)throw new IllegalArgumentException("落点被遮挡。");
    }
    void validatePotionPoint(ServerPlayer player,int slot,String id,Vec3 point) {
        var action=validateGroundItem(player,slot,id);
        if(com.matuvent.mineturn.api.Riptide.action(action))BattleRiptide.route(this,player,player.getInventory().getItem(slot),action,point);
        else if(com.matuvent.mineturn.api.EnderPearl.action(action))BattleTeleport.point(this,player,point,action.range(),true);
        else checkPotionPoint(player,point);
    }
    void usePotionPoint(ServerPlayer player,int slot,String id,Vec3 point) {
        validatePotionPoint(player,slot,id,point);
        var action=definitions.actions().get(id);var stack=player.getInventory().getItem(slot);
        if(com.matuvent.mineturn.api.EnderPearl.action(action)){BattleTeleport.pearl(this,player,slot,id,point);message("末影珍珠传送完成。");return;}
        if(com.matuvent.mineturn.api.Riptide.action(action)){
            var route=BattleRiptide.route(this,player,stack,action,point);budget.spend(action.cost());
            member(player).cooldowns.put(cooldownKey(id),clock.time()+action.cooldown());
            BattleRiptide.launch(this,player,stack,action,route);revision++;prune();
            message("激流位移完成，普通移动距离保留。");return;
        }
        budget.spend(action.cost());shot=new RangedShot(player,player,id,action,stack,System.nanoTime(),point);
        stack.consume(action.consume(),player);member(player).cooldowns.put(cooldownKey(id),clock.time()+action.cooldown());
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,point);
        message("地面投掷：完成远程判定，未命中也会消耗药水和行动。");
    }
    void sendAim(boolean active) {
        BattleNetwork.send(shot.shooter,new BattleNetwork.Aim(id,shot.token,active,shot.action.ranged().durationMs(),shot.elapsedMs(System.nanoTime()),shot.low,shot.high));
    }
    void cancelShot() { if(shot!=null){sendAim(false);shot=null;} }
    void expireShot(long now) { if(shot!=null && shot.expired(now-250_000_000L))finishShot(false); }
    void submitShot(ServerPlayer player, UUID token, long now) {
        if(shot==null || shot.shooter!=player || actor!=player || !shot.token.equals(token)) return;
        finishShot(!shot.expired(now) && shot.hit(now));
    }
    void finishShot(boolean hit) {
        RangedShot resolved=shot; if(resolved==null)return;
        cancelShot(); // Consume token before invoking any extension code.
        boolean valid=members.containsKey(resolved.shooter.getUUID()) && members.containsKey(resolved.target.getUUID())
                && resolved.shooter.isAlive() && resolved.target.isAlive() && resolved.target.level()==level && resolved.shooter.hasLineOfSight(resolved.target);
        if(resolved.ground!=null) {
            valid=members.containsKey(resolved.shooter.getUUID()) && resolved.shooter.isAlive();
            try{checkPotionPoint(resolved.shooter,resolved.ground);}catch(IllegalArgumentException ex){valid=false;}
            if(valid)valid=CombatEffects.get(resolved.action.effect()).validate(effectContext(resolved.shooter,resolved.target,resolved.weapon,resolved.action,false,resolved.ammunition,resolved.ground))==null;
        }
        if(resolved.grantId!=null) {
            var grant=definitions.grants().get(resolved.grantId);
            valid=valid && grant!=null && grant.available(resolved.shooter);
            if(valid)valid=CombatEffects.get(resolved.action.effect()).validate(effectContext(resolved.shooter,resolved.target,resolved.weapon,resolved.action,false))==null;
        }
        if(hit && valid) runEffect(resolved.shooter,resolved.target,resolved.weapon,resolved.action,CombatEffects.get(resolved.action.effect())::execute,resolved.ammunition,resolved.ground);
        com.matuvent.mineturn.api.CombatVisuals.shot(resolved.shooter,resolved.ground==null?resolved.target.getBoundingBox().getCenter():resolved.ground,resolved.action.effect(),resolved.weapon,resolved.ammunition,hit && valid);
        message(hit && valid ? "远程判定成功！" : "远程判定失败，攻击未命中。");
        var owner=members.get(resolved.shooter.getUUID());
        if(owner!=null) callback(owner,"on_action_resolved",resolved.actionId,resolved.ground==null?resolved.target.getUUID():null,hit && valid);
        revision++; prune(); if(!closed)syncAll();
    }
    void requireAmmo(LivingEntity source, CombatData.Action action) {
        requireAmmo(source,action,ItemStack.EMPTY);
    }
    private void requireAmmo(LivingEntity source, CombatData.Action action, ItemStack weapon) {
        if(action.ranged()==null)return;
        if(!LoadedCrossbow.ammunition(weapon,action).isEmpty())return;
        int available=ammoStacks(source,action).stream().mapToInt(ItemStack::getCount).sum();
        if(available<action.ranged().ammoCount())throw new IllegalArgumentException("弹药不足：需要 "+action.ranged().ammoCount()+" 个匹配弹药，背包与副手共有 "+available+" 个。");
    }
    private List<ItemStack> ammoStacks(LivingEntity source, CombatData.Action action) {
        List<ItemStack> result=new ArrayList<>();
        if(source instanceof ServerPlayer player) {for(int i=0;i<36;i++)result.add(player.getInventory().getItem(i));result.add(player.getOffhandItem());}
        else {result.add(source.getMainHandItem());result.add(source.getOffhandItem());}
        String ammo=action.ranged().ammunition();
        return result.stream().filter(stack->BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(ammo)).toList();
    }
    private ItemStack ammoSnapshot(LivingEntity source,CombatData.Action action){
        return action.ranged()==null?ItemStack.EMPTY:ammoStacks(source,action).stream().filter(s->!s.isEmpty()).findFirst().map(s->s.copyWithCount(1)).orElse(ItemStack.EMPTY);
    }
    private ItemStack ammoSnapshot(LivingEntity source,CombatData.Action action,ItemStack weapon){
        var loaded=LoadedCrossbow.ammunition(weapon,action);
        return loaded.isEmpty()?ammoSnapshot(source,action):loaded;
    }
    private void spendAmmo(LivingEntity source,CombatData.Action action,ItemStack weapon){
        if(LoadedCrossbow.consume(weapon,action))return;
        if(com.matuvent.mineturn.api.CombatProjectiles.preservesArrow(source,weapon,action))return;
        if(action.ranged()==null)return;
        int left=action.ranged().ammoCount();
        for(var stack:ammoStacks(source,action)){int spent=Math.min(left,stack.getCount());stack.shrink(spent);left-=spent;if(left==0)break;}
    }
    void execute(LivingEntity source, LivingEntity target, String id, CombatData.Action action, ItemStack stack) {
        ItemStack itemSnapshot=action.ranged()==null?stack:stack.copy();
        requireAmmo(source,action,stack); ItemStack ammunition=ammoSnapshot(source,action,stack); spendAmmo(source,action,stack);
        faceTarget(source,target);
        ItemStack weapon=Set.of("mineturn:mob_crossbow","mineturn:pillager_charge").contains(action.effect())?com.matuvent.mineturn.api.CombatProjectiles.mobCrossbow(source):itemSnapshot;
        member(source).cooldowns.put(cooldownKey(id), clock.time() + com.matuvent.mineturn.api.CombatProjectiles.cooldown(source,weapon,action));
        boolean hit=action.ranged()==null || source.getRandom().nextDouble()<action.ranged().width(source.distanceTo(target));
        BattleManager.authorized(() -> {
            try { if(hit) runEffect(source, target, weapon, action, CombatEffects.get(action.effect())::execute,ammunition); }
            finally {
                if(!action.effect().equals("mineturn:food"))stack.consume(action.consume(),source);
                if(!hit && action.effect().equals("mineturn:mob_crossbow"))com.matuvent.mineturn.api.CombatProjectiles.prepareMobCrossbow(source);
            }
            source.swing(InteractionHand.MAIN_HAND);
            message(source.getName().getString() + " 对 " + target.getName().getString() + " 使用了「" + action.name() + "」。" + (hit ? "" : "未命中。"));
        });
        if(action.ranged()!=null)com.matuvent.mineturn.api.CombatVisuals.shot(source,target.getBoundingBox().getCenter(),action.effect(),weapon,ammunition,hit);
        Member owner = members.get(source.getUUID());
        if (owner != null) callback(owner, "on_action_resolved", id, action.self() ? null : target.getUUID(),hit);
    }
    private static void faceTarget(LivingEntity source,LivingEntity target) {
        if(source==target)return;
        source.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,target.getEyePosition());
        source.setYHeadRot(source.getYRot());
        source.setYBodyRot(source.getYRot());
    }
    private CombatEffects.Context activeEffect;
    CombatEffects.Context effectContext(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, boolean executable) {
        return effectContext(source,target,item,action,executable,ItemStack.EMPTY);
    }
    private CombatEffects.Context effectContext(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, boolean executable,ItemStack ammunition) {
        return effectContext(source,target,item,action,executable,ammunition,null);
    }
    private CombatEffects.Context effectContext(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, boolean executable,ItemStack ammunition,Vec3 impact) {
        Member owner = member(source);
        CombatEffects.Context[] context = new CombatEffects.Context[1];
        var access = new CombatEffects.BattleAccess() {
            private void requireActive() {
                if (!level.getServer().isSameThread() || !executable || activeEffect != context[0] || closed || members.get(source.getUUID()) != owner || !source.isAlive())
                    throw new IllegalStateException("技能上下文已过期或尚未消耗行动。");
            }
            @Override public String previewField(com.matuvent.mineturn.api.CombatFields.Request request){return BattleFields.preview(BattleSession.this,source,request);}
            @Override public com.matuvent.mineturn.api.CombatFields.Result createField(com.matuvent.mineturn.api.CombatFields.Request request,java.util.function.Consumer<CombatEffects.Context> callback){requireActive();return BattleFields.create(BattleSession.this,source,request,action,item,callback);}
            @Override public boolean removeField(UUID id){requireActive();return BattleFields.remove(BattleSession.this,source,id);}
            @Override public String previewSummon(com.matuvent.mineturn.api.CombatSummons.Request request){return BattleSummons.preview(BattleSession.this,source,request);}
            @Override public com.matuvent.mineturn.api.CombatSummons.Result summon(com.matuvent.mineturn.api.CombatSummons.Request request){requireActive();return BattleSummons.spawn(BattleSession.this,source,request);}
            @Override public com.matuvent.mineturn.api.CombatTeleport.Result previewTeleport(LivingEntity victim,Vec3 destination,com.matuvent.mineturn.api.CombatTeleport.Rules rules){
                if(!level.getServer().isSameThread() || closed || members.get(source.getUUID())!=owner || !source.isAlive())return com.matuvent.mineturn.api.CombatTeleport.Result.rejected("技能上下文已失效。");
                return BattleTeleport.preview(BattleSession.this,victim,destination,rules);
            }
            @Override public com.matuvent.mineturn.api.CombatTeleport.Result teleportTo(LivingEntity victim,Vec3 destination,com.matuvent.mineturn.api.CombatTeleport.Rules rules){
                requireActive();return BattleTeleport.apply(BattleSession.this,victim,destination,rules);
            }
            @Override public boolean chorusReady(){return owner.chorusUntil<=clock.time();}
            @Override public void chorusTeleport(){requireActive();if(source instanceof ServerPlayer player)BattleTeleport.chorus(BattleSession.this,player,action);}
            @Override public boolean blazeCharged(){return owner.blazeCharged;}
            @Override public boolean canChargeBlaze(){return !owner.blazeCharged && ready(source,"mineturn:blaze_volley");}
            @Override public void blazeCharge(boolean value){requireActive();BattleRealm.charged(BattleSession.this,source,value);}
            @Override public void fireball(boolean large){requireActive();if(BattleManager.gap(source.getBoundingBox(),target.getBoundingBox())<=action.range())BattleRealm.fireball(BattleSession.this,source,target,large);}
            @Override public boolean canTeleport(double range){return BattleRealm.teleportPoint(BattleSession.this,source,range)!=null;}
            @Override public void teleport(double range){requireActive();BattleRealm.teleport(BattleSession.this,source,range);}
            @Override public boolean canLaunchBullet(){return BattleRealm.canBullet(BattleSession.this);}
            @Override public void launchBullet(){requireActive();BattleRealm.bullet(BattleSession.this,source,target);}
            @Override public int lingeringCount(){return clouds.size();}
            @Override public boolean canGuard(){return owner.shieldDisabledUntil<=clock.time()
                    && (!(source instanceof ServerPlayer player) || !player.getCooldowns().isOnCooldown(net.minecraft.world.item.Items.SHIELD));}
            @Override public boolean canChargeCrossbow(){return owner.chargeUntil<=clock.time();}
            @Override public void chargeCrossbow(double av){
                requireActive();if(!(source instanceof net.minecraft.world.entity.monster.Pillager pillager) || !canChargeCrossbow())throw new IllegalArgumentException("Already charging");
                owner.chargeUntil=clock.time()+av;owner.chargeTarget=target.getUUID();pillager.setChargingCrossbow(true);
            }
            @Override public void endCrossbowCharge(){requireActive();BattleRaid.endCharge(owner);}
            @Override public void disableShield(double av){
                requireActive();if(target instanceof ServerPlayer && members.containsKey(target.getUUID())){
                    var victim=member(target);victim.shieldDisabledUntil=Math.max(victim.shieldDisabledUntil,clock.time()+av);
                    if(victim.guarding){target.stopUsingItem();victim.guarding=false;victim.guardConsumed=false;}
                    BattleRaid.shieldClock(BattleSession.this,victim);
                }
            }
            @Override public boolean canSummonVex(){return !BattleSummons.temporary(source) && BattleRaid.canSummon(BattleSession.this);}
            @Override public void summonVex(){requireActive();if(!BattleSummons.temporary(source) && source instanceof net.minecraft.world.entity.monster.Evoker evoker)BattleRaid.summon(BattleSession.this,evoker);}
            @Override public void fangs(boolean circle){requireActive();BattleRaid.fangs(BattleSession.this,source,target,circle,(float)action.amount());}
            @Override public void explodeCreeper(){
                requireActive();if(!(source instanceof net.minecraft.world.entity.monster.Creeper creeper))throw new IllegalArgumentException("Not a creeper");
                BattleExplosion.explode(BattleSession.this,creeper);
            }
            @Override public boolean canSummonSilverfish(){return !BattleSummons.temporary(source) && !BattleInfestation.candidates(BattleSession.this,source,action).isEmpty()
                    && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);}
            @Override public void summonSilverfish(){requireActive();if(BattleSummons.temporary(source))throw new IllegalArgumentException("Temporary summon cannot summon");BattleInfestation.summon(BattleSession.this,source,action);}
            @Override public boolean canPrepareDive(){return source instanceof net.minecraft.world.entity.monster.Phantom && BattleAerial.canPrepare(BattleSession.this,source,target,action.range());}
            @Override public void prepareDive(){requireActive();BattleAerial.prepare(BattleSession.this,source,target,action.range());}
            @Override public boolean canDive(){return actor==source && diveRoute(source,target,action.range())!=null;}
            @Override public void dive(){
                requireActive();var route=diveRoute(source,target,action.range());
                if(actor!=source || route==null)throw new IllegalArgumentException("俯冲路线已失效。");
                budget.move(route.cost());motion=new Movement(source,route,"flying");
                motion.target=target.getUUID();
                motion.onLanding=()->{
                    if(!closed && members.containsKey(source.getUUID()) && members.containsKey(target.getUUID())
                            && source.isAlive() && target.isAlive() && enemy(source,target) && source.hasLineOfSight(target)
                            && BattleManager.gap(source.getBoundingBox(),target.getBoundingBox())<=1.5){
                        var attack=definitions.actions().get("mineturn:mob_melee");
                        if(attack!=null)runEffect(source,target,ItemStack.EMPTY,attack,CombatEffects.get(attack.effect())::execute);
                    }
                };
                source.playSound(net.minecraft.sounds.SoundEvents.PHANTOM_SWOOP,1,1);revision++;syncAll();
            }
            @Override public void guard(){
                requireActive();
                if(!canGuard())throw new IllegalArgumentException("盾牌仍在冷却。");
                if(!(source instanceof ServerPlayer player) || !item.is(net.minecraft.world.item.Items.SHIELD))throw new IllegalArgumentException("需要盾牌。");
                boolean offhand=player.getOffhandItem()==item;
                if(!offhand){
                    int slot=-1;for(int i=0;i<9;i++)if(player.getInventory().getItem(i)==item){slot=i;break;}
                    if(slot<0)throw new IllegalArgumentException("盾牌不在快捷栏或副手中。");
                    player.getInventory().selected=slot;
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(slot));
                }
                var enemy=nearestEnemy(player);if(enemy!=null)faceTarget(player,enemy);
                player.startUsingItem(offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);
                ((com.matuvent.mineturn.mixin.StatusAccess)player).mineturn$useRemaining(item.getUseDuration(player)-5);
                owner.guarding=true;owner.guardConsumed=false;
            }
            @Override public boolean canLeap(){return actor==source && leapRoute(source,target)!=null;}
            @Override public void leap(){
                requireActive();var route=leapRoute(source,target);
                if(route==null || actor!=source)throw new IllegalArgumentException("跳扑路线已失效。");
                budget.move(route.cost());motion=new Movement(source,route,"leap");
                motion.onLanding=()->{
                    if(!closed && members.containsKey(target.getUUID()) && target.isAlive() && enemy(source,target)
                            && source.hasLineOfSight(target) && BattleManager.gap(source.getBoundingBox(),target.getBoundingBox())<=1.5){
                        var attack=definitions.actions().get("mineturn:species_melee");
                        if(attack!=null)runEffect(source,target,ItemStack.EMPTY,attack,CombatEffects.get(attack.effect())::execute);
                    }
                };
                revision++;syncAll();
            }
            @Override public void guardianBeam(double duration){requireActive();owner.beamRange=action.range();BattleSpecies.beginBeam(BattleSession.this,source,target,duration);}
            @Override public void endGuardianBeam(){requireActive();BattleSpecies.clearBeam(owner);}
            @Override public void changeActions(int mainDelta,int bonusDelta){
                requireActive();if(actor!=source)throw new IllegalStateException("Only the current actor can gain actions");
                budget.setActions(Math.addExact(budget.mainActions(),mainDelta),Math.addExact(budget.bonusActions(),bonusDelta));revision++;
            }
            @Override public void spendBonusAction(){requireActive();if(actor!=source)throw new IllegalStateException("Not actor");budget.bonusAct();revision++;}
            @Override public boolean displace(LivingEntity victim,Vec3 offset){
                requireActive();if(victim instanceof BattleDevice)return false;
                if(motion!=null || !members.containsKey(victim.getUUID()) || !victim.isAlive() || victim.level()!=level)throw new IllegalArgumentException("Displacement target unavailable");
                var route=BattleDisplacement.trace(BattleSession.this,victim,offset,false,p->true);
                if(route.cost()<0.01)return false;
                BattleDisplacement.apply(BattleSession.this,victim,route);
                revision++;syncAll();return true;
            }
            @Override public void lingering(net.minecraft.world.item.alchemy.PotionContents potion){
                requireActive();if(clouds.size()>=32)throw new IllegalStateException("Too many lingering clouds");
                var cloud=new BattleCloud(BattleSession.this,source,impact==null?target.position():impact,potion);
                if(level.addFreshEntity(cloud))clouds.add(cloud);
            }
            @Override public List<LivingEntity> participants(){return closed?List.of():members.values().stream().map(m->m.entity).filter(LivingEntity::isAlive).toList();}
            @Override public Vec3 impactPosition(){return impact;}
            @Override public ItemStack ammunition(){return ammunition.copy();}
            @Override public List<LivingEntity> enemies() {
                if (closed || members.get(source.getUUID()) != owner) return List.of();
                return List.copyOf(BattleSession.this.enemies(source));
            }
            @Override public void hurt(LivingEntity victim, float amount) {
                requireActive();
                if (!Float.isFinite(amount) || amount < 0 || amount > 1000) throw new IllegalArgumentException("Invalid skill damage");
                if (members.containsKey(victim.getUUID()) && victim.isAlive() && victim.level() == level && enemy(source, victim)) {
                    victim.invulnerableTime = 0;
                    victim.hurt(source instanceof ServerPlayer p ? source.damageSources().playerAttack(p) : source.damageSources().mobAttack(source), amount);
                }
            }
            @Override public boolean canSchedule(double delay){
                return level.getServer().isSameThread() && !closed && members.get(source.getUUID())==owner && source.isAlive()
                        && source.level()==level && Double.isFinite(delay) && delay>=0.01 && delay<=100000 && scheduled.size()<128;
            }
            @Override public boolean cancelScheduled(UUID ticket){
                requireActive();Objects.requireNonNull(ticket);
                return scheduled.removeIf(timer->timer.owner==owner && ticket.equals(timer.ticket));
            }
            @Override public void after(double delay,java.util.function.Consumer<CombatEffects.Context> continuation){
                afterChecked(delay,ignored->true,continuation);
            }
            @Override public UUID afterChecked(double delay,java.util.function.Predicate<CombatEffects.Context> check,java.util.function.Consumer<CombatEffects.Context> continuation){
                requireActive();
                if(!canSchedule(delay))throw new IllegalArgumentException("Invalid skill AV delay or full queue");
                Objects.requireNonNull(check);Objects.requireNonNull(continuation);
                ItemStack snapshot=item.copy(),ammoSnapshot=ammunition.copy();
                Member intended=members.get(target.getUUID());
                if(intended==null || !target.isAlive() || target.level()!=level)throw new IllegalArgumentException("Delayed target unavailable");
                UUID ticket=UUID.randomUUID();
                scheduled.add(new Scheduled(clock.time()+delay,scheduleOrder++,owner,()->{
                    if(members.get(source.getUUID())!=owner || !source.isAlive() || source.level()!=level
                            || members.get(target.getUUID())!=intended || !target.isAlive() || target.level()!=level)return;
                    // Preflight cannot use the mutation APIs, even if this task runs inside another effect.
                    var preview=effectContext(source,target,snapshot.copy(),action,false,ammoSnapshot.copy(),impact);
                    if(check.test(preview))runEffect(source,target,snapshot,action,continuation,ammoSnapshot,impact);
                },ticket,intended));
                return ticket;
            }
        };
        return context[0] = new CombatEffects.Context(source, target, item, action, clock.time(), access);
    }
    void runEffect(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, java.util.function.Consumer<CombatEffects.Context> effect) {
        runEffect(source,target,item,action,effect,ItemStack.EMPTY);
    }
    private void runEffect(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, java.util.function.Consumer<CombatEffects.Context> effect,ItemStack ammunition) {
        runEffect(source,target,item,action,effect,ammunition,null);
    }
    private void runEffect(LivingEntity source, LivingEntity target, ItemStack item, CombatData.Action action, java.util.function.Consumer<CombatEffects.Context> effect,ItemStack ammunition,Vec3 impact) {
        if(impact==null)faceTarget(source,target);
        else source.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,impact);
        var previous = activeEffect;
        var context = effectContext(source, target, item, action, true,ammunition,impact);
        activeEffect = context;
        try { BattleManager.authorized(() -> effect.accept(context)); }
        finally { activeEffect = previous; }
    }
    void callback(Member member, String event, String action, UUID target) {
        callback(member,event,action,target,true);
    }
    void callback(Member member, String event, String action, UUID target, boolean success) {
        if (member.brain == null || member.brain.functions() == null) return;
        ResourceLocation function = member.brain.functions().callbacks().get(event);
        if (function != null && callbacks.size() < 128) callbacks.add(new FunctionAi.Invocation(member, function, event, action, target,success));
    }
    void drainCallbacks() {
        if (drainingCallbacks) return;
        drainingCallbacks = true;
        try {
            int count = 0;
            while (!callbacks.isEmpty() && count++ < 64) FunctionAi.run(this, callbacks.removeFirst());
            if (!callbacks.isEmpty()) { callbacks.clear(); MineTurn.LOGGER.error("AI callback limit reached for battle {}", id); message("AI 回调次数超过上限，已清理剩余回调，请检查数据包逻辑。"); }
        } finally { drainingCallbacks = false; }
    }
    void schedule(Member member, ResourceLocation function, double delay, UUID target) {
        if (closed || members.get(member.entity.getUUID()) != member || !member.entity.isAlive()) throw new IllegalArgumentException("已离开战斗，不能安排函数。");
        if (scheduled.size() >= 128) throw new IllegalArgumentException("当前战斗的 AV 定时函数已满。");
        var definition = level.getServer().getFunctions().get(function).orElseThrow(() -> new IllegalArgumentException("函数不存在：" + function));
        try { definition.instantiate(member.brain.functions().parameters().copy(), level.getServer().getCommands().getDispatcher()); }
        catch (Exception ex) { throw new IllegalArgumentException("函数参数不匹配：" + function, ex); }
        scheduled.add(new Scheduled(clock.time() + delay, scheduleOrder++, member, () -> {
            callbacks.add(new FunctionAi.Invocation(member, function, "scheduled", "", target)); drainCallbacks();
        }));
    }
    void runScheduledBeforeNextTurn() {
        advanceEvents(false);
    }
    private void advanceEvents(boolean toTurn) {
        int count=0,steps=0;
        while(!closed && !members.isEmpty() && (toTurn || !scheduled.isEmpty() || !returningTridents.isEmpty() || !summons.isEmpty() || !fields.isEmpty())) {
            if(++steps>10000)throw new IllegalStateException("AV 状态推进超过安全上限");
            double turnAt=clock.time()+clock.nextDelay(this::agility);
            double statusAt=members.values().stream().mapToDouble(m->m.nextStatusAv).min().orElse(Double.POSITIVE_INFINITY);
            double timerAt=scheduled.isEmpty()?Double.POSITIVE_INFINITY:scheduled.peek().at;
            double cloudAt=clouds.stream().mapToDouble(c->c.nextAv).min().orElse(Double.POSITIVE_INFINITY);
            double returnAt=returningTridents.stream().mapToDouble(BattleTridents.Returning::at).min().orElse(Double.POSITIVE_INFINITY);
            double next=Math.min(BattleFields.next(this),Math.min(BattleSummons.next(this),Math.min(returnAt,Math.min(cloudAt,Math.min(Math.min(statusAt,timerAt),nextRegen)))));
            if(next>turnAt+1e-7) { if(toTurn)clock.advance(Math.max(0,turnAt-clock.time()),this::agility); return; }
            clock.advance(Math.max(0,next-clock.time()),this::agility);
            // Statuses at the same timestamp run before pending skills and the next actor.
            for(Member member:new ArrayList<>(members.values()))if(member.nextStatusAv<=clock.time()+1e-7){
                member.nextStatusAv+=BattleStatus.AV_PER_TICK;
                if(member.entity.isAlive() && member.entity.level()==level)BattleStatus.tick(member);
                if(member.mountState!=null && member.mountState.entity.isAlive() && member.mountState.entity.level()==level)BattleStatus.tick(member.mountState);
                BattleRiding.maintain(this,member);
            }
            BattleSummons.advance(this);
            BattleFields.advance(this);
            regenerate();
            BattleTridents.advance(this);
            for(var cloud:new ArrayList<>(clouds))if(!cloud.isRemoved() && cloud.nextAv<=clock.time()+1e-7)cloud.advance();
            clouds.removeIf(net.minecraft.world.entity.Entity::isRemoved);
            for (Member member : new ArrayList<>(members.values()))
                if (!BattleManager.eligible(member.entity) || member.entity.level() != level) remove(member.entity, "已离开战斗。");
            if (!hasOpponents()) { close("战斗结束。"); return; }
            while(!scheduled.isEmpty() && scheduled.peek().at<=clock.time()+1e-7){
                if(++count>128){scheduled.clear();message("AV 任务触发过多，已清理队列。");break;}
                var timer=scheduled.remove();
                try {timer.task.run();}catch(RuntimeException error){MineTurn.LOGGER.error("Delayed combat effect failed in battle {}",id,error);}
            }
            for(Member member:new ArrayList<>(members.values()))if(!BattleManager.eligible(member.entity)||member.entity.level()!=level)remove(member.entity,"已离开战斗。");
            if(!hasOpponents()){close("战斗结束。");return;}
            if(toTurn && clock.nextDelay(this::agility)<=1e-7)return;
        }
    }
    void ai(Mob mob) {
        if(mob instanceof BattleBullet bullet){BattleRealm.bulletTurn(this,bullet);return;}
        Member own = member(mob);
        if(mob instanceof net.minecraft.world.entity.animal.axolotl.Axolotl a && a.isPlayingDead())return;
        if(mob instanceof net.minecraft.world.entity.animal.Bee bee && bee.hasStung())return;
        if(BattleAerial.turn(this,own))return;
        if(own.chargeUntil>clock.time())return;
        if(BattleSpecies.busy(mob))return;
        if (own.brain.functions() != null) {
            callback(own, "on_turn", "", own.aiTarget); drainCallbacks(); return;
        }
        LivingEntity target = nearestEnemy(mob);
        if (target == null) return;
        Member member = member(mob);
        var brain = member.brain;
        boolean inReach = BattleManager.gap(mob.getBoundingBox(), target.getBoundingBox()) <= brain.reach() && mob.hasLineOfSight(target);
        var state = brain.states().get(member.state);
        if (state == null) return;
        for (var transition : state.transitions()) {
            if (transition.condition().equals("in_reach") == inReach) { member.state = transition.to(); break; }
        }
        state = brain.states().get(member.state);
        if (state == null) return;
        if (state.behavior().equals("approach")) {
            if (engaged(mob)) budget.disengage();
            var route=pursuitRoute(mob,target);
            if(route.cost()>=0.01){budget.move(route.cost());motion=new Movement(mob,route,animationMode(mob,route));revision++;syncAll();}
            message(mob.getName().getString() + " 正在追击 " + target.getName().getString() + "。");
        } else if (state.behavior().equals("weighted_action")) {
            List<CombatData.Choice> choices = state.choices().stream().filter(choice -> {
                var action = definitions.actions().get(choice.action());
                try { requireAmmo(mob,action); } catch(IllegalArgumentException error){return false;}
                return budget.canPay(action.cost()) && ready(mob, choice.action()) && action.consume() == 0 && CombatEffects.get(action.effect()).validate(
                        effectContext(mob, action.self() ? mob : target, ItemStack.EMPTY, action, false)) == null
                        && (action.self() || mob.hasLineOfSight(target) && (action.ranged()!=null || BattleManager.gap(mob.getBoundingBox(), target.getBoundingBox()) <= action.range()));
            }).toList();
            int total = choices.stream().mapToInt(CombatData.Choice::weight).sum();
            if (total == 0) return;
            int roll = mob.getRandom().nextInt(total);
            for (var choice : choices) if ((roll -= choice.weight()) < 0) {
                var action = definitions.actions().get(choice.action());
                budget.spend(action.cost()); execute(mob, action.self() ? mob : target, choice.action(), action, ItemStack.EMPTY); break;
            }
        }
    }
    /** Shared tactical pursuit for the state machine and opt-in asynchronous functions. */
    TerrainPath.Result diveRoute(LivingEntity source,LivingEntity target,double range){
        if(motion!=null || !source.isAlive() || !target.isAlive() || !budget.canMove() || engaged(source)
                || !movementMode(source).equals("flying") || source.getY()-target.getY()<1.5
                || !source.hasLineOfSight(target) || BattleManager.gap(source.getBoundingBox(),target.getBoundingBox())>range)return null;
        Vec3 start=source.position(),radial=start.subtract(target.position()).multiply(1,0,1);
        if(radial.lengthSqr()<1e-6)radial=new Vec3(1,0,0);
        double stop=(source.getBbWidth()+target.getBbWidth())/2+0.35;
        Vec3 end=target.position().add(radial.normalize().scale(stop)).add(0,target.getBbHeight()*0.35,0);
        double cost=MovementDistance.between(start,end);
        if(cost<0.01 || cost>budget.remaining()+1e-6 || cost>40)return null;
        int steps=Math.max(1,(int)Math.ceil(cost/0.05));var samples=new ArrayList<Vec3>();Vec3 last=start;
        var control=movementControl(source,false);
        for(int i=1;i<=steps;i++){
            double t=i/(double)steps;
            Vec3 point=new Vec3(start.x+(end.x-start.x)*t,start.y+(end.y-start.y)*t*t,start.z+(end.z-start.z)*t);
            if(!SpatialPath.canStep(source,last,point,false) || !control.test(point))return null;
            samples.add(point);last=point;
        }
        return new TerrainPath.Result(end,cost,List.of(),samples);
    }
    TerrainPath.Result leapRoute(LivingEntity source,LivingEntity target){
        if(motion!=null || engaged(source) || !source.hasLineOfSight(target) || !budget.canMove()
                || Math.abs(source.getY()-target.getY())>1 || AquaticPath.inWater(level,source.position()))return null;
        double gap=BattleManager.gap(source.getBoundingBox(),target.getBoundingBox());
        if(gap<=1.5 || gap>4)return null;
        Vec3 start=source.position(),offset=target.position().subtract(start);
        double stop=(source.getBbWidth()+target.getBbWidth())/2+0.55;
        Vec3 end=target.position().subtract(offset.multiply(1,0,1).normalize().scale(stop));
        var body=source.getBoundingBox().move(end.subtract(start)).deflate(1e-6);
        if(level.noCollision(source,body.move(0,-0.08,0)))return null;
        int steps=80;Vec3 last=start;double cost=0;var samples=new ArrayList<Vec3>();
        var control=movementControl(source,false);
        for(int i=1;i<=steps;i++){
            double t=i/(double)steps;Vec3 point=start.lerp(end,t).add(0,4*0.75*t*(1-t),0);
            if(!SpatialPath.canStep(source,last,point,false) || !control.test(point))return null;
            cost+=MovementDistance.between(last,point);samples.add(point);last=point;
        }
        return cost>budget.remaining()+1e-6?null:new TerrainPath.Result(end,cost,List.of(),samples);
    }
    TerrainPath.Result pursuitRoute(LivingEntity entity,LivingEntity target) {
        // Search the connected water volume before flattening the target's height for ground AI.
        // This also admits a supported shoreline, without allowing flight across dry gaps.
        if(movementMode(entity).equals("ground")
                && AquaticPath.inWater(level,target.position()) && budget.canMove() && (!engaged(entity) || budget.disengaged())) {
            double stop=(entity.getBbWidth()+target.getBbWidth())/2+member(entity).brain.reach()*0.65;
            Vec3 toward=entity.position().subtract(target.position()).multiply(1,0,1);
            Vec3 near=toward.lengthSqr()<1e-8?new Vec3(stop,0,0):toward.normalize().scale(stop);
            for(Vec3 side:List.of(near,new Vec3(-near.z,0,near.x),new Vec3(near.z,0,-near.x),near.scale(-1))) {
                var route=SpatialRoutes.pursue(entity,target.position().add(side),budget.remaining(),"aquatic",()->movementControl(entity,budget.disengaged()));
                if(route!=null)return route;
            }
        }
        Vec3 offset=towardOffset(entity,target);
        double stop=(entity.getBbWidth()+target.getBbWidth())/2+member(entity).brain.reach()*0.65;
        if(movementMode(entity).equals("phasing"))stop=(entity.getBbWidth()+target.getBbWidth())/2+0.1;
        Vec3 desired=offset.normalize().scale(Math.max(0,offset.length()-stop));
        double desiredCost=MovementDistance.spatial(desired);
        double travel=Math.min(budget.remaining(),desiredCost);
        if(!budget.canMove() || engaged(entity) && !budget.disengaged())return new TerrainPath.Result(entity.position(),0,List.of());
        // Horizontal proximity does not imply reachability: the target may be on a higher ledge.
        // A zero-length direct approach must still try routes to the target's elevation.
        var route=travel<0.01?new TerrainPath.Result(entity.position(),0,List.of())
                :path(entity,desired.scale(travel/desiredCost),budget.disengaged());
        if(travel>=0.01 && route.cost()+1e-5>=travel)return route;
        if(travel<0.01 && entity.hasLineOfSight(target)
                && BattleManager.gap(entity.getBoundingBox(),target.getBoundingBox())<=member(entity).brain.reach())return route;
        String mode=movementMode(entity);
        Vec3 radial=offset.lengthSqr()<1e-8?new Vec3(-stop,0,0):offset.normalize().scale(-stop);
        var sides=new ArrayList<>(List.of(radial,new Vec3(-radial.z,0,radial.x),new Vec3(radial.z,0,-radial.x),radial.scale(-1)));
        if(!mode.equals("ground")){sides.add(new Vec3(0,stop,0));sides.add(new Vec3(0,-stop,0));}
        for(Vec3 side:sides) {
            var detour=mode.equals("ground")?GroundRoutes.pursue(entity,target.position().add(side),budget.remaining(),()->movementControl(entity,budget.disengaged()))
                    :SpatialRoutes.pursue(entity,target.position().add(side),budget.remaining(),mode,()->movementControl(entity,budget.disengaged()));
            if(detour!=null)return detour;
        }
        return route;
    }
}
