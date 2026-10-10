package com.matuvent.mineturn.client;

import com.matuvent.mineturn.network.ActionAnimationOrder;
import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side action performance player. It owns one running performance plus a single pending slot and
 * exposes a fixed camera pose for {@link com.matuvent.mineturn.mixin.BattleCameraMixin}: the camera is
 * placed at a world-space offset relative to the actor (mirroring a Star Rail style skill cut), aimed at
 * the target, cut to instantly and released with a short blend.
 *
 * <p>Preemption is delegated to {@link ActionAnimationOrder}: attacks interrupt, minor actions wait.
 */
public final class ActionAnimations {
    /** Fully resolved camera state: world position plus the look direction toward the aim point. */
    public record CameraPose(Vec3 position, float yaw, float pitch) {}
    /**
     * Release blend back to the free camera. Kept short on purpose: a slow push-in or a long return
     * reads as the camera swinging around and is unpleasant to play with. The cut itself is instant.
     * Resource packs can override this through {@code blend_out_ms}.
     */
    private static final double DEFAULT_BLEND_OUT_MS=80;

    private static final ActionAnimationOrder order=new ActionAnimationOrder();
    private static Playback playback;
    private static Queued pending;
    private static CameraPose blendOutFrom;
    private static CameraPose blendOutTo;
    private static long blendOutStart;
    /** Captured at release time; the playback record is cleared by then. */
    private static double blendOutMs=DEFAULT_BLEND_OUT_MS;
    private static java.util.UUID lastBattle;
    private static net.minecraft.client.multiplayer.ClientLevel world;

    private static void checkWorld(){
        var current=Minecraft.getInstance().level;
        if(world!=current){clear();world=current;}
    }

    private ActionAnimations(){}

    /** The instant cut plus how long the release blend back to the free camera lasts. */
    private record Playback(ResourceLocation id, long started, long durationMs, CameraPose hold, double blendOutMs, int actorId) {}
    private record Queued(BattleNetwork.ActionAnimation packet, CameraPose hold) {}

    /**
     * Called every frame by the camera mixin. The live pose is not needed any more now that the cut is
     * instant, but the hook is kept so the camera module stays the single owner of that state.
     */
    public static void noteFreeCamera(CameraPose pose){ }

    public static void receive(BattleNetwork.ActionAnimation packet){
        var mc=Minecraft.getInstance();var level=mc.level;var player=mc.player;
        if(level==null || player==null)return;
        checkWorld();
        if(packet.battle()==null || packet.animationId()==null)return;
        if(!packet.battle().equals(lastBattle)){clear();lastBattle=packet.battle();}
        switch(order.arrive(packet.sequence(),packet.priority())){
            case DROP -> { }
            case QUEUE -> pending=new Queued(packet,null);   // Hold pose is resolved when it is promoted.
            case PLAY -> play(packet);
        }
    }

    /** Snaps straight to the skill framing. There is deliberately no push-in. */
    private static void play(BattleNetwork.ActionAnimation packet){
        var level=Minecraft.getInstance().level;
        if(level==null)return;
        if(!(level.getEntity(packet.actorId()) instanceof LivingEntity actor))return;
        var framing=computeHold(level,packet.animationId(),actor,pickAim(level,packet,actor));
        blendOutFrom=null;blendOutTo=null;
        playback=new Playback(packet.animationId(),System.nanoTime(),durationFor(packet.animationId()),
                framing.pose(),framing.blendOutMs(),packet.actorId());
    }

    /** Resolved framing plus the release duration, sampled once when the performance starts. */
    private record Framing(CameraPose pose, double blendOutMs) {}

    /** Self-targeted actions aim at the actor's own face; everything else aims at the target. */
    private static LivingEntity pickAim(net.minecraft.client.multiplayer.ClientLevel level,BattleNetwork.ActionAnimation packet,LivingEntity actor){
        if(packet.actorId()==packet.targetId())return actor;
        return level.getEntity(packet.targetId()) instanceof LivingEntity target?target:actor;
    }

    private static long durationFor(ResourceLocation id){
        var key=id.toString();
        if(key.equals("mineturn:eat"))return 900;
        if(key.equals("mineturn:drink"))return 800;
        if(key.equals("mineturn:ranged"))return 800;
        return 700;
    }

    /**
     * Place the camera beside and slightly in front of the actor, looking at the aim point. The offsets
     * come from {@link ActionAnimationData} (resource-pack tunable), are expressed in the actor's own
     * facing so the framing reads the same regardless of world yaw, and grow with the actor's collision
     * box so large mobs do not swallow the frame.
     */
    /**
     * Frame the action as an over-the-shoulder shot: the camera sits behind and to the right of the
     * actor and looks past it toward the aim point, so actor and target share the frame. Offsets come
     * from {@link ActionAnimationData} (resource-pack tunable) and are expressed in the actor's own
     * facing, so the framing reads the same regardless of world yaw. Both offsets grow with the actor's
     * collision box so large mobs do not swallow the frame.
     */
    private static Framing computeHold(net.minecraft.client.multiplayer.ClientLevel level,ResourceLocation id,LivingEntity actor,LivingEntity aim){
        double yaw=Math.toRadians(actor.getYRot());
        Vec3 forward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        Vec3 right=new Vec3(Math.cos(yaw),0,-Math.sin(yaw));
        var rule=ActionAnimationData.camera(id);
        // Camera offsets come straight from the rule. Body size only pushes the look-at point ahead, so a
        // broad actor does not fill the shot - scaling the offsets by width is what previously dragged the
        // camera two blocks sideways and lost the attacker on a ravager.
        double side=rule.side(), front=rule.front();
        double lift=rule.lift()+actor.getBbHeight()*0.05;
        double aimAhead=rule.aimDistance()+rule.scaleFor(actor.getBbWidth());
        Vec3 base=actor.position().add(0,actor.getEyeHeight()*0.7,0);
        // Look at a point a fixed distance in front of the actor rather than at a fraction of the way to the
        // target. Aiming at the target meant the framing changed with how far away it was: a distant target
        // left the camera right on top of the actor, which pushed the attacker out of frame.
        Vec3 look=switch(rule.aim()){
            case ACTOR -> actor.getEyePosition();
            case TARGET -> aim.getEyePosition();
            case MIDPOINT -> actor.getEyePosition().add(forward.scale(aimAhead));
        };
        Vec3 wanted=base.add(forward.scale(front)).add(right.scale(side)).add(0,lift,0);
        // First keep the frame clear of the subject, then clamp it out of any wall on the sight line.
        Vec3 spaced=setBack(wanted,look,rule.minDistance());
        Vec3 position=clearSight(level,spaced,look,actor);
        return new Framing(poseLookAt(position,look),rule.blendOutMs());
    }

    /** Back the camera off along the sight line if the framing step placed it too close to the aim point. */
    private static Vec3 setBack(Vec3 position,Vec3 eye,double minimum){
        Vec3 away=position.subtract(eye);
        double gap=away.length();
        if(gap>=minimum || gap<1e-6)return position;
        return eye.add(away.scale(minimum/gap));
    }

    /** Clamp the camera toward the aim point if the desired spot is blocked or outside loaded chunks. */
    private static Vec3 clearSight(net.minecraft.client.multiplayer.ClientLevel level,Vec3 position,Vec3 eye,LivingEntity actor){
        var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,position,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,actor));
        if(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS)return position;
        Vec3 back=eye.subtract(position);
        if(back.lengthSqr()<1e-6)return eye;
        Vec3 clamped=hit.getLocation().add(back.normalize().scale(0.25));
        return clamped.distanceToSqr(eye)>0.04?clamped:eye;
    }

    private static CameraPose poseLookAt(Vec3 position,Vec3 look){
        Vec3 d=look.subtract(position);
        double horizontal=d.horizontalDistance();
        float yaw=(float)Math.toDegrees(Math.atan2(-d.x,d.z));
        float pitch=(float)Math.toDegrees(-Math.atan2(d.y,horizontal));
        return new CameraPose(position,yaw,pitch);
    }

    /** Resolved camera for this frame, or null when no performance is active. */
    public static CameraPose cameraPose(){
        var mc=Minecraft.getInstance();if(mc.level==null)return null;
        checkWorld();
        if(!BattleClient.active())return null;      // Never hold a stale cut after the battle ends.
        long now=System.nanoTime();
        if(playback==null){
            if(blendOutTo==null)return null;
            double b=blendOutMs<=0?1:Math.clamp((now-blendOutStart)/1_000_000.0/blendOutMs,0,1);
            if(b<1)return blend(blendOutFrom,blendOutTo,b);
            blendOutFrom=null;blendOutTo=null;
            // A queued performance takes over once the release finished, so the camera never snaps free
            // and back again between two adjacent performances. Ordering is always advanced, and the
            // pending slot is only claimed when the order actually promotes it.
            boolean promote=order.finishRunning();
            if(promote && pending!=null){play(pending.packet());pending=null;}
            return playback!=null?playback.hold():null;
        }
        long elapsedMs=(now-playback.started())/1_000_000L;
        if(elapsedMs>=playback.durationMs()){
            // Release: blend from the hold pose back to where the free camera was before the cut.
            blendOutFrom=playback.hold();
            blendOutTo=freeCamera(playback.actorId());
            blendOutStart=now;
            blendOutMs=playback.blendOutMs();
            playback=null;
            return blendOutFrom;
        }
        // Hold the cut flat for the whole performance: any interpolation here reads as the camera
        // swinging around, which is what made this unpleasant to play.
        return playback.hold();
    }

    /**
     * The camera the player would see without a performance, derived from the free orbit state. Sampled
     * once when the performance releases so the return trip targets a stable pose.
     *
     * <p>Clamped against terrain like the skill framing is. Without this the release target could sit
     * inside a wall - the orbit distance does not know about geometry - and the camera would visibly pass
     * through it on the way back.
     */
    private static CameraPose freeCamera(int actorId){
        double yaw=Math.toRadians(BattleClient.yaw),pitch=Math.toRadians(BattleClient.pitch);
        Vec3 focus=BattleClient.focus;
        double horizontal=Math.cos(pitch);
        Vec3 back=new Vec3(-Math.sin(yaw)*horizontal,-Math.sin(pitch),Math.cos(yaw)*horizontal);
        Vec3 wanted=focus.add(back.scale(BattleClient.distance));
        var level=Minecraft.getInstance().level;
        if(level!=null && level.getEntity(actorId) instanceof LivingEntity observer){
            // Pull the camera in toward the focus point until it is clear of any block.
            wanted=clearSight(level,wanted,focus,observer);
        }
        return new CameraPose(wanted,BattleClient.yaw,BattleClient.pitch);
    }

    private static CameraPose blend(CameraPose from,CameraPose to,double t){
        if(from==null||to==null)return to!=null?to:from;
        double ease=0.5-0.5*Math.cos(Math.PI*Math.clamp(t,0,1));
        float yaw=from.yaw()+net.minecraft.util.Mth.wrapDegrees(to.yaw()-from.yaw())*(float)ease;
        float pitch=(float)(from.pitch()+(to.pitch()-from.pitch())*ease);
        return new CameraPose(from.position().lerp(to.position(),ease),yaw,pitch);
    }

    public static void clear(){
        playback=null;pending=null;blendOutFrom=null;blendOutTo=null;
        lastBattle=null;order.clear();
    }
}
