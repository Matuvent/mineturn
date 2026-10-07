package com.matuvent.mineturn.client;

import com.matuvent.mineturn.network.BattleNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side action performance player. It owns a single active performance and exposes a camera
 * override for {@link com.matuvent.mineturn.mixin.BattleCameraMixin}. Actor posing and particles are
 * intentionally left for a later phase; P0 only moves the camera.
 */
public final class ActionAnimations {
    public record CameraPose(float yaw, float pitch, double distance, Vec3 focus) {}
    private static final ResourceLocation ID_EAT=ResourceLocation.parse("mineturn:eat");
    private static final ResourceLocation ID_MELEE=ResourceLocation.parse("mineturn:melee");
    private static final ResourceLocation ID_RANGED=ResourceLocation.parse("mineturn:ranged");
    private static final double BLEND_OUT_MS=150;

    private static Playback playback;
    private static java.util.UUID lastBattle;
    private static long lastSequence=-1;
    private static net.minecraft.client.multiplayer.ClientLevel world;

    private static void checkWorld(){
        var current=Minecraft.getInstance().level;
        if(world!=current){clear();world=current;lastBattle=null;lastSequence=-1;}
    }

    private ActionAnimations(){}

    private record Playback(ResourceLocation id, long started, long durationMs, Vec3 actor, Vec3 target, boolean self){}

    public static void receive(BattleNetwork.ActionAnimation packet){
        var mc=Minecraft.getInstance();var level=mc.level;var player=mc.player;
        if(level==null || player==null)return;
        checkWorld();
        if(packet.battle()==null || packet.animationId()==null)return;
        if(!packet.battle().equals(lastBattle)){lastBattle=packet.battle();lastSequence=-1;}
        if(packet.sequence()<=lastSequence)return;
        lastSequence=packet.sequence();
        var actor=level.getEntity(packet.actorId());
        var target=level.getEntity(packet.targetId());
        if(!(actor instanceof LivingEntity a) || !(target instanceof LivingEntity t))return;
        // Keep it purely presentational: positions are captured now and the camera does not follow motion.
        Vec3 actorPos=a.getEyePosition();Vec3 targetPos=t.getEyePosition();
        long duration=switch(packet.animationId().toString()){
            case "mineturn:eat" -> 900;
            case "mineturn:melee" -> 700;
            case "mineturn:ranged" -> 800;
            default -> 600;
        };
        playback=new Playback(packet.animationId(),System.nanoTime(),duration,actorPos,targetPos,packet.actorId()==packet.targetId());
    }

    public static CameraPose cameraPose(){
        var mc=Minecraft.getInstance();if(mc.level==null)return null;
        checkWorld();
        if(playback==null)return null;
        long now=System.nanoTime();long elapsedMs=(now-playback.started())/1_000_000L;
        double total=playback.durationMs()+BLEND_OUT_MS;
        if(elapsedMs>total){playback=null;return null;}
        double t=Math.clamp((double)elapsedMs/playback.durationMs(),0,1);
        double ease=0.5-0.5*Math.cos(Math.PI*t);            // smooth in-out
        Vec3 focus=computeFocus(playback,ease);
        double distance=computeDistance(playback.id());
        // Blend the final stretch back to the free camera so the hand-off has no snap.
        if(elapsedMs>playback.durationMs()){
            double b=Math.clamp((elapsedMs-playback.durationMs())/BLEND_OUT_MS,0,1);
            var free=BattleClient.focus;double freeDist=BattleClient.distance;
            focus=focus.lerp(free,b);distance=distance+(freeDist-distance)*b;
        }
        return new CameraPose(BattleClient.yaw,BattleClient.pitch,distance,focus);
    }

    private static Vec3 computeFocus(Playback p,double ease){
        var id=p.id();
        if(id.equals(ID_EAT))return p.actor().add(0,-0.2,0);
        if(id.equals(ID_RANGED))return p.actor().lerp(p.target(),ease);
        if(id.equals(ID_MELEE))return p.self()?p.actor():p.actor().lerp(p.target(),0.5);
        return p.actor().lerp(p.target(),0.5);
    }

    private static double computeDistance(ResourceLocation id){
        if(id.equals(ID_EAT))return 3.2;
        if(id.equals(ID_MELEE))return 4.5;
        if(id.equals(ID_RANGED))return 6.0;
        return 5.0;
    }

    public static void clear(){playback=null;lastBattle=null;lastSequence=-1;}
}
