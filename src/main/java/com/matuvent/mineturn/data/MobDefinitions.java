package com.matuvent.mineturn.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.matuvent.mineturn.MineTurn;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Resolve template inheritance and selectors before compiling runtime brains. No world/entity creation. */
public final class MobDefinitions {
    public record Definition(ResourceLocation source, JsonObject json) {}
    private record Candidate(ResourceLocation source, JsonObject json, int priority, int specificity) {}
    private final Map<ResourceLocation, JsonObject> templates = new TreeMap<>();
    private final Map<ResourceLocation, JsonObject> expanded = new HashMap<>();
    private final Set<String> entities;
    private final Map<ResourceLocation, Set<String>> tags;

    private MobDefinitions(Map<ResourceLocation, JsonElement> files, Set<String> entities, Map<ResourceLocation, Set<String>> tags) {
        this.entities = entities; this.tags = tags;
        files.forEach((id, json) -> {
            if (id.getPath().startsWith("ai_templates/")) templates.put(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath().substring(13)), json.getAsJsonObject());
        });
    }
    public static Map<String, Definition> resolve(Map<ResourceLocation, JsonElement> files, Set<String> entities, Map<ResourceLocation, Set<String>> tags) {
        return new MobDefinitions(files, entities, tags).resolve(files);
    }
    private Map<String, Definition> resolve(Map<ResourceLocation, JsonElement> files) {
        for (ResourceLocation id : templates.keySet()) template(id, new ArrayList<>());
        Map<String, List<Candidate>> candidates = new TreeMap<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!entry.getKey().getPath().startsWith("mobs/")) return;
            try {
                JsonObject raw = entry.getValue().getAsJsonObject();
                boolean optional = flag(raw, "optional", false);
                Map<String, Integer> matches = match(raw, optional);
                if (matches.isEmpty()) { MineTurn.LOGGER.warn("MineTurn rule {} matches no entities", entry.getKey()); return; }
                JsonObject json = raw.has("template") ? merge(template(id(raw.get("template").getAsString()), new ArrayList<>()), raw) : raw.deepCopy();
                int priority = integer(raw, "priority", 0);
                matches.forEach((entity, specificity) -> candidates.computeIfAbsent(entity, ignored -> new ArrayList<>())
                        .add(new Candidate(entry.getKey(), json, priority, specificity)));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid MineTurn file " + entry.getKey() + ": " + error.getMessage(), error);
            }
        });
        Map<String, Definition> result = new TreeMap<>();
        Comparator<Candidate> rank = Comparator.comparingInt(Candidate::priority).thenComparingInt(Candidate::specificity).reversed();
        candidates.forEach((entity, matches) -> {
            matches.sort(rank);
            Candidate winner = matches.getFirst();
            if (matches.size() > 1 && rank.compare(winner, matches.get(1)) == 0)
                throw new IllegalArgumentException("Conflicting mobs/" + entity + " at priority " + winner.priority + ": " + winner.source + " and " + matches.get(1).source);
            result.put(entity, new Definition(winner.source, winner.json));
        });
        return Collections.unmodifiableMap(result);
    }
    private Map<String, Integer> match(JsonObject json, boolean optional) {
        if (json.has("entity") == json.has("match")) throw new IllegalArgumentException("Specify exactly one of entity or match");
        Map<String, Integer> result = new TreeMap<>();
        if (json.has("entity")) {
            addEntity(result, json.get("entity").getAsString(), optional); return result;
        }
        JsonObject match = json.getAsJsonObject("match");
        for (String key : match.keySet()) if (!Set.of("entities", "entity_tags", "namespaces", "exclude").contains(key))
            throw new IllegalArgumentException("Unknown match selector " + key);
        boolean positive = false;
        if (match.has("entities")) {
            positive = true;
            for (JsonElement value : match.getAsJsonArray("entities")) addEntity(result, value.getAsString(), optional);
        }
        if (match.has("entity_tags")) {
            positive = true;
            for (JsonElement value : match.getAsJsonArray("entity_tags"))
                for (String entity : tag(value.getAsString(), optional)) result.merge(entity, 2, Math::max);
        }
        if (match.has("namespaces")) {
            positive = true;
            for (JsonElement value : match.getAsJsonArray("namespaces")) {
                String namespace = value.getAsString();
                ResourceLocation.fromNamespaceAndPath(namespace, "validation");
                var selected = entities.stream().filter(entity -> id(entity).getNamespace().equals(namespace)).toList();
                if (selected.isEmpty() && !optional) throw new IllegalArgumentException("Unknown entity namespace " + namespace);
                for (String entity : selected) result.merge(entity, 1, Math::max);
            }
        }
        if (!positive) throw new IllegalArgumentException("match needs entities, entity_tags or namespaces");
        if (match.has("exclude")) for (JsonElement value : match.getAsJsonArray("exclude")) {
            String text = value.getAsString();
            if (text.startsWith("#")) tag(text.substring(1), optional).forEach(result::remove);
            else {
                String entity = id(text).toString();
                if (!entities.contains(entity) && !optional) throw new IllegalArgumentException("Unknown excluded entity " + entity);
                result.remove(entity);
            }
        }
        return result;
    }
    private void addEntity(Map<String, Integer> selected, String value, boolean optional) {
        String entity = id(value).toString();
        if (!entities.contains(entity)) {
            if (!optional) throw new IllegalArgumentException("Unknown entity " + entity);
            MineTurn.LOGGER.info("MineTurn optional entity skipped: {}", entity); return;
        }
        selected.put(entity, 3);
    }
    private Set<String> tag(String value, boolean optional) {
        ResourceLocation tag = id(value);
        if (!tags.containsKey(tag)) {
            if (!optional) throw new IllegalArgumentException("Unknown entity tag " + tag);
            MineTurn.LOGGER.info("MineTurn optional entity tag skipped: {}", tag); return Set.of();
        }
        Set<String> values = tags.get(tag);
        if (values.isEmpty()) MineTurn.LOGGER.warn("MineTurn entity tag is empty: {}", tag);
        return values;
    }
    private JsonObject template(ResourceLocation id, List<ResourceLocation> path) {
        // Check depth before the cache: a long chain remains invalid even when ancestors were expanded earlier.
        if (path.contains(id)) throw new IllegalArgumentException("AI template inheritance cycle: " + path + " → " + id);
        if (path.size() >= 8) throw new IllegalArgumentException("AI template inheritance exceeds 8 levels: " + path + " → " + id);
        JsonObject raw = templates.get(id);
        if (raw == null) throw new IllegalArgumentException("Missing AI template " + id);
        for (String key : raw.keySet()) if (!Set.of("extends", "schema_version", "agility", "reach", "ai", "states", "initial_state", "movement_mode").contains(key))
            throw new IllegalArgumentException("AI template " + id + " contains unsupported field " + key);
        List<ResourceLocation> next = new ArrayList<>(path); next.add(id);
        JsonObject parent = raw.has("extends") ? template(id(raw.get("extends").getAsString()), next) : new JsonObject();
        return expanded.computeIfAbsent(id, ignored -> merge(parent, raw)).deepCopy();
    }
    /** Parameters/callbacks merge by key; each named state is replaced as a whole. Other fields replace. */
    private static JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject result = parent.deepCopy();
        if (child.has("ai") && !child.has("states")) { result.remove("states"); result.remove("initial_state"); }
        if (child.has("states") && !child.has("ai")) result.remove("ai");
        child.entrySet().forEach(entry -> {
            String key = entry.getKey(); JsonElement value = entry.getValue();
            if (key.equals("extends") || key.equals("template")) return;
            if (value.isJsonNull()) { result.remove(key); return; }
            if ((key.equals("ai") || key.equals("states")) && value.isJsonObject() && result.has(key)) {
                JsonObject object = result.getAsJsonObject(key).deepCopy();
                value.getAsJsonObject().entrySet().forEach(field -> {
                    if (field.getValue().isJsonNull()) object.remove(field.getKey());
                    else if (key.equals("ai") && field.getKey().equals("parameters") && object.has("parameters")) {
                        JsonObject parameters = object.getAsJsonObject("parameters").deepCopy();
                        field.getValue().getAsJsonObject().entrySet().forEach(parameter -> {
                            if (parameter.getValue().isJsonNull()) parameters.remove(parameter.getKey());
                            else parameters.add(parameter.getKey(), parameter.getValue().deepCopy());
                        });
                        object.add("parameters", parameters);
                    } else object.add(field.getKey(), field.getValue().deepCopy());
                });
                result.add(key, object);
            } else result.add(key, value.deepCopy());
        });
        return result;
    }
    public static boolean flag(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        if (!json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean " + key);
        return json.get(key).getAsBoolean();
    }
    private static int integer(JsonObject json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > 1000000) throw new IllegalArgumentException("Invalid " + key);
        return (int) value;
    }
    private static ResourceLocation id(String text) { return ResourceLocation.parse(text); }
}
