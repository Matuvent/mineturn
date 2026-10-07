package com.matuvent.mineturn.client;

import com.matuvent.mineturn.network.ProjectileVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Bounded client-only model flights; never adds an entity to the world. */
final class ProjectileAnimations {
    private record Flight(ProjectileVisual packet,long started,net.minecraft.world.entity.Entity model){}
    private static final java.util.ArrayDeque<Flight> FLIGHTS=new java.util.ArrayDeque<>();
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private static void checkWorld(){var current=Minecraft.getInstance().level;if(world!=current){FLIGHTS.clear();world=current;}}
    static void receive(ProjectileVisual packet){
        checkWorld();if(world==null || !packet.valid() || !world.dimension().location().equals(packet.dimension()))return;
        if(FLIGHTS.size()>=64)FLIGHTS.removeFirst();FLIGHTS.addLast(new Flight(packet,System.nanoTime(),createModel(packet)));
    }
    /** Detached render proxies: never add to ClientLevel, tick, or invoke hit methods. */
    private static net.minecraft.world.entity.Entity createModel(ProjectileVisual p){
        var item=p.item();var from=p.from();
        if(item.is(net.minecraft.world.item.Items.TRIDENT))return new net.minecraft.world.entity.projectile.ThrownTrident(world,from.x,from.y,from.z,item);
        if(item.is(net.minecraft.world.item.Items.SPECTRAL_ARROW))return new net.minecraft.world.entity.projectile.SpectralArrow(world,from.x,from.y,from.z,item,null);
        if(item.is(net.minecraft.world.item.Items.ARROW) || item.is(net.minecraft.world.item.Items.TIPPED_ARROW))return new net.minecraft.world.entity.projectile.Arrow(world,from.x,from.y,from.z,item,null);
        if(item.is(net.minecraft.world.item.Items.FIREWORK_ROCKET))return new net.minecraft.world.entity.projectile.FireworkRocketEntity(world,item,from.x,from.y,from.z,true);
        return null;
    }
    static void tick(){
        checkWorld();if(world==null)return;long now=System.nanoTime();
        var iterator=FLIGHTS.iterator();while(iterator.hasNext()){
            var flight=iterator.next();var p=flight.packet();
            if(now-flight.started()<p.ticks()*50_000_000L)continue;
            iterator.remove();if(!p.hit())continue;
            if(p.firework()){
                var data=p.item().get(net.minecraft.core.component.DataComponents.FIREWORKS);
                if(data!=null)world.createFireworks(p.to().x,p.to().y,p.to().z,0,0,0,data.explosions().stream().limit(16).toList());
            }else if(p.item().is(net.minecraft.world.item.Items.SNOWBALL) || p.item().is(net.minecraft.world.item.Items.SPLASH_POTION) || p.item().is(net.minecraft.world.item.Items.LINGERING_POTION)){
                var particle=new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,p.item());
                for(int i=0;i<8;i++)world.addParticle(particle,p.to().x,p.to().y,p.to().z,(world.random.nextDouble()-.5)*.15,world.random.nextDouble()*.1,(world.random.nextDouble()-.5)*.15);
            }
        }
    }
    static void render(net.neoforged.neoforge.client.event.RenderLevelStageEvent event){
        if(event.getStage()!=net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)return;
        checkWorld();if(world==null)return;var mc=Minecraft.getInstance();var buffers=mc.renderBuffers().bufferSource();var camera=event.getCamera().getPosition();long now=System.nanoTime();
        for(var flight:FLIGHTS){
            var p=flight.packet();double t=Math.clamp((double)(now-flight.started())/(p.ticks()*50_000_000L),0,1);
            Vec3 at=p.from().lerp(p.to(),t).add(0,Math.sin(Math.PI*t)*Math.min(1,p.from().distanceTo(p.to())*.08),0);
            var pose=new com.mojang.blaze3d.vertex.PoseStack();
            Vec3 direction=p.to().subtract(p.from()).add(0,Math.cos(Math.PI*t)*Math.PI*Math.min(1,p.from().distanceTo(p.to())*.08),0);
            var model=flight.model();
            if(model!=null){
                model.setPos(at);model.setDeltaMovement(direction);
                float yaw=(float)(Math.atan2(direction.x,direction.z)*180/Math.PI);
                float pitch=(float)(Math.atan2(direction.y,direction.horizontalDistance())*180/Math.PI);
                model.setYRot(yaw);model.yRotO=yaw;model.setXRot(pitch);model.xRotO=pitch;
                // Native renderers own geometry, texture, glint, scale and orientation.
                pose.translate(at.x-camera.x,at.y-camera.y,at.z-camera.z);
                mc.getEntityRenderDispatcher().getRenderer(model).render(model,yaw,1,pose,buffers,net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            }else{
                pose.translate(at.x-camera.x,at.y-camera.y,at.z-camera.z);
                pose.mulPose(com.mojang.math.Axis.YP.rotation((float)Math.atan2(-direction.x,direction.z)));pose.scale(.65f,.65f,.65f);
                mc.getItemRenderer().renderStatic(p.item(),net.minecraft.world.item.ItemDisplayContext.FIXED,net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,pose,buffers,world,0);
            }
        }
        if(!FLIGHTS.isEmpty())buffers.endBatch();
    }
    private ProjectileAnimations(){}
}
