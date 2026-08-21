package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelJointPivotsTest {
    @Test
    void derivesScaledShoulderPivotFromTheUpperArmTopRing() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = faceBone("RightArm", 1.0F, 3.0F, 1.0F, 2.0F);
        GeometryDocument.Bone alternate = faceBone("RightArm2", 20.0F, 30.0F, 20.0F, 30.0F);
        geometry.add(arm);
        geometry.add(alternate);
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 2.0F, 3.0F);

        Vector3f shoulder = pivots.get(HumanoidRig.RIGHT_SHOULDER);
        Vector3f armPivot = pivots.get(HumanoidRig.RIGHT_ARM);
        assertEquals(4.0F, shoulder.x(), 0.00001F);
        assertEquals(6.0F, shoulder.y(), 0.00001F);
        assertEquals(0.0F, shoulder.z(), 0.00001F);
        assertEquals(shoulder, armPivot);
        assertFalse(pivots.containsKey(HumanoidRig.LEFT_ARM));
    }

    @Test
    void includesDefaultFormSegmentsInPivotEstimation() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(faceBone("LeftArm_Default", -3.0F, -1.0F, 1.0F, 2.0F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        assertEquals(-2.0F, pivots.get(HumanoidRig.LEFT_ARM).x(), 0.00001F);
        assertEquals(2.0F, pivots.get(HumanoidRig.LEFT_ARM).y(), 0.00001F);
    }

    private static GeometryDocument.Bone faceBone(String name, float minX, float maxX,
                                                   float minY, float maxY) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(minX, minY, 0.0F), new Vector3f(maxX, minY, 0.0F),
                new Vector3f(maxX, maxY, 0.0F), new Vector3f(minX, maxY, 0.0F)},
                new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}},
                new Vector3f(0, 0, 1)));
        return bone;
    }
}
