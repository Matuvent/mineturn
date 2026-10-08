package com.matuvent.mineturn.test;

import com.google.gson.JsonParser;
import com.matuvent.mineturn.data.CombatData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Regression coverage for datapack override, disable and optional-reference semantics.
 *
 * <p>A sibling suite already covers priority beating specificity, an exact selector beating a tag
 * selector, template inheritance with {@code null} removal, cycle and depth diagnostics, one
 * {@code enabled:false} mob, and optional selectors that match nothing. What is asserted here is what
 * those cases leave open: that equal priorities are rejected rather than silently resolved, that a
 * disabled entry cannot be resurrected by the vanilla catalog, and that a non-optional unknown
 * reference fails loudly while the same reference marked optional is allowed to vanish.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class DataOverrideRegressionGameTests {
    private static Map<ResourceLocation, com.google.gson.JsonElement> files(String... pairs) {
        var map = new HashMap<ResourceLocation, com.google.gson.JsonElement>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(ResourceLocation.parse(pairs[i]), JsonParser.parseString(pairs[i + 1]));
        }
        return map;
    }

    /**
     * A minimal but complete item file. Items and grants are validated against the action table, and a
     * fixture set defines its own actions, so every reference here points at {@link #PROBE_ACTION}.
     */
    private static String item(double meleeRange, String extra) {
        return "{\"items\":[\"minecraft:trident\"]" + extra
                + ",\"combat\":{\"actions\":[\"" + PROBE_ACTION + "\"],\"melee_range\":" + meleeRange + "}}";
    }

    /** The action every fixture references; declared in the same file map so the reference resolves. */
    private static final String PROBE_ACTION = "test:probe";

    private static final String PROBE_ACTION_FILE =
            "{\"name\":\"probe\",\"effect\":\"mineturn:bee_sting\",\"amount\":1,\"range\":2}";

    /**
     * A minimal but complete mob file. The resolver requires {@code agility}, {@code reach} and an
     * {@code ai} object, and {@code ai.on_turn} is mandatory and must be a real identifier - it is stored
     * as a ResourceLocation and never resolved at parse time, so a plain id is enough here. The legacy
     * {@code states} form is mutually exclusive with {@code ai}.
     */
    private static String mob(String selector, String extra) {
        return "{" + selector + extra + ",\"agility\":100,\"reach\":1.5,"
                + "\"ai\":{\"on_turn\":\"test:turn\",\"parameters\":{}}}";
    }

    /**
     * The catalog only checks that the actions {@code mineturn:eat} and {@code mineturn:melee} exist by
     * key, and never inspects their effects, so any two real registered effects will do. A real datapack
     * ships matching effect ids; a fixture cannot, because {@code mineturn:eat} is an action, not an
     * effect. Both use the same effect because it is one of the few whose definition validation is just
     * "not self-targeted, costs nothing extra, not ranged" - effects registered for mobs carry extra
     * parameter requirements that have nothing to do with what this test checks.
     */
    private static final String EAT_ACTION =
            "{\"name\":\"eat\",\"effect\":\"mineturn:fangs_line\",\"amount\":0,\"range\":1}";
    private static final String MELEE_ACTION =
            "{\"name\":\"melee\",\"effect\":\"mineturn:fangs_line\",\"amount\":1,\"range\":2}";

    /** Two files claiming the same item at the same priority is ambiguous, so it must be an error. */
    @GameTest(template = "empty")
    public static void equalPriorityIsRejectedInsteadOfSilentlyResolved(GameTestHelper h) {
        var conflicting = files(
                "test:actions/probe", PROBE_ACTION_FILE,
                "test:items/a", item(3.0, ""),
                "test:items/b", item(7.0, ""));
        try {
            CombatData.parse(conflicting);
            throw new AssertionError("Two files at equal priority were accepted");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("Conflicting"),
                    "Missing conflict diagnostic: " + expected.getMessage());
        }

        // A strictly higher priority wins without complaint, and the losing file must not contribute.
        var ranked = files(
                "test:actions/probe", PROBE_ACTION_FILE,
                "test:items/a", item(3.0, ""),
                "test:items/b", item(7.0, ",\"priority\":5"));
        var winner = CombatData.parse(ranked);
        h.assertTrue(winner.items().get("minecraft:trident").meleeRange() == 7.0,
                "Higher priority did not win: melee_range=" + winner.items().get("minecraft:trident").meleeRange());
        h.assertTrue(winner.sources().get("items/minecraft:trident").equals("test:items/b"),
                "Source attribution points at the losing file: " + winner.sources().get("items/minecraft:trident"));
        h.succeed();
    }

    /**
     * Disabling an item is expressed by overriding it with {@code enabled:false}. The vanilla catalog
     * fills in unconfigured items, and it must treat the explicit entry as configured - otherwise a
     * disable would be silently undone.
     */
    @GameTest(template = "empty")
    public static void explicitDisableSurvivesTheVanillaCatalog(GameTestHelper h) {
        var disabled = CombatData.parse(files(
                "mineturn:actions/eat", EAT_ACTION,
                "mineturn:actions/melee", MELEE_ACTION,
                "mineturn:catalog/vanilla", "{\"enabled\":true}",
                "test:items/off", "{\"items\":[\"minecraft:trident\"],\"combat\":{\"enabled\":false}}"));
        h.assertTrue(disabled.items().containsKey("minecraft:trident"),
                "The disabled entry vanished instead of being recorded as disabled");
        h.assertTrue(!disabled.items().get("minecraft:trident").enabled(),
                "The vanilla catalog re-enabled an item the datapack disabled");
        h.assertTrue(disabled.items().keySet().stream().anyMatch(id -> !id.equals("minecraft:trident")),
                "The catalog did not fill in any other vanilla item, so this test proves nothing");

        // With the catalog switched off entirely, nothing is auto-added.
        var noCatalog = CombatData.parse(files(
                "mineturn:actions/eat", EAT_ACTION,
                "mineturn:actions/melee", MELEE_ACTION,
                "mineturn:catalog/vanilla", "{\"enabled\":false}",
                "test:items/off", "{\"items\":[\"minecraft:trident\"],\"combat\":{\"enabled\":false}}"));
        h.assertTrue(noCatalog.items().size() == 1,
                "A disabled catalog still contributed items: " + noCatalog.items().keySet());
        h.succeed();
    }

    /**
     * Only catalog, mobs, items and grants understand {@code enabled}. An actions file that carries the
     * flag is still registered, so this test records the real scope rather than an assumed one: anyone
     * expecting to switch an action off with {@code enabled:false} needs to know it does nothing.
     */
    @GameTest(template = "empty")
    public static void enabledScopeIsCatalogMobsItemsAndGrantsOnly(GameTestHelper h) {
        // An actions file ignores the flag entirely: the action stays registered.
        var action = CombatData.parse(files(
                "test:actions/probe",
                "{\"name\":\"probe\",\"effect\":\"mineturn:bee_sting\",\"amount\":1,\"range\":2,\"enabled\":false}"));
        h.assertTrue(action.actions().containsKey("test:probe"),
                "An actions file unexpectedly honoured enabled:false, so the documented scope is wrong");

        // A grants file does read the flag. The grant's action field must name a registered action key,
        // and the action must not consume an equipment stack.
        var grant = CombatData.parse(files(
                "test:actions/probe", PROBE_ACTION_FILE,
                "test:grants/off",
                "{\"action\":\"" + PROBE_ACTION + "\",\"name\":\"probe\",\"icon\":\"minecraft:stick\",\"order\":1,\"enabled\":false}"));
        var off = grant.grants().get("test:off");
        h.assertTrue(off != null, "The disabled grant was not registered at all: " + grant.grants().keySet());
        h.assertTrue(!off.enabled(), "A grants file ignored enabled:false");
        h.assertTrue(off.action().equals(PROBE_ACTION) && off.name().equals("probe"),
                "The grant lost fields while being disabled: " + off);

        var on = CombatData.parse(files(
                "test:actions/probe", PROBE_ACTION_FILE,
                "test:grants/on",
                "{\"action\":\"" + PROBE_ACTION + "\",\"name\":\"probe\",\"icon\":\"minecraft:stick\",\"order\":1}"))
                .grants().get("test:on");
        h.assertTrue(on != null && on.enabled(), "A grant without the flag was not enabled by default");
        h.succeed();
    }

    /**
     * An unknown reference is a hard error by default, but marking the entry optional is an explicit
     * promise that the referenced content may be absent - which is how a datapack can depend on another
     * mod without breaking when that mod is missing.
     */
    @GameTest(template = "empty")
    public static void optionalReferencesSkipMissingContent(GameTestHelper h) {
        var missing = files("test:mobs/gone", mob("\"entity\":\"absent:mod_mob\"", ""));
        try {
            CombatData.parse(missing);
            throw new AssertionError("An unknown entity was accepted without optional:true");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("absent:mod_mob"),
                    "Missing reference diagnostic does not name the offender: " + expected.getMessage());
        }

        var optional = CombatData.parse(files("test:mobs/gone",
                mob("\"match\":{\"entities\":[\"absent:mod_mob\"],\"entity_tags\":[\"absent:tag\"],"
                        + "\"namespaces\":[\"absent\"]}", ",\"optional\":true")));
        h.assertTrue(optional.mobs().isEmpty(),
                "An optional reference to missing content still produced mob mappings: " + optional.mobs().keySet());

        // The same missing name inside an entity tag list is covered by the same flag.
        var optionalTags = CombatData.parse(
                files("test:mobs/gone", mob("\"match\":{\"entity_tags\":[\"absent:tag\"]}", ",\"optional\":true")),
                Map.of());
        h.assertTrue(optionalTags.mobs().isEmpty(), "An optional missing tag produced mob mappings");

        // But a tag that DOES exist must still be honoured, so optional does not disable matching.
        var present = CombatData.parse(
                files("test:mobs/here", mob("\"match\":{\"entity_tags\":[\"test:group\"]}", ",\"optional\":true")),
                Map.of(ResourceLocation.parse("test:group"), Set.of("minecraft:husk")));
        h.assertTrue(present.mobs().containsKey("minecraft:husk"),
                "optional:true also suppressed a tag that exists: " + present.mobs().keySet());
        h.succeed();
    }

    /** A disabled mob mapping must not appear, while other entities from the same selector survive. */
    @GameTest(template = "empty")
    public static void disabledMobIsDroppedWithoutAffectingSiblings(GameTestHelper h) {
        var tags = Map.of(ResourceLocation.parse("test:group"), Set.of("minecraft:husk", "minecraft:drowned"));
        var parsed = CombatData.parse(
                files("test:mobs/batch", mob("\"match\":{\"entity_tags\":[\"test:group\"]}", "")), tags);
        h.assertTrue(parsed.mobs().size() == 2,
                "The shared selector did not map both entities: " + parsed.mobs().keySet());

        var patched = new HashMap<ResourceLocation, com.google.gson.JsonElement>();
        patched.put(ResourceLocation.parse("test:mobs/batch"),
                JsonParser.parseString(mob("\"match\":{\"entity_tags\":[\"test:group\"]}", "")));
        patched.put(ResourceLocation.parse("test:mobs/off"),
                JsonParser.parseString(mob("\"entity\":\"minecraft:husk\"", ",\"enabled\":false")));
        var filtered = CombatData.parse(patched, tags);
        h.assertTrue(!filtered.mobs().containsKey("minecraft:husk"),
                "A disabled mob mapping survived: " + filtered.mobs().keySet());
        h.assertTrue(filtered.mobs().containsKey("minecraft:drowned"),
                "Disabling one entity also removed an unrelated one from the same selector");
        h.succeed();
    }
}
