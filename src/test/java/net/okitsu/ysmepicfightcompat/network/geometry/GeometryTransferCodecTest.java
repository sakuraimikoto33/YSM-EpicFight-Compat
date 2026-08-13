package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTransferCodecTest {
    @Test
    void roundTripsOnlyGeometryAndTheDefaultFormProgram() throws IOException {
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

        AnimationClip endless = new AnimationClip("parallel.endless");
        endless.playback(AnimationClip.Playback.REPEAT);
        endless.duration(Float.POSITIVE_INFINITY);

        ModelBundle source = ModelBundle.remote("server/model", geometry,
                Map.of(clip.name(), clip, endless.name(), endless),
                0.75F, 0.8F, "default");
        byte[] payload = GeometryTransferCodec.encode(source);
        ModelBundle decoded = GeometryTransferCodec.decode("server/model", payload);

        assertTrue(decoded.textures().isEmpty());
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
        assertEquals("variable.scale=1", restored.timeline().get(0).statements().get(0));
        assertEquals("variable.scale", restored.boneTracks().get("head")
                .scale().keyframes().get(0).value().expression(2));
        assertEquals(0.0F, decoded.animations().get("parallel.endless").duration());
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
