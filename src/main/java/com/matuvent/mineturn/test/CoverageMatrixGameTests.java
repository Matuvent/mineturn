package com.matuvent.mineturn.test;

import com.matuvent.mineturn.api.CombatEffects;
import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Coverage-matrix consistency checks for the shipped datapack.
 *
 * <p>{@code docs/VANILLA_COVERAGE.md} accumulated a long tail of historical counts: the registered mob
 * total appears as 36, 37, 39 and 40 in different paragraphs, the server test count runs from 142 to 171,
 * and the network protocol as 11 and 12. None of that is checkable by reading it. These tests derive the
 * numbers from the parsed definitions the running server actually uses, log a matrix, and assert the
 * relationships that must hold, so documentation drift becomes a failing test instead of a stale number.
 *
 * <p>Scope is deliberately limited to relations between the data and the code: every registered mob is a
 * real entity type, every item action names an action that exists, every action names a real effect, and
 * the resource-mob exclusion still holds. Whether a mechanic "feels" complete is not something a server
 * test can decide, so that stays a documentation concern.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class CoverageMatrixGameTests {
    private static CombatData.Snapshot data() {
        var snapshot = CombatData.current;
        if (snapshot == null || snapshot.mobs().isEmpty()) {
            throw new IllegalStateException("The shipped datapack was not parsed; cannot verify coverage");
        }
        return snapshot;
    }

    /**
     * Every registered mob must be a real entity type, and the counts must be internally consistent. Also
     * records the matrix so the documentation can be written from measured values rather than memory.
     */
    @GameTest(template = "empty")
    public static void registeredMobsAreRealAndCountable(GameTestHelper h) {
        var snapshot = data();
        var unknown = new TreeSet<String>();
        var participation = new java.util.TreeMap<String, Integer>();
        for (var entry : snapshot.mobs().entrySet()) {
            var id = ResourceLocation.tryParse(entry.getKey());
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) unknown.add(entry.getKey());
            participation.merge(entry.getValue().participation(), 1, Integer::sum);
        }
        h.assertTrue(unknown.isEmpty(), "Registered mobs that are not real entity types: " + unknown);
        h.assertTrue(snapshot.mobs().size() >= 30,
                "Far fewer mobs registered than the documented scope: " + snapshot.mobs().size());

        // Split vanilla from mod-added entries: the documentation's headline count is about vanilla mobs,
        // and the mod's own entities (for example its shulker bullet) are registered here too but are not
        // part of that scope.
        var vanilla = new TreeSet<String>();
        var modAdded = new TreeSet<String>();
        for (String id : snapshot.mobs().keySet()) {
            if (id.startsWith("minecraft:")) vanilla.add(id.substring("minecraft:".length()));
            else modAdded.add(id);
        }

        // Every mob that can take part must have somewhere to go. Only the legacy "states" form has named
        // states; the "ai" form drives turns through function callbacks and legitimately has none, so an
        // empty map is not a defect.
        var noInitial = new TreeSet<String>();
        for (var entry : snapshot.mobs().entrySet()) {
            var brain = entry.getValue();
            if (brain.states().isEmpty()) continue;
            if (!brain.states().containsKey(brain.initial())) {
                noInitial.add(entry.getKey() + " (initial=" + brain.initial() + ")");
            }
        }
        h.assertTrue(noInitial.isEmpty(), "Mobs with an unreachable initial state: " + noInitial);

        var matrix = new StringBuilder("\nMineTurn coverage matrix\n");
        matrix.append("  mobs registered      : ").append(snapshot.mobs().size())
                .append(" (vanilla ").append(vanilla.size())
                .append(", mod-added ").append(modAdded.size()).append(" ").append(modAdded).append(")\n");
        matrix.append("  vanilla mob ids      : ").append(vanilla).append('\n');
        matrix.append("  actions registered   : ").append(snapshot.actions().size()).append('\n');
        matrix.append("  grants registered    : ").append(snapshot.grants().size()).append('\n');
        matrix.append("  participation modes  : ").append(participation).append('\n');

        // Item coverage has to be split three ways, because "registered" and "usable in combat" are not the
        // same thing. The vanilla catalog walks the whole item registry and records everything it has not
        // seen as an explicit entry with enabled=false and no actions, so the raw total (over a thousand)
        // says nothing about how much is playable. What is usable is an item that carries at least one
        // action; everything else is deliberately registered-but-off.
        var usable = new ArrayList<String>();
        var knownDisabled = new ArrayList<String>();
        var explicitlyOff = new ArrayList<String>();
        for (var entry : snapshot.items().entrySet()) {
            var item = entry.getValue();
            if (!item.actions().isEmpty() && item.enabled()) {
                usable.add(entry.getKey());
            } else if (!item.enabled()) {
                String source = snapshot.sources().getOrDefault("items/" + entry.getKey(), "?");
                if (source.startsWith("mineturn:catalog/")) knownDisabled.add(entry.getKey());
                else explicitlyOff.add(entry.getKey());
            }
        }
        matrix.append("  items registered     : ").append(snapshot.items().size()).append('\n');
        matrix.append("    usable in combat   : ").append(usable.size()).append(" ").append(usable).append('\n');
        matrix.append("    known but disabled : ").append(knownDisabled.size())
                .append(" (catalog registered, no actions)").append('\n');
        matrix.append("    explicitly off     : ").append(explicitlyOff.size()).append(" ").append(explicitlyOff).append('\n');
        matrix.append("  actions by effect    : ").append(effectsByUse(snapshot)).append('\n');
        com.matuvent.mineturn.MineTurn.LOGGER.info("{}", matrix);
        h.succeed();
    }

    /** Item mappings and grants must only name actions that exist; a typo here silently disables content. */
    @GameTest(template = "empty")
    public static void itemAndGrantReferencesResolve(GameTestHelper h) {
        var snapshot = data();
        var dangling = new TreeSet<String>();
        for (var entry : snapshot.items().entrySet()) {
            for (String action : entry.getValue().actions()) {
                if (!snapshot.actions().containsKey(action)) {
                    dangling.add("items/" + entry.getKey() + " -> " + action);
                }
            }
        }
        for (var entry : snapshot.grants().entrySet()) {
            var grant = entry.getValue();
            if (!snapshot.actions().containsKey(grant.action())) {
                dangling.add("grants/" + entry.getKey() + " -> " + grant.action());
            }
            if (grant.enabled() && snapshot.actions().get(grant.action()) != null
                    && snapshot.actions().get(grant.action()).consume() != 0) {
                dangling.add("grants/" + entry.getKey() + " consumes an equipment stack");
            }
        }
        h.assertTrue(dangling.isEmpty(), "References that do not resolve: " + dangling);
        h.succeed();
    }

    /** Every action must name an effect the code knows, otherwise the action can never run. */
    @GameTest(template = "empty")
    public static void everyActionHasARegisteredEffect(GameTestHelper h) {
        var snapshot = data();
        var unknown = new TreeSet<String>();
        for (var entry : snapshot.actions().entrySet()) {
            if (!CombatEffects.contains(entry.getValue().effect())) {
                unknown.add(entry.getKey() + " -> " + entry.getValue().effect());
            }
        }
        h.assertTrue(unknown.isEmpty(), "Actions naming an unregistered effect: " + unknown);
        h.succeed();
    }

    /** Item mappings must point at real items, and melee ranges must stay inside the engine's limit. */
    @GameTest(template = "empty")
    public static void itemMappingsPointAtRealItems(GameTestHelper h) {
        var snapshot = data();
        var unknown = new TreeSet<String>();
        for (var entry : snapshot.items().entrySet()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getKey()));
            if (item == null) unknown.add(entry.getKey());
            double range = entry.getValue().meleeRange();
            h.assertTrue(range >= 0.1 && range <= 16,
                    "Item " + entry.getKey() + " has an out-of-range melee_range: " + range);
        }
        h.assertTrue(unknown.isEmpty(), "Item mappings naming unknown items: " + unknown);

        // The vanilla catalog iterates the whole item registry, so it also maps minecraft:air. That is
        // harmless - air is never held - but it is worth recording rather than silently accepting, because
        // it means the "how many items are covered" number includes one entry that cannot do anything.
        if (snapshot.items().containsKey("minecraft:air")) {
            com.matuvent.mineturn.MineTurn.LOGGER.info(
                    "Coverage note: the vanilla catalog also maps minecraft:air, so item coverage counts include it");
        }
        h.succeed();
    }

    /**
     * Resource mobs must stay out of combat unless a datapack explicitly promotes them. This is the one
     * scope rule the task calls out by name ("do not let resource mobs into combat again"), so it is
     * asserted rather than merely documented.
     */
    @GameTest(template = "empty")
    public static void resourceMobsDoNotJoinCombat(GameTestHelper h) {
        var snapshot = data();
        var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
                ResourceLocation.parse("mineturn:non_combatants"));
        var listed = BuiltInRegistries.ENTITY_TYPE.stream()
                .filter(type -> type.is(tag))
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                .sorted()
                .toList();
        h.assertTrue(listed.size() >= 30,
                "The resource-mob exclusion list shrank unexpectedly: " + listed.size());

        var leaky = new TreeSet<String>();
        for (String id : listed) {
            var brain = snapshot.mobs().get(id);
            if (brain == null) continue;
            // "auto" defers to the tag, which is what keeps a resource mob out; "hostile" and "neutral"
            // would pull it in regardless.
            if (!brain.participation().equals("auto") && !brain.participation().equals("never")) {
                leaky.add(id + " is " + brain.participation());
            }
        }
        h.assertTrue(leaky.isEmpty(),
                "Resource mobs registered with a participation mode that ignores the exclusion tag: " + leaky);

        h.assertTrue(EntityTypeTags.RAIDERS != null, "sanity: entity tags are available");
        h.succeed();
    }

    /** Counts actions by whether they are used by a mob, an item, or only by a grant. */
    private static String effectsByUse(CombatData.Snapshot snapshot) {
        var counts = new java.util.TreeMap<String, Integer>();
        for (var action : snapshot.actions().values()) {
            counts.merge(action.effect(), 1, Integer::sum);
        }
        var top = new ArrayList<>(counts.entrySet());
        top.sort(Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue).reversed());
        return top.stream().limit(8).map(e -> e.getKey() + "=" + e.getValue()).toList().toString();
    }
}
