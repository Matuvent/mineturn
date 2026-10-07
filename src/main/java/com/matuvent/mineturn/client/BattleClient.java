package com.matuvent.mineturn.client;

import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.network.BattleNetwork;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(value = MineTurn.MODID, dist = Dist.CLIENT)
public final class BattleClient {
    public static BattleNetwork.State state;
    public static BattleNetwork.Preview preview;
    public static java.util.List<BattleNetwork.Offer> offers=java.util.List.of();
    public static float yaw;
    public static float pitch = 45;
    public static double distance = 10;
    public static Vec3 focus = Vec3.ZERO;
    private static CameraType previousCamera;
    private static int pendingTicks;
    public static boolean pending;
    private static boolean moving;
    private static final com.matuvent.mineturn.network.MotionOrder motionOrder=new com.matuvent.mineturn.network.MotionOrder();
    private static Vec3 motionTarget;
    private static final com.matuvent.mineturn.network.StatusLocks statusLocks=new com.matuvent.mineturn.network.StatusLocks();
    public static BattleNetwork.Aim aim;
    private static long aimReceived;
    public static boolean aimSubmitted;
    public static double aimProgress() { return aim==null ? 0 : (aim.elapsedMs()+(System.nanoTime()-aimReceived)/1_000_000.0)/aim.durationMs(); }
    public static void judgeAim() {
        if(aim==null || aimSubmitted)return;
        aimSubmitted=true;
        PacketDistributor.sendToServer(new BattleNetwork.AimSubmit(aim.battle(),aim.token()));
    }

    public BattleClient(IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event)->event.registerEntityRenderer(MineTurn.BATTLE_BULLET.get(),BattleBulletRenderer::new));
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event)->event.registerEntityRenderer(MineTurn.BATTLE_DEVICE.get(),BattleDeviceRenderer::new));

        com.matuvent.mineturn.battle.BattleManager.clientStatusLocked=entity->entity.level().isClientSide && statusLocks.locked(entity.getUUID(),System.nanoTime());
        BattleNetwork.receiveStatusClock=packet->{
            var level=Minecraft.getInstance().level;if(level==null)return;
            var entity=level.getEntity(packet.entityId());
            if(entity instanceof net.minecraft.world.entity.LivingEntity living && entity.getUUID().equals(packet.entity())){
                statusLocks.update(packet.entity(),packet.locked(),System.nanoTime());
                com.matuvent.mineturn.battle.BattleStatus.applyClientFire(living,packet.fireTicks());
            }
        };
        BattleNetwork.receiveState = BattleClient::receive;
        BattleNetwork.receiveProjectile=ProjectileAnimations::receive;
        BattleNetwork.receiveActionAnimation=ActionAnimations::receive;
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event)->
                event.registerReloadListener(new ActionAnimationData()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event)->ProjectileAnimations.tick());
        NeoForge.EVENT_BUS.addListener(ProjectileAnimations::render);
        BattleNetwork.receiveOffers=packet->{
            if(active() && state.battle().equals(packet.battle()) && state.revision()==packet.revision())offers=packet.actions();
        };
        BattleNetwork.receiveMotion = packet -> {
            if (!active() || !state.battle().equals(packet.battle()) || !motionOrder.acceptMotion(packet.sequence())) return;
            moving=packet.active(); motionTarget=packet.position();
        };
        BattleNetwork.receiveAim = packet -> {
            if(!active() || !state.battle().equals(packet.battle()))return;
            if(!packet.active()){if(aim!=null && aim.token().equals(packet.token()))aim=null;return;}
            if(aim!=null && aim.token().equals(packet.token()))return;
            aim=packet;aimReceived=System.nanoTime();aimSubmitted=false;
        };
        BattleNetwork.receivePreview = packet -> {
            if (state != null && state.battle().equals(packet.battle()) && state.revision() == packet.revision()) preview = packet;
        };
        NeoForge.EVENT_BUS.addListener(BattleClient::tick);
        NeoForge.EVENT_BUS.addListener(BattleClient::input);
        NeoForge.EVENT_BUS.addListener(BattleClient::hud);
        NeoForge.EVENT_BUS.addListener(BattleClient::world);
    }
    public static boolean active() { return state != null && state.active(); }
    public static boolean local(Entity entity) { return active() && entity == Minecraft.getInstance().player; }
    public static boolean localVehicle(Entity entity) {var player=Minecraft.getInstance().player;return active() && player!=null && player.getVehicle()==entity;}
    private static void positionLocal(Vec3 point) {
        var player=Minecraft.getInstance().player;var mount=player.getVehicle();
        if(mount!=null){mount.setPos(mount.position().add(point.subtract(player.position())));mount.setDeltaMovement(Vec3.ZERO);}
        player.setPos(point);
    }
    public static boolean ownTurn() { return active() && Minecraft.getInstance().player != null && state.actorId() == Minecraft.getInstance().player.getId(); }
    private static void receive(BattleNetwork.State packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!packet.active()) {
            if (state != null && state.battle().equals(packet.battle())) {
                reset();
                if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(packet.message()), true);
            }
            return;
        }
        if (mc.player == null || mc.level == null) return;
        boolean entering = !active() || !state.battle().equals(packet.battle());
        if (!entering && packet.revision() < state.revision()) return;
        if (entering) {
            motionOrder.reset();moving=false;motionTarget=null;
            previousCamera = mc.options.getCameraType();
            yaw = mc.player.getYRot(); pitch = 45; distance = 10;
            focus = packet.enemyPosition().add(0, 1, 0);
            KeyMapping.releaseAll();
        } else {
            focus = focus.add(packet.enemyPosition().subtract(state.enemyPosition()));
        }
        boolean relocated = entering || state.anchor().distanceToSqr(packet.anchor()) > 1e-8;
        if (entering || state.revision() != packet.revision()) { preview = null; offers=java.util.List.of(); }
        boolean motionCurrent=motionOrder.acceptSnapshot(packet.motionSequence());
        if(motionCurrent){moving=packet.moving();motionTarget=packet.anchor();}
        state = packet;
        pending = false;
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        if (relocated && !moving && motionCurrent) {
            positionLocal(packet.anchor());
            mc.player.setOldPosAndRot();
            mc.player.walkAnimation.setSpeed(0);
            mc.player.walkAnimation.update(0, 1);
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
        if (entering || mc.screen == null) mc.setScreen(new BattleScreen());
    }
    public static void reset() {
        offers=java.util.List.of();
        moving=false; motionTarget=null;motionOrder.reset();
        statusLocks.clear();
        ActionAnimations.clear();
        aim=null;aimSubmitted=false;
        Minecraft mc = Minecraft.getInstance();
        state = null; preview = null; pending = false;
        if (previousCamera != null) { mc.options.setCameraType(previousCamera); previousCamera = null; }
        KeyMapping.releaseAll();
        if (mc.screen instanceof BattleScreen) mc.setScreen(null);
    }
    public static void send(String operation, int slot, String action, int target, Vec3 destination, int previewId) {
        if (!active() || pending && !operation.endsWith("preview")) return;
        PacketDistributor.sendToServer(new BattleNetwork.Request(state.battle(), state.revision(), operation, slot, action, target, destination, previewId));
        if (!operation.endsWith("preview")) { pending = true; pendingTicks = 0; }
    }
    public static void simple(String operation) { send(operation, 0, "", -1, Vec3.ZERO, 0); }
    public static void useAmmo(int slot,String action,int target,int ammoSlot,net.minecraft.world.item.ItemStack expected) {
        if(!active() || pending || expected.isEmpty())return;
        var request=new BattleNetwork.Request(state.battle(),state.revision(),"use",slot,action,target,Vec3.ZERO,0);
        PacketDistributor.sendToServer(new BattleNetwork.AmmoUse(request,ammoSlot,expected.copyWithCount(1)));
        pending=true;pendingTicks=0;
    }
    private static void tick(ClientTickEvent.Post event) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null || !mc.player.isAlive()) { reset(); return; }
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setSprinting(false);
        if (motionTarget != null) {
            Vec3 delta=motionTarget.subtract(mc.player.position());
            positionLocal(motionTarget);
            mc.player.walkAnimation.update((float)Math.min(1,delta.horizontalDistance()*4),0.4f);
            if(delta.horizontalDistanceSqr()>1e-6) {
                com.matuvent.mineturn.battle.BattleFacing.along(mc.player,delta);
            }
            if(!moving)motionTarget=null;
        }
        if (mc.screen == null) mc.setScreen(new BattleScreen());
        if (pending && ++pendingTicks > 100) pending = false;
    }
    private static void input(MovementInputUpdateEvent event) {
        if (!local(event.getEntity())) return;
        var input = event.getInput();
        input.forwardImpulse = 0; input.leftImpulse = 0;
        input.up = false; input.down = false; input.left = false; input.right = false;
        input.jumping = false; input.shiftKeyDown = false;
    }
    private static void hud(RenderGuiEvent.Pre event) {
        if (active() && Minecraft.getInstance().screen instanceof BattleScreen) event.setCanceled(true);
    }
    private static void world(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !(mc.screen instanceof BattleScreen screen)
                || !active() || screen.destination() == null) return;
        Vec3 point = screen.destination();
        boolean valid = screen.validDestination();
        boolean verified = screen.verifiedDestination();
        float r = valid ? 0.25f : verified ? 1f : 1f;
        float g = valid ? 0.95f : verified ? 0.3f : 0.75f;
        float b = valid ? 0.65f : 0.25f;
        PoseStack pose = new PoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, new AABB(point.x - 0.43, point.y + 0.02, point.z - 0.43,
                point.x + 0.43, point.y + 0.08, point.z + 0.43), r, g, b, 1);
        if (valid) for (Vec3 p : preview.path()) {
            LevelRenderer.renderLineBox(pose, lines, new AABB(p.x - 0.025, p.y + 0.035, p.z - 0.025, p.x + 0.025, p.y + 0.055, p.z + 0.025), r, g, b, 0.8f);
        }
        if(screen.potionGround() && screen.potionRadius()>0)for(int i=0;i<72;i++) {
            double angle=i*Math.PI*2/72,x=point.x+Math.cos(angle)*screen.potionRadius(),z=point.z+Math.sin(angle)*screen.potionRadius();
            LevelRenderer.renderLineBox(pose,lines,new AABB(x-.035,point.y+.04,z-.035,x+.035,point.y+.06,z+.035),r,g,b,.8f);
        }
        buffers.endBatch(RenderType.lines());
    }
}
