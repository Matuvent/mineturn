package com.matuvent.mineturn.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record CombatItem(boolean enabled, List<String> actions, double meleeRange) {
    public CombatItem { actions = List.copyOf(actions); }
    /**
     * {@code actions} is optional so that the documented way of switching an item off
     * ({@code {"enabled": false}}) parses on its own. Requiring the list made that form invalid, since a
     * disable carries no actions. The same codec backs the {@code mineturn:combat} item component, so a
     * hand written component is not forced to list actions either.
     */
    public static final Codec<CombatItem> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(CombatItem::enabled),
            Codec.STRING.listOf().optionalFieldOf("actions", List.of()).forGetter(CombatItem::actions),
            Codec.doubleRange(0.1, 16).optionalFieldOf("melee_range", 2.5).forGetter(CombatItem::meleeRange)
    ).apply(instance, CombatItem::new));
}
