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

    @Test
    void derivesScaledHipAndKneePivotsFromTheJointControls() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone body = bone("DownBody", "", 0.0F, 1.0F, 0.0F);
        GeometryDocument.Bone thigh = bone("LeftLeg", "DownBody", -0.15F, 1.3F, 0.1F);
        GeometryDocument.Bone knee = bone(
                "LeftLowerLeg", "LeftLeg", -0.14F, 0.7F, 0.05F);
        GeometryDocument.Bone foot = bone("LeftFoot", "LeftLowerLeg", -9.0F, -9.0F, -9.0F);
        GeometryDocument.Bone alternate = bone("LeftLeg2", "DownBody", 8.0F, 8.0F, 8.0F);
        geometry.add(body);
        geometry.add(thigh);
        geometry.add(knee);
        geometry.add(foot);
        geometry.add(alternate);
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 2.0F, 3.0F);

        Vector3f hip = pivots.get(HumanoidRig.LEFT_THIGH);
        assertEquals(-0.3F, hip.x(), 0.00001F);
        assertEquals(3.9F, hip.y(), 0.00001F);
        assertEquals(0.2F, hip.z(), 0.00001F);
        Vector3f kneePivot = pivots.get(HumanoidRig.LEFT_LEG);
        assertEquals(-0.28F, kneePivot.x(), 0.00001F);
        assertEquals(2.1F, kneePivot.y(), 0.00001F);
        assertEquals(0.1F, kneePivot.z(), 0.00001F);
    }

    @Test
    void carriesLowerLimbPivotsThroughTheParentBindTransform() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone body = bone("DownBody", "", 0.0F, 0.0F, 0.0F);
        body.rotation(0.0F, 0.0F, (float) Math.toRadians(90.0D));
        GeometryDocument.Bone thigh = bone("RightLeg", "DownBody", 1.0F, 0.0F, 0.0F);
        geometry.add(body);
        geometry.add(thigh);
        geometry.linkHierarchy();

        Vector3f pivot = ModelJointPivots.estimate(geometry, 1.0F, 1.0F)
                .get(HumanoidRig.RIGHT_THIGH);

        assertEquals(0.0F, pivot.x(), 0.00001F);
        assertEquals(1.0F, pivot.y(), 0.00001F);
        assertEquals(0.0F, pivot.z(), 0.00001F);
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

    private static GeometryDocument.Bone bone(String name, String parent,
                                               float pivotX, float pivotY, float pivotZ) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        bone.pivot(pivotX, pivotY, pivotZ);
        return bone;
    }
}
