package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelJointPivotsTest {
    @Test
    void derivesScaledCentralPivotsFromOfficialBodyControls() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("AllBody", "", 0.1F, 1.5F, 0.2F));
        geometry.add(bone("UpBody", "AllBody", 0.1F, 1.0F, 0.2F));
        geometry.add(bone("DownBody", "AllBody", 0.1F, 1.0F, 0.2F));
        geometry.add(bone("UpperBody", "UpBody", 0.1F, 1.3F, 0.2F));
        geometry.add(bone("AllHead", "UpperBody", 0.1F, 1.8F, 0.2F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 2.0F, 3.0F);

        assertVectorEquals(new Vector3f(0.2F, 3.0F, 0.4F), pivots.get(HumanoidRig.TORSO));
        assertVectorEquals(new Vector3f(0.2F, 3.9F, 0.4F), pivots.get(HumanoidRig.CHEST));
        assertVectorEquals(new Vector3f(0.2F, 5.4F, 0.4F), pivots.get(HumanoidRig.HEAD));
    }

    @Test
    void selectsTheClosestBodyControlPairWhenOneWaistControlIsAnOutlier() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("AllBody", "", 0.0F, 0.625F, 0.0F));
        geometry.add(bone("UpBody", "AllBody", 0.0F, -0.143F, 0.0F));
        geometry.add(bone("DownBody", "AllBody", 0.0F, 0.556F, 0.0F));
        geometry.add(bone("UpperBody", "AllBody", 0.0F, 0.9F, 0.0F));
        geometry.add(bone("AllHead", "UpperBody", 0.0F, 1.2F, 0.0F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        assertEquals(0.5905F, pivots.get(HumanoidRig.TORSO).y(), 0.00001F);
        assertEquals(0.9F, pivots.get(HumanoidRig.CHEST).y(), 0.00001F);
        assertEquals(1.2F, pivots.get(HumanoidRig.HEAD).y(), 0.00001F);
    }

    @Test
    void usesUpBodyAsTheVerifiedLegacyChestFallback() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("AllBody", "", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("UpBody", "AllBody", 0.0F, 1.2F, 0.0F));
        geometry.add(bone("DownBody", "AllBody", 0.0F, 1.2F, 0.0F));
        geometry.add(bone("AllHead", "UpBody", 0.0F, 1.6F, 0.0F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        assertVectorEquals(new Vector3f(0.0F, 1.2F, 0.0F), pivots.get(HumanoidRig.TORSO));
        assertVectorEquals(new Vector3f(0.0F, 1.2F, 0.0F), pivots.get(HumanoidRig.CHEST));
        assertVectorEquals(new Vector3f(0.0F, 1.6F, 0.0F), pivots.get(HumanoidRig.HEAD));
    }

    @Test
    void carriesCentralPivotsThroughTheParentBindTransform() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = bone("Root", "", 0.0F, 0.0F, 0.0F);
        root.rotation(0.0F, 0.0F, (float) Math.toRadians(90.0D));
        geometry.add(root);
        geometry.add(bone("AllBody", "Root", 1.0F, 0.0F, 0.0F));
        geometry.add(bone("UpBody", "AllBody", 1.0F, 0.0F, 0.0F));
        geometry.add(bone("DownBody", "AllBody", 1.0F, 0.0F, 0.0F));
        geometry.add(bone("UpperBody", "UpBody", 2.0F, 0.0F, 0.0F));
        geometry.add(bone("AllHead", "UpperBody", 3.0F, 0.0F, 0.0F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        assertEquals(0.0F, pivots.get(HumanoidRig.TORSO).x(), 0.00001F);
        assertEquals(1.0F, pivots.get(HumanoidRig.TORSO).y(), 0.00001F);
        assertEquals(0.0F, pivots.get(HumanoidRig.CHEST).x(), 0.00001F);
        assertEquals(2.0F, pivots.get(HumanoidRig.CHEST).y(), 0.00001F);
        assertEquals(0.0F, pivots.get(HumanoidRig.HEAD).x(), 0.00001F);
        assertEquals(3.0F, pivots.get(HumanoidRig.HEAD).y(), 0.00001F);
    }

    @Test
    void rejectsConflictingCentralAliasesPerJoint() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("AllBody", "", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("UpBody", "AllBody", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("DownBody", "AllBody", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("UpperBody", "UpBody", 0.0F, 1.3F, 0.0F));
        geometry.add(bone("Chest", "UpBody", 0.0F, 2.0F, 0.0F));
        geometry.add(bone("AllHead", "UpBody", 0.0F, 1.6F, 0.0F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        assertVectorEquals(new Vector3f(0.0F, 1.0F, 0.0F), pivots.get(HumanoidRig.TORSO));
        assertFalse(pivots.containsKey(HumanoidRig.CHEST));
        assertVectorEquals(new Vector3f(0.0F, 1.6F, 0.0F), pivots.get(HumanoidRig.HEAD));
    }

    @Test
    void excludesAlternateOrLimbNestedCentralControls() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(bone("AllBody", "", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("UpBody", "AllBody", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("DownBody", "AllBody", 0.0F, 1.0F, 0.0F));
        geometry.add(bone("UpperBody2_Default", "UpBody", 0.0F, 1.3F, 0.0F));
        geometry.add(bone("RightArm", "UpBody", -0.3F, 1.4F, 0.0F));
        geometry.add(bone("AllHead", "RightArm", 0.0F, 1.6F, 0.0F));
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        assertVectorEquals(new Vector3f(0.0F, 1.0F, 0.0F), pivots.get(HumanoidRig.TORSO));
        assertFalse(pivots.containsKey(HumanoidRig.CHEST));
        assertFalse(pivots.containsKey(HumanoidRig.HEAD));
    }

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

    @Test
    void prefersAnAuthoredToolLocatorAndCarriesItThroughTheParentBindTransform() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone("RightForeArm", -1.0F, 1.0F,
                -4.0F, 0.0F);
        forearm.rotation(0.0F, 0.0F, (float) Math.toRadians(90.0D));
        GeometryDocument.Bone locator = bone(
                "RightHandLocator", "RightForeArm", 1.0F, 2.0F, 3.0F);
        geometry.add(forearm);
        geometry.add(locator);
        geometry.linkHierarchy();

        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, 2.0F, 3.0F);
        Vector3f pivot = estimate.pivots().get(HumanoidRig.RIGHT_TOOL);

        assertEquals(-4.0F, pivot.x(), 0.00001F);
        assertEquals(3.0F, pivot.y(), 0.00001F);
        assertEquals(6.0F, pivot.z(), 0.00001F);
        assertSame(forearm, estimate.toolSources().get(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void keepsASingleAuthoredLocatorEvenWhenAnEmptyHandPivotIsFarAway() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone(
                "RightForeArm", -4.5F, -2.5F, -7.0F, -3.0F);
        GeometryDocument.Bone hand = bone(
                "RightHand", "RightForeArm", -3.6F, -4.4F, 0.0F);
        GeometryDocument.Bone locator = bone(
                "RightHandLocator", "RightHand", -2.2F, 10.6F, 0.5F);
        geometry.add(forearm);
        geometry.add(hand);
        geometry.add(locator);
        geometry.linkHierarchy();

        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, 1.0F, 1.0F);

        assertEquals(new Vector3f(-2.2F, 10.6F, 0.5F),
                estimate.pivots().get(HumanoidRig.RIGHT_TOOL));
        assertSame(hand, estimate.toolSources().get(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void prefersTheHandGeometryCentroidWithoutRequiringAnAxisLength() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone(
                "RightForeArm", -1.0F, 1.0F, -4.0F, 0.0F);
        GeometryDocument.Bone hand = faceBone(
                "RightHand", 4.5F, 5.5F, -0.5F, 0.5F);
        geometry.add(forearm);
        geometry.add(hand);
        geometry.linkHierarchy();

        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, 1.0F, 1.0F);

        Vector3f fist = estimate.pivots().get(HumanoidRig.RIGHT_TOOL);
        assertEquals(5.0F, fist.x(), 0.00001F);
        assertEquals(0.0F, fist.y(), 0.00001F);
        assertSame(hand, estimate.toolSources().get(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void prefersForearmGeometryOverAnEmptyHandControlPivot() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone(
                "LeftForeArm", -1.0F, 1.0F, -4.0F, 0.0F);
        geometry.add(forearm);
        GeometryDocument.Bone hand = bone("LeftHand", "", -3.0F, 2.0F, 1.0F);
        geometry.add(hand);
        geometry.linkHierarchy();

        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, 1.0F, 1.0F);

        assertEquals(new Vector3f(0.0F, -4.0F, 0.0F),
                estimate.pivots().get(HumanoidRig.LEFT_TOOL));
        assertSame(forearm, estimate.toolSources().get(HumanoidRig.LEFT_TOOL));
    }

    @Test
    void fallsBackToTheDistalHandRingWithoutIncludingChildAccessories() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = bone("LeftArm", "", -2.0F, 2.0F, 0.0F);
        GeometryDocument.Bone forearm = faceBone(
                "LeftForeArm", -3.0F, -1.0F, -2.0F, 2.0F);
        forearm.parentName("LeftArm");
        forearm.pivot(-2.0F, 2.0F, 0.0F);
        GeometryDocument.Bone accessory = faceBone(
                "LongSleeveDecoration", -30.0F, 30.0F, -100.0F, 100.0F);
        accessory.parentName("LeftForeArm");
        geometry.add(arm);
        geometry.add(forearm);
        geometry.add(accessory);
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(geometry, 1.0F, 1.0F);

        Vector3f fist = pivots.get(HumanoidRig.LEFT_TOOL);
        assertEquals(-2.0F, fist.x(), 0.00001F);
        assertEquals(-2.0F, fist.y(), 0.00001F);
        assertEquals(0.0F, fist.z(), 0.00001F);
        assertFalse(pivots.containsKey(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void rejectsCompetingToolLocatorsAndUsesTheGeometryFallback() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = bone("RightArm", "", 2.0F, 0.0F, 0.0F);
        GeometryDocument.Bone forearm = faceBone(
                "RightForeArm", 1.0F, 3.0F, -4.0F, 0.0F);
        forearm.parentName("RightArm");
        forearm.pivot(2.0F, 0.0F, 0.0F);
        GeometryDocument.Bone locator = bone(
                "RightHandLocator", "RightForeArm", 2.0F, -3.0F, 0.0F);
        GeometryDocument.Bone competing = bone(
                "RightItem", "RightForeArm", 20.0F, 30.0F, 0.0F);
        geometry.add(arm);
        geometry.add(forearm);
        geometry.add(locator);
        geometry.add(competing);
        geometry.linkHierarchy();

        Vector3f fist = ModelJointPivots.estimate(geometry, 1.0F, 1.0F)
                .get(HumanoidRig.RIGHT_TOOL);

        assertEquals(2.0F, fist.x(), 0.00001F);
        assertEquals(-4.0F, fist.y(), 0.00001F);
        assertEquals(0.0F, fist.z(), 0.00001F);
    }

    @Test
    void rejectsSamePointLocatorsWhenTheirDisplayedSourceBonesDiffer() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone(
                "RightForeArm", -1.0F, 1.0F, -4.0F, 0.0F);
        GeometryDocument.Bone hand = faceBone(
                "RightHand", 9.0F, 11.0F, -1.0F, 1.0F);
        hand.parentName("RightForeArm");
        GeometryDocument.Bone locator = bone(
                "RightHandLocator", "RightForeArm", 0.0F, -4.0F, 0.0F);
        GeometryDocument.Bone competing = bone(
                "RightItem", "RightHand", 0.0F, -4.0F, 0.0F);
        geometry.add(forearm);
        geometry.add(hand);
        geometry.add(locator);
        geometry.add(competing);
        geometry.linkHierarchy();

        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, 1.0F, 1.0F);

        Vector3f fist = estimate.pivots().get(HumanoidRig.RIGHT_TOOL);
        assertEquals(10.0F, fist.x(), 0.00001F);
        assertEquals(0.0F, fist.y(), 0.00001F);
        assertSame(hand, estimate.toolSources().get(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void derivesTheDistalRingAlongARotatedForearmAxis() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = bone("RightArm", "", 0.0F, 0.0F, 0.0F);
        GeometryDocument.Bone forearm = faceBone(
                "RightForeArm", -1.0F, 1.0F, -4.0F, 0.0F);
        forearm.parentName("RightArm");
        forearm.rotation(0.0F, 0.0F, (float) Math.toRadians(90.0D));
        geometry.add(arm);
        geometry.add(forearm);
        geometry.linkHierarchy();

        Vector3f fist = ModelJointPivots.estimate(geometry, 1.0F, 1.0F)
                .get(HumanoidRig.RIGHT_TOOL);

        assertEquals(4.0F, fist.x(), 0.00001F);
        assertEquals(0.0F, fist.y(), 0.00001F);
        assertEquals(0.0F, fist.z(), 0.00001F);
    }

    @Test
    void rejectsAToolLocatorOnTheOppositeArmBranch() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone leftArm = bone("LeftArm", "", -2.0F, 3.0F, 0.0F);
        GeometryDocument.Bone leftForearm = bone(
                "LeftForeArm", "LeftArm", -2.0F, 1.0F, 0.0F);
        GeometryDocument.Bone wrong = bone(
                "RightHandLocator", "LeftForeArm", -2.0F, 0.0F, 0.0F);
        geometry.add(leftArm);
        geometry.add(leftForearm);
        geometry.add(wrong);
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(
                geometry, 1.0F, 1.0F);

        assertFalse(pivots.containsKey(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void rejectsUpperArmFallbackGeometryOnTheOppositeArmBranch() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone left = bone(
                "LeftArm", "", -2.0F, 0.0F, 0.0F);
        GeometryDocument.Bone wrong = faceBone(
                "RightArm", 1.0F, 3.0F, -5.0F, 0.0F);
        wrong.parentName("LeftArm");
        geometry.add(left);
        geometry.add(wrong);
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(
                geometry, 1.0F, 1.0F);

        assertFalse(pivots.containsKey(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void usesUpperArmGeometryOnlyAsTheLastFistFallback() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(faceBone("RightArm", -1.0F, 1.0F, -5.0F, 0.0F));
        geometry.linkHierarchy();

        Vector3f fist = ModelJointPivots.estimate(geometry, 1.0F, 1.0F)
                .get(HumanoidRig.RIGHT_TOOL);

        assertEquals(0.0F, fist.x(), 0.00001F);
        assertEquals(-5.0F, fist.y(), 0.00001F);
        assertEquals(0.0F, fist.z(), 0.00001F);
    }

    @Test
    void acceptsADirectRootForearmAsTheFistGeometrySource() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone(
                "LeftForeArm", -2.0F, 0.0F, -6.0F, 0.0F);
        forearm.pivot(-1.0F, 0.0F, 0.0F);
        geometry.add(forearm);
        geometry.linkHierarchy();

        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, 1.0F, 1.0F);

        Vector3f fist = estimate.pivots().get(HumanoidRig.LEFT_TOOL);
        assertEquals(-1.0F, fist.x(), 0.00001F);
        assertEquals(-6.0F, fist.y(), 0.00001F);
        assertSame(forearm, estimate.toolSources().get(HumanoidRig.LEFT_TOOL));
    }

    @Test
    void excludesNumberedDefaultFormLocators() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = bone("RightArm", "", 2.0F, 3.0F, 0.0F);
        GeometryDocument.Bone alternateHand = bone(
                "RightHand2_Default", "RightArm", 20.0F, 30.0F, 0.0F);
        GeometryDocument.Bone alternateLocator = bone(
                "RightHandLocator2_Default", "RightHand2_Default", 20.0F, 30.0F, 0.0F);
        geometry.add(arm);
        geometry.add(alternateHand);
        geometry.add(alternateLocator);
        geometry.linkHierarchy();

        Map<Integer, Vector3f> pivots = ModelJointPivots.estimate(
                geometry, 1.0F, 1.0F);

        assertFalse(pivots.containsKey(HumanoidRig.RIGHT_TOOL));
    }

    @Test
    void refusesNonFiniteOrNonPositiveModelScales() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(faceBone("RightArm", -1.0F, 1.0F, -5.0F, 0.0F));
        geometry.linkHierarchy();

        assertEquals(Map.of(), ModelJointPivots.estimate(geometry, Float.NaN, 1.0F));
        assertEquals(Map.of(), ModelJointPivots.estimate(geometry, 1.0F, 0.0F));
        assertEquals(Map.of(), ModelJointPivots.estimate(geometry, -1.0F, 1.0F));
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

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x(), actual.x(), 0.00001F);
        assertEquals(expected.y(), actual.y(), 0.00001F);
        assertEquals(expected.z(), actual.z(), 0.00001F);
    }
}
