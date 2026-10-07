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
}
