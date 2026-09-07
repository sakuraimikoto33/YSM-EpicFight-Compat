package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static net.okitsu.ysmepicfightcompat.mesh.HandLocatorSelection.Status.AMBIGUOUS;
import static net.okitsu.ysmepicfightcompat.mesh.HandLocatorSelection.Status.HIDDEN;
import static net.okitsu.ysmepicfightcompat.mesh.HandLocatorSelection.Status.INVALID;
import static net.okitsu.ysmepicfightcompat.mesh.HandLocatorSelection.Status.MISSING;
import static net.okitsu.ysmepicfightcompat.mesh.HandLocatorSelection.Status.VISIBLE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandLocatorSelectionTest {
    @Test
    void absentCandidatesAreMissingEvenWithoutAFrame() {
        assertStatus(MISSING, HandLocatorSelection.resolve(List.of(), null, Set.of()));
    }

    @Test
    void acceptsAnEmptyLocatorOutsideTheHumanoidArmBranch() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");

        assertEquals(0, mouth.bone().faces().size());
        assertSelection(mouth, HandLocatorSelection.resolve(
                List.of(mouth), fixture.complete(), Set.of()));
    }

    @Test
    void choosesTheOnlyVisibleCandidateWithoutPreferringItsSuffixOrOrder() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry hand = fixture.entry("RightHandLocator");
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");

        for (List<AuxiliaryBoneLayout.Entry> candidates :
                List.of(List.of(hand, mouth), List.of(mouth, hand))) {
            assertSelection(mouth, HandLocatorSelection.resolve(
                    candidates, fixture.complete(), Set.of("HumanShape")));
            assertSelection(hand, HandLocatorSelection.resolve(
                    candidates, fixture.complete(), Set.of("AnimalShape")));
        }
    }

    @Test
    void multipleVisibleCandidatesAreAmbiguousInBothOrders() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry hand = fixture.entry("RightHandLocator");
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");

        assertStatus(AMBIGUOUS, HandLocatorSelection.resolve(
                List.of(hand, mouth), fixture.complete(), Set.of()));
        assertStatus(AMBIGUOUS, HandLocatorSelection.resolve(
                List.of(mouth, hand), fixture.complete(), Set.of()));
    }

    @Test
    void propagatesHiddenAncestorsAndKeepsExplicitHideSeparateFromMissing() {
        Fixture fixture = fixture();
        List<AuxiliaryBoneLayout.Entry> candidates = List.of(
                fixture.entry("RightHandLocator"), fixture.entry("RightHandLocator2"));

        assertStatus(HIDDEN, HandLocatorSelection.resolve(
                candidates, fixture.complete(), Set.of("Root")));
        assertStatus(HIDDEN, HandLocatorSelection.resolve(candidates, fixture.complete(),
                Set.of("RightHandLocator", "AnimalHead")));
    }

    @Test
    void anExplicitlyHiddenCandidateDoesNotNeedAValidMatrix() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        fixture.complete()[mouth.poseIndex()] = null;

        assertStatus(HIDDEN, HandLocatorSelection.resolve(
                List.of(mouth), fixture.complete(), Set.of("AnimalShape")));
        assertStatus(HIDDEN, HandLocatorSelection.resolve(
                List.of(mouth), null, Set.of("AnimalHead")));
        assertSelection(fixture.entry("RightHandLocator"), HandLocatorSelection.resolve(
                List.of(mouth, fixture.entry("RightHandLocator")),
                fixture.complete(), Set.of("AnimalShape")));
    }

    @Test
    void finalZeroOrSingularScaleIsHiddenButReflectedScaleIsVisible() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");

        for (float[] scale : new float[][]{{0, 0, 0}, {1, 0, 1}, {1.0E-9F, 1, 1}}) {
            fixture.complete()[mouth.poseIndex()] = new OpenMatrix4f()
                    .scale(scale[0], scale[1], scale[2]);
            assertStatus(HIDDEN, HandLocatorSelection.resolve(
                    List.of(mouth), fixture.complete(), Set.of()));
        }
        fixture.complete()[mouth.poseIndex()] = new OpenMatrix4f().scale(-1, 2, 3);
        assertSelection(mouth, HandLocatorSelection.resolve(
                List.of(mouth), fixture.complete(), Set.of()));
    }

    @Test
    void aCollapsedCandidateDoesNotHideItsVisibleSibling() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry hand = fixture.entry("RightHandLocator");
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        fixture.complete()[hand.poseIndex()] = new OpenMatrix4f().scale(0, 0, 0);

        assertSelection(mouth, HandLocatorSelection.resolve(
                List.of(hand, mouth), fixture.complete(), Set.of()));
    }

    @Test
    void usesTheSameStrictCollapseBoundaryAsAuthoredItemSwitches() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        fixture.complete()[mouth.poseIndex()] = new OpenMatrix4f()
                .scale(1.0E-8F, 1, 1);

        assertSelection(mouth, HandLocatorSelection.resolve(
                List.of(mouth), fixture.complete(), Set.of()));
        fixture.complete()[mouth.poseIndex()].m00 = Math.nextDown(1.0E-8F);
        assertStatus(HIDDEN, HandLocatorSelection.resolve(
                List.of(mouth), fixture.complete(), Set.of()));
    }

    @Test
    void selectionUsesTheCurrentCompletedFrameWithoutRetainingPriorChoices() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry hand = fixture.entry("RightHandLocator");
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        List<AuxiliaryBoneLayout.Entry> candidates = List.of(hand, mouth);
        fixture.complete()[mouth.poseIndex()] = new OpenMatrix4f().scale(0, 0, 0);
        assertSelection(hand, HandLocatorSelection.resolve(
                candidates, fixture.complete(), Set.of()));

        fixture.complete()[hand.poseIndex()] = new OpenMatrix4f().scale(0, 0, 0);
        fixture.complete()[mouth.poseIndex()] = new OpenMatrix4f();
        assertSelection(mouth, HandLocatorSelection.resolve(
                candidates, fixture.complete(), Set.of()));

        fixture.complete()[hand.poseIndex()] = new OpenMatrix4f();
        assertStatus(AMBIGUOUS, HandLocatorSelection.resolve(
                candidates, fixture.complete(), Set.of()));
    }

    @Test
    void missingNullOrTruncatedFrameIsInvalidInsteadOfHidden() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        List<AuxiliaryBoneLayout.Entry> candidates = List.of(mouth);

        assertStatus(INVALID, HandLocatorSelection.resolve(candidates, null, Set.of()));
        assertStatus(INVALID, HandLocatorSelection.resolve(
                candidates, new OpenMatrix4f[mouth.poseIndex()], Set.of()));
        fixture.complete()[mouth.poseIndex()] = null;
        assertStatus(INVALID, HandLocatorSelection.resolve(
                candidates, fixture.complete(), Set.of()));
    }

    @Test
    void invalidVisibleCandidateCannotBeSilentlyIgnoredForAnotherValidOne() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry hand = fixture.entry("RightHandLocator");
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        fixture.complete()[mouth.poseIndex()].m00 = Float.NaN;

        assertStatus(INVALID, HandLocatorSelection.resolve(
                List.of(hand, mouth), fixture.complete(), Set.of()));
        assertStatus(INVALID, HandLocatorSelection.resolve(
                List.of(mouth, hand), fixture.complete(), Set.of()));
    }

    @Test
    void checksTranslationAndHomogeneousComponentsNotOnlyTheLinearDeterminant() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");

        for (int field = 0; field < 4; field++) {
            OpenMatrix4f invalid = new OpenMatrix4f();
            switch (field) {
                case 0 -> invalid.m30 = Float.POSITIVE_INFINITY;
                case 1 -> invalid.m31 = Float.NaN;
                case 2 -> invalid.m03 = Float.NEGATIVE_INFINITY;
                case 3 -> invalid.m33 = Float.NaN;
                default -> throw new AssertionError();
            }
            fixture.complete()[mouth.poseIndex()] = invalid;
            assertStatus(INVALID, HandLocatorSelection.resolve(
                    List.of(mouth), fixture.complete(), Set.of()));
        }
    }

    @Test
    void finiteUnbalancedScalesDoNotOverflowTheVisibilityDeterminant() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        fixture.complete()[mouth.poseIndex()] = new OpenMatrix4f()
                .scale(1.0E20F, 1.0E20F, 1.0E-30F);

        assertSelection(mouth, HandLocatorSelection.resolve(
                List.of(mouth), fixture.complete(), Set.of()));
    }

    @Test
    void doesNotMutateTheCandidateOrderOrCompletedMatrices() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");
        AuxiliaryBoneLayout.Entry hand = fixture.entry("RightHandLocator");
        List<AuxiliaryBoneLayout.Entry> candidates = new ArrayList<>(List.of(mouth, hand));
        OpenMatrix4f matrix = new OpenMatrix4f().translate(2, 3, 4)
                .rotateDeg(27, Vec3f.Y_AXIS).scale(-1, 2, 3);
        fixture.complete()[mouth.poseIndex()] = matrix;
        float[] before = components(matrix);

        assertSelection(mouth, HandLocatorSelection.resolve(
                candidates, fixture.complete(), Set.of("HumanShape")));

        assertEquals(List.of(mouth, hand), candidates);
        assertSame(matrix, fixture.complete()[mouth.poseIndex()]);
        assertArrayEquals(before, components(matrix), 0.0F);
    }

    @Test
    void validatesSelectionRecordInvariantsAndNullCandidates() {
        Fixture fixture = fixture();
        AuxiliaryBoneLayout.Entry mouth = fixture.entry("RightHandLocator2");

        assertThrows(NullPointerException.class, () -> new HandLocatorSelection(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HandLocatorSelection(VISIBLE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HandLocatorSelection(HIDDEN, mouth));
        assertStatus(INVALID, HandLocatorSelection.resolve(null, fixture.complete(), Set.of()));
        assertStatus(INVALID, HandLocatorSelection.resolve(
                Arrays.asList(mouth, null), fixture.complete(), Set.of()));
    }

    private static void assertStatus(HandLocatorSelection.Status expected,
                                     HandLocatorSelection actual) {
        assertEquals(expected, actual.status());
        assertNull(actual.locator());
    }

    private static void assertSelection(AuxiliaryBoneLayout.Entry expected,
                                        HandLocatorSelection actual) {
        assertEquals(VISIBLE, actual.status());
        assertSame(expected, actual.locator());
    }

    private record Fixture(AuxiliaryBoneLayout layout, OpenMatrix4f[] complete) {
        AuxiliaryBoneLayout.Entry entry(String name) {
            return layout.entryForBoneName(name);
        }
    }

    private static Fixture fixture() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        add(geometry, "HumanShape", "Root");
        add(geometry, "RightArm", "HumanShape");
        add(geometry, "RightHandLocator", "RightArm");
        add(geometry, "AnimalShape", "Root");
        add(geometry, "AnimalHead", "AnimalShape");
        add(geometry, "RightHandLocator2", "AnimalHead");
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        return new Fixture(layout, AuxiliaryPoseMatrices.allocate(layout.totalPoseCount()));
    }

    private static void add(GeometryDocument geometry, String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        geometry.add(bone);
    }

    private static float[] components(OpenMatrix4f value) {
        return new float[]{value.m00, value.m01, value.m02, value.m03,
                value.m10, value.m11, value.m12, value.m13,
                value.m20, value.m21, value.m22, value.m23,
                value.m30, value.m31, value.m32, value.m33};
    }
}
