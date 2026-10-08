package com.matuvent.mineturn.test;

import com.google.gson.JsonParser;
import com.matuvent.mineturn.client.ActionAnimationData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.util.Map;

/**
 * The action-camera rules live in a client resource file. A malformed file falls back to defaults
 * silently, so this test asserts the shipped file actually parses and that the collision-box framing
 * maths scales the way the tuning assumes.
 */
@GameTestHolder("mineturn")
@PrefixGameTestTemplate(false)
public final class ActionAnimationDataGameTests {
    private static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath("mineturn", "animation_camera");

    /** Loads the shipped resource and returns how many rules it installed. */
    private static int loadShipped() {
        var stream = ActionAnimationDataGameTests.class.getResourceAsStream("/assets/mineturn/mineturn/animation_camera.json");
        if (stream == null) throw new AssertionError("shipped animation_camera.json is missing from the mod resources");
        try (var reader = new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
            return ActionAnimationData.installFromFiles(Map.of(FILE, JsonParser.parseReader(reader)));
        } catch (java.io.IOException error) {
            throw new AssertionError("cannot read animation_camera.json", error);
        }
    }

    @GameTest(template = "empty")
    public static void shippedCameraFileParsesAndScales(GameTestHelper h) {
        int rules = loadShipped();
        var melee = ActionAnimationData.camera(ResourceLocation.parse("mineturn:melee"));
        var eat = ActionAnimationData.camera(ResourceLocation.parse("mineturn:eat"));
        var ranged = ActionAnimationData.camera(ResourceLocation.parse("mineturn:ranged"));
        var unknown = ActionAnimationData.camera(ResourceLocation.parse("mineturn:not_configured_anywhere"));

        h.assertTrue(rules >= 3, "Shipped file installed too few camera rules: " + rules);
        h.assertTrue(!melee.equals(ActionAnimationData.Camera.DEFAULT),
                "Shipped file did not override the built-in default camera rule: " + melee
                        + " keys=" + ActionAnimationData.installedKeys() + " eat=" + eat + " ranged=" + ranged);
        h.assertTrue(!eat.equals(melee) || !ranged.equals(melee),
                "Per-action camera overrides were not applied");
        h.assertTrue(unknown.equals(ActionAnimationData.Camera.DEFAULT),
                "Unconfigured animation did not fall back to the built-in default");
        h.assertTrue(melee.minDistance() >= 1.5 && melee.side() >= 0.4,
                "Camera framing defaults are implausibly tight: " + melee);

        // A normal mob must be framed further out than the old fixed 1-block offset, and a wide boss
        // must be pushed further still.
        double normal = melee.side() + melee.scaleFor(0.6);
        double boss = melee.side() + melee.scaleFor(4.0);
        h.assertTrue(normal > 1.2, "Normal-sized actor framing is too close: " + normal);
        h.assertTrue(boss > normal * 1.8, "Framing did not grow with the collision box: " + normal + " -> " + boss);
        h.assertTrue(melee.scaleFor(0.0) < melee.scaleFor(4.0), "Collision-box term ignored actor width");
        h.succeed();
    }

    /**
     * The cut to the skill camera is instant; only the release is blended, and its duration is
     * configurable so a resource pack can make the return instant as well.
     */
    @GameTest(template = "empty")
    public static void blendOutDurationIsConfigurableAndValidated(GameTestHelper h) {
        int rules = loadShipped();
        var melee = ActionAnimationData.camera(ResourceLocation.parse("mineturn:melee"));
        h.assertTrue(rules >= 6, "Shipped file should define the full preset set: " + rules
                + " keys=" + ActionAnimationData.installedKeys());
        h.assertTrue(melee.blendOutMs() > 0 && melee.blendOutMs() <= 2000,
                "Shipped release blend is out of range: " + melee.blendOutMs());
        h.assertTrue(ActionAnimationData.Camera.DEFAULT.blendOutMs() > 0,
                "Built-in default should still blend the release");

        // Zero is legal and means "release instantly".
        ActionAnimationData.installFromFiles(java.util.Map.of(FILE, JsonParser.parseString(
                "{\"actions\":{\"melee\":{\"blend_out_ms\":0}}}")));
        h.assertTrue(ActionAnimationData.camera(ResourceLocation.parse("mineturn:melee")).blendOutMs() == 0,
                "blend_out_ms 0 was not accepted as an instant release");

        // Field validation is exercised directly so the check does not depend on install order.
        h.assertTrue(ActionAnimationData.parseCamera(JsonParser.parseString("{}")).equals(ActionAnimationData.Camera.DEFAULT),
                "An empty rule did not fall back to the built-in default");
        h.assertTrue(ActionAnimationData.parseCamera(JsonParser.parseString("{\"blend_out_ms\":0}")).blendOutMs() == 0,
                "blend_out_ms 0 was rejected by the parser");
        h.assertTrue(ActionAnimationData.parseCamera(JsonParser.parseString("{\"blend_out_ms\":2000}")).blendOutMs() == 2000,
                "The maximum blend_out_ms was rejected by the parser");
        for (String bad : java.util.List.of("{\"blend_out_ms\":-5}", "{\"blend_out_ms\":2001}",
                "{\"side\":99999}", "{\"front\":-1}")) {
            boolean rejected = false;
            try {
                ActionAnimationData.parseCamera(JsonParser.parseString(bad));
            } catch (RuntimeException expected) {
                rejected = true;
            }
            h.assertTrue(rejected, "Out-of-range camera rule was accepted: " + bad);
        }

        // A file that fails validation must not replace the working table with an empty one.
        var meleeBefore = ActionAnimationData.camera(ResourceLocation.parse("mineturn:melee"));
        ActionAnimationData.installFromFiles(java.util.Map.of(FILE, JsonParser.parseString(
                "{\"actions\":{\"melee\":{\"side\":99999}}}")));
        h.assertTrue(ActionAnimationData.camera(ResourceLocation.parse("mineturn:melee")).equals(meleeBefore),
                "A rejected file wiped or changed the installed rules instead of being discarded");

        // Restore the shipped file so later tests and the client see the real configuration.
        loadShipped();
        h.succeed();
    }
}
