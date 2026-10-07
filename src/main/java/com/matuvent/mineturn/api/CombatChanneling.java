package com.matuvent.mineturn.api;

import com.matuvent.mineturn.MineTurn;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.ArrayList;
import java.util.List;

/** Capture only lightning spawned by a committed player trident's native enchantment callback. */
@EventBusSubscriber(modid=MineTurn.MODID)
public final class CombatChanneling {
    public static final String VISUAL="mineturn:channeling_visual";
    private static final ThreadLocal<List<LightningBolt>> CAPTURE=new ThreadLocal<>();

    public static void hit(CombatEffects.Context context,Runnable nativeHit) {
        var previous=CAPTURE.get();var bolts=new ArrayList<LightningBolt>();CAPTURE.set(bolts);
        try {nativeHit.run();}
        finally {if(previous==null)CAPTURE.remove();else CAPTURE.set(previous);}
        for(var bolt:bolts)strike(context,bolt);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void capture(EntityJoinLevelEvent event) {
        var bolts=CAPTURE.get();
        if(bolts!=null && !event.getLevel().isClientSide() && event.getEntity() instanceof LightningBolt bolt
                && !bolt.getPersistentData().getBoolean(VISUAL)) {
            event.setCanceled(true);
            if(bolts.size()<8)bolts.add(bolt);
        }
    }
    private static void strike(CombatEffects.Context context,LightningBolt bolt) {
        var level=(ServerLevel)context.source().level();var p=bolt.position();
        var area=new AABB(p.x-3,p.y-3,p.z-3,p.x+3,p.y+9,p.z+3);
        var hit=new ArrayList<net.minecraft.world.entity.Entity>();
        for(var target:context.battle().participants()) {
            if(target.level()!=level || !target.isAlive() || !target.getBoundingBox().intersects(area))continue;
            if(net.neoforged.neoforge.event.EventHooks.onEntityStruckByLightning(target,bolt))continue;
            target.invulnerableTime=0;
            target.thunderHit(level,bolt);
            // Native lightning relies on repeated ticks to ignite; this adapter settles once.
            if(target.isAlive() && !target.fireImmune())target.igniteForSeconds(8);
            target.setDeltaMovement(Vec3.ZERO);hit.add(target);
        }
        if(context.source() instanceof ServerPlayer player)
            net.minecraft.advancements.CriteriaTriggers.CHANNELED_LIGHTNING.trigger(player,hit);
        bolt.setVisualOnly(true);bolt.getPersistentData().putBoolean(VISUAL,true);
        level.addFreshEntity(bolt);
    }
    private CombatChanneling() {}
}
