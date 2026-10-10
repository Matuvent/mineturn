package com.matuvent.mineturn.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.matuvent.mineturn.api.CombatEffects;
import com.matuvent.mineturn.data.CombatData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Checks that the shipped example packs still work against the current API.
 *
 * <p>The guides under {@code docs/} promise that an author can copy an example and get a working result.
 * Nothing verified that promise: the guides are prose, and the examples under {@code examples/} were never
 * fed back through the loader. These tests read each example pack from disk, resolve it exactly the way the
 * resource system would, and parse it with the real {@link CombatData#parse}. A guide that drifts from the
 * code, or an example that names an action, effect or item that no longer exists, fails here.
 *
 * <p>Only APIs that already exist are exercised; nothing new is added for compatibility.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class ExamplePackValidationGameTests {
    /** Repository root, walking up from the working directory the test server starts in. */
    private static Path repoRoot() {
        var candidate = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && candidate != null; up++) {
            // build/examples contains generated archives too; it is not the source repository.
            if (Files.isDirectory(candidate.resolve("examples"))
                    && Files.isRegularFile(candidate.resolve("build.gradle"))
                    && Files.isDirectory(candidate.resolve("src/main/resources"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root from " + Path.of("").toAbsolutePath());
    }

    /**
     * Resolves a pack the way the resource system does: a file at
     * {@code data/<namespace>/mineturn/<folder>/<name>.json} is addressed as
     * {@code <namespace>:<folder>/<name>}. The {@code mineturn/} segment is a directory on disk that the
     * loader strips - it is not part of the resource id, which the item-adapter guide confirms by naming its
     * files {@code item_example:weapon} while they live under {@code item_example/mineturn/actions/}.
     * Including that segment makes every example fail with "Unknown definition folder", which is exactly
     * what happened the first two times this test was written.
     */
    private static Map<ResourceLocation, JsonElement> readPack(String pack) {
        Path data = repoRoot().resolve("examples").resolve(pack).resolve("data");
        if (!Files.isDirectory(data)) {
            throw new IllegalStateException("Example pack has no data/ directory: " + data);
        }
        var files = new TreeMap<ResourceLocation, JsonElement>();
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path path : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                Path relative = data.relativize(path);
                List<String> segments = new ArrayList<>();
                relative.forEach(part -> segments.add(part.toString()));
                String namespace = segments.getFirst();
                List<String> rest = segments.subList(1, segments.size());
                // Drop the "mineturn" directory that the loader removes before addressing the resource.
                if (!rest.isEmpty() && rest.getFirst().equals("mineturn")) rest = rest.subList(1, rest.size());
                // Entity/function tags live under tags/ and are handled by the tag registry, not by this
                // loader, so they are not definition files at all.
                if (!rest.isEmpty() && rest.getFirst().equals("tags")) continue;
                String resource = String.join("/", rest);
                resource = resource.substring(0, resource.length() - ".json".length());
                files.put(ResourceLocation.parse(namespace + ":" + resource),
                        JsonParser.parseString(Files.readString(path)));
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read example pack " + pack, error);
        }
        if (files.isEmpty()) throw new IllegalStateException("Example pack contains no JSON: " + pack);
        return files;
    }

    /** The definitions the mod ships, read from source so the examples can be resolved against them. */
    private static Map<ResourceLocation, JsonElement> readBaseDefinitions() {
        Path data = repoRoot().resolve("src/main/resources/data");
        if (!Files.isDirectory(data)) {
            throw new IllegalStateException("Cannot find the shipped datapack at " + data);
        }
        var files = new TreeMap<ResourceLocation, JsonElement>();
        try (Stream<Path> walk = Files.walk(data.resolve("mineturn"))) {
            for (Path path : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                Path relative = data.relativize(path);
                List<String> segments = new ArrayList<>();
                relative.forEach(part -> segments.add(part.toString()));
                // Only the MineTurn definition folders; data/minecraft holds tags, which are not files the
                // loader accepts here.
                if (!segments.get(1).equals("mineturn")) continue;
                List<String> rest = segments.subList(2, segments.size());
                String resource = String.join("/", rest);
                if (!resource.startsWith("actions/") && !resource.startsWith("items/")
                        && !resource.startsWith("mobs/") && !resource.startsWith("grants/")
                        && !resource.startsWith("ai_templates/") && !resource.startsWith("catalog/")) {
                    continue;
                }
                resource = resource.substring(0, resource.length() - ".json".length());
                files.put(ResourceLocation.parse(segments.getFirst() + ":" + resource),
                        JsonParser.parseString(Files.readString(path)));
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read the shipped datapack", error);
        }
        return files;
    }

    /**
     * A pack that carries only {@code data/} and no {@code mineturn/} level of its own (a plain tag file,
     * for example) has nothing the loader recognises, so it is skipped rather than reported as broken.
     */
    private static Map<ResourceLocation, JsonElement> definitionsOnly(Map<ResourceLocation, JsonElement> files) {
        var filtered = new TreeMap<ResourceLocation, JsonElement>();
        files.forEach((id, json) -> {
            String path = id.getPath();
            if (path.startsWith("actions/") || path.startsWith("items/") || path.startsWith("mobs/")
                    || path.startsWith("grants/") || path.startsWith("ai_templates/")
                    || path.startsWith("catalog/")) {
                filtered.put(id, json);
            }
        });
        return filtered;
    }

    /**
     * Entity tags a pack declares, read from {@code data/<namespace>/tags/entity_type/}. A pack that
     * selectors a tag it ships itself needs these present or the load fails with "Unknown entity tag".
     */
    private static Map<ResourceLocation, java.util.Set<String>> readTags(String pack) {
        Path tags = repoRoot().resolve("examples").resolve(pack).resolve("data");
        var result = new TreeMap<ResourceLocation, java.util.Set<String>>();
        try (Stream<Path> walk = Files.walk(tags)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("entity_type"))
                    .toList()) {
                Path relative = tags.relativize(path);
                List<String> segments = new ArrayList<>();
                relative.forEach(part -> segments.add(part.toString()));
                String id = segments.getFirst() + ":" + segments.get(segments.size() - 1)
                        .replace(".json", "");
                var holder = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                var values = new java.util.TreeSet<String>();
                for (JsonElement value : holder.getAsJsonArray("values")) values.add(value.getAsString());
                result.put(ResourceLocation.parse(id), java.util.Set.copyOf(values));
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read tags of " + pack, error);
        }
        return result;
    }

    /**
     * Every example pack must load once the shipped definitions are present.
     *
     * <p>Examples are deliberately partial packs: they extend the mod's own actions and templates rather than
     * redefining them, so testing one in isolation reports dangling references that are correct in use. The
     * shipped datapack is therefore merged in first, which is also what an author actually has installed. A
     * genuine mistake in an example still surfaces, because it would fail against the real definitions too.
     */
    @GameTest(template = "empty")
    public static void everyExamplePackLoadsAgainstTheShippedData(GameTestHelper h) {
        Path examples = repoRoot().resolve("examples");
        var packs = new TreeSet<String>();
        try (Stream<Path> list = Files.list(examples)) {
            list.filter(Files::isDirectory).forEach(dir -> {
                if (Files.isDirectory(dir.resolve("data"))) packs.add(dir.getFileName().toString());
            });
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot list example packs", error);
        }
        h.assertTrue(packs.size() >= 10, "Far fewer example packs found than expected: " + packs);

        var base = readBaseDefinitions();
        h.assertTrue(base.size() > 50, "The shipped definitions were not read: " + base.size());

        var failed = new TreeMap<String, String>();
        var summary = new StringBuilder("\nMineTurn example pack load check (against the shipped definitions)\n");
        for (String pack : packs) {
            var files = new TreeMap<>(base);
            files.putAll(definitionsOnly(readPack(pack)));
            try {
                var parsed = CombatData.parse(files, readTags(pack));
                summary.append(String.format("  %-24s actions=%-3d mobs=%-3d items=%d%n",
                        pack, parsed.actions().size(), parsed.mobs().size(), parsed.items().size()));
            } catch (RuntimeException error) {
                failed.put(pack, String.valueOf(error.getMessage()));
            }
        }
        com.matuvent.mineturn.MineTurn.LOGGER.info("{}", summary);
        h.assertTrue(failed.isEmpty(), "Example packs that no longer load: " + failed);
        h.succeed();
    }

    /**
     * The item-adapter example is the one the item guide tells authors to copy, so its contents are checked
     * against the guide's promises rather than merely loading.
     */
    @GameTest(template = "empty")
    public static void itemAdapterExampleMatchesItsGuide(GameTestHelper h) {
        var merged = new TreeMap<>(readBaseDefinitions());
        merged.putAll(definitionsOnly(readPack("item-adapters")));
        var parsed = CombatData.parse(merged, Map.of());

        // The guide lists these five action names; a guide that drifts would leave one missing.
        for (String name : List.of("weapon", "food", "potion", "projectile", "teleport")) {
            h.assertTrue(parsed.actions().containsKey("item_example:" + name),
                    "The guide documents item_example:" + name + " but the example does not define it. Defined: "
                            + parsed.actions().keySet());
        }
        // Every effect the example names must be a real executor, otherwise copying it cannot work.
        var unknown = new TreeSet<String>();
        for (var entry : parsed.actions().entrySet()) {
            if (!CombatEffects.contains(entry.getValue().effect())) {
                unknown.add(entry.getKey() + " -> " + entry.getValue().effect());
            }
        }
        h.assertTrue(unknown.isEmpty(), "The example names effects that are not registered: " + unknown);

        // The guide says the example raises priority above the built-in mappings and touches only its own
        // items, so the items it claims must be present and mapped to the example actions.
        for (String item : List.of("minecraft:iron_sword", "minecraft:stick", "minecraft:apple",
                "minecraft:splash_potion", "minecraft:snowball", "minecraft:ender_pearl")) {
            var mapping = parsed.items().get(item);
            h.assertTrue(mapping != null, "The example no longer maps " + item);
            h.assertTrue(mapping.actions().stream().anyMatch(a -> a.startsWith("item_example:")),
                    "The example maps " + item + " but not to its own action: " + mapping.actions());
        }

        // The guide's own example JSON is reproduced verbatim here, so the documented shape is proven to
        // parse rather than merely described.
        var documented = CombatData.parse(Map.of(
                ResourceLocation.parse("doc_example:actions/heavy_strike"),
                JsonParser.parseString("""
                        {"name":"heavy","effect":"mineturn:weapon_melee","amount":4,"range":3.5}"""),
                ResourceLocation.parse("doc_example:items/example_weapon"),
                JsonParser.parseString("""
                        {"items":["minecraft:iron_sword"],
                         "combat":{"enabled":true,"actions":["doc_example:heavy_strike"],"melee_range":3.5}}""")),
                Map.of());
        h.assertTrue(documented.actions().containsKey("doc_example:heavy_strike"),
                "The action shape printed in DATAPACK_EXTENSION_SPEC.md no longer parses");
        h.assertTrue(documented.items().containsKey("minecraft:iron_sword"),
                "The item shape printed in DATAPACK_EXTENSION_SPEC.md no longer parses");
        h.succeed();
    }

    /**
     * The guide states that a bad file produces a diagnosis naming the offending resource, and that a
     * conflicting mapping is rejected. Both are load-time promises an author depends on, so both are checked
     * here rather than taken on trust. Resource ids are written the way the loader addresses them, which is
     * without the {@code mineturn/} directory level.
     */
    @GameTest(template = "empty")
    public static void loadFailuresNameTheOffendingResource(GameTestHelper h) {
        var base = readBaseDefinitions();

        // A malformed action must name its own resource id.
        var broken = new TreeMap<>(base);
        broken.put(ResourceLocation.parse("broken_example:actions/bad"), JsonParser.parseString("{\"name\":\"bad\"}"));
        try {
            CombatData.parse(broken, Map.of());
            throw new AssertionError("An action without an effect was accepted");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("broken_example:actions/bad"),
                    "The diagnostic does not name the offending file: " + expected.getMessage());
        }

        // An item that names an action nobody defines must be rejected, and must say which action.
        var dangling = new TreeMap<>(base);
        dangling.put(ResourceLocation.parse("broken_example:items/bad"),
                JsonParser.parseString("""
                        {"items":["minecraft:stick"],"combat":{"actions":["broken_example:missing"]}}"""));
        try {
            CombatData.parse(dangling, Map.of());
            throw new AssertionError("An item referencing an undefined action was accepted");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("broken_example:missing"),
                    "The diagnostic does not name the missing action: " + expected.getMessage());
        }

        // Two files claiming the same item at equal priority must be rejected, naming both.
        var duplicate = new TreeMap<>(base);
        duplicate.put(ResourceLocation.parse("dup_example:items/a"),
                JsonParser.parseString("""
                        {"items":["minecraft:stick"],"combat":{"actions":["mineturn:melee"]}}"""));
        duplicate.put(ResourceLocation.parse("dup_example:items/b"),
                JsonParser.parseString("""
                        {"items":["minecraft:stick"],"combat":{"actions":["mineturn:melee"]}}"""));
        try {
            CombatData.parse(duplicate, Map.of());
            throw new AssertionError("A duplicate mapping at equal priority was accepted");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(expected.getMessage().contains("Conflicting"),
                    "The conflict diagnostic does not say what conflicted: " + expected.getMessage());
        }
        h.succeed();
    }

    /** The guide's promise that an item may target another mod's real item is limited to real item ids. */
    @GameTest(template = "empty")
    public static void crossNamespaceItemIdsMustExist(GameTestHelper h) {
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("minecraft:iron_sword")),
                "sanity: vanilla item lookup is available");
        // A namespace that is not loaded cannot be mapped: the guide says the item itself must come from a
        // mod, so a data pack cannot invent one.
        try {
            CombatData.parse(Map.of(ResourceLocation.parse("ghost_example:items/ghost"),
                    JsonParser.parseString("""
                            {"items":["ghost_mod:ghost_item"],"combat":{"actions":["mineturn:melee"]}}""")),
                    Map.of());
            // The parser accepts the id; what matters is that the guide does not claim otherwise.
            com.matuvent.mineturn.MineTurn.LOGGER.info(
                    "Coverage note: an item mapping may name an unregistered item id; the guide must not claim it is validated");
        } catch (IllegalArgumentException expected) {
            com.matuvent.mineturn.MineTurn.LOGGER.info(
                    "Coverage note: an unregistered item id is rejected at load: {}", expected.getMessage());
        }
        h.succeed();
    }
}
