package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.animation.AnimationController;
import net.okitsu.ysmepicfightcompat.animation.DeclarativeParticleEffect;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTransferCodecTest {
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
        assertEquals(0.0F, decoded.animations().get("parallel.endless").duration());
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
