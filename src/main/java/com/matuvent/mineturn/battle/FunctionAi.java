package com.matuvent.mineturn.battle;

import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.api.CombatEffects;
import com.matuvent.mineturn.data.CombatData;
import com.matuvent.mineturn.mixin.CommandsAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.TraceCallbacks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;

/** Native mcfunction execution with a per-invocation actor context; no blanket damage authorization. */
public final class FunctionAi {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    static final int COMMAND_LIMIT = 10000;
    record Invocation(BattleSession.Member member, ResourceLocation function, String event, String action, java.util.UUID target, boolean success) {
        Invocation(BattleSession.Member member, ResourceLocation function, String event, String action, java.util.UUID target) { this(member,function,event,action,target,true); }
    }
    static final class Frame {
        final BattleSession battle;
        final Invocation call;
        java.util.UUID target;
        boolean ended;
        Frame(BattleSession battle, Invocation call) { this.battle = battle; this.call = call; target = call.target; }
        LivingEntity self() { return call.member.entity; }
        LivingEntity target() {
            var member = battle.members.get(target);
            if (member == null || !member.entity.isAlive() || !battle.enemy(self(), member.entity)) throw new IllegalArgumentException("没有合法的敌方目标。");
            return member.entity;
        }
        void requireTurn() {
            if (!(call.event.equals("on_turn") || call.event.equals("on_move_finished")) || ended || battle.motion!=null || battle.closed || battle.actor != self()
                    || !self().isAlive() || battle.members.get(self().getUUID()) != call.member)
                throw new IllegalArgumentException("当前函数没有行动权限。");
        }
    }
    private FunctionAi() {}

    static void run(BattleSession battle, Invocation call) {
        if (call.member.aiFailed && !call.event.equals("on_leave")) return;
        var server = battle.level.getServer();
        Frame previous = CURRENT.get();
        var nativeContext = CommandsAccessor.mineturn$contexts();
        var previousNative = nativeContext.get();
        Frame frame = new Frame(battle, call);
        var source = call.member.entity.createCommandSourceStack().withPermission(2).withSuppressedOutput();
        try (var execution = new ExecutionContext<CommandSourceStack>(COMMAND_LIMIT + 1, 128, server.getProfiler()) {
            int used;
            @Override public void incrementCost() {
                super.incrementCost();
                if (++used > COMMAND_LIMIT) throw new IllegalStateException("AI function exceeded " + COMMAND_LIMIT + " commands");
            }
        }) {
            CURRENT.set(frame); nativeContext.set(execution);
            execution.tracer(new TraceCallbacks() {
                boolean reported;
                public void onCommand(int depth, String command) {}
                public void onReturn(int depth, String command, int result) {}
                public void onCall(int depth, ResourceLocation function, int count) {}
                public void onError(String error) {
                    if (!reported) { reported = true; MineTurn.LOGGER.warn("AI {} / {}: {}", call.function, call.event, error); }
                }
                public void close() {}
            });
            var function = server.getFunctions().get(call.function).orElseThrow(() -> new IllegalArgumentException("Missing AI function " + call.function));
            var compiled = function.instantiate(call.member.brain.functions().parameters().copy(), server.getCommands().getDispatcher());
            ExecutionContext.queueInitialFunctionCall(execution, compiled, source, CommandResultCallback.EMPTY);
            execution.runCommandQueue();
        } catch (Exception error) {
            call.member.aiFailed = true;
            MineTurn.LOGGER.error("Disabled AI for {}: {} / {}", call.member.entity.getUUID(), call.function, call.event, error);
            battle.message("生物 AI 函数异常，已暂停其 AI：" + call.function);
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (previousNative == null) nativeContext.remove(); else nativeContext.set(previousNative);
        }
    }

    @FunctionalInterface private interface Operation { int run(Frame frame) throws Exception; }
    private static int command(CommandSourceStack source, Operation operation) {
        Frame frame = CURRENT.get();
        if (frame == null || source.getEntity() != frame.self() || source.getLevel() != frame.battle.level) {
            source.sendFailure(Component.literal("此命令只能由 MineTurn 生物回调中的原行动者执行。")); return 0;
        }
        try { return operation.run(frame); }
        catch (Exception error) { source.sendFailure(Component.literal(error.getMessage() == null ? "AI 操作失败。" : error.getMessage())); return 0; }
    }
    private static int scaled(double number) { return (int) Math.clamp(Math.round(number * 1000), Integer.MIN_VALUE, Integer.MAX_VALUE); }
    private static int query(Frame frame, String key) {
        var battle = frame.battle;
        return switch (key) {
            case "has_target" -> frame.target != null && battle.members.containsKey(frame.target) && battle.members.get(frame.target).entity.isAlive() ? 1 : 0;
            case "can_act" -> (frame.call.event.equals("on_turn") || frame.call.event.equals("on_move_finished")) && !frame.ended && battle.motion==null && !battle.closed && frame.self().isAlive()
                    && battle.actor == frame.self() && battle.budget.canAct() ? 1 : 0;
            case "movement" -> battle.actor == frame.self() ? scaled(battle.budget.remaining()) : 0;
            case "main_actions" -> battle.actor==frame.self()?battle.budget.mainActions():0;
            case "bonus_actions" -> battle.actor==frame.self()?battle.budget.bonusActions():0;
            case "health_ratio" -> scaled(frame.self().getHealth() / frame.self().getMaxHealth());
            case "health" -> scaled(frame.self().getHealth());
            case "time" -> scaled(battle.clock.time());
            case "engaged" -> battle.engaged(frame.self()) ? 1 : 0;
            case "distance" -> scaled(BattleManager.gap(frame.self().getBoundingBox(), frame.target().getBoundingBox()));
            case "height_difference" -> scaled(frame.self().getY()-frame.target().getY());
            case "in_attack_range" -> BattleManager.gap(frame.self().getBoundingBox(), frame.target().getBoundingBox()) <= frame.call.member.brain.reach()
                    && frame.self().hasLineOfSight(frame.target()) ? 1 : 0;
            case "event_success" -> (frame.call.event.equals("on_action_resolved") || frame.call.event.equals("on_move_finished")) && frame.call.success ? 1 : 0;
            default -> throw new IllegalArgumentException("未知查询：" + key);
        };
    }
    private static int select(Frame frame, String strategy) {
        var enemies = frame.battle.tacticalEnemies(frame.self());
        Comparator<LivingEntity> order = switch (strategy) {
            case "nearest_enemy" -> Comparator.comparingDouble(frame.self()::distanceToSqr);
            case "alternate_enemy" -> Comparator.<LivingEntity>comparingInt(e->e.getUUID().equals(frame.call.member.lastBeamTarget)?1:0).thenComparingDouble(frame.self()::distanceToSqr);
            case "blaze_first" -> Comparator.<LivingEntity>comparingInt(e->e instanceof net.minecraft.world.entity.monster.Blaze?0:1).thenComparingDouble(frame.self()::distanceToSqr);
            case "lowest_health" -> Comparator.comparingDouble(LivingEntity::getHealth);
            case "farthest_enemy" -> Comparator.<LivingEntity>comparingDouble(frame.self()::distanceToSqr).reversed();
            default -> throw new IllegalArgumentException("未知目标策略。");
        };
        if(strategy.equals("nearest_enemy")){var preferred=frame.battle.preferredEnemy(frame.self());
            order=Comparator.<LivingEntity>comparingInt(e->e==preferred?0:1).thenComparing(order);}
        var target = enemies.stream().min(order).orElse(null);
        frame.target = target == null ? null : target.getUUID();
        frame.call.member.aiTarget = frame.target;
        return target == null ? 0 : 1;
    }
    private static CombatData.Action validateUse(Frame frame, String id) {
        frame.requireTurn();
        var battle = frame.battle;
        
        var action = battle.definitions.actions().get(id);
        if (action == null) throw new IllegalArgumentException("动作不存在：" + id);
        battle.budget.require(action.cost());
        var target = action.self() ? frame.self() : frame.target();
        if (!action.self() && (!frame.self().hasLineOfSight(target)
                || action.ranged() == null && BattleManager.gap(frame.self().getBoundingBox(), target.getBoundingBox()) > action.range())) throw new IllegalArgumentException("目标超出范围或被遮挡。");
        if (!battle.ready(frame.self(), id)) throw new IllegalArgumentException("动作仍在冷却。");
        var stack = frame.self().getMainHandItem();
        if (stack.getCount() < action.consume()) throw new IllegalArgumentException("主手物品数量不足。");
        battle.requireAmmo(frame.self(),action);
        String error = CombatEffects.get(action.effect()).validate(battle.effectContext(frame.self(), target, stack, action, false));
        if (error != null) throw new IllegalArgumentException(error);
        return action;
    }
    private static int use(Frame frame, String id) {
        var action = validateUse(frame, id);
        frame.battle.budget.spend(action.cost());
        frame.battle.execute(frame.self(), action.self() ? frame.self() : frame.target(), id, action, frame.self().getMainHandItem());
        frame.battle.revision++;
        return 1;
    }
    private static int move(Frame frame, Vec3 offset) {
        return move(frame,offset,false);
    }
    private static int move(Frame frame, Vec3 offset,boolean asynchronous) {
        frame.requireTurn();
        var battle = frame.battle;
        if (!battle.budget.canMove() || battle.engaged(frame.self()) && !battle.budget.disengaged()) throw new IllegalArgumentException("移动距离不足或需要先撤退。");
        double length = MovementDistance.spatial(offset);
        if (!Double.isFinite(length) || length < 0.01 || length > battle.budget.remaining() + 1e-6) return 0;
        var route = battle.path(frame.self(), offset, battle.budget.disengaged());
        return startRoute(frame,route,asynchronous);
    }
    private static int startRoute(Frame frame,TerrainPath.Result route,boolean asynchronous) {
        var battle=frame.battle;
        if (route.cost() < 0.01) return 0;
        if(asynchronous) {
            battle.budget.move(route.cost());
            battle.motion=new BattleSession.Movement(frame.self(),route,battle.animationMode(frame.self(),route));
            battle.motion.functionMove=true;battle.motion.target=frame.target;
            frame.ended=true;
        } else battle.commitMovement(frame.self(), route);
        battle.revision++;
        return 1;
    }
    private static int toward(Frame frame) {
        return toward(frame,false);
    }
    private static int toward(Frame frame,boolean asynchronous) {
        frame.requireTurn();
        var target = frame.target();
        if(asynchronous) {
            if(!frame.battle.budget.canMove() || frame.battle.engaged(frame.self()) && !frame.battle.budget.disengaged())
                throw new IllegalArgumentException("移动距离不足或需要先撤退。");
            return startRoute(frame,frame.battle.pursuitRoute(frame.self(),target),true);
        }
        Vec3 offset = frame.battle.towardOffset(frame.self(), target);
        double stop = (frame.self().getBbWidth() + target.getBbWidth()) / 2 + frame.call.member.brain.reach() * 0.65;
        Vec3 desired=offset.normalize().scale(Math.max(0,offset.length()-stop));
        double cost=MovementDistance.spatial(desired);
        return move(frame,desired.scale(cost>0?Math.min(1,frame.battle.budget.remaining()/cost):0),asynchronous);
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("ai").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("wander").executes(ctx->command(ctx.getSource(),frame->{
            frame.requireTurn();var b=frame.battle;
            if(!b.budget.canMove() || b.engaged(frame.self()) && !b.budget.disengaged())return 0;
            for(int i=0;i<12;i++){
                var random=frame.self().getRandom();var offset=new Vec3(random.nextDouble()*2-1,random.nextDouble()-0.5,random.nextDouble()*2-1);
                offset=offset.scale(b.budget.remaining()/Math.max(0.001,MovementDistance.spatial(offset)));
                var route=b.path(frame.self(),offset,b.budget.disengaged());if(route.cost()>0.1)return startRoute(frame,route,true);
            }return 0;
        })));
        root.then(Commands.literal("move_async")
                .then(Commands.literal("toward_target").executes(ctx->command(ctx.getSource(),frame->toward(frame,true))))
                .then(Commands.argument("dx",DoubleArgumentType.doubleArg(-40,40))
                .then(Commands.argument("dy",DoubleArgumentType.doubleArg(-40,40))
                .then(Commands.argument("dz",DoubleArgumentType.doubleArg(-40,40))
                .executes(ctx->command(ctx.getSource(),frame->move(frame,new Vec3(DoubleArgumentType.getDouble(ctx,"dx"),DoubleArgumentType.getDouble(ctx,"dy"),DoubleArgumentType.getDouble(ctx,"dz")),true)))))));
        root.then(Commands.literal("target").then(Commands.argument("strategy", StringArgumentType.word())
                .executes(ctx -> command(ctx.getSource(), frame -> select(frame, StringArgumentType.getString(ctx, "strategy"))))));
        root.then(Commands.literal("target_entity").then(Commands.argument("target", EntityArgument.entity())
                .executes(ctx -> command(ctx.getSource(), frame -> {
                    var entity = EntityArgument.getEntity(ctx, "target");
                    if (!(entity instanceof LivingEntity living) || !frame.battle.members.containsKey(entity.getUUID())
                            || !living.isAlive() || !frame.battle.enemy(frame.self(), living)) return 0;
                    frame.target = entity.getUUID(); frame.call.member.aiTarget = frame.target; return 1;
                }))));
        root.then(Commands.literal("query").then(Commands.argument("key", StringArgumentType.word())
                .executes(ctx -> command(ctx.getSource(), frame -> query(frame, StringArgumentType.getString(ctx, "key"))))));
        root.then(Commands.literal("query").then(Commands.literal("ready").then(Commands.argument("action", ResourceLocationArgument.id())
                .executes(ctx -> command(ctx.getSource(), frame -> { try { validateUse(frame, ResourceLocationArgument.getId(ctx, "action").toString()); return 1; } catch (IllegalArgumentException error) { return 0; } })))));
        root.then(Commands.literal("query").then(Commands.literal("event_action").then(Commands.argument("action", ResourceLocationArgument.id())
                .executes(ctx -> command(ctx.getSource(), frame -> frame.call.action.equals(ResourceLocationArgument.getId(ctx, "action").toString()) ? 1 : 0)))));
        root.then(Commands.literal("use").then(Commands.argument("action", ResourceLocationArgument.id())
                .executes(ctx -> command(ctx.getSource(), frame -> use(frame, ResourceLocationArgument.getId(ctx, "action").toString())))));
        root.then(Commands.literal("move").then(Commands.literal("toward_target").executes(ctx -> command(ctx.getSource(), FunctionAi::toward)))
                .then(Commands.argument("dx", DoubleArgumentType.doubleArg(-40, 40)).then(Commands.argument("dz", DoubleArgumentType.doubleArg(-40, 40))
                        .executes(ctx -> command(ctx.getSource(), frame -> move(frame, new Vec3(DoubleArgumentType.getDouble(ctx, "dx"), 0, DoubleArgumentType.getDouble(ctx, "dz"))))))));
        root.then(Commands.literal("sprint").executes(ctx -> command(ctx.getSource(), frame -> { frame.requireTurn(); frame.battle.budget.sprint(); return 1; })));
        root.then(Commands.literal("move3d").then(Commands.argument("dx", DoubleArgumentType.doubleArg(-40, 40))
                .then(Commands.argument("dy", DoubleArgumentType.doubleArg(-40, 40)).then(Commands.argument("dz", DoubleArgumentType.doubleArg(-40, 40))
                        .executes(ctx -> command(ctx.getSource(), frame -> move(frame, new Vec3(DoubleArgumentType.getDouble(ctx, "dx"), DoubleArgumentType.getDouble(ctx, "dy"), DoubleArgumentType.getDouble(ctx, "dz")))))))));
        root.then(Commands.literal("retreat").executes(ctx -> command(ctx.getSource(), frame -> { frame.requireTurn(); frame.battle.budget.disengage(); return 1; })));
        root.then(Commands.literal("wait").executes(ctx -> command(ctx.getSource(), frame -> { frame.requireTurn(); frame.ended = true; return 1; })));
        root.then(Commands.literal("schedule").then(Commands.argument("delay_av", DoubleArgumentType.doubleArg(0.01, 100000))
                .then(Commands.argument("function", ResourceLocationArgument.id()).executes(ctx -> command(ctx.getSource(), frame -> {
                    frame.battle.schedule(frame.call.member, ResourceLocationArgument.getId(ctx, "function"), DoubleArgumentType.getDouble(ctx, "delay_av"), frame.target); return 1;
                })))));
        root.then(Commands.literal("inspect").then(Commands.argument("entity_type", ResourceLocationArgument.id()).executes(ctx -> {
            String entity = ResourceLocationArgument.getId(ctx, "entity_type").toString();
            var brain = CombatData.current.mobs().get(entity);
            String source = CombatData.current.sources().get("mobs/" + entity);
            StringBuilder description = new StringBuilder(source == null ? "未配置：" + entity : (brain == null ? "已禁用：" : "") + entity + " ← " + source);
            if (brain != null) {
                if (brain.functions() == null) description.append("\n旧状态机");
                else brain.functions().callbacks().forEach((event, function) -> {
                    String pack = ctx.getSource().getServer().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                            function.getNamespace(), "function/" + function.getPath() + ".mcfunction")).map(resource -> resource.sourcePackId()).orElse("missing");
                    description.append("\n").append(event).append(" → ").append(function).append(" [").append(pack).append("]");
                });
            }
            ctx.getSource().sendSuccess(() -> Component.literal(description.toString()), false);
            return brain == null ? 0 : 1;
        })));
        dispatcher.register(Commands.literal("mineturn").then(root));
    }
}
