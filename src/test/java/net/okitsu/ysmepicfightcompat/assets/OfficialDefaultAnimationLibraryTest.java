package net.okitsu.ysmepicfightcompat.assets;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialDefaultAnimationLibraryTest {
    private static final List<String> LINES = List.of(
            "v.ready ? {", "v.value=1; // preserve this newline", "};");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritsMergedTimelineAccordingToPrimaryManifestNotConsumingModel(
            boolean consumerMode, @TempDir Path root) throws Exception {
        writePrimary(root, "true");
        ModelBundle target = new ModelBundle("consumer");
        target.mergeMultilineExpressions(consumerMode);
        AnimationClip ownClip = new AnimationClip("own");
        ownClip.timeline().add(new AnimationClip.TimelineEvent(0.0F, LINES));
        target.animations().put("own", ownClip);

        OfficialDefaultAnimationLibrary.inherit(root, target);

        assertEquals(consumerMode, target.mergeMultilineExpressions(),
                "inheriting clips must not change the consumer's property");
        assertEquals(List.of(String.join("\n", LINES)), statements(target));
        assertEquals(List.of("v.later=2;"),
                target.animations().get("idle").timeline().get(1).statements(),
                "separate timestamps remain separate events");
        assertSame(ownClip, target.animations().get("own"));
        assertEquals(LINES, ownClip.timeline().get(0).statements());
        AnimationClip.VectorValue rotation = target.animations().get("idle")
                .boneTracks().get("Head").rotation().keyframes().get(0).value();
        assertEquals("v.x", rotation.expression(0));
        assertEquals("v.y", rotation.expression(1));
        assertEquals("v.z", rotation.expression(2));
        assertTrue(target.animations().get("empty").timeline().isEmpty());
        assertTrue(target.animationControllers().isEmpty(),
                "the primary model's controllers must not be inherited or merged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "absent"})
    void keepsPrimaryTimelineIndependentWhenOnlyConsumerEnablesMerging(
            String primaryMode, @TempDir Path root) throws Exception {
        writePrimary(root, primaryMode);
        ModelBundle enabledConsumer = new ModelBundle("enabled-consumer");
        enabledConsumer.mergeMultilineExpressions(true);
        ModelBundle disabledConsumer = new ModelBundle("disabled-consumer");

        OfficialDefaultAnimationLibrary.inherit(root, enabledConsumer);
        OfficialDefaultAnimationLibrary.inherit(root, disabledConsumer);

        assertTrue(enabledConsumer.mergeMultilineExpressions());
        assertEquals(LINES, statements(enabledConsumer));
        assertEquals(LINES, statements(disabledConsumer),
                "a consumer's mode must not mutate the cached primary snapshot");
    }

    @Test
    void manifestModeChangeInvalidatesDigestWithoutMutatingPreviousSnapshots(
            @TempDir Path root) throws Exception {
        writePrimary(root, "false");
        byte[] before = digest(root);
        ModelBundle original = new ModelBundle("original");
        OfficialDefaultAnimationLibrary.inherit(root, original);

        writeManifest(root, "true");
        byte[] after = digest(root);
        ModelBundle updated = new ModelBundle("updated");
        OfficialDefaultAnimationLibrary.inherit(root, updated);

        assertFalse(Arrays.equals(before, after),
                "changing only the primary property invalidates inherited model caches");
        assertEquals(LINES, statements(original));
        assertEquals(List.of(String.join("\n", LINES)), statements(updated));

        writeManifest(root, "false");
        ModelBundle restored = new ModelBundle("restored");
        OfficialDefaultAnimationLibrary.inherit(root, restored);
        assertEquals(LINES, statements(restored));
        assertEquals(List.of(String.join("\n", LINES)), statements(updated),
                "reloading the primary source must not retoggle existing model snapshots");
    }

    private static List<String> statements(ModelBundle target) {
        return target.animations().get("idle").timeline().get(0).statements();
    }

    private static byte[] digest(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        OfficialDefaultAnimationLibrary.contributeDigest(root, digest);
        return digest.digest();
    }

    private static void writePrimary(Path root, String mode) throws IOException {
        Path primary = Files.createDirectories(root.resolve("builtin/default"));
        writeManifest(root, mode);
        Files.writeString(primary.resolve("animations.json"), """
                {"animations":{
                  "idle":{
                    "bones":{"Head":{"rotation":["v.x","v.y","v.z"]}},
                    "timeline":{
                      "0.0":["v.ready ? {","v.value=1; // preserve this newline","};"],
                      "1.0":["v.later=2;"]
                    }
                  },
                  "empty":{"timeline":{}},
                  "own":{"timeline":{"0.0":["v.primary=1;","v.primary=2;"]}}
                }}
                """);
        Files.writeString(primary.resolve("controllers.json"), """
                {"animation_controllers":{"player.parallel_0":{
                  "states":{"default":{"on_entry":["v.a=1;","v.b=2;"]}}
                }}}
                """);
    }

    private static void writeManifest(Path root, String mode) throws IOException {
        String properties = "absent".equals(mode) ? ""
                : "\"properties\":{\"merge_multiline_expr\":" + mode + "},";
        Files.writeString(root.resolve("builtin/default/ysm.json"), "{" + properties + """
                "files":{"player":{
                  "animation":{"main":"animations.json"},
                  "animation_controllers":["controllers.json"]
                }}}
                """);
    }
}
