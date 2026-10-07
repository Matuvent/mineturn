package com.matuvent.mineturn.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Resource providers run on the server thread. Preview must be pure; failed spending must not mutate anything. */
public final class CombatResources {
    public interface Resource {
        default void validateAmount(double amount) {}
        /** Null means affordable; otherwise return a short user-facing reason. */
        String check(ServerPlayer player,double amount);
        /** Atomically recheck and deduct. False must leave all resources unchanged. */
        boolean trySpend(ServerPlayer player,double amount);
    }
    private static final ConcurrentHashMap<String,Resource> TYPES=new ConcurrentHashMap<>();
    static {
        register(ResourceLocation.parse("mineturn:experience_levels"),new Resource(){
            @Override public void validateAmount(double amount){if(amount!=Math.rint(amount))throw new IllegalArgumentException("Experience level cost must be an integer");}
            @Override public String check(ServerPlayer player,double amount){return player.experienceLevel>=amount?null:"经验等级不足，需要 "+(int)amount+" 级。";}
            @Override public boolean trySpend(ServerPlayer player,double amount){
                if(check(player,amount)!=null)return false;
                player.giveExperienceLevels(-(int)amount);return true;
            }
        });
    }
    public static void register(ResourceLocation id,Resource resource){
        if(TYPES.putIfAbsent(id.toString(),Objects.requireNonNull(resource))!=null)throw new IllegalArgumentException("Duplicate combat resource: "+id);
    }
    public static Resource get(String id){
        var resource=TYPES.get(id);if(resource==null)throw new IllegalArgumentException("Unknown combat resource: "+id);return resource;
    }
    public record Cost(String resource,double amount) {
        public Cost {
            resource=ResourceLocation.parse(resource).toString();
            if(!Double.isFinite(amount)||amount<=0||amount>1000000)throw new IllegalArgumentException("Resource cost must be finite and within (0,1000000]");
            get(resource).validateAmount(amount);
        }
        public String check(ServerPlayer player){return get(resource).check(player,amount);}
        public boolean trySpend(ServerPlayer player){return get(resource).trySpend(player,amount);}
    }
    private CombatResources(){}
}
