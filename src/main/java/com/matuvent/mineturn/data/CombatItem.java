package com.matuvent.mineturn.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record CombatItem(boolean enabled, List<String> actions, double meleeRange) {
    public CombatItem { actions = List.copyOf(actions); }
    public static final Codec<CombatItem> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(CombatItem::enabled),
            Codec.STRING.listOf().fieldOf("actions").forGetter(CombatItem::actions),
            Codec.doubleRange(0.1, 16).optionalFieldOf("melee_range", 2.5).forGetter(CombatItem::meleeRange)
    ).apply(instance, CombatItem::new));
}
