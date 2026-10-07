package com.matuvent.mineturn.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

/**
 * Client-side tunables for action-camera framing, loaded from the resource pack directory
 * {@code assets/<namespace>/mineturn/animation_camera.json}. A client resource reload (including
 * {@code /reload}) rebuilds this map, so framing can be tuned without recompiling.
 *
 * <p>Framing deliberately scales with the actor's collision box, so the configured numbers describe a
 * normal-sized mob: the final offset is {@code bbWidth * width_scale + width_base}.
 */
public final class ActionAnimationData extends SimpleJsonResourceReloadListener {
    /**
     * One framing rule. {@code side}/{@code front} are absolute blocks added on top of the collision-box
     * term, {@code widthBase}/{@code widthScale} define that term, {@code lift} raises the camera and
     * {@code minDistance} is a floor measured along the sight line to the aim point.
     */
    public record Camera(double side, double front, double lift, double widthBase, double widthScale, double minDistance) {
        public static final Camera DEFAULT = new Camera(1.25, 1.0, 0.4, 1.2, 0.9, 1.8);
        /** Collision-box term for this rule; final offsets are {@code side + this} / {@code front + this}. */
        public double scaleFor(double bbWidth) { return widthBase + Math.max(0.0, bbWidth) * widthScale; }
    }

    private record FileEntry(Camera camera, Map<String, Camera> actions) {}

    private static volatile Map<String, Camera> cameras = Map.of();
    private final Gson gson = new Gson();

    public ActionAnimationData() { super(new Gson(), "mineturn"); }

    /**
     * Framing rule for an animation id. Files may key overrides by the bare animation path
     * ({@code "eat"}) or by the full id ({@code "mineturn:eat"}); the full id wins.
     */
    public static Camera camera(ResourceLocation animationId) {
        if (animationId == null) return Camera.DEFAULT;
        var byId = cameras.get(animationId.toString());
        if (byId != null) return byId;
        return cameras.getOrDefault(animationId.getPath(), Camera.DEFAULT);
    }

    /** Test/reload hook: install a parsed map directly. */
    public static void install(Map<String, Camera> parsed) { cameras = Map.copyOf(parsed); }

    /** Diagnostics: the currently installed rule keys. */
    public static java.util.Set<String> installedKeys() { return java.util.Set.copyOf(cameras.keySet()); }

    /**
     * Parse and install raw file contents without a resource manager. Used by tests to prove the shipped
     * resource actually loads; a malformed file would otherwise fall back to defaults silently.
     */
    public static int installFromFiles(Map<ResourceLocation, JsonElement> files) {
        new ActionAnimationData().apply(files, null, null);
        return cameras.size();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
        var parsed = new java.util.HashMap<String, Camera>();
        for (var entry : files.entrySet()) {
            try {
                var file = parseFile(entry.getValue());
                if (file.camera() != null) parsed.put(entry.getKey().toString(), file.camera());
                parsed.putAll(file.actions());
            } catch (RuntimeException error) {
                com.matuvent.mineturn.MineTurn.LOGGER.error("Invalid animation camera file {}: {}", entry.getKey(), error.getMessage());
            }
        }
        cameras = Map.copyOf(parsed);
        com.matuvent.mineturn.MineTurn.LOGGER.info("MineTurn animation camera rules: {}", cameras.size());
    }

    private FileEntry parseFile(JsonElement element) {
        var root = element.getAsJsonObject();
        var actions = new java.util.HashMap<String, Camera>();
        Camera camera = root.has("camera") ? readCamera(root.getAsJsonObject("camera")) : null;
        if (root.has("actions")) {
            for (var entry : root.getAsJsonObject("actions").entrySet()) {
                actions.put(entry.getKey(), readCamera(entry.getValue().getAsJsonObject()));
            }
        }
        return new FileEntry(camera, actions);
    }

    /** Reads a camera object, filling every omitted field from {@link Camera#DEFAULT}. */
    private Camera readCamera(com.google.gson.JsonObject object) {
        var base = Camera.DEFAULT;
        return new Camera(
                number(object, "side", base.side()),
                number(object, "front", base.front()),
                number(object, "lift", base.lift()),
                number(object, "width_base", base.widthBase()),
                number(object, "width_scale", base.widthScale()),
                number(object, "min_distance", base.minDistance()));
    }

    private static double number(com.google.gson.JsonObject object, String key, double fallback) {
        if (!object.has(key)) return fallback;
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 64) {
            throw new IllegalArgumentException(key + " must be a finite number in 0..64");
        }
        return value;
    }
}
