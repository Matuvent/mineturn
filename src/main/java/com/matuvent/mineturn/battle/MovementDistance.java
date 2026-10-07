package com.matuvent.mineturn.battle;

import net.minecraft.world.phys.Vec3;

/** Movement budget metric; combat range and visual interpolation retain their geometric distances. */
final class MovementDistance {
    static double ground(Vec3 offset){return Math.abs(offset.x)+Math.abs(offset.z);}
    static double spatial(Vec3 offset){return ground(offset)+Math.abs(offset.y);}
    static double between(Vec3 a,Vec3 b){return spatial(a.subtract(b));}
    private MovementDistance(){}
}
