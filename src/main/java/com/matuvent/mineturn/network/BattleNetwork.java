package com.matuvent.mineturn.network;

import com.matuvent.mineturn.battle.BattleManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** No client class references: safe to load on a dedicated server. */
public final class BattleNetwork {
    public static Consumer<State> receiveState = ignored -> {};
    public static Consumer<Preview> receivePreview = ignored -> {};
    public static Consumer<Motion> receiveMotion = ignored -> {};
    public static Consumer<Offers> receiveOffers=ignored->{};
    public static Consumer<Aim> receiveAim = ignored -> {};
    public static Consumer<StatusClock> receiveStatusClock = ignored -> {};
    public static Consumer<ProjectileVisual> receiveProjectile=ignored->{};
    public static Consumer<ActionAnimation> receiveActionAnimation=ignored->{};
    private BattleNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("21");
        registrar.playToClient(ProjectileVisual.TYPE,ProjectileVisual.CODEC,(payload,context)->receiveProjectile.accept(payload));
        registrar.playToServer(AmmoUse.TYPE,AmmoUse.CODEC,(payload,context)->{
            if(context.player() instanceof ServerPlayer player) BattleManager.request(player,payload.request(),payload);
        });
        registrar.playToClient(Offers.TYPE,Offers.CODEC,(payload,context)->receiveOffers.accept(payload));
        registrar.playToClient(Motion.TYPE, Motion.CODEC, (payload,context)->receiveMotion.accept(payload));
        registrar.playToClient(StatusClock.TYPE,StatusClock.CODEC,(payload,context)->receiveStatusClock.accept(payload));
        registrar.playToClient(Aim.TYPE, Aim.CODEC, (payload, context) -> receiveAim.accept(payload));
        registrar.playToServer(AimSubmit.TYPE, AimSubmit.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) BattleManager.submitAim(player, payload);
        });
        registrar.playToClient(State.TYPE, State.CODEC, (payload, context) -> receiveState.accept(payload));
        registrar.playToClient(Preview.TYPE, Preview.CODEC, (payload, context) -> receivePreview.accept(payload));
        registrar.playToServer(Request.TYPE, Request.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) BattleManager.request(player, payload);
        });
        registrar.playToClient(ActionAnimation.TYPE, ActionAnimation.CODEC, (payload, context) -> receiveActionAnimation.accept(payload));
    }
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        // Mock GameTest connections do not negotiate play channels.
        if (player.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(player, payload);
    }
    private static Vec3 vector(FriendlyByteBuf buf) { return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()); }
    private static void vector(FriendlyByteBuf buf, Vec3 value) { buf.writeDouble(value.x); buf.writeDouble(value.y); buf.writeDouble(value.z); }
    public record ItemAction(String id, String name, boolean self, boolean allied,String unavailable,int mainCost,int bonusCost,String ammo,int ammoCount,boolean ground,boolean teleport) {
        public ItemAction(String id,String name,boolean self,boolean allied,String unavailable,int mainCost,int bonusCost,String ammo,int ammoCount,boolean ground){this(id,name,self,allied,unavailable,mainCost,bonusCost,ammo,ammoCount,ground,false);}
        public ItemAction(String id,String name,boolean self,boolean allied,String unavailable,int mainCost,int bonusCost,String ammo,int ammoCount){this(id,name,self,allied,unavailable,mainCost,bonusCost,ammo,ammoCount,false);}
        public ItemAction(String id,String name,boolean self,boolean allied,String unavailable,int mainCost,int bonusCost){this(id,name,self,allied,unavailable,mainCost,bonusCost,"",0);}
        public ItemAction(String id,String name,boolean self,boolean allied,String unavailable){this(id,name,self,allied,unavailable,1,0);}
        public String costText(){return "消耗：主要行动 ×"+mainCost+"，次要行动 ×"+bonusCost;}
        public ItemAction(String id,String name,boolean self,String unavailable){this(id,name,self,false,unavailable);}
        static ItemAction read(FriendlyByteBuf buf) { return new ItemAction(buf.readUtf(256), buf.readUtf(256), buf.readBoolean(),buf.readBoolean(), buf.readUtf(512),buf.readVarInt(),buf.readVarInt(),buf.readUtf(256),buf.readVarInt(),buf.readBoolean(),buf.readBoolean()); }
        void write(FriendlyByteBuf buf) { buf.writeUtf(id, 256); buf.writeUtf(name, 256); buf.writeBoolean(self);buf.writeBoolean(allied); buf.writeUtf(unavailable, 512);buf.writeVarInt(mainCost);buf.writeVarInt(bonusCost);buf.writeUtf(ammo,256);buf.writeVarInt(ammoCount);buf.writeBoolean(ground);buf.writeBoolean(teleport); }
    }
    public record AmmoUse(Request request,int ammoSlot,net.minecraft.world.item.ItemStack expected) implements CustomPacketPayload {
        public static final Type<AmmoUse> TYPE=new Type<>(ResourceLocation.parse("mineturn:ammo_use"));
        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,AmmoUse> CODEC=StreamCodec.of((buf,value)->{
            Request.CODEC.encode(buf,value.request);buf.writeVarInt(value.ammoSlot);
            net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(buf,value.expected);
        },buf->new AmmoUse(Request.CODEC.decode(buf),buf.readVarInt(),net.minecraft.world.item.ItemStack.STREAM_CODEC.decode(buf)));
        @Override public Type<AmmoUse> type(){return TYPE;}
    }
    public record Slot(List<ItemAction> actions) {
        public Slot { actions = List.copyOf(actions); }
        static Slot read(FriendlyByteBuf buf) { return new Slot(buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, 32), ItemAction::read)); }
        void write(FriendlyByteBuf buf) { buf.writeCollection(actions, (out, action) -> action.write(out)); }
    }
    public record Fighter(int id, String name, boolean enemy, Vec3 position, float health, float maxHealth, double nextAv) {
        static Fighter read(FriendlyByteBuf buf) { return new Fighter(buf.readVarInt(), buf.readUtf(256), buf.readBoolean(), vector(buf), buf.readFloat(), buf.readFloat(), buf.readDouble()); }
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id); buf.writeUtf(name, 256); buf.writeBoolean(enemy); vector(buf, position);
            buf.writeFloat(health); buf.writeFloat(maxHealth); buf.writeDouble(nextAv);
        }
    }
    public record QueueEntry(int id, String name, double inAv) {
        static QueueEntry read(FriendlyByteBuf buf) { return new QueueEntry(buf.readVarInt(), buf.readUtf(256), buf.readDouble()); }
        void write(FriendlyByteBuf buf) { buf.writeVarInt(id); buf.writeUtf(name, 256); buf.writeDouble(inAv); }
    }
    public record State(UUID battle, long revision, boolean active, int enemyId, int actorId, double time,
                        Vec3 anchor, Vec3 enemyPosition, float enemyHealth, float enemyMaxHealth,
                        boolean canMove, boolean canAct, boolean engaged, boolean canFlee, double movement,
                        String message, List<Slot> slots, List<Fighter> fighters, List<QueueEntry> queue,
                        int mainActions,int bonusActions,int turnSeconds,long motionSequence,boolean moving) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(ResourceLocation.parse("mineturn:battle_state"));
        public static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.of((buf, value) -> value.write(buf), State::read);
        public State { slots = List.copyOf(slots); fighters = List.copyOf(fighters); queue = List.copyOf(queue); }
        @Override public Type<State> type() { return TYPE; }
        static State read(FriendlyByteBuf buf) {
            return new State(buf.readUUID(), buf.readVarLong(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(),
                    vector(buf), vector(buf), buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean(), buf.readDouble(), buf.readUtf(1024), buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, 10), Slot::read),
                    buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, 32), Fighter::read),
                    buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, 32), QueueEntry::read),buf.readVarInt(),buf.readVarInt(),buf.readVarInt(),buf.readVarLong(),buf.readBoolean());
        }
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(battle); buf.writeVarLong(revision); buf.writeBoolean(active); buf.writeVarInt(enemyId); buf.writeVarInt(actorId); buf.writeDouble(time);
            vector(buf, anchor); vector(buf, enemyPosition); buf.writeFloat(enemyHealth); buf.writeFloat(enemyMaxHealth);
            buf.writeBoolean(canMove); buf.writeBoolean(canAct); buf.writeBoolean(engaged); buf.writeBoolean(canFlee); buf.writeDouble(movement);
            buf.writeUtf(message, 1024); buf.writeCollection(slots, (out, slot) -> slot.write(out));
            buf.writeCollection(fighters, (out, fighter) -> fighter.write(out));
            buf.writeCollection(queue, (out, entry) -> entry.write(out));
            buf.writeVarInt(mainActions);buf.writeVarInt(bonusActions);buf.writeVarInt(turnSeconds);buf.writeVarLong(motionSequence);buf.writeBoolean(moving);
        }
        public static State closed(UUID id, long revision, String reason) {
            return new State(id, revision, false, -1, -1, 0, Vec3.ZERO, Vec3.ZERO, 0, 0, false, false, false, false, 0, reason, List.of(), List.of(), List.of(),0,0,-1,0,false);
        }
    }
    public record Request(UUID battle, long revision, String operation, int slot, String action, int target,
                          Vec3 destination, int previewId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.parse("mineturn:battle_request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, value) -> value.write(buf), Request::read);
        @Override public Type<Request> type() { return TYPE; }
        static Request read(FriendlyByteBuf buf) {
            return new Request(buf.readUUID(), buf.readVarLong(), buf.readUtf(24), buf.readVarInt(), buf.readUtf(256), buf.readVarInt(), vector(buf), buf.readVarInt());
        }
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(battle); buf.writeVarLong(revision); buf.writeUtf(operation, 24); buf.writeVarInt(slot); buf.writeUtf(action, 256);
            buf.writeVarInt(target); vector(buf, destination); buf.writeVarInt(previewId);
        }
    }
    public record Preview(UUID battle, long revision, int requestId, Vec3 destination, boolean valid, String reason, List<Vec3> path) implements CustomPacketPayload {
        public Preview { path = List.copyOf(path); if (path.size() > 256) throw new IllegalArgumentException("Preview path too long"); }
        public static final Type<Preview> TYPE = new Type<>(ResourceLocation.parse("mineturn:move_preview"));
        public static final StreamCodec<FriendlyByteBuf, Preview> CODEC = StreamCodec.of((buf, value) -> value.write(buf), Preview::read);
        @Override public Type<Preview> type() { return TYPE; }
        static Preview read(FriendlyByteBuf buf) { return new Preview(buf.readUUID(), buf.readVarLong(), buf.readVarInt(), vector(buf), buf.readBoolean(), buf.readUtf(512), buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, 256), BattleNetwork::vector)); }
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(battle); buf.writeVarLong(revision); buf.writeVarInt(requestId); vector(buf, destination); buf.writeBoolean(valid); buf.writeUtf(reason, 512);
            buf.writeCollection(path, BattleNetwork::vector);
        }
    }
    public record Motion(UUID battle, long sequence, Vec3 position, boolean active) implements CustomPacketPayload {
        public static final Type<Motion> TYPE=new Type<>(ResourceLocation.parse("mineturn:motion"));
        public static final StreamCodec<FriendlyByteBuf,Motion> CODEC=StreamCodec.of((buf,value)->{
            buf.writeUUID(value.battle);buf.writeVarLong(value.sequence); vector(buf,value.position); buf.writeBoolean(value.active);
        },buf->new Motion(buf.readUUID(),buf.readVarLong(),vector(buf),buf.readBoolean()));
        @Override public Type<Motion> type(){return TYPE;}
    }
    public record Offer(String id,String name,String icon,boolean self,boolean allied,String unavailable,int mainCost,int bonusCost) {
        public Offer(String id,String name,String icon,boolean self,boolean allied,String unavailable){this(id,name,icon,self,allied,unavailable,1,0);}
        public String costText(){return "消耗：主要行动 ×"+mainCost+"，次要行动 ×"+bonusCost;}
        public Offer(String id,String name,String icon,boolean self,String unavailable){this(id,name,icon,self,false,unavailable);}
        static Offer read(FriendlyByteBuf buf){return new Offer(buf.readUtf(256),buf.readUtf(64),buf.readUtf(256),buf.readBoolean(),buf.readBoolean(),buf.readUtf(512),buf.readVarInt(),buf.readVarInt());}
        void write(FriendlyByteBuf buf){buf.writeUtf(id,256);buf.writeUtf(name,64);buf.writeUtf(icon,256);buf.writeBoolean(self);buf.writeBoolean(allied);buf.writeUtf(unavailable,512);buf.writeVarInt(mainCost);buf.writeVarInt(bonusCost);}
    }
    public record Offers(UUID battle,long revision,List<Offer> actions) implements CustomPacketPayload {
        public Offers {actions=List.copyOf(actions);}
        public static final Type<Offers> TYPE=new Type<>(ResourceLocation.parse("mineturn:offers"));
        public static final StreamCodec<FriendlyByteBuf,Offers> CODEC=StreamCodec.of((buf,value)->{
            buf.writeUUID(value.battle);buf.writeVarLong(value.revision);buf.writeCollection(value.actions,(out,offer)->offer.write(out));
        },buf->new Offers(buf.readUUID(),buf.readVarLong(),buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new,128),Offer::read)));
        @Override public Type<Offers> type(){return TYPE;}
    }
    public record Aim(UUID battle, UUID token, boolean active, int durationMs, double elapsedMs, double low, double high) implements CustomPacketPayload {
        public static final Type<Aim> TYPE=new Type<>(ResourceLocation.parse("mineturn:aim"));
        public static final StreamCodec<FriendlyByteBuf,Aim> CODEC=StreamCodec.of((buf,v)->{
            buf.writeUUID(v.battle);buf.writeUUID(v.token);buf.writeBoolean(v.active);buf.writeVarInt(v.durationMs);
            buf.writeDouble(v.elapsedMs);buf.writeDouble(v.low);buf.writeDouble(v.high);
        },buf->new Aim(buf.readUUID(),buf.readUUID(),buf.readBoolean(),buf.readVarInt(),buf.readDouble(),buf.readDouble(),buf.readDouble()));
        @Override public Type<Aim> type(){return TYPE;}
    }
    public record AimSubmit(UUID battle, UUID token) implements CustomPacketPayload {
        public static final Type<AimSubmit> TYPE=new Type<>(ResourceLocation.parse("mineturn:aim_submit"));
        public static final StreamCodec<FriendlyByteBuf,AimSubmit> CODEC=StreamCodec.of((buf,v)->{buf.writeUUID(v.battle);buf.writeUUID(v.token);},
                buf->new AimSubmit(buf.readUUID(),buf.readUUID()));
        @Override public Type<AimSubmit> type(){return TYPE;}
    }
    public record StatusClock(int entityId,UUID entity,boolean locked,int fireTicks) implements CustomPacketPayload {
        public static final Type<StatusClock> TYPE=new Type<>(ResourceLocation.parse("mineturn:status_clock"));
        public static final StreamCodec<FriendlyByteBuf,StatusClock> CODEC=StreamCodec.of((buf,v)->{
            buf.writeVarInt(v.entityId);buf.writeUUID(v.entity);buf.writeBoolean(v.locked);buf.writeVarInt(v.fireTicks);
        },buf->new StatusClock(buf.readVarInt(),buf.readUUID(),buf.readBoolean(),buf.readVarInt()));
        @Override public Type<StatusClock> type(){return TYPE;}
    }
    /**
     * One action performance broadcast to every player in the battle. The client resolves the animationId
     * to a built-in camera/actor animation; the server only says what and where, never how.
     */
    public record ActionAnimation(UUID battle, long sequence, ResourceLocation animationId, int actorId, int targetId, Vec3 impact, boolean hasImpact) implements CustomPacketPayload {
        public static final Type<ActionAnimation> TYPE=new Type<>(ResourceLocation.parse("mineturn:action_animation"));
        public static final StreamCodec<FriendlyByteBuf,ActionAnimation> CODEC=StreamCodec.of((buf,v)->{
            buf.writeUUID(v.battle);buf.writeVarLong(v.sequence);buf.writeResourceLocation(v.animationId);
            buf.writeVarInt(v.actorId);buf.writeVarInt(v.targetId);vector(buf,v.impact);buf.writeBoolean(v.hasImpact);
        },buf->new ActionAnimation(buf.readUUID(),buf.readVarLong(),buf.readResourceLocation(),buf.readVarInt(),buf.readVarInt(),vector(buf),buf.readBoolean()));
        @Override public Type<ActionAnimation> type(){return TYPE;}
        /** Map a resolved action to the client animation id; every action maps to at least {@code mineturn:generic}. */
        public static ResourceLocation idFor(String effect){
            if(effect==null)return ResourceLocation.parse("mineturn:generic");
            return switch(effect){
                case "mineturn:food","mineturn:eat","mineturn:native_food","mineturn:drink" -> ResourceLocation.parse("mineturn:eat");
                case "mineturn:damage","mineturn:weapon_melee","mineturn:mob_melee","mineturn:species_melee","mineturn:slime_melee","mineturn:vindicator_strike","mineturn:heavy_strike" -> ResourceLocation.parse("mineturn:melee");
                case "mineturn:projectile","mineturn:firework","mineturn:skeleton_arrow","mineturn:mob_crossbow","mineturn:pillager_charge","mineturn:player_trident","mineturn:drowned_trident","mineturn:player_snowball","mineturn:snowball_support","mineturn:splash","mineturn:lingering","mineturn:wind_burst","mineturn:ghast_fireball" -> ResourceLocation.parse("mineturn:ranged");
                default -> ResourceLocation.parse("mineturn:generic");
            };
        }
    }
}
