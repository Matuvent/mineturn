package com.matuvent.mineturn.api;

import net.minecraft.world.phys.Vec3;

/** Safe battle-local teleport contract. Coordinates are feet positions; X/Z snap to block centers. */
public final class CombatTeleport {
    public record Rules(double range, boolean requireSight, boolean allowWater) {
        public Rules {
            if(!Double.isFinite(range) || range<=0 || range>32)throw new IllegalArgumentException("Teleport range must be finite and in (0,32]");
        }
    }
    /** A preview is not a reservation; execution always validates again. */
    public record Result(Vec3 destination, String error) {
        public boolean success(){return destination!=null && error==null;}
        public static Result rejected(String error){return new Result(null,error);}
        public static Result accepted(Vec3 destination){return new Result(destination,null);}
    }
    private CombatTeleport(){}
}
