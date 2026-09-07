package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigBindingPlanTest {
    @Test
    void preservesTheCanonicalHumanoidAndItsAccessoryAnchors() {
        GeometryDocument geometry = humanoid("");
        add(geometry, "ear", "Head");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        for (GeometryDocument.Bone bone : geometry.bones().values()) {
            assertEquals(HumanoidRig.jointFor(bone), plan.epicAnchorFor(bone), bone.name());
            assertFalse(plan.usesAuthoredPose(bone), bone.name());
        }
        assertSame(bone(geometry, "LeftArm"), plan.primaryControl(HumanoidRig.LEFT_ARM));
        assertTrue(plan.isEpicPoseControl(bone(geometry, "Root")));
        assertFalse(plan.isEpicPoseControl(bone(geometry, "ear")));
    }

    @Test
    void preservesACompleteNumberedOnlyHumanoid() {
        for (String suffix : new String[]{"2", "_Default", "2_Default"}) {
            GeometryDocument geometry = humanoid(suffix);

            RigBindingPlan plan = RigBindingPlan.create(geometry);

            assertFalse(plan.hasAuthoredBranches(), suffix);
            assertEquals(HumanoidRig.LEFT_ARM,
                    plan.epicAnchorFor(bone(geometry, "LeftArm" + suffix)), suffix);
            assertTrue(plan.isEpicPoseControl(bone(geometry, "LeftArm" + suffix)), suffix);
            assertSame(bone(geometry, "RightHandLocator" + suffix),
                    plan.primaryControl(HumanoidRig.RIGHT_TOOL), suffix);
        }
    }

    @Test
    void isolatesASeparateHeadHeldBodyAndIncludesItsUnsharedWrapper() {
        GeometryDocument geometry = humanoid("");
        mouthBody(geometry, "AlternateVisual", "Root", "2");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertTrue(plan.hasAuthoredBranches());
        for (String name : new String[]{"AlternateVisual", "AllBody2", "Head2", "LeftArm2",
                "RightLeg2", "MouthMount2", "RightHandLocator2"}) {
            GeometryDocument.Bone selected = bone(geometry, name);
            assertTrue(plan.usesAuthoredPose(selected), name);
            assertSame(bone(geometry, "AlternateVisual"), plan.domainRoot(selected), name);
            assertEquals(-1, plan.epicAnchorFor(selected), name);
            assertFalse(plan.isEpicPoseControl(selected), name);
        }
        assertFalse(plan.usesAuthoredPose(bone(geometry, "Root")));
        assertTrue(plan.influencesAuthoredPose(bone(geometry, "Root")));
        assertTrue(plan.isEpicPoseControl(bone(geometry, "Root")));
        assertFalse(plan.usesAuthoredPose(bone(geometry, "LeftArm")));
        assertSame(bone(geometry, "Head"), plan.primaryControl(HumanoidRig.HEAD));
    }

    @Test
    void aStandaloneHeadHeldBodyDoesNotNeedAHiddenHumanoid() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        mouthBody(geometry, "AlternateVisual", "Root", "2");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertTrue(plan.usesAuthoredPose(bone(geometry, "Root")));
        assertSame(bone(geometry, "Root"), plan.domainRoot(bone(geometry, "LeftArm2")));
        assertEquals(-1, plan.epicAnchorFor(bone(geometry, "RightHandLocator2")));
        assertNull(plan.primaryControl(HumanoidRig.HEAD));
    }

    @Test
    void anAttachedMiniatureKeepsOneExternalHeadAnchor() {
        GeometryDocument geometry = humanoid("");
        mouthBody(geometry, "Miniature", "Head", "4");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertTrue(plan.usesAuthoredPose(bone(geometry, "LeftArm4")));
        assertSame(bone(geometry, "Miniature"), plan.domainRoot(bone(geometry, "Head4")));
        assertEquals(HumanoidRig.HEAD, plan.epicAnchorFor(bone(geometry, "LeftArm4")));
        assertEquals(HumanoidRig.HEAD, plan.epicAnchorFor(bone(geometry, "RightHandLocator4")));
        assertTrue(plan.influencesAuthoredPose(bone(geometry, "Head")));
        assertFalse(plan.usesAuthoredPose(bone(geometry, "Head")));
        assertSame(bone(geometry, "Head"), plan.primaryControl(HumanoidRig.HEAD));
    }

    @Test
    void aNumberedArmOrUmbrellaAttachmentIsNotAnAlternateBody() {
        GeometryDocument geometry = humanoid("");
        add(geometry, "LeftArm2", "UpperBody");
        add(geometry, "LeftHand2", "LeftArm2");
        add(geometry, "LeftHandLocator2", "LeftHand2");
        add(geometry, "PropArm", "UpperBody");
        add(geometry, "LeftForeArm3", "PropArm");
        add(geometry, "LeftHand5", "LeftForeArm3");
        add(geometry, "LeftHandLocator5", "LeftHand5");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        assertEquals(HumanoidRig.LEFT_ARM, plan.epicAnchorFor(bone(geometry, "LeftArm2")));
        assertTrue(plan.isEpicPoseControl(bone(geometry, "LeftArm2")));
        assertTrue(plan.isEpicPoseControl(bone(geometry, "PropArm")));
    }

    @Test
    void aCompleteNumberedHumanAlternateStillUsesEpicFight() {
        GeometryDocument geometry = humanoid("");
        add(geometry, "Alternative", "Root");
        body(geometry, "Alternative", "2", false);
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        assertSame(bone(geometry, "LeftHandLocator"), plan.primaryControl(HumanoidRig.LEFT_TOOL));
        assertEquals(HumanoidRig.LEFT_ARM, plan.epicAnchorFor(bone(geometry, "LeftArm2")));
    }

    @Test
    void arbitraryBonesWithoutHumanoidControlsUseTheirAuthoredHierarchy() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        add(geometry, "Core", "Root");
        add(geometry, "Tentacle", "Core");
        add(geometry, "Cape", "Core");
        add(geometry, "LeftHand", "Core");
        add(geometry, "LeftHandLocator", "LeftHand");
        add(geometry, "RightHandLocator", "Core");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertTrue(plan.hasAuthoredBranches());
        for (GeometryDocument.Bone bone : geometry.bones().values()) {
            assertTrue(plan.usesAuthoredPose(bone), bone.name());
            assertEquals(-1, plan.epicAnchorFor(bone), bone.name());
            assertFalse(plan.isEpicPoseControl(bone), bone.name());
        }
    }

    @Test
    void standaloneHandAndToolIslandsKeepTheirExplicitEpicAttachments() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "RightHand", "");
        add(geometry, "RightHandLocator", "RightHand");
        add(geometry, "LeftHandLocator", "");
        add(geometry, "Accessory", "RightHand");
        geometry.linkHierarchy();
        RigBindingPlan plan = RigBindingPlan.create(geometry);
        assertFalse(plan.hasAuthoredBranches());
        for (GeometryDocument.Bone bone : geometry.bones().values()) {
            assertFalse(plan.usesAuthoredPose(bone), bone.name());
        }
        assertEquals(HumanoidRig.RIGHT_HAND,
                plan.epicAnchorFor(bone(geometry, "RightHand")));
        assertEquals(HumanoidRig.LEFT_TOOL,
                plan.epicAnchorFor(bone(geometry, "LeftHandLocator")));
    }

    @Test
    void anArbitraryRootCanRemainNativeBesideAStandaloneHumanHand() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Core", "");
        add(geometry, "Tentacle", "Core");
        add(geometry, "RightHand", "");
        add(geometry, "RightHandLocator", "RightHand");
        geometry.linkHierarchy();
        RigBindingPlan plan = RigBindingPlan.create(geometry);
        assertTrue(plan.usesAuthoredPose(bone(geometry, "Core")));
        assertTrue(plan.usesAuthoredPose(bone(geometry, "Tentacle")));
        assertFalse(plan.usesAuthoredPose(bone(geometry, "RightHand")));
        assertFalse(plan.usesAuthoredPose(bone(geometry, "RightHandLocator")));
    }

    @Test
    void partialOrOneSidedEvidenceRetainsTheLegacyPolicy() {
        GeometryDocument geometry = humanoid("");
        mouthBody(geometry, "AlternateVisual", "Root", "2");
        bone(geometry, "RightHand2").parentName("RightArm2");
        add(geometry, "IncompleteBody", "Root");
        add(geometry, "AllBody3", "IncompleteBody");
        add(geometry, "Head3", "AllBody3");
        add(geometry, "LeftHand3", "Head3");
        add(geometry, "LeftHandLocator3", "LeftHand3");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        assertTrue(plan.isEpicPoseControl(bone(geometry, "LeftHand2")));
    }

    @Test
    void headMountedExtraToolsDoNotReclassifyAnOtherwiseHumanoidBody() {
        GeometryDocument geometry = humanoid("");
        add(geometry, "LeftHand7", "Head");
        add(geometry, "LeftHandLocator7", "LeftHand7");
        add(geometry, "RightHand7", "Head");
        add(geometry, "RightHandLocator7", "RightHand7");
        geometry.linkHierarchy();

        assertFalse(RigBindingPlan.create(geometry).hasAuthoredBranches());
    }

    @Test
    void evenOneArmHeldHandMakesAHeadHeldBodyAmbiguous() {
        GeometryDocument geometry = humanoid("");
        mouthBody(geometry, "AlternateVisual", "Root", "2");
        add(geometry, "RightHand9", "RightArm2");
        geometry.linkHierarchy();

        assertFalse(RigBindingPlan.create(geometry).hasAuthoredBranches());
    }

    @Test
    void aForearmOnlyFragmentStillHasHumanoidAnatomy() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        add(geometry, "RightForeArm", "Root");
        add(geometry, "RightHandLocator", "RightForeArm");
        geometry.linkHierarchy();

        assertFalse(RigBindingPlan.create(geometry).hasAuthoredBranches());
    }

    @Test
    void explicitRootsAreExactImmutableOverridesAndTheNearestOneWins() {
        GeometryDocument geometry = humanoid("");
        GeometryDocument.Bone head = bone(geometry, "Head");
        add(geometry, "Accessory", "Head");
        geometry.linkHierarchy();
        Map<GeometryDocument.Bone, RigBindingPlan.PoseDomain> overrides = new IdentityHashMap<>();
        overrides.put(head, RigBindingPlan.PoseDomain.AUTHORED_SUBTREE);
        overrides.put(bone(geometry, "Accessory"), RigBindingPlan.PoseDomain.EPIC_RETARGETED);

        RigBindingPlan plan = RigBindingPlan.create(geometry, overrides);
        overrides.clear();

        assertTrue(plan.usesAuthoredPose(head));
        assertFalse(plan.usesAuthoredPose(bone(geometry, "Accessory")));
        assertSame(head, plan.domainRoot(head));
        assertEquals(HumanoidRig.CHEST, plan.epicAnchorFor(head));
        assertFalse(RigBindingPlan.create(geometry).hasAuthoredBranches());
        assertThrows(IllegalArgumentException.class, () -> RigBindingPlan.create(geometry,
                Map.of(new GeometryDocument.Bone("Head"), RigBindingPlan.PoseDomain.AUTHORED_SUBTREE)));
    }

    @Test
    void anExplicitEpicRootCanDisableAnAutomaticallyRecognizedBody() {
        GeometryDocument geometry = humanoid("");
        mouthBody(geometry, "AlternateVisual", "Root", "2");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry,
                Map.of(bone(geometry, "AlternateVisual"), RigBindingPlan.PoseDomain.EPIC_RETARGETED));

        assertFalse(plan.hasAuthoredBranches());
        assertEquals(HumanoidRig.LEFT_ARM, plan.epicAnchorFor(bone(geometry, "LeftArm2")));
    }

    @Test
    void anExplicitAncestorOverridesAutomaticDescendants() {
        GeometryDocument geometry = humanoid("");
        mouthBody(geometry, "AlternateVisual", "Root", "2");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry,
                Map.of(bone(geometry, "Root"), RigBindingPlan.PoseDomain.EPIC_RETARGETED));

        assertFalse(plan.hasAuthoredBranches());
    }

    @Test
    void anOversizedGeometryKeepsLegacyBindingsWithoutAutomaticAnalysis() {
        GeometryDocument geometry = humanoid("");
        String parent = "Head";
        for (int index = 0; index <= RigBindingPlan.MAX_AUTOMATIC_BONES; index++) {
            String name = "Accessory" + index;
            add(geometry, name, parent);
            parent = name;
        }
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        assertEquals(HumanoidRig.HEAD, plan.epicAnchorFor(bone(geometry, parent)));
        assertTrue(plan.isEpicPoseControl(bone(geometry, "Root")));
        assertFalse(plan.isEpicPoseControl(bone(geometry, parent)));
        assertThrows(IllegalArgumentException.class, () -> RigBindingPlan.create(geometry,
                Map.of(bone(geometry, "Root"), RigBindingPlan.PoseDomain.AUTHORED_SUBTREE)));
    }

    @Test
    void adversarialNestedBodiesExhaustAnalysisIntoTheLegacyFallback() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        String parent = "Root";
        for (int index = 0; index < RigBindingPlan.MAX_AUTOMATIC_BONES - 20; index++) {
            String name = "Torso" + index;
            add(geometry, name, parent);
            parent = name;
        }
        mouthBody(geometry, "AlternateVisual", parent, "2");
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        assertEquals(HumanoidRig.LEFT_ARM, plan.epicAnchorFor(bone(geometry, "LeftArm2")));
    }

    @Test
    void competingNumberedHumanoidsHaveNoInferredPrimaryControl() {
        GeometryDocument geometry = humanoid("2");
        body(geometry, "Root", "3", false);
        geometry.linkHierarchy();

        RigBindingPlan plan = RigBindingPlan.create(geometry);

        assertFalse(plan.hasAuthoredBranches());
        assertNull(plan.primaryControl(HumanoidRig.HEAD));
    }

    @Test
    void anEmptyGeometryHasNoPoseDomains() {
        RigBindingPlan plan = RigBindingPlan.create(new GeometryDocument());

        assertFalse(plan.hasAuthoredBranches());
        assertNull(plan.primaryControl(HumanoidRig.HEAD));
    }

    private static GeometryDocument humanoid(String suffix) {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        body(geometry, "Root", suffix, false);
        geometry.linkHierarchy();
        return geometry;
    }

    private static void mouthBody(GeometryDocument geometry, String wrapper,
                                  String parent, String suffix) {
        add(geometry, wrapper, parent);
        body(geometry, wrapper, suffix, true);
    }

    private static void body(GeometryDocument geometry, String parent,
                             String suffix, boolean headHeld) {
        add(geometry, "AllBody" + suffix, parent);
        add(geometry, "UpperBody" + suffix, "AllBody" + suffix);
        add(geometry, "Head" + suffix, "UpperBody" + suffix);
        add(geometry, "LeftArm" + suffix, "UpperBody" + suffix);
        add(geometry, "RightArm" + suffix, "UpperBody" + suffix);
        add(geometry, "DownBody" + suffix, "AllBody" + suffix);
        add(geometry, "LeftLeg" + suffix, "DownBody" + suffix);
        add(geometry, "RightLeg" + suffix, "DownBody" + suffix);
        if (headHeld) {
            add(geometry, "MouthMount" + suffix, "Head" + suffix);
        }
        for (String side : new String[]{"Left", "Right"}) {
            add(geometry, side + "Hand" + suffix,
                    headHeld ? "MouthMount" + suffix : side + "Arm" + suffix);
            add(geometry, side + "HandLocator" + suffix, side + "Hand" + suffix);
        }
    }

    private static void add(GeometryDocument geometry, String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        geometry.add(bone);
    }

    private static GeometryDocument.Bone bone(GeometryDocument geometry, String name) {
        return geometry.bones().get(name);
    }
}
