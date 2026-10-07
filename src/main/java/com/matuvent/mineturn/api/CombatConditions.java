package com.matuvent.mineturn.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Register pure server-side conditions during mod setup, e.g. a Curios equipment lookup. */
public final class CombatConditions {
    private static final ConcurrentHashMap<String,Predicate<ServerPlayer>> CONDITIONS=new ConcurrentHashMap<>();
    public static void register(ResourceLocation id,Predicate<ServerPlayer> condition) {
        if(CONDITIONS.putIfAbsent(id.toString(),java.util.Objects.requireNonNull(condition))!=null)
            throw new IllegalArgumentException("Duplicate condition: "+id);
    }
    public static boolean contains(String id){return CONDITIONS.containsKey(id);}
    public static boolean test(String id,ServerPlayer player){var condition=CONDITIONS.get(id);return condition!=null && condition.test(player);}
    private CombatConditions(){}
}
