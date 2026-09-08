package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.animation.AnimationController;
import net.okitsu.ysmepicfightcompat.animation.DeclarativeParticleEffect;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.assets.ModelFunctionAssets;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.geometry.BedrockGeometryParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.SkinMeshCompiler;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTransferCodecTest {
    @ParameterizedTest
    @ValueSource(floats = {0.0F, 2.5F, Float.POSITIVE_INFINITY})
    void preservesFiniteUnspecifiedAndUnboundedDurationsAcrossEveryCacheRegion(
            float duration, @TempDir Path root) throws IOException {
        for (AnimationClip.Playback playback : AnimationClip.Playback.values()) {
            ModelBundle model = durationModel(duration);
            AnimationClip original = model.animations().get("duration");
            original.playback(playback);
            original.timeline().add(new AnimationClip.TimelineEvent(1.0F, List.of("v.event+=1;")));
            original.soundEffects().add(new AnimationClip.SoundEvent(1.5F, "later"));
            original.particleEffects().add(new AnimationClip.ParticleEvent(2.0F,
                    new DeclarativeParticleEffect("smoke", "", "", false)));
            byte[] payload = GeometryTransferCodec.encode(model);
            byte[] digest = ModelDiskCache.sha256(payload);
            for (String region : List.of("client", "server", "remote")) {
                Path directory = root.resolve(region);
                assertTrue(ModelDiskCache.write(directory, "duration",
                        new ModelDiskCache.Entry(digest, digest, payload), 1024 * 1024));
                ModelBundle restored = GeometryTransferCodec.decode("duration",
                        ModelDiskCache.read(directory, "duration", 1024 * 1024)
                                .orElseThrow().payload());
                ModelBundle forwarded = GeometryTransferCodec.decode("duration",
                        GeometryTransferCodec.encode(restored));
                for (ModelBundle candidate : List.of(restored, forwarded)) {
                    AnimationClip clip = candidate.animations().get("duration");
                    assertEquals(duration, clip.duration());
                    assertEquals(playback, clip.playback());
                    assertEquals(original.timeline(), clip.timeline());
                    assertEquals(original.soundEffects(), clip.soundEffects());
                    assertEquals(original.particleEffects(), clip.particleEffects());
                }
            }
        }
    }

    @Test
    void rejectsInvalidDurationValuesInBothDirectionsWithoutRelaxingOtherFields()
            throws IOException {
        byte[] expanded = expanded(GeometryTransferCodec.encode(durationModel(1.0F)));
        int durationOffset = durationOffset(expanded);
        for (float invalid : new float[]{Float.NaN, Float.NEGATIVE_INFINITY}) {
            assertThrows(IOException.class, () -> GeometryTransferCodec.encode(durationModel(invalid)));
            byte[] malformed = expanded.clone();
            ByteBuffer.wrap(malformed).putFloat(durationOffset, invalid);
            assertThrows(IOException.class, () -> GeometryTransferCodec.decode("duration", gzip(malformed)));
        }
        for (float invalid : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            ModelBundle invalidScale = ModelBundle.remote("scale", new GeometryDocument(),
                    Map.of(), invalid, 1.0F, "");
            assertThrows(IOException.class, () -> GeometryTransferCodec.encode(invalidScale));
            byte[] malformed = expanded.clone();
            ByteBuffer.wrap(malformed).putFloat(3 * Integer.BYTES, invalid);
            assertThrows(IOException.class, () -> GeometryTransferCodec.decode("scale", gzip(malformed)));
            ModelBundle invalidEvent = durationModel(Float.POSITIVE_INFINITY);
            invalidEvent.animations().get("duration").timeline().add(
                    new AnimationClip.TimelineEvent(invalid, List.of("v.event=1;")));
            assertThrows(IOException.class, () -> GeometryTransferCodec.encode(invalidEvent));
        }
    }

    @Test
    void rejectsLossyLegacyPayloadsAndCannotBeMisreadByTheOldFiniteScaleDecoder()
            throws IOException {
        // Zero is also a legitimate duration. Never guess which old zero values
        // used to be unbounded; require the new semantic marker for every clip.
        byte[] expanded = expanded(GeometryTransferCodec.encode(durationModel(0.0F)));
        byte[] legacy = new byte[expanded.length - Integer.BYTES];
        System.arraycopy(expanded, 0, legacy, 0, 2 * Integer.BYTES);
        System.arraycopy(expanded, 3 * Integer.BYTES, legacy, 2 * Integer.BYTES,
                expanded.length - 3 * Integer.BYTES);
        IOException rejected = assertThrows(IOException.class,
                () -> GeometryTransferCodec.decode("legacy", gzip(legacy)));
        assertEquals("Unsupported model animation duration semantics", rejected.getMessage());
        // An old decoder reads the marker as widthScale and rejects it using its
        // existing finite-value guard, before interpreting any further fields.
        assertTrue(Float.isFinite(ByteBuffer.wrap(legacy).getFloat(2 * Integer.BYTES)));
        assertTrue(Float.isNaN(ByteBuffer.wrap(expanded).getFloat(2 * Integer.BYTES)));
        byte[] wrongMarker = expanded.clone();
        ByteBuffer.wrap(wrongMarker).putInt(2 * Integer.BYTES, 0);
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("marker", gzip(wrongMarker)));
    }

    private static ModelBundle durationModel(float duration) {
        AnimationClip clip = new AnimationClip("duration");
        clip.duration(duration);
        return ModelBundle.remote("duration", new GeometryDocument(), Map.of(clip.name(), clip),
                1.0F, 1.0F, "");
    }

    private static byte[] expanded(byte[] payload) throws IOException {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            return gzip.readAllBytes();
        }
    }

    private static int durationOffset(byte[] expanded) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(expanded))) {
            input.skipNBytes(3 * Integer.BYTES + 2 * Float.BYTES);
            input.skipNBytes(input.readInt()); // default texture
            input.skipNBytes(2 * Integer.BYTES); // texture dimensions
            assertEquals(0, input.readInt()); // geometry bones
            assertEquals(1, input.readInt()); // animation count
            input.skipNBytes(input.readInt()); // animation name
            input.readUnsignedByte(); // playback
            return expanded.length - input.available();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsFlatFaceGeometryAndUvsAcrossTransferAndEveryCacheRegion(
            boolean allCutout, @TempDir Path root) throws IOException {
        GeometryDocument geometry = BedrockGeometryParser.parse("""
                {"minecraft:geometry":[{
                  "description":{"texture_width":32,"texture_height":32},
                  "bones":[{"name":"head","cubes":[{
                    "origin":[0,0,0],"size":[8,8,0],
                    "uv":{"north":{"uv":[0,0],"uv_size":[8,8]},
                          "south":{"uv":[16,0],"uv_size":[8,8]}}
                  }]}]
                }]}
                """, allCutout);
        ModelBundle model = ModelBundle.remote("flat", geometry, Map.of(), 1.0F, 1.0F, "");
        model.allCutout(allCutout);
        var expected = geometry.bones().get("head").faces();
        assertEquals(allCutout ? 2 : 1, expected.size());
        byte[] payload = GeometryTransferCodec.encode(model);
        byte[] digest = ModelDiskCache.sha256(payload);
        for (String region : List.of("client", "remote", "server")) {
            Path directory = root.resolve(region);
            assertTrue(ModelDiskCache.write(directory, "flat",
                    new ModelDiskCache.Entry(digest, digest, payload), 1024 * 1024));
            ModelBundle decoded = GeometryTransferCodec.decode("flat",
                    ModelDiskCache.read(directory, "flat", 1024 * 1024).orElseThrow().payload());
            ModelBundle forwarded = GeometryTransferCodec.decode("flat", GeometryTransferCodec.encode(decoded));
            for (ModelBundle restored : List.of(decoded, forwarded)) {
                assertEquals(allCutout, restored.allCutout());
                var actual = restored.geometry().bones().get("head").faces();
                assertEquals(expected.size(), actual.size());
                for (int face = 0; face < expected.size(); face++) {
                    assertEquals(expected.get(face).normal(), actual.get(face).normal());
                    assertArrayEquals(expected.get(face).positions(), actual.get(face).positions());
                    for (int corner = 0; corner < 4; corner++) {
                        assertArrayEquals(expected.get(face).textureCoordinates()[corner],
                                actual.get(face).textureCoordinates()[corner]);
                    }
                }
                assertEquals(expected.size(), SkinMeshCompiler.compile(restored).faceCount());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void preservesIndependentRenderFlagsAcrossTransferAndEveryCacheRegion(
            boolean allCutout, boolean renderLayersFirst, @TempDir Path root) throws IOException {
        ModelBundle model = functionModel();
        model.allCutout(allCutout);
        model.renderLayersFirst(renderLayersFirst);
        byte[] payload = GeometryTransferCodec.encode(model);
        byte[] digest = ModelDiskCache.sha256(payload);
        for (String region : List.of("client", "remote", "server")) {
            Path directory = root.resolve(region);
            assertTrue(ModelDiskCache.write(directory, "model",
                    new ModelDiskCache.Entry(digest, digest, payload), 1024 * 1024));
            ModelBundle decoded = GeometryTransferCodec.decode("model",
                    ModelDiskCache.read(directory, "model", 1024 * 1024).orElseThrow().payload());
            assertEquals(allCutout, decoded.allCutout());
            assertEquals(renderLayersFirst, decoded.renderLayersFirst());
            ModelBundle second = GeometryTransferCodec.decode("model", GeometryTransferCodec.encode(decoded));
            assertEquals(allCutout, second.allCutout());
            assertEquals(renderLayersFirst, second.renderLayersFirst());
        }
    }

    @Test
    void rejectsPreRenderFlagPayloadsAndInvalidFlagValues() throws IOException {
        byte[] expanded;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(
                GeometryTransferCodec.encode(functionModel())))) {
            expanded = gzip.readAllBytes();
        }
        byte[] previous = Arrays.copyOf(expanded, expanded.length - 2);
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("old", gzip(previous)));
        byte[] truncated = Arrays.copyOf(expanded, expanded.length - 1);
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("truncated", gzip(truncated)));
        for (int offset = 1; offset <= 2; offset++) {
            byte[] invalid = expanded.clone();
            invalid[invalid.length - offset] = 2;
            assertThrows(IOException.class, () -> GeometryTransferCodec.decode("invalid", gzip(invalid)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMultilineEvaluationBoundariesAcrossTransferAndCaches(
            boolean merge, @TempDir Path root) throws IOException {
        ModelBundle model = functionModel();
        model.mergeMultilineExpressions(merge);
        AnimationClip own = new AnimationClip("parallel0");
        own.timeline().add(new AnimationClip.TimelineEvent(0, List.of("return 1;", "v.after=1;")));
        if (merge) {
            own.mergeTimelineExpressions();
        }
        model.animations().put(own.name(), own);
        // Inherited clips retain their source model's setting, independently of
        // the consuming bundle flag. Decoding must not normalize all clips again.
        AnimationClip inherited = new AnimationClip("inherited");
        inherited.timeline().add(new AnimationClip.TimelineEvent(0, List.of("return 2;", "v.after=2;")));
        if (!merge) {
            inherited.mergeTimelineExpressions();
        }
        model.animations().put(inherited.name(), inherited);
        byte[] payload = GeometryTransferCodec.encode(model);
        byte[] digest = ModelDiskCache.sha256(payload);
        for (String region : List.of("client", "remote", "server")) {
            Path directory = root.resolve(region);
            assertTrue(ModelDiskCache.write(directory, "model",
                    new ModelDiskCache.Entry(digest, digest, payload), 1024 * 1024));
            ModelBundle decoded = GeometryTransferCodec.decode("model",
                    ModelDiskCache.read(directory, "model", 1024 * 1024).orElseThrow().payload());
            assertEquals(merge, decoded.mergeMultilineExpressions());
            assertEquals(own.timeline(), decoded.animations().get(own.name()).timeline());
            assertEquals(inherited.timeline(), decoded.animations().get(inherited.name()).timeline());
            ModelBundle second = GeometryTransferCodec.decode("model", GeometryTransferCodec.encode(decoded));
            assertEquals(own.timeline(), second.animations().get(own.name()).timeline());
            assertEquals(inherited.timeline(), second.animations().get(inherited.name()).timeline());
        }
    }

    @Test
    void supportsLongMergedTimelineWithoutRelaxingIdentifierLimits() throws IOException {
        ModelBundle model = functionModel();
        model.mergeMultilineExpressions(true);
        AnimationClip clip = new AnimationClip("parallel0");
        String commentLine = "//" + "雪".repeat(8_000);
        clip.timeline().add(new AnimationClip.TimelineEvent(0,
                List.of(commentLine, commentLine, "return 7;")));
        clip.mergeTimelineExpressions();
        model.animations().put(clip.name(), clip);
        ModelBundle restored = GeometryTransferCodec.decode("model", GeometryTransferCodec.encode(model));
        assertEquals(clip.timeline(), restored.animations().get(clip.name()).timeline());
        assertTrue(net.okitsu.ysmepicfightcompat.animation.ExpressionEngine
                .compile(restored.animations().get(clip.name()).timeline().get(0).statements().get(0))
                .isValid());
        model.defaultTexture("x".repeat(16 * 1024 + 1));
        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
        model.defaultTexture("");
        clip.timeline().clear();
        clip.timeline().add(new AnimationClip.TimelineEvent(0, List.of("x".repeat(3 * 65_536 + 1))));
        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
    }

    @Test
    void roundTripsFunctionSourcesThroughEveryCacheRegion(@TempDir Path root) throws IOException {
        ModelBundle model = functionModel();
        model.functions().put("calculate", "return args[0] + 3;\n// 雪");
        model.functions().put("@player_ctrl_main", "return ctrl.state_bypass;");
        model.functions().put("setup@player_init", "v.ready=1;");
        model.mergeMultilineExpressions(true);
        byte[] payload = GeometryTransferCodec.encode(model);
        byte[] digest = ModelDiskCache.sha256(payload);
        for (String region : java.util.List.of("client", "remote", "server")) {
            Path directory = root.resolve(region);
            assertTrue(ModelDiskCache.write(directory, "model",
                    new ModelDiskCache.Entry(digest, digest, payload), 1024 * 1024));
            byte[] restored = ModelDiskCache.read(directory, "model", 1024 * 1024)
                    .orElseThrow().payload();
            ModelBundle decoded = GeometryTransferCodec.decode("model", restored);
            assertEquals(model.functions(), decoded.functions());
            assertTrue(decoded.mergeMultilineExpressions());
            try (var files = Files.list(directory)) {
                assertTrue(files.allMatch(file -> file.getFileName().toString().endsWith(".cache")));
            }
        }
    }

    @Test
    void rejectsInvalidFunctionNamesAndEncodingLimits() {
        ModelBundle model = functionModel();
        model.functions().put("../escape", "return 1;");
        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
        model.functions().clear();
        model.functions().put("sum", "x".repeat(ModelFunctionAssets.MAX_SOURCE_BYTES + 1));
        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
        model.functions().clear();
        model.functions().put("sum", "x".repeat(ModelFunctionAssets.MAX_SOURCE_BYTES));
        String shared = model.functions().get("sum");
        for (int index = 0; index < 16; index++) {
            model.functions().put("copy_" + index, shared);
        }
        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
        model.functions().clear();
        for (int index = 0; index <= ModelFunctionAssets.MAX_FUNCTIONS; index++) {
            model.functions().put("entry_" + index, "");
        }
        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
    }

    @Test
    void rejectsMalformedFunctionTransferSectionsAndOldScriptlessCachePayloads() throws IOException {
        byte[] baseline;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(
                GeometryTransferCodec.encode(functionModel())))) {
            baseline = gzip.readAllBytes();
        }
        byte[] prefix = Arrays.copyOf(baseline, baseline.length - 7);
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("old", gzip(prefix)));
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("too-many",
                withFunctionSection(prefix, output -> {
                    output.writeBoolean(false);
                    output.writeInt(ModelFunctionAssets.MAX_FUNCTIONS + 1);
                })));
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("oversized",
                withFunctionSection(prefix, output -> {
                    output.writeBoolean(false);
                    output.writeInt(1);
                    writeString(output, "sum");
                    output.writeInt(ModelFunctionAssets.MAX_SOURCE_BYTES + 1);
                })));
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("utf8",
                withFunctionSection(prefix, output -> {
                    output.writeBoolean(false);
                    output.writeInt(1);
                    writeString(output, "sum");
                    output.writeInt(2);
                    output.write(new byte[]{(byte) 0xC3, 0x28});
                })));
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("duplicate",
                withFunctionSection(prefix, output -> {
                    output.writeBoolean(false);
                    output.writeInt(2);
                    for (String name : java.util.List.of("sum", "SUM")) {
                        writeString(output, name);
                        writeString(output, "return 1;");
                    }
                })));
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("total-size",
                withFunctionSection(prefix, output -> {
                    output.writeBoolean(false);
                    output.writeInt(17);
                    byte[] source = new byte[ModelFunctionAssets.MAX_SOURCE_BYTES];
                    for (int index = 0; index < 17; index++) {
                        writeString(output, "entry_" + index);
                        output.writeInt(source.length);
                        output.write(source);
                    }
                })));
    }

    private static ModelBundle functionModel() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("root", ""));
        geometry.linkHierarchy();
        return ModelBundle.remote("functions", geometry, Map.of(), 1, 1, "");
    }

    private static byte[] withFunctionSection(byte[] prefix, SectionWriter writer) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(prefix);
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            writer.write(output);
            output.writeBoolean(false);
            output.writeBoolean(false);
        }
        return gzip(buffer.toByteArray());
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(bytes);
        }
        return buffer.toByteArray();
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    @FunctionalInterface
    private interface SectionWriter {
        void write(DataOutputStream output) throws IOException;
    }

    @Test
    void keepsUnreleasedProtocolsAtVersionOne() throws IOException {
        assertEquals("1", CompatNetwork.PROTOCOL);

        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("root", ""));
        geometry.linkHierarchy();
        byte[] payload = GeometryTransferCodec.encode(
                ModelBundle.remote("protocol", geometry, Map.of(), 1, 1, ""));

        try (DataInputStream input = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            assertEquals(0x59454632, input.readInt());
            assertEquals(1, input.readInt());
        }
    }

    @Test
    void roundTripsGeometryAnimationsAndBoundedTexturesWithoutPackageAssets() throws IOException {
        GeometryDocument geometry = new GeometryDocument();
        geometry.textureSize(128, 64);
        GeometryDocument.Bone root = bone("root", "");
        GeometryDocument.Bone head = bone("head", "root");
        head.faces().add(face());
        geometry.add(root);
        geometry.add(head);
        geometry.linkHierarchy();

        AnimationClip clip = new AnimationClip("parallel.test");
        clip.playback(AnimationClip.Playback.REPEAT);
        clip.duration(1.25F);
        clip.blendWeight().setExpression("variable.tail_weight");
        AnimationClip.Track scale = new AnimationClip.Track();
        AnimationClip.VectorValue scaleValue = new AnimationClip.VectorValue();
        scaleValue.setConstant(0, 1.0D);
        scaleValue.setConstant(1, 0.5D);
        scaleValue.setExpression(2, "variable.scale");
        scale.keyframes().add(new AnimationClip.Keyframe(0.0F,
                AnimationClip.Interpolation.STEP, scaleValue, null));
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.scale(scale);
        clip.boneTracks().put("head", tracks);
        clip.timeline().add(new AnimationClip.TimelineEvent(
                0.0F, java.util.List.of("variable.scale=1")));
        clip.soundEffects().add(new AnimationClip.SoundEvent(0.25F, "model.chime"));
        clip.particleEffects().add(new AnimationClip.ParticleEvent(0.5F,
                new DeclarativeParticleEffect("minecraft:flame", "head",
                        "v.ready=1", false)));

        AnimationClip endless = new AnimationClip("parallel.endless");
        endless.playback(AnimationClip.Playback.REPEAT);
        endless.duration(Float.POSITIVE_INFINITY);

        AnimationController.State controllerState = new AnimationController.State(
                "default", java.util.List.of(
                new AnimationController.AnimationReference("parallel.test", "v.enabled")),
                java.util.List.of(new AnimationController.Transition(
                        "hidden", "q.all_animations_finished")),
                java.util.List.of("v.entered=1;"), java.util.List.of("v.exited=1;"),
                java.util.List.of("model.enter"),
                java.util.List.of(new AnimationController.StateVariable(
                        "speed", "q.ground_speed", java.util.List.of(
                        new AnimationController.RemapPoint(0.0F, 0.0F),
                        new AnimationController.RemapPoint(1.0F, 2.0F)))),
                java.util.List.of(new DeclarativeParticleEffect(
                        "minecraft:smoke", "body", "", true)),
                new AnimationController.BlendTransition(0.0F, java.util.List.of(
                new AnimationController.BlendPoint(0.0F, 1.0F),
                new AnimationController.BlendPoint(0.2F, 0.0F))), true);
        AnimationController.State hidden = new AnimationController.State(
                "hidden", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(),
                new AnimationController.BlendTransition(0.0F, java.util.List.of()), false);
        AnimationController controller = new AnimationController(
                "player.parallel_4", "default",
                Map.of("default", controllerState, "hidden", hidden));

        ModelBundle source = ModelBundle.remote("server/model", geometry,
                Map.of(clip.name(), clip, endless.name(), endless),
                Map.of(controller.name(), controller), 0.75F, 0.8F, "default");
        source.textures().put("default", new byte[]{1, 2, 3, 4});
        source.textures().put("alternate", new byte[]{5, 6, 7});
        source.textureInfo().put("default", new ModelBundle.TextureInfo(1, 1, -1));
        source.pbrTextures().put("default", new ModelBundle.PbrTextures(
                new ModelBundle.EncodedTexture(new byte[]{8, 9, 10, 11},
                        new ModelBundle.TextureInfo(1, 1, -1)),
                new ModelBundle.EncodedTexture(new byte[]{12, 13, 14}, null)));
        byte[] payload = GeometryTransferCodec.encode(source);
        ModelBundle decoded = GeometryTransferCodec.decode("server/model", payload);

        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.textures().get("default"));
        assertArrayEquals(new byte[]{5, 6, 7}, decoded.textures().get("alternate"));
        assertEquals(new ModelBundle.TextureInfo(1, 1, -1),
                decoded.textureInfo().get("default"));
        assertArrayEquals(new byte[]{8, 9, 10, 11},
                decoded.pbrTextures().get("default").normal().bytes());
        assertEquals(new ModelBundle.TextureInfo(1, 1, -1),
                decoded.pbrTextures().get("default").normal().info());
        assertArrayEquals(new byte[]{12, 13, 14},
                decoded.pbrTextures().get("default").specular().bytes());
        assertEquals(0.75F, decoded.widthScale());
        assertEquals(0.8F, decoded.heightScale());
        assertEquals("default", decoded.defaultTexture());
        assertEquals(128, decoded.geometry().textureWidth());
        assertSame(decoded.geometry().bones().get("root"),
                decoded.geometry().bones().get("head").parent());
        assertArrayEquals(new float[]{0.1F, 0.2F},
                decoded.geometry().bones().get("head").faces().get(0).textureCoordinates()[0]);
        AnimationClip restored = decoded.animations().get("parallel.test");
        assertEquals(AnimationClip.Playback.REPEAT, restored.playback());
        assertEquals(1.25F, restored.duration());
        assertEquals("variable.tail_weight", restored.blendWeight().expression());
        assertEquals("variable.scale=1", restored.timeline().get(0).statements().get(0));
        assertEquals(new AnimationClip.SoundEvent(0.25F, "model.chime"),
                restored.soundEffects().get(0));
        assertEquals(new DeclarativeParticleEffect("minecraft:flame", "head",
                        "v.ready=1", false),
                restored.particleEffects().get(0).particle());
        assertEquals("variable.scale", restored.boneTracks().get("head")
                .scale().keyframes().get(0).value().expression(2));
        assertEquals(Float.POSITIVE_INFINITY, decoded.animations().get("parallel.endless").duration());
        AnimationController restoredController = decoded.animationControllers()
                .get("player.parallel_4");
        assertEquals("default", restoredController.initialState());
        assertEquals("v.enabled", restoredController.states().get("default")
                .animations().get(0).weightExpression());
        assertEquals("q.all_animations_finished", restoredController.states().get("default")
                .transitions().get(0).conditionExpression());
        assertEquals(0.5F, restoredController.states().get("default")
                .blendTransition().progress(0.1D), 0.0001F);
        assertTrue(restoredController.states().get("default").blendViaShortestPath());
        assertEquals(java.util.List.of("model.enter"), restoredController.states()
                .get("default").soundEffects());
        assertEquals("q.ground_speed", restoredController.states().get("default")
                .variables().get(0).inputExpression());
        assertEquals(1.0D, restoredController.states().get("default")
                .variables().get(0).remap(0.5D), 0.0001D);
        assertEquals("minecraft:smoke", restoredController.states().get("default")
                .particleEffects().get(0).effect());
    }

    @Test
    void rejectsCyclesAndTruncation() throws IOException {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone first = bone("first", "second");
        GeometryDocument.Bone second = bone("second", "first");
        geometry.add(first);
        geometry.add(second);
        geometry.linkHierarchy();
        byte[] payload = GeometryTransferCodec.encode(
                ModelBundle.remote("cycle", geometry, Map.of(), 1, 1, ""));

        assertThrows(IOException.class, () -> GeometryTransferCodec.decode("cycle", payload));
        assertThrows(IOException.class, () -> GeometryTransferCodec.decode(
                "short", Arrays.copyOf(payload, payload.length / 2)));
    }

    @Test
    void rejectsPbrCompanionsWithoutASelectableBaseTexture() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("root", ""));
        geometry.linkHierarchy();
        ModelBundle model = ModelBundle.remote(
                "orphan-pbr", geometry, Map.of(), 1, 1, "");
        model.pbrTextures().put("missing", new ModelBundle.PbrTextures(
                new ModelBundle.EncodedTexture(new byte[]{1}, null), null));

        assertThrows(IOException.class, () -> GeometryTransferCodec.encode(model));
    }

    private static GeometryDocument.Bone bone(String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        bone.pivot(1, 2, 3);
        bone.rotation(0.1F, 0.2F, 0.3F);
        return bone;
    }

    private static GeometryDocument.Face face() {
        return new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0)},
                new float[][]{{0.1F, 0.2F}, {0.3F, 0.2F},
                        {0.3F, 0.4F}, {0.1F, 0.4F}},
                new Vector3f(0, 0, 1));
    }
}
