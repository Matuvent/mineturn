package com.matuvent.mineturn.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import com.matuvent.mineturn.network.*;

public final class CombatVisuals {
    public static ItemStack projectile(String effect,ItemStack weapon,ItemStack ammo){
        return switch(effect){
            case "mineturn:projectile","mineturn:skeleton_arrow","mineturn:mob_crossbow" -> ammo.isEmpty()?new ItemStack(Items.ARROW):ammo.copyWithCount(1);
            case "mineturn:firework" -> ammo.isEmpty()?ItemStack.EMPTY:ammo.copyWithCount(1);
            case "mineturn:player_trident","mineturn:drowned_trident" -> weapon.is(Items.TRIDENT)?weapon.copyWithCount(1):new ItemStack(Items.TRIDENT);
            case "mineturn:splash","mineturn:lingering","mineturn:player_snowball","mineturn:wind_burst" -> weapon.copyWithCount(1);
            case "mineturn:snowball_support" -> new ItemStack(Items.SNOWBALL);
            default -> ItemStack.EMPTY;
        };
    }
    public static void shot(LivingEntity source,Vec3 destination,String effect,ItemStack weapon,ItemStack ammo,boolean hit){
        if(hit && java.util.Set.of("mineturn:firework","mineturn:projectile","mineturn:mob_crossbow","mineturn:skeleton_arrow").contains(effect))return; // Its actual blast centers are emitted by CombatFireworks.
        var item=projectile(effect,weapon,ammo);send(source,destination,item,hit,false);
        if(weapon.is(Items.CROSSBOW) && !item.isEmpty()){
            var loaded=weapon.getOrDefault(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,net.minecraft.world.item.component.ChargedProjectiles.EMPTY);
            // Presentation must not roll native random enchantment predicates a second time.
            var multishot=source.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT);
            int count=loaded.isEmpty()?(net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(multishot,weapon)>0?3:1):loaded.getItems().size();
            for(int lane=1;lane<Math.min(3,count);lane++){
                Vec3 end=source.getEyePosition().add(destination.subtract(source.getEyePosition()).yRot((float)Math.toRadians(lane==1?10:-10)));
                var block=source.level().clip(new net.minecraft.world.level.ClipContext(source.getEyePosition(),end,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,source));
                if(block.getType()!=net.minecraft.world.phys.HitResult.Type.MISS)end=block.getLocation();
                send(source,end,item,false,false);
            }
        }
    }
    public static void send(LivingEntity source,Vec3 destination,ItemStack item,boolean hit,boolean firework){
        if(item.isEmpty() || !(source.level() instanceof ServerLevel level))return;
        Vec3 from=source.getEyePosition();
        Vec3 to=hit?destination:destination.add(0.8,0.2,0.8);
        flight(level,from,to,item,hit,firework);
    }
    /** Exact endpoints, also used for loyalty recovery; no miss offset and no gameplay mutation. */
    public static void flight(ServerLevel level,Vec3 from,Vec3 to,ItemStack item,boolean hit,boolean firework){
        if(item.isEmpty())return;
        var packet=new ProjectileVisual(level.dimension().location(),from,to,item,(int)Math.clamp(Math.ceil(from.distanceTo(to)*1.2),6,16),hit,firework);
        if(!packet.valid())return;
        for(var player:level.players())if(player.position().distanceToSqr(from)<128*128 || player.position().distanceToSqr(to)<128*128)BattleNetwork.send(player,packet);
    }
    public static java.util.List<Vec3> arrowEnds(CombatEffects.Context context,java.util.Set<LivingEntity> resolvedTargets){
        var source=context.source();Vec3 from=source.getEyePosition(),selected=context.target().getEyePosition();
        Vec3 direction=selected.subtract(from).normalize();double initial=from.distanceTo(selected);
        int count=1;
        if(context.item().is(Items.CROSSBOW)){
            var loaded=context.item().getOrDefault(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,net.minecraft.world.item.component.ChargedProjectiles.EMPTY);
            var enchantment=source.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT);
            count=loaded.isEmpty()?(net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(enchantment,context.item())>0?3:1):Math.clamp(loaded.getItems().size(),1,3);
        }
        var endpoints=new java.util.ArrayList<Vec3>();
        for(int lane=0;lane<count;lane++){
            Vec3 ray=direction.yRot((float)Math.toRadians(lane==0?0:lane==1?10:-10));double distance=initial;
            Vec3 limit=from.add(ray.scale(Math.min(256,initial+context.action().range())));
            for(var target:resolvedTargets)if(target.getBoundingBox().inflate(.1).clip(from,limit).isPresent())
                distance=Math.max(distance,target.getBoundingBox().getCenter().subtract(from).dot(ray)+.35);
            Vec3 end=from.add(ray.scale(Math.min(256,distance)));
            var wall=source.level().clip(new net.minecraft.world.level.ClipContext(from,end,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,source));
            endpoints.add(wall.getType()==net.minecraft.world.phys.HitResult.Type.MISS?end:wall.getLocation());
        }
        return java.util.List.copyOf(endpoints);
    }
    public static void arrows(CombatEffects.Context context,java.util.Set<LivingEntity> resolvedTargets,ItemStack ammo){
        for(var end:arrowEnds(context,resolvedTargets))flight((ServerLevel)context.source().level(),context.source().getEyePosition(),end,ammo,true,false);
    }
    private CombatVisuals(){}
}
