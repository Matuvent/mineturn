package com.matuvent.mineturn.client;

import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side action performance player. It owns a single active performance and exposes a fixed
 * camera pose for {@link com.matuvent.mineturn.mixin.BattleCameraMixin}: the camera is placed at a
 * world-space offset relative to the actor (mirroring a Star Rail style skill cut), aimed at the
 * target, pushed in and released with a short blend.
 */
public final class ActionAnimations {
    /** Fully resolved camera state: world position plus the look direction toward the aim point. */
    public record CameraPose(Vec3 position, float yaw, float pitch) {}
    private static final double BLEND_IN_MS=250;
    private static final double BLEND_OUT_MS=180;

    private static Playback playback;
    private static CameraPose blendOutFrom;
    private static CameraPose blendOutTo;
    private static long blendOutStart;
    private static CameraPose liveCamera;
    private static java.util.UUID lastBattle;
    private static long lastSequence=-1;
    private static net.minecraft.client.multiplayer.ClientLevel world;

    private static void checkWorld(){
        var current=Minecraft.getInstance().level;
        if(world!=current){clear();world=current;}
    }

    private ActionAnimations(){}

    private record Playback(ResourceLocation id, long started, long durationMs, CameraPose from, CameraPose hold) {}

    /** Recorded every frame by the camera mixin so a cut can push in from wherever the player was. */
    public static void noteFreeCamera(CameraPose pose){liveCamera=pose;}

    public static void receive(BattleNetwork.ActionAnimation packet){
        var mc=Minecraft.getInstance();var level=mc.level;var player=mc.player;
        if(level==null || player==null)return;
        checkWorld();
        if(packet.battle()==null || packet.animationId()==null)return;
        if(!packet.battle().equals(lastBattle)){lastBattle=packet.battle();lastSequence=-1;}
        if(packet.sequence()<=lastSequence)return;
        lastSequence=packet.sequence();
        if(!(level.getEntity(packet.actorId()) instanceof LivingEntity actor))return;
        LivingEntity aim=pickAim(level,packet,actor);
        var hold=computeHold(level,packet.animationId(),actor,aim);
        blendOutFrom=null;blendOutTo=null;
        playback=new Playback(packet.animationId(),System.nanoTime(),durationFor(packet.animationId()),
                liveCamera!=null?liveCamera:hold,hold);
    }

    /** Self-targeted actions aim at the actor's own face; everything else aims at the target. */
    private static LivingEntity pickAim(net.minecraft.client.multiplayer.ClientLevel level,BattleNetwork.ActionAnimation packet,LivingEntity actor){
        if(packet.actorId()==packet.targetId())return actor;
        return level.getEntity(packet.targetId()) instanceof LivingEntity target?target:actor;
    }

    private static long durationFor(ResourceLocation id){
        var key=id.toString();
        if(key.equals("mineturn:eat"))return 900;
        if(key.equals("mineturn:ranged"))return 800;
        return 700;
    }

    /**
     * Place the camera beside and slightly in front of the actor, looking at the aim point. The offsets
     * come from {@link ActionAnimationData} (resource-pack tunable), are expressed in the actor's own
     * facing so the framing reads the same regardless of world yaw, and grow with the actor's collision
     * box so large mobs do not swallow the frame.
     */
    private static CameraPose computeHold(net.minecraft.client.multiplayer.ClientLevel level,ResourceLocation id,LivingEntity actor,LivingEntity aim){
        double yaw=Math.toRadians(actor.getYRot());
        Vec3 forward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        Vec3 right=new Vec3(Math.cos(yaw),0,-Math.sin(yaw));
        var rule=ActionAnimationData.camera(id);
        double bbWidth=actor.getBbWidth();
        double scale=rule.scaleFor(bbWidth);
        double side=rule.side()+scale, front=rule.front()+scale;
        double lift=rule.lift()+actor.getBbHeight()*0.05;
        Vec3 base=actor.position().add(0,actor.getEyeHeight()*0.7,0);
        Vec3 eye=aim.getEyePosition();
        Vec3 wanted=base.add(forward.scale(front)).add(right.scale(side)).add(0,lift,0);
        // First keep the frame clear of the target, then clamp it out of any wall on the sight line.
        Vec3 spaced=setBack(wanted,eye,rule.minDistance());
        Vec3 position=clearSight(level,spaced,eye,actor);
        return poseLookAt(position,eye);
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
        long now=System.nanoTime();
        if(playback==null){
            if(blendOutTo==null)return null;
            double b=Math.clamp((now-blendOutStart)/1_000_000.0/BLEND_OUT_MS,0,1);
            if(b>=1){blendOutFrom=null;blendOutTo=null;return null;}
            return blend(blendOutFrom,blendOutTo,b);
        }
        long elapsedMs=(now-playback.started())/1_000_000L;
        if(elapsedMs>=playback.durationMs()){
            // Release: blend from the hold pose back to where the free camera was before the cut.
            blendOutFrom=playback.hold();
            blendOutTo=freeCamera();
            blendOutStart=now;
            playback=null;
            return blendOutFrom;
        }
        if(elapsedMs<BLEND_IN_MS){
            double ease=0.5-0.5*Math.cos(Math.PI*elapsedMs/BLEND_IN_MS);
            return blend(playback.from(),playback.hold(),ease);
        }
        return playback.hold();
    }

    /**
     * The camera the player would see without a performance, derived from the free orbit state. Sampled
     * once when the performance releases so the return trip targets a stable pose.
     */
    private static CameraPose freeCamera(){
        double yaw=Math.toRadians(BattleClient.yaw),pitch=Math.toRadians(BattleClient.pitch);
        Vec3 focus=BattleClient.focus;
        double horizontal=Math.cos(pitch);
        Vec3 back=new Vec3(-Math.sin(yaw)*horizontal,-Math.sin(pitch),Math.cos(yaw)*horizontal);
        return new CameraPose(focus.add(back.scale(BattleClient.distance)),BattleClient.yaw,BattleClient.pitch);
    }

    private static CameraPose blend(CameraPose from,CameraPose to,double t){
        if(from==null||to==null)return to!=null?to:from;
        double ease=0.5-0.5*Math.cos(Math.PI*Math.clamp(t,0,1));
        float yaw=from.yaw()+net.minecraft.util.Mth.wrapDegrees(to.yaw()-from.yaw())*(float)ease;
        float pitch=(float)(from.pitch()+(to.pitch()-from.pitch())*ease);
        return new CameraPose(from.position().lerp(to.position(),ease),yaw,pitch);
    }

    public static void clear(){playback=null;blendOutFrom=null;blendOutTo=null;lastBattle=null;lastSequence=-1;}
}
