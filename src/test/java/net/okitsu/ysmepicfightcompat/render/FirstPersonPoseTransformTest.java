package net.okitsu.ysmepicfightcompat.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FirstPersonPoseTransformTest {
    private static final float EPSILON = 0.00001F;

    @Test
    void bridgesCameraAndWorldBasesForIndependentAndCombinedViewAngles() {
        Matrix4f incoming = new Matrix4f();
        for (float eye : new float[]{0.4F, 1.62F, 2.25F}) {
            for (float pitch : new float[]{-60.0F, 0.0F, 35.0F}) {
                for (float yaw : new float[]{-40.0F, 15.0F, 70.0F}) {
                    assertMatrix(worldRoot(pitch, yaw - 15.0F, eye),
                            correctedRoot(incoming, pitch, yaw, 15.0F, eye));
                }
            }
        }
    }

    @Test
    void removesNoncommutingIncomingTranslationRotationAndScale() {
        Matrix4f incoming = incomingHandTransform();

        assertMatrix(worldRoot(-27.0F, 48.0F, 1.54F),
                correctedRoot(incoming, -27.0F, 73.0F, 25.0F, 1.54F));
        assertMatrix(new Matrix4f().translation(0.0F, -1.54F, 0.0F),
                correctedRoot(incoming, 0.0F, 25.0F, 25.0F, 1.54F));
    }

    @Test
    void viewIncrementsCancelTheAuthoredAimOnceWithoutDependingOnModelYaw() {
        Matrix4f incoming = incomingHandTransform();
        for (float pitch : new float[]{-40.0F, -20.0F, 0.0F, 20.0F, 40.0F}) {
            for (float yaw : new float[]{-45.0F, 0.0F, 45.0F}) {
                for (float modelYaw : new float[]{-20.0F, 15.0F, 30.0F}) {
                    Matrix4f composed = correctedRoot(incoming, pitch, yaw, modelYaw, 1.62F)
                            .rotateY(radians(modelYaw - yaw)).rotateX(radians(-pitch));
                    for (Vector3f axis : List.of(new Vector3f(1, 0, 0),
                            new Vector3f(0, 1, 0), new Vector3f(0, 0, 1))) {
                        assertVector(axis, composed.transformDirection(new Vector3f(axis)));
                    }
                }
            }
        }
    }

    @Test
    void wrapsYawAcrossTheSignedBoundaryAndFullTurns() {
        Matrix4f incoming = incomingHandTransform();
        assertMatrix(worldRoot(10.0F, -2.0F, 1.62F),
                correctedRoot(incoming, 10.0F, 179.0F, -179.0F, 1.62F));
        assertMatrix(worldRoot(10.0F, 2.0F, 1.62F),
                correctedRoot(incoming, 10.0F, -179.0F, 179.0F, 1.62F));
        assertMatrix(correctedRoot(incoming, 10.0F, 25.0F, 5.0F, 1.62F),
                correctedRoot(incoming, 370.0F, 745.0F, -355.0F, 1.62F));
    }

    @Test
    void rotatesAroundTheRenderersEyeHeightInBlockUnits() {
        for (float eye : new float[]{0.4F, 1.62F, 2.25F}) {
            OpenMatrix4f correction = FirstPersonPoseTransform.forCameraRelativePose(
                    true, new Matrix4f(), 35.0F, 50.0F, -10.0F, eye);
            assertNotNull(correction);
            assertVector(new Vector3f(0, eye, 0),
                    OpenMatrix4f.exportToMojangMatrix(correction)
                            .transformPosition(new Vector3f(0, eye, 0)));
        }
    }

    @Test
    void preservesIncomingAndReturnsAnIndependentCorrection() {
        Matrix4f incoming = incomingHandTransform();
        float[] before = incoming.get(new float[16]);
        OpenMatrix4f first = FirstPersonPoseTransform.forCameraRelativePose(
                true, incoming, 25.0F, 45.0F, 15.0F, 1.62F);
        assertNotNull(first);
        Matrix4f expected = OpenMatrix4f.exportToMojangMatrix(first);
        assertArrayEquals(before, incoming.get(new float[16]), 0.0F);
        first.m30 = 999.0F;
        OpenMatrix4f second = FirstPersonPoseTransform.forCameraRelativePose(
                true, incoming, 25.0F, 45.0F, 15.0F, 1.62F);
        assertNotNull(second);
        incoming.identity();
        assertMatrix(expected, OpenMatrix4f.exportToMojangMatrix(second));
    }

    @Test
    void leavesOtherRootPoliciesAndInvalidInputsUnchanged() {
        assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                false, new Matrix4f(), 20, 30, 10, 1.62F));
        assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                true, null, 20, 30, 10, 1.62F));
        for (Matrix4f invalid : List.of(new Matrix4f().m00(Float.NaN),
                new Matrix4f().m31(Float.POSITIVE_INFINITY),
                new Matrix4f().scaling(0, 1, 1),
                new Matrix4f().scaling(0.00001F),
                new Matrix4f().m03(0.01F), new Matrix4f().m33(0.5F))) {
            assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                    true, invalid, 20, 30, 10, 1.62F));
        }
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                    true, new Matrix4f(), invalid, 30, 10, 1.62F));
            assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                    true, new Matrix4f(), 20, invalid, 10, 1.62F));
            assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                    true, new Matrix4f(), 20, 30, invalid, 1.62F));
            assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                    true, new Matrix4f(), 20, 30, 10, invalid));
        }
        for (float invalidEye : new float[]{0.0F, -1.0F}) {
            assertNull(FirstPersonPoseTransform.forCameraRelativePose(
                    true, new Matrix4f(), 20, 30, 10, invalidEye));
        }
    }

    private static Matrix4f incomingHandTransform() {
        return new Matrix4f().translate(0.13F, -0.09F, 0.21F)
                .rotateZ(0.19F).rotateX(-0.23F).rotateY(0.31F)
                .scale(1.02F, 0.95F, 1.05F);
    }

    private static Matrix4f correctedRoot(Matrix4f incoming, float pitch,
                                          float yaw, float modelYaw, float eye) {
        OpenMatrix4f correction = FirstPersonPoseTransform.forCameraRelativePose(
                true, incoming, pitch, yaw, modelYaw, eye);
        assertNotNull(correction);
        return new Matrix4f().translate(0.0F, -eye, 0.0F).mul(incoming)
                .mul(OpenMatrix4f.exportToMojangMatrix(correction));
    }

    private static Matrix4f worldRoot(float pitch, float relativeYaw, float eye) {
        return new Matrix4f().rotateX(radians(pitch)).rotateY(radians(relativeYaw))
                .translate(0.0F, -eye, 0.0F);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        assertArrayEquals(expected.get(new float[16]), actual.get(new float[16]), EPSILON);
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
