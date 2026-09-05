package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoneQuerySnapshotTest {
    @Test
    void readsTheLastPublishedAuthoredChannelsWithoutSharingMutableMaps() {
        Map<String, BoneQuerySnapshot.BoneValues> source = new LinkedHashMap<>();
        source.put("Head", new BoneQuerySnapshot.BoneValues(
                new Vec3(10, 20, 30), new Vec3(1, 2, 3),
                new Vec3(0.5, 1, 2), new Vec3(4, 5, 6)));
        BoneQuerySnapshot snapshot = new BoneQuerySnapshot(source);
        source.clear();

        assertEquals(20.0D, snapshot.query("ysm.bone_rot", "Head").get("y"));
        assertEquals(3.0D, snapshot.query("ysm.bone_pos", "Head").get("z"));
        assertEquals(0.5D, snapshot.query("ysm.bone_scale", "Head").get("x"));
        assertEquals(5.0D, snapshot.query("ysm.bone_pivot_abs", "Head").get("y"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.query("ysm.bone_rot", "Head").put("x", 200.0D));
    }

    @Test
    void missingBonesAndNonFiniteChannelsCannotLeakAnInvalidPose() {
        BoneQuerySnapshot snapshot = new BoneQuerySnapshot(Map.of("Head",
                new BoneQuerySnapshot.BoneValues(new Vec3(Double.NaN, 1, 2),
                        null, null, new Vec3(1, Double.POSITIVE_INFINITY, 2))));

        assertEquals(Map.of("x", 0.0D, "y", 0.0D, "z", 0.0D),
                snapshot.query("ysm.bone_rot", "Head"));
        assertEquals(1.0D, snapshot.query("ysm.bone_scale", "Head").get("x"));
        assertEquals(0.0D, snapshot.query("ysm.bone_pivot_abs", "Head").get("y"));
        assertEquals(0.0D, snapshot.query("ysm.bone_rot", "head").get("x"));
        assertEquals(0.0D, BoneQuerySnapshot.EMPTY.query("ysm.bone_rot", null).get("x"));
    }

    @Test
    void snapshotAllocationIsBoundedByTheRendererPoseLimit() {
        Map<String, BoneQuerySnapshot.BoneValues> source = new LinkedHashMap<>();
        for (int index = 0; index < 1001; index++) {
            source.put("bone" + index, new BoneQuerySnapshot.BoneValues(
                    new Vec3(1, 1, 1), null, null, null));
        }
        BoneQuerySnapshot snapshot = new BoneQuerySnapshot(source);
        assertEquals(1.0D, snapshot.query("ysm.bone_rot", "bone999").get("x"));
        assertEquals(0.0D, snapshot.query("ysm.bone_rot", "bone1000").get("x"));
    }
}
