package com.matuvent.mineturn.data;

import com.google.gson.*;
import com.matuvent.mineturn.MineTurn;
import com.matuvent.mineturn.api.CombatEffects;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Cross-file validation and atomic snapshot replacement. */
public final class CombatData extends SimpleJsonResourceReloadListener {
    public record Ranged(int durationMs, double nearWidth, double minWidth, double distanceFactor, String ammunition, int ammoCount) {
        public double width(double distance) { return Math.max(minWidth, nearWidth / (1 + Math.max(0, distance) * distanceFactor)); }
    }
    public record Action(String name, String effect, double amount, double range, boolean self, int consume, double cooldown, JsonObject parameters, Ranged ranged) {
        public Action(String name, String effect, double amount, double range, boolean self, int consume, double cooldown, JsonObject parameters) {
            this(name, effect, amount, range, self, consume, cooldown, parameters, null);
        }
        public Action { parameters = parameters.deepCopy(); ActionCost.parse(parameters.get("action_cost")); }
        public ActionCost cost(){return ActionCost.parse(parameters.get("action_cost"));}
        @Override public JsonObject parameters() { return parameters.deepCopy(); }
        public boolean allied(){return parameters.has("target_side") && parameters.get("target_side").getAsString().equals("ally");}
    }
    public record Choice(String action, int weight) {}
    public record Transition(String condition, String to) {}
    public record State(String behavior, List<Transition> transitions, List<Choice> choices) {}
    public record Functions(Map<String, ResourceLocation> callbacks, CompoundTag parameters) {}
    public record Brain(double agility, double reach, String initial, Map<String, State> states, Functions functions, String movementMode,String participation) {
        public Brain(double agility,double reach,String initial,Map<String,State> states,Functions functions,String movementMode){this(agility,reach,initial,states,functions,movementMode,"auto");}
    }
    public record Snapshot(Map<String, Action> actions, Map<String, Brain> mobs, Map<String, CombatItem> items, Map<String, String> sources, Map<String,GrantedAction> grants) {
        public Snapshot(Map<String,Action> actions,Map<String,Brain> mobs,Map<String,CombatItem> items,Map<String,String> sources){this(actions,mobs,items,sources,Map.of());}
        public Snapshot {grants=Map.copyOf(grants);}
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of()); }
    }
    public static volatile Snapshot current = Snapshot.empty();
    private static final Map<net.minecraft.server.ServerFunctionLibrary, Snapshot> PREPARED = Collections.synchronizedMap(new WeakHashMap<>());
    public static void activate(net.minecraft.server.ServerFunctionLibrary library) {
        Snapshot snapshot = PREPARED.remove(library);
        if (snapshot != null) current = snapshot;
    }
    private final ReloadableServerResources reloadables;
    public CombatData(ReloadableServerResources reloadables) { super(new Gson(), "mineturn"); this.reloadables = reloadables; }
    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
        try {
            Map<ResourceLocation, Set<String>> tags = new HashMap<>();
            reloadables.getConditionContext().getAllTags(net.minecraft.core.registries.Registries.ENTITY_TYPE).forEach((id, holders) ->
                    tags.put(id, holders.stream().map(holder -> BuiltInRegistries.ENTITY_TYPE.getKey(holder.value()).toString()).collect(java.util.stream.Collectors.toUnmodifiableSet())));
            Snapshot candidate = parse(files, tags);
            validateFunctions(candidate, id -> reloadables.getFunctionLibrary().getFunction(id).orElse(null), reloadables.getCommands().getDispatcher());
            candidate.mobs.keySet().forEach(id -> require(BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id)), "Unknown entity " + id));
            candidate.items.keySet().forEach(id -> require(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id)), "Unknown item " + id));
            Map<String, String> sources = new HashMap<>();
            candidate.sources.forEach((key, definition) -> {
                ResourceLocation id = ResourceLocation.parse(definition);
                String pack = resources.getResource(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "mineturn/" + id.getPath() + ".json"))
                        .map(resource -> resource.sourcePackId()).orElse("unknown");
                sources.put(key, definition + " [" + pack + "]");
            });
            PREPARED.put(reloadables.getFunctionLibrary(), new Snapshot(candidate.actions, candidate.mobs, candidate.items, Map.copyOf(sources),candidate.grants));
            MineTurn.LOGGER.info("Prepared MineTurn: {} actions, {} mobs, {} items", candidate.actions.size(), candidate.mobs.size(), candidate.items.size());
        } catch (RuntimeException ex) {
            MineTurn.LOGGER.error("MineTurn reload rejected; retaining previous definitions", ex);
            throw ex;
        }
    }
    public static Snapshot parse(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, Set<String>> tags = new HashMap<>();
        BuiltInRegistries.ENTITY_TYPE.getTags().forEach(pair -> tags.put(pair.getFirst().location(), pair.getSecond().stream()
                .map(holder -> BuiltInRegistries.ENTITY_TYPE.getKey(holder.value()).toString()).collect(java.util.stream.Collectors.toUnmodifiableSet())));
        return parse(files, tags);
    }
    public static Snapshot parse(Map<ResourceLocation, JsonElement> files, Map<ResourceLocation, Set<String>> tags) {
        Map<String, Action> actions = new TreeMap<>();
        Map<String, Brain> mobs = new TreeMap<>();
        Map<String, CombatItem> items = new TreeMap<>();
        Map<String, Integer> priorities = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        Map<String,GrantedAction> grants=new TreeMap<>();
        var resolved = MobDefinitions.resolve(files, BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toUnmodifiableSet()), tags);
        var ordered = files.entrySet().stream().filter(entry -> !entry.getKey().getPath().startsWith("mobs/") && !entry.getKey().getPath().startsWith("ai_templates/")).sorted(Comparator.<Map.Entry<ResourceLocation, JsonElement>>comparingInt(
                entry -> priority(entry.getValue().getAsJsonObject())).reversed().thenComparing(entry -> entry.getKey().toString())).toList();
        for (var entry : ordered) {
            try {
                var id = entry.getKey();
                var json = entry.getValue().getAsJsonObject();
                if (json.has("schema_version")) require(integer(json, "schema_version", 1, 2) <= 2, "Unsupported schema_version");
                String path = id.getPath();
                if (id.equals(ResourceLocation.parse("mineturn:catalog/vanilla"))) {
                    MobDefinitions.flag(json, "enabled", true);
                } else if (path.startsWith("actions/")) {
                    String key = id.getNamespace() + ":" + path.substring(8);
                    String effect = str(json, "effect");
                    require(CombatEffects.contains(effect), "Unknown effect " + effect);
                    actions.put(key, new Action(str(json, "name"), effect, number(json, "amount", 0, 1000),
                            number(json, "range", 0.1, 16), json.has("self") && json.get("self").getAsBoolean(),
                            json.has("consume") ? integer(json, "consume", 0, 64) : 0,
                            json.has("cooldown_av") ? number(json, "cooldown_av", 0, 100000) : 0,
                            json.has("parameters") ? json.getAsJsonObject("parameters") : new JsonObject(), parseRanged(json)));
                    require(actions.get(key).ranged() == null || !actions.get(key).self() && (actions.get(key).consume() == 0 || actions.get(key).consume() == 1 && actions.get(key).ranged().ammoCount() == 0), "Ranged actions require enemy target and either ammo or one consumed thrown item with ammo_count=0");
                    require(actions.get(key).parameters().toString().length() <= 8192, "Action parameters too large");
                    var cost=actions.get(key).parameters();
                    actions.get(key).cost();
                    require(!cost.has("target_side") || Set.of("enemy","ally").contains(cost.get("target_side").getAsString()),"Invalid target_side");
                    require(!actions.get(key).allied() || !actions.get(key).self() && (actions.get(key).ranged()==null
                            || java.util.Set.of("mineturn:splash","mineturn:lingering").contains(actions.get(key).effect())),"Ranged ally actions require a potion effect");
                    if(cost.has("ground_target")) {
                        var ground=cost.get("ground_target");
                        require(ground.isJsonPrimitive() && ground.getAsJsonPrimitive().isBoolean(),"ground_target must be boolean");
                        require(!ground.getAsBoolean() || java.util.Set.of("mineturn:splash","mineturn:lingering","mineturn:riptide","mineturn:ender_pearl").contains(actions.get(key).effect()) && !actions.get(key).allied(),"Ground targets require a potion/riptide effect and no ally filter");
                    }
                    CombatEffects.get(effect).validateDefinition(actions.get(key));
                    sources.put("actions/" + key, id.toString());
                } else if(path.startsWith("grants/")) {
                    String key=id.getNamespace()+":"+path.substring(7);
                    grants.put(key,GrantedAction.parse(json));sources.put("grants/"+key,id.toString());
                } else if (path.startsWith("items/")) {
                    CombatItem config = CombatItem.CODEC.parse(JsonOps.INSTANCE, json.get("combat")).getOrThrow();
                    for (var item : json.getAsJsonArray("items")) {
                        String key = validId(item.getAsString());
                        if (select("items/" + key, id, priority(json), priorities, sources)) items.put(key, config);
                    }
                } else throw new IllegalArgumentException("Unknown definition folder");
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Invalid MineTurn file " + entry.getKey() + ": " + ex.getMessage(), ex);
            }
        }
        resolved.forEach((entity, definition) -> {
            try {
                sources.put("mobs/" + entity, definition.source().toString());
                JsonObject json = definition.json();
                if (json.has("schema_version")) integer(json, "schema_version", 1, 2);
                if (MobDefinitions.flag(json, "enabled", true)) mobs.put(entity, parseBrain(json));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid MineTurn file " + definition.source() + " for " + entity + ": " + error.getMessage(), error);
            }
        });
        var catalog=files.get(ResourceLocation.parse("mineturn:catalog/vanilla"));
        if(catalog!=null && MobDefinitions.flag(catalog.getAsJsonObject(),"enabled",true))VanillaCatalog.addItems(items,actions,sources);
        for (var mob : mobs.entrySet()) for (State state : mob.getValue().states.values()) for (Choice choice : state.choices)
            require(actions.containsKey(choice.action), "Mob " + mob.getKey() + " references missing action " + choice.action);
        for (var item : items.entrySet()) for (String action : item.getValue().actions())
            require(actions.containsKey(action), "Item " + item.getKey() + " references missing action " + action);
        require(grants.size()<=128,"Too many granted actions (max 128)");
        grants.forEach((id,grant)->{
            var action=actions.get(grant.action());require(action!=null,"Grant references missing action: "+id);
            require(action.consume()==0,"Granted actions cannot consume an equipment stack: "+id);
        });
        return new Snapshot(Map.copyOf(actions), Map.copyOf(mobs), Map.copyOf(items), Map.copyOf(sources),grants);
    }
    private static Brain parseBrain(JsonObject json) {
        String participation=json.has("participation")?str(json,"participation"):"auto";
        require(Set.of("auto","never","neutral","hostile").contains(participation),"Unknown participation "+participation);
        String movementMode = json.has("movement_mode") ? str(json, "movement_mode") : "ground";
        require(Set.of("ground", "flying", "swimming", "climbing", "phasing").contains(movementMode), "Unknown movement_mode " + movementMode);
        if (json.has("ai")) {
            require(!json.has("states"), "Choose ai or legacy states, not both");
            JsonObject ai = json.getAsJsonObject("ai");
            Map<String, ResourceLocation> callbacks = new HashMap<>();
            for (String key : ai.keySet()) {
                require(Set.of("on_enter", "on_turn", "on_move_finished", "on_action_resolved", "on_leave", "parameters").contains(key), "Unknown ai field " + key);
                if (!key.equals("parameters")) callbacks.put(key, ResourceLocation.parse(validId(str(ai, key))));
            }
            require(callbacks.containsKey("on_turn"), "ai.on_turn is required");
            CompoundTag parameters;
            try { parameters = ai.has("parameters") ? TagParser.parseTag(ai.getAsJsonObject("parameters").toString()) : new CompoundTag(); }
            catch (Exception ex) { throw new IllegalArgumentException("Invalid ai.parameters", ex); }
            require(parameters.toString().length() <= 8192, "ai.parameters too large");
            return new Brain(json.has("agility") ? number(json, "agility", 1, 1000) : 100,
                    json.has("reach") ? number(json, "reach", 0.1, 16) : 1.5, "", Map.of(), new Functions(Map.copyOf(callbacks), parameters), movementMode,participation);

        }
        Map<String, State> states = new LinkedHashMap<>();
        for (var stateEntry : json.getAsJsonObject("states").entrySet()) {
            var state = stateEntry.getValue().getAsJsonObject();
            String behavior = str(state, "behavior");
            require(Set.of("approach", "weighted_action", "wait").contains(behavior), "Unknown behavior " + behavior);
            List<Transition> transitions = new ArrayList<>();
            if (state.has("transitions")) for (var t : state.getAsJsonArray("transitions")) {
                var obj = t.getAsJsonObject();
                String condition = str(obj, "when");
                require(Set.of("in_reach", "out_of_reach").contains(condition), "Unknown condition " + condition);
                transitions.add(new Transition(condition, str(obj, "to")));
            }
            List<Choice> choices = new ArrayList<>();
            if (state.has("choices")) for (var c : state.getAsJsonArray("choices")) {
                var obj = c.getAsJsonObject();
                choices.add(new Choice(str(obj, "action"), integer(obj, "weight", 1, 10000)));
            }
            require(choices.size() <= 64, "Too many choices");
            require(!behavior.equals("weighted_action") || !choices.isEmpty(), "Empty weighted action");
            states.put(stateEntry.getKey(), new State(behavior, List.copyOf(transitions), List.copyOf(choices)));
        }
        String initial = str(json, "initial_state");
        require(states.containsKey(initial), "Missing initial state");
        for (State state : states.values()) for (Transition t : state.transitions) require(states.containsKey(t.to), "Missing state " + t.to);
        return new Brain(number(json, "agility", 1, 1000), number(json, "reach", 0.1, 16), initial, Map.copyOf(states), null, movementMode,participation);
    }
    private static Ranged parseRanged(JsonObject json) {
        if (!json.has("ranged")) return null;
        var value = json.getAsJsonObject("ranged");
        int duration = value.has("duration_ms") ? integer(value, "duration_ms", 800, 10000) : 1200;
        double near = value.has("near_width") ? number(value, "near_width", 0.02, 1) : 0.4;
        double min = value.has("min_width") ? number(value, "min_width", 0.02, 1) : 0.06;
        require(min <= near, "min_width must not exceed near_width");
        String ammo = value.has("ammunition") ? validId(str(value, "ammunition")) : "minecraft:arrow";
        require(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(ammo)) && !ammo.equals("minecraft:air"), "Unknown ammunition " + ammo);
        return new Ranged(duration, near, min, value.has("distance_factor") ? number(value,"distance_factor",0,10) : 0.14,
                ammo, value.has("ammo_count") ? integer(value,"ammo_count",0,64) : 1);
    }
    public static void validateFunctions(Snapshot data,
            java.util.function.Function<ResourceLocation, net.minecraft.commands.functions.CommandFunction<net.minecraft.commands.CommandSourceStack>> lookup,
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        data.mobs.forEach((entity, brain) -> {
            if (brain.functions == null) return;
            brain.functions.callbacks.forEach((event, id) -> {
                var function = lookup.apply(id);
                String where = data.sources.get("mobs/" + entity) + " → ai." + event + " → " + id;
                require(function != null, "Missing or invalid function " + where);
                try { function.instantiate(brain.functions.parameters.copy(), dispatcher); }
                catch (Exception ex) { throw new IllegalArgumentException("Invalid function parameters " + where, ex); }
            });
        });
    }
    private static int priority(JsonObject json) { return json.has("priority") ? integer(json, "priority", -1000000, 1000000) : 0; }
    private static boolean select(String key, ResourceLocation file, int priority, Map<String, Integer> priorities, Map<String, String> sources) {
        if (priorities.containsKey(key)) {
            require(priorities.get(key) != priority, "Conflicting " + key + " at priority " + priority + ": " + sources.get(key) + " and " + file);
            return false;
        }
        priorities.put(key, priority); sources.put(key, file.toString()); return true;
    }
    private static String validId(String value) { require(ResourceLocation.tryParse(value) != null, "Invalid ID " + value); return value; }
    private static String str(JsonObject obj, String key) { return obj.get(key).getAsString(); }
    private static double number(JsonObject obj, String key, double min, double max) {
        double value = obj.get(key).getAsDouble();
        require(Double.isFinite(value) && value >= min && value <= max, "Invalid " + key);
        return value;
    }
    private static int integer(JsonObject obj, String key, int min, int max) {
        double value = number(obj, key, min, max);
        require(value == Math.rint(value), "Expected integer " + key);
        return (int) value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
