package com.matuvent.mineturn.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Addons can provide a live equipment check; no default accessory grants water walking. */
public final class CombatMovement {
    private static final ConcurrentHashMap<ResourceLocation,Predicate<LivingEntity>> WATER=new ConcurrentHashMap<>();
    public static void registerWaterWalking(ResourceLocation id,Predicate<LivingEntity> condition){
        if(WATER.putIfAbsent(java.util.Objects.requireNonNull(id),java.util.Objects.requireNonNull(condition))!=null)
            throw new IllegalArgumentException("Duplicate water walking provider: "+id);
    }
    public static boolean walksOnWater(LivingEntity entity){return WATER.values().stream().anyMatch(check->check.test(entity));}
    private CombatMovement(){}
}
