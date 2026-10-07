package com.matuvent.mineturn.api;

import com.matuvent.mineturn.data.CombatData;

/** Explicit opt-in for position-targeted potion actions. */
public final class PotionTargets {
    public static boolean ground(CombatData.Action action) {
        var value=action.parameters().get("ground_target");
        return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    private PotionTargets() {}
}
