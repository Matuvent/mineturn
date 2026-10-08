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
     * One framing rule. {@code side} offsets the camera to the actor's right and {@code front} along its
     * facing, where a negative {@code front} places the camera behind the actor (an over-the-shoulder
     * shot) and a positive one in front of it. {@code widthBase}/{@code widthScale} scale both offsets
     * with the actor's collision box, {@code lift} raises the camera, {@code minDistance} is a floor
     * measured along the sight line to the aim point, {@code aim} selects what the camera looks at, and
     * {@code blendOutMs} is how long the release back to the free camera takes (0 snaps instantly).
     */
    public record Camera(double side, double front, double lift, double widthBase, double widthScale,
                         double minDistance, double blendOutMs, Aim aim) {
        public static final Camera DEFAULT = new Camera(0.9, -1.6, 0.8, 0.5, 0.5, 1.6, 80, Aim.MIDPOINT);
        /** Collision-box term for this rule; final offsets are {@code side + this} / {@code front + this}. */
        public double scaleFor(double bbWidth) { return widthBase + Math.max(0.0, bbWidth) * widthScale; }
    }

    /** What the camera points at once it holds the skill framing. */
    public enum Aim { ACTOR, TARGET, MIDPOINT }

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
        boolean rejected = false;
        for (var entry : files.entrySet()) {
            try {
                var file = parseFile(entry.getValue());
                if (file.camera() != null) parsed.put(entry.getKey().toString(), file.camera());
                parsed.putAll(file.actions());
            } catch (RuntimeException error) {
                rejected = true;
                com.matuvent.mineturn.MineTurn.LOGGER.error("Invalid animation camera file {}: {}", entry.getKey(), error.getMessage());
            }
        }
        // A rejected file must not replace the working rules with a half-parsed or empty set: that would
        // silently wipe every camera preset instead of falling back.
        if (rejected) {
            com.matuvent.mineturn.MineTurn.LOGGER.error("Keeping the previous {} animation camera rules because a file was rejected", cameras.size());
            return;
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

    /**
     * Parse one camera object without touching the installed table. Used by tests to exercise field
     * validation and range limits in isolation.
     */
    public static Camera parseCamera(JsonElement element) {
        return new ActionAnimationData().readCamera(element.getAsJsonObject());
    }

    /** Reads a camera object, filling every omitted field from {@link Camera#DEFAULT}. */
    private Camera readCamera(com.google.gson.JsonObject object) {
        var base = Camera.DEFAULT;
        return new Camera(
                number(object, "side", base.side(), 64),
                signed(object, "front", base.front(), 64),
                number(object, "lift", base.lift(), 64),
                number(object, "width_base", base.widthBase(), 64),
                number(object, "width_scale", base.widthScale(), 64),
                number(object, "min_distance", base.minDistance(), 64),
                number(object, "blend_out_ms", base.blendOutMs(), 2000),
                aim(object, base.aim()));
    }

    private static Aim aim(com.google.gson.JsonObject object, Aim fallback) {
        if (!object.has("aim")) return fallback;
        var text = object.get("aim").getAsString().toLowerCase(java.util.Locale.ROOT);
        return switch (text) {
            case "actor" -> Aim.ACTOR;
            case "target" -> Aim.TARGET;
            case "midpoint", "between" -> Aim.MIDPOINT;
            default -> throw new IllegalArgumentException("aim must be actor, target or midpoint, got: " + text);
        };
    }

    /** Like {@link #number} but accepts negatives, for offsets such as {@code front} that may go behind. */
    private static double signed(com.google.gson.JsonObject object, String key, double fallback, double maximum) {
        if (!object.has(key)) return fallback;
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || Math.abs(value) > maximum) {
            throw new IllegalArgumentException(key + " must be a finite number in -" + (int) maximum + ".." + (int) maximum);
        }
        return value;
    }

    private static double number(com.google.gson.JsonObject object, String key, double fallback, double maximum) {
        if (!object.has(key)) return fallback;
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > maximum) {
            throw new IllegalArgumentException(key + " must be a finite number in 0.." + (int) maximum);
        }
        return value;
    }
}
