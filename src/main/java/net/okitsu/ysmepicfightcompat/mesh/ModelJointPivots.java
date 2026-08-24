package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Estimates humanoid bind pivots from model geometry and authored bone controls. */
final class ModelJointPivots {
    private static final float TOP_RING_EPSILON = 0.05F;
    private static final float DISTAL_RING_EPSILON = 0.05F;
    private static final float MIN_SEGMENT_LENGTH = 0.1F;
    private static final float ANCHOR_CLUSTER_EPSILON_SQUARED = 0.0025F;
    /** Four authored model pixels after the package's configured model scale is applied. */
    private static final float CENTRAL_PAIR_TOLERANCE = 0.25F;
    private static final float CENTRAL_MIN_SPAN_SQUARED = 0.0025F;

    private enum CentralRole {
        ALL_BODY,
        UP_BODY,
        DOWN_BODY,
        EXPLICIT_TORSO,
        UPPER_BODY,
        HEAD_BASE
    }

    private record Visit(GeometryDocument.Bone bone, Matrix4f parentTransform) {
    }

    record Estimate(Map<Integer, Vector3f> pivots,
                    Map<Integer, GeometryDocument.Bone> toolSources) {
        Estimate {
            pivots = Map.copyOf(pivots);
            toolSources = Map.copyOf(toolSources);
        }
    }

    private record ToolAnchor(Vector3f point, GeometryDocument.Bone source) {
    }

    /** Merges equivalent/default central controls while rejecting conflicting definitions. */
    private static final class PivotCluster {
        private final Vector3f sum = new Vector3f();
        private Vector3f first;
        private int count;
        private int candidates;
        private boolean ambiguous;

        private void include(Vector3f value) {
            candidates++;
            if (value == null || !finite(value)) {
                ambiguous = true;
                return;
            }
            if (first == null) {
                first = new Vector3f(value);
            } else if (first.distanceSquared(value) > ANCHOR_CLUSTER_EPSILON_SQUARED) {
                ambiguous = true;
            }
            sum.add(value);
            count++;
        }

        private boolean present() {
            return candidates > 0;
        }

        private Vector3f value() {
            return ambiguous || count == 0 ? null : new Vector3f(sum).div(count);
        }
    }

    private static final class VerticalExtent {
        private float maximum = -Float.MAX_VALUE;

        private void include(float value) {
            maximum = Math.max(maximum, value);
        }
    }

    private static final class TopRing {
        private final Vector3f sum = new Vector3f();
        private int count;

        private void include(Vector3f vertex, VerticalExtent extent) {
            if (vertex.y() >= extent.maximum - TOP_RING_EPSILON) {
                sum.add(vertex);
                count++;
            }
        }

        private Vector3f value() {
            return count == 0 ? null : new Vector3f(sum).div(count);
        }
    }

    /** Accepts duplicate/default controls only when they describe the same bind point. */
    private static final class AnchorCluster {
        private final Vector3f sum = new Vector3f();
        private Vector3f first;
        private GeometryDocument.Bone source;
        private int count;
        private boolean ambiguous;

        private void include(Vector3f value, GeometryDocument.Bone bone) {
            if (!finite(value)) {
                return;
            }
            if (first == null) {
                first = new Vector3f(value);
                source = bone;
            } else if (source != bone
                    || first.distanceSquared(value) > ANCHOR_CLUSTER_EPSILON_SQUARED) {
                ambiguous = true;
            }
            sum.add(value);
            count++;
        }

        private ToolAnchor value() {
            return ambiguous || count == 0 || source == null ? null
                    : new ToolAnchor(new Vector3f(sum).div(count), source);
        }
    }

    /** One directly-authored arm segment, evaluated around its own proximal pivot. */
    private static final class SegmentCandidate {
        private final GeometryDocument.Bone source;
        private final int toolJoint;
        private final int rank;
        private final Vector3f proximal;
        private final Vector3f axis = new Vector3f();
        private final Vector3f centroidSum = new Vector3f();
        private final Vector3f distalSum = new Vector3f();
        private final Vector3f projectionScratch = new Vector3f();
        private final boolean centroidAnchor;
        private Vector3f directValue;
        private float maximumProjection = -Float.MAX_VALUE;
        private float ringThreshold;
        private int centroidCount;
        private int distalCount;

        private SegmentCandidate(GeometryDocument.Bone source, int toolJoint, int rank,
                                 Vector3f proximal, boolean centroidAnchor) {
            this.source = source;
            this.toolJoint = toolJoint;
            this.rank = rank;
            this.proximal = proximal;
            this.centroidAnchor = centroidAnchor;
        }

        private void includeForAxis(Vector3f vertex) {
            centroidSum.add(vertex);
            centroidCount++;
        }

        private boolean prepareAxis() {
            if (centroidCount == 0) {
                return false;
            }
            if (centroidAnchor) {
                directValue = new Vector3f(centroidSum).div(centroidCount);
                return finite(directValue);
            }
            axis.set(centroidSum).div(centroidCount).sub(proximal);
            float lengthSquared = axis.lengthSquared();
            return Float.isFinite(lengthSquared)
                    && lengthSquared > MIN_SEGMENT_LENGTH * MIN_SEGMENT_LENGTH
                    && finite(axis.normalize());
        }

        private void includeProjection(Vector3f vertex) {
            if (centroidAnchor) {
                return;
            }
            float projection = projectionScratch.set(vertex).sub(proximal).dot(axis);
            if (Float.isFinite(projection)) {
                maximumProjection = Math.max(maximumProjection, projection);
            }
        }

        private boolean prepareRing() {
            if (centroidAnchor) {
                return directValue != null;
            }
            if (!Float.isFinite(maximumProjection)
                    || maximumProjection <= MIN_SEGMENT_LENGTH) {
                return false;
            }
            ringThreshold = maximumProjection - DISTAL_RING_EPSILON;
            return true;
        }

        private void includeDistal(Vector3f vertex) {
            if (centroidAnchor) {
                return;
            }
            float projection = projectionScratch.set(vertex).sub(proximal).dot(axis);
            if (Float.isFinite(projection) && projection >= ringThreshold) {
                distalSum.add(vertex);
                distalCount++;
            }
        }

        private Vector3f value() {
            return centroidAnchor ? directValue
                    : distalCount == 0 ? null : new Vector3f(distalSum).div(distalCount);
        }

    }

    /** Prefers forearm geometry over the less precise upper-arm-only fallback. */
    private static final class RankedAnchors {
        private final AnchorCluster[] anchors = new AnchorCluster[5];

        private void include(SegmentCandidate candidate) {
            Vector3f value = candidate.value();
            if (value == null || candidate.rank <= 0 || candidate.rank >= anchors.length) {
                return;
            }
            AnchorCluster cluster = anchors[candidate.rank];
            if (cluster == null) {
                cluster = new AnchorCluster();
                anchors[candidate.rank] = cluster;
            }
            cluster.include(value, candidate.source);
        }

        private ToolAnchor value() {
            for (int rank = anchors.length - 1; rank > 0; rank--) {
                ToolAnchor result = anchors[rank] == null ? null : anchors[rank].value();
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
    }

    private ModelJointPivots() {
    }

    static Map<Integer, Vector3f> estimate(GeometryDocument geometry,
                                          float horizontalScale, float verticalScale) {
        return estimateWithSources(geometry, horizontalScale, verticalScale).pivots();
    }

    static Estimate estimateWithSources(GeometryDocument geometry,
                                        float horizontalScale, float verticalScale) {
        if (geometry == null || !validScale(horizontalScale) || !validScale(verticalScale)) {
            return new Estimate(Map.of(), Map.of());
        }
        Map<Integer, VerticalExtent> extents = new HashMap<>();
        Map<Integer, Vector3f> lowerLimbPivots = new HashMap<>();
        Map<Integer, AnchorCluster> toolLocators = new HashMap<>();
        Map<GeometryDocument.Bone, SegmentCandidate> segments = new IdentityHashMap<>();
        Map<CentralRole, PivotCluster> centralControls = new EnumMap<>(CentralRole.class);

        ArrayDeque<Visit> pending = roots(geometry);
        Vector3f scratch = new Vector3f();
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            GeometryDocument.Bone bone = visit.bone();
            Matrix4f transform = bindTransform(visit.parentTransform(), bone);
            boolean primary = isPrimaryVariant(bone.name());

            CentralRole centralRole = primary && isOnCentralBranch(bone)
                    ? centralRole(bone.name()) : null;
            if (centralRole != null) {
                centralControls.computeIfAbsent(centralRole, ignored -> new PivotCluster())
                        .include(transformedPivot(
                                bone, visit.parentTransform(), horizontalScale, verticalScale));
            }

            if (primary && isLowerLimbSegmentRoot(bone)) {
                Vector3f pivot = transformedPivot(
                        bone, visit.parentTransform(), horizontalScale, verticalScale);
                if (pivot != null) {
                    lowerLimbPivots.putIfAbsent(HumanoidRig.jointFor(bone), pivot);
                }
            }
            if (primary && isToolLocator(bone)
                    && isOnExpectedArmBranch(bone, HumanoidRig.jointFor(bone))) {
                Vector3f pivot = transformedPivot(
                        bone, visit.parentTransform(), horizontalScale, verticalScale);
                if (pivot != null) {
                    GeometryDocument.Bone source = locatorAnchorSource(
                            bone, HumanoidRig.jointFor(bone));
                    toolLocators.computeIfAbsent(HumanoidRig.jointFor(bone),
                            ignored -> new AnchorCluster()).include(pivot, source);
                }
            }

            SegmentCandidate segment = primary ? segmentCandidate(
                    bone, visit.parentTransform(), horizontalScale, verticalScale) : null;
            if (segment != null) {
                segments.put(bone, segment);
            }
            if (primary && isUpperLimbSegment(bone)) {
                VerticalExtent extent = extents.computeIfAbsent(
                        HumanoidRig.jointFor(bone), ignored -> new VerticalExtent());
                for (GeometryDocument.Face face : bone.faces()) {
                    for (Vector3f authored : face.positions()) {
                        transform(scratch, authored, transform, horizontalScale, verticalScale);
                        if (!finite(scratch)) {
                            continue;
                        }
                        extent.include(scratch.y());
                        if (segment != null) {
                            segment.includeForAxis(scratch);
                        }
                    }
                }
            }
            pushChildren(pending, bone, transform);
        }

        segments.values().removeIf(candidate -> !candidate.prepareAxis());
        Map<Integer, TopRing> topRings = collectRings(
                geometry, extents, segments, horizontalScale, verticalScale);
        segments.values().removeIf(candidate -> !candidate.prepareRing());
        collectDistalRings(geometry, segments, horizontalScale, verticalScale);
        Map<Integer, RankedAnchors> geometryAnchors = new HashMap<>();
        for (SegmentCandidate segment : segments.values()) {
            geometryAnchors.computeIfAbsent(segment.toolJoint,
                    ignored -> new RankedAnchors()).include(segment);
        }

        Map<Integer, Vector3f> result = new HashMap<>();
        float centralTolerance = CENTRAL_PAIR_TOLERANCE
                * Math.max(horizontalScale, verticalScale);
        Vector3f torso = selectTorsoPivot(centralControls, centralTolerance);
        Vector3f head = centralValue(centralControls, CentralRole.HEAD_BASE);
        Vector3f chest = selectChestPivot(
                centralControls, torso, head, centralTolerance);
        put(result, HumanoidRig.TORSO, torso);
        put(result, HumanoidRig.CHEST, chest);
        put(result, HumanoidRig.HEAD, head);
        putPair(result, HumanoidRig.RIGHT_SHOULDER, HumanoidRig.RIGHT_ARM,
                topOf(topRings, HumanoidRig.RIGHT_ARM));
        putPair(result, HumanoidRig.LEFT_SHOULDER, HumanoidRig.LEFT_ARM,
                topOf(topRings, HumanoidRig.LEFT_ARM));
        putPair(result, HumanoidRig.RIGHT_HAND, HumanoidRig.RIGHT_ELBOW,
                topOf(topRings, HumanoidRig.RIGHT_HAND));
        putPair(result, HumanoidRig.LEFT_HAND, HumanoidRig.LEFT_ELBOW,
                topOf(topRings, HumanoidRig.LEFT_HAND));
        put(result, HumanoidRig.RIGHT_THIGH,
                lowerLimbPivots.get(HumanoidRig.RIGHT_THIGH));
        put(result, HumanoidRig.RIGHT_LEG,
                lowerLimbPivots.get(HumanoidRig.RIGHT_LEG));
        put(result, HumanoidRig.LEFT_THIGH,
                lowerLimbPivots.get(HumanoidRig.LEFT_THIGH));
        put(result, HumanoidRig.LEFT_LEG,
                lowerLimbPivots.get(HumanoidRig.LEFT_LEG));
        ToolAnchor rightTool = selectToolAnchor(
                toolLocators.get(HumanoidRig.RIGHT_TOOL),
                geometryAnchors.get(HumanoidRig.RIGHT_TOOL));
        ToolAnchor leftTool = selectToolAnchor(
                toolLocators.get(HumanoidRig.LEFT_TOOL),
                geometryAnchors.get(HumanoidRig.LEFT_TOOL));
        Map<Integer, GeometryDocument.Bone> toolSources = new HashMap<>();
        putTool(result, toolSources, HumanoidRig.RIGHT_TOOL, rightTool);
        putTool(result, toolSources, HumanoidRig.LEFT_TOOL, leftTool);
        return new Estimate(result, toolSources);
    }

    private static Vector3f selectTorsoPivot(
            Map<CentralRole, PivotCluster> controls, float tolerance) {
        PivotCluster explicit = controls.get(CentralRole.EXPLICIT_TORSO);
        if (explicit != null && explicit.present()) {
            return explicit.value();
        }
        Vector3f[] candidates = {
                centralValue(controls, CentralRole.ALL_BODY),
                centralValue(controls, CentralRole.UP_BODY),
                centralValue(controls, CentralRole.DOWN_BODY)
        };
        float toleranceSquared = tolerance * tolerance;
        float bestDistance = Float.MAX_VALUE;
        Vector3f best = null;
        for (int first = 0; first < candidates.length; first++) {
            if (candidates[first] == null) {
                continue;
            }
            for (int second = first + 1; second < candidates.length; second++) {
                if (candidates[second] == null) {
                    continue;
                }
                float distance = candidates[first].distanceSquared(candidates[second]);
                if (Float.isFinite(distance) && distance <= toleranceSquared
                        && distance < bestDistance) {
                    bestDistance = distance;
                    best = new Vector3f(candidates[first]).add(candidates[second]).mul(0.5F);
                }
            }
        }
        return best;
    }

    private static Vector3f selectChestPivot(
            Map<CentralRole, PivotCluster> controls,
            Vector3f torso, Vector3f head, float tolerance) {
        PivotCluster upperBody = controls.get(CentralRole.UPPER_BODY);
        if (upperBody != null && upperBody.present()) {
            Vector3f direct = upperBody.value();
            return direct != null && plausibleCentralPoint(direct, torso, head, tolerance)
                    ? direct : null;
        }

        // Some official legacy models omit UpperBody. UpBody is the corresponding authored
        // upper-body control, but only use it when the surrounding torso/head span verifies it.
        Vector3f fallback = centralValue(controls, CentralRole.UP_BODY);
        return fallback != null && torso != null && head != null
                && plausibleCentralPoint(fallback, torso, head, tolerance) ? fallback : null;
    }

    private static boolean plausibleCentralPoint(
            Vector3f point, Vector3f torso, Vector3f head, float tolerance) {
        if (torso == null || head == null) {
            return true;
        }
        Vector3f span = new Vector3f(head).sub(torso);
        float spanSquared = span.lengthSquared();
        if (!Float.isFinite(spanSquared) || spanSquared < CENTRAL_MIN_SPAN_SQUARED) {
            return false;
        }
        Vector3f offset = new Vector3f(point).sub(torso);
        float progress = offset.dot(span) / spanSquared;
        if (!Float.isFinite(progress) || progress < -0.05F || progress > 1.05F) {
            return false;
        }
        Vector3f projected = new Vector3f(span).mul(progress).add(torso);
        float distance = projected.distanceSquared(point);
        return Float.isFinite(distance) && distance <= tolerance * tolerance;
    }

    private static Vector3f centralValue(
            Map<CentralRole, PivotCluster> controls, CentralRole role) {
        PivotCluster cluster = controls.get(role);
        return cluster == null ? null : cluster.value();
    }

    private static CentralRole centralRole(String name) {
        return switch (normalizedName(name)) {
            case "allbody" -> CentralRole.ALL_BODY;
            case "upbody" -> CentralRole.UP_BODY;
            case "downbody" -> CentralRole.DOWN_BODY;
            case "waist", "torso", "hip", "hips", "pelvis" ->
                    CentralRole.EXPLICIT_TORSO;
            case "upperbody", "chest" -> CentralRole.UPPER_BODY;
            case "allhead", "neck" -> CentralRole.HEAD_BASE;
            default -> null;
        };
    }

    private static boolean isOnCentralBranch(GeometryDocument.Bone bone) {
        for (GeometryDocument.Bone parent = bone.parent(); parent != null;
             parent = parent.parent()) {
            if (!isPrimaryVariant(parent.name())) {
                return false;
            }
            int direct = HumanoidRig.directJointFor(parent);
            if (direct >= 0 && direct != HumanoidRig.ROOT
                    && direct != HumanoidRig.TORSO && direct != HumanoidRig.CHEST) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, TopRing> collectRings(
            GeometryDocument geometry, Map<Integer, VerticalExtent> extents,
            Map<GeometryDocument.Bone, SegmentCandidate> segments,
            float horizontalScale, float verticalScale) {
        Map<Integer, TopRing> result = new HashMap<>();
        ArrayDeque<Visit> pending = roots(geometry);
        Vector3f scratch = new Vector3f();
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            GeometryDocument.Bone bone = visit.bone();
            Matrix4f transform = bindTransform(visit.parentTransform(), bone);
            boolean upper = isPrimaryVariant(bone.name()) && isUpperLimbSegment(bone);
            SegmentCandidate segment = segments.get(bone);
            if (upper) {
                int joint = HumanoidRig.jointFor(bone);
                VerticalExtent extent = extents.get(joint);
                TopRing ring = result.computeIfAbsent(joint, ignored -> new TopRing());
                for (GeometryDocument.Face face : bone.faces()) {
                    for (Vector3f authored : face.positions()) {
                        transform(scratch, authored, transform, horizontalScale, verticalScale);
                        if (!finite(scratch)) {
                            continue;
                        }
                        ring.include(scratch, extent);
                        if (segment != null) {
                            segment.includeProjection(scratch);
                        }
                    }
                }
            }
            pushChildren(pending, bone, transform);
        }
        return result;
    }

    private static void collectDistalRings(
            GeometryDocument geometry,
            Map<GeometryDocument.Bone, SegmentCandidate> segments,
            float horizontalScale, float verticalScale) {
        ArrayDeque<Visit> pending = roots(geometry);
        Vector3f scratch = new Vector3f();
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            GeometryDocument.Bone bone = visit.bone();
            Matrix4f transform = bindTransform(visit.parentTransform(), bone);
            SegmentCandidate segment = segments.get(bone);
            if (segment != null) {
                for (GeometryDocument.Face face : bone.faces()) {
                    for (Vector3f authored : face.positions()) {
                        transform(scratch, authored, transform, horizontalScale, verticalScale);
                        if (finite(scratch)) {
                            segment.includeDistal(scratch);
                        }
                    }
                }
            }
            pushChildren(pending, bone, transform);
        }
    }

    private static SegmentCandidate segmentCandidate(
            GeometryDocument.Bone bone, Matrix4f parentTransform,
            float horizontalScale, float verticalScale) {
        int sourceJoint = HumanoidRig.directJointFor(bone);
        int toolJoint;
        int rank;
        boolean centroidAnchor = false;
        if (HumanoidRig.isHandControl(bone)) {
            if (!isOnCompatibleArmBranch(bone, sourceJoint)) {
                return null;
            }
            toolJoint = sourceJoint == HumanoidRig.RIGHT_HAND
                    ? HumanoidRig.RIGHT_TOOL : sourceJoint == HumanoidRig.LEFT_HAND
                    ? HumanoidRig.LEFT_TOOL : -1;
            rank = bone.faces().isEmpty() ? 2 : 4;
            centroidAnchor = true;
        } else if (HumanoidRig.isForearmControl(bone)) {
            if (!isOnCompatibleArmBranch(bone, sourceJoint)) {
                return null;
            }
            toolJoint = sourceJoint == HumanoidRig.RIGHT_HAND
                    ? HumanoidRig.RIGHT_TOOL : sourceJoint == HumanoidRig.LEFT_HAND
                    ? HumanoidRig.LEFT_TOOL : -1;
            rank = 3;
        } else if (HumanoidRig.isUpperArmControl(bone)) {
            if (!isOnCompatibleArmBranch(bone, sourceJoint)) {
                return null;
            }
            toolJoint = sourceJoint == HumanoidRig.RIGHT_ARM
                    ? HumanoidRig.RIGHT_TOOL : sourceJoint == HumanoidRig.LEFT_ARM
                    ? HumanoidRig.LEFT_TOOL : -1;
            rank = 1;
        } else {
            return null;
        }
        Vector3f proximal = transformedPivot(
                bone, parentTransform, horizontalScale, verticalScale);
        if (toolJoint < 0 || proximal == null
                || bone.faces().isEmpty() && !centroidAnchor) {
            return null;
        }
        SegmentCandidate result = new SegmentCandidate(
                bone, toolJoint, rank, proximal, centroidAnchor);
        if (bone.faces().isEmpty()) {
            result.includeForAxis(proximal);
        }
        return result;
    }

    private static ToolAnchor selectToolAnchor(AnchorCluster authored,
                                               RankedAnchors geometry) {
        ToolAnchor explicit = authored == null ? null : authored.value();
        ToolAnchor fallback = geometry == null ? null : geometry.value();
        return explicit == null ? fallback : explicit;
    }

    private static boolean isUpperLimbSegment(GeometryDocument.Bone bone) {
        if (!HumanoidRig.isMajorBone(bone)) {
            return false;
        }
        int joint = HumanoidRig.jointFor(bone);
        return joint == HumanoidRig.RIGHT_ARM || joint == HumanoidRig.LEFT_ARM
                || joint == HumanoidRig.RIGHT_HAND || joint == HumanoidRig.LEFT_HAND;
    }

    private static boolean isToolLocator(GeometryDocument.Bone bone) {
        int joint = HumanoidRig.directJointFor(bone);
        return joint == HumanoidRig.RIGHT_TOOL || joint == HumanoidRig.LEFT_TOOL;
    }

    private static boolean isOnExpectedArmBranch(GeometryDocument.Bone bone, int joint) {
        boolean right = joint == HumanoidRig.RIGHT_TOOL || joint == HumanoidRig.RIGHT_HAND;
        boolean left = joint == HumanoidRig.LEFT_TOOL || joint == HumanoidRig.LEFT_HAND;
        if (!right && !left) {
            return false;
        }
        for (GeometryDocument.Bone parent = bone.parent(); parent != null;
             parent = parent.parent()) {
            int direct = HumanoidRig.directJointFor(parent);
            if ((right && (direct == HumanoidRig.LEFT_ARM
                    || direct == HumanoidRig.LEFT_HAND || direct == HumanoidRig.LEFT_TOOL)
                    || left && (direct == HumanoidRig.RIGHT_ARM
                    || direct == HumanoidRig.RIGHT_HAND || direct == HumanoidRig.RIGHT_TOOL))) {
                return false;
            }
            if (!isPrimaryVariant(parent.name()) && (direct == HumanoidRig.RIGHT_ARM
                    || direct == HumanoidRig.RIGHT_HAND || direct == HumanoidRig.RIGHT_TOOL
                    || direct == HumanoidRig.LEFT_ARM || direct == HumanoidRig.LEFT_HAND
                    || direct == HumanoidRig.LEFT_TOOL)) {
                return false;
            }
            if ((right && (direct == HumanoidRig.RIGHT_ARM
                    || direct == HumanoidRig.RIGHT_HAND || direct == HumanoidRig.RIGHT_TOOL)
                    || left && (direct == HumanoidRig.LEFT_ARM
                    || direct == HumanoidRig.LEFT_HAND || direct == HumanoidRig.LEFT_TOOL))) {
                return true;
            }
        }
        return false;
    }

    /** Locators define a point; their containing arm bone supplies its displayed skin. */
    private static GeometryDocument.Bone locatorAnchorSource(
            GeometryDocument.Bone locator, int toolJoint) {
        int hand = toolJoint == HumanoidRig.RIGHT_TOOL ? HumanoidRig.RIGHT_HAND
                : toolJoint == HumanoidRig.LEFT_TOOL ? HumanoidRig.LEFT_HAND : -1;
        int arm = toolJoint == HumanoidRig.RIGHT_TOOL ? HumanoidRig.RIGHT_ARM
                : toolJoint == HumanoidRig.LEFT_TOOL ? HumanoidRig.LEFT_ARM : -1;
        GeometryDocument.Bone armFallback = null;
        for (GeometryDocument.Bone parent = locator.parent(); parent != null;
             parent = parent.parent()) {
            int direct = HumanoidRig.directJointFor(parent);
            if (direct == hand && isPrimaryVariant(parent.name())) {
                return parent;
            }
            if (armFallback == null && direct == arm && isPrimaryVariant(parent.name())) {
                armFallback = parent;
            }
        }
        return armFallback;
    }

    /** Directly named arm segments may be roots, but never belong under the opposite arm. */
    private static boolean isOnCompatibleArmBranch(GeometryDocument.Bone bone, int joint) {
        boolean right = joint == HumanoidRig.RIGHT_ARM || joint == HumanoidRig.RIGHT_HAND;
        boolean left = joint == HumanoidRig.LEFT_ARM || joint == HumanoidRig.LEFT_HAND;
        if (!right && !left) {
            return false;
        }
        for (GeometryDocument.Bone parent = bone.parent(); parent != null;
             parent = parent.parent()) {
            int direct = HumanoidRig.directJointFor(parent);
            if (right && (direct == HumanoidRig.LEFT_ARM
                    || direct == HumanoidRig.LEFT_HAND || direct == HumanoidRig.LEFT_TOOL)
                    || left && (direct == HumanoidRig.RIGHT_ARM
                    || direct == HumanoidRig.RIGHT_HAND || direct == HumanoidRig.RIGHT_TOOL)) {
                return false;
            }
            if (!isPrimaryVariant(parent.name()) && (direct == HumanoidRig.RIGHT_ARM
                    || direct == HumanoidRig.RIGHT_HAND || direct == HumanoidRig.RIGHT_TOOL
                    || direct == HumanoidRig.LEFT_ARM || direct == HumanoidRig.LEFT_HAND
                    || direct == HumanoidRig.LEFT_TOOL)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowerLimbSegmentRoot(GeometryDocument.Bone bone) {
        if (!HumanoidRig.isMajorBone(bone)) {
            return false;
        }
        int joint = HumanoidRig.jointFor(bone);
        if (joint != HumanoidRig.RIGHT_THIGH && joint != HumanoidRig.RIGHT_LEG
                && joint != HumanoidRig.LEFT_THIGH && joint != HumanoidRig.LEFT_LEG) {
            return false;
        }
        GeometryDocument.Bone parent = bone.parent();
        return parent == null || HumanoidRig.jointFor(parent) != joint;
    }

    private static boolean isPrimaryVariant(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String compact = name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        boolean numbered = false;
        String previous;
        do {
            previous = compact;
            int end = compact.length();
            while (end > 0 && Character.isDigit(compact.charAt(end - 1))) {
                end--;
                numbered = true;
            }
            compact = compact.substring(0, end);
            if (compact.endsWith("default")) {
                compact = compact.substring(0, compact.length() - "default".length());
            }
        } while (!compact.equals(previous));
        return !numbered;
    }

    private static String normalizedName(String name) {
        String compact = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
        String previous;
        do {
            previous = compact;
            int end = compact.length();
            while (end > 0 && Character.isDigit(compact.charAt(end - 1))) {
                end--;
            }
            compact = compact.substring(0, end);
            if (compact.endsWith("default")) {
                compact = compact.substring(0, compact.length() - "default".length());
            }
        } while (!compact.equals(previous));
        return compact;
    }

    private static ArrayDeque<Visit> roots(GeometryDocument geometry) {
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        List<GeometryDocument.Bone> roots = geometry.roots();
        for (int index = roots.size() - 1; index >= 0; index--) {
            pending.push(new Visit(roots.get(index), new Matrix4f()));
        }
        return pending;
    }

    private static void pushChildren(ArrayDeque<Visit> pending,
                                     GeometryDocument.Bone bone, Matrix4f transform) {
        List<GeometryDocument.Bone> children = bone.children();
        for (int index = children.size() - 1; index >= 0; index--) {
            pending.push(new Visit(children.get(index), transform));
        }
    }

    private static Matrix4f bindTransform(Matrix4f parent, GeometryDocument.Bone bone) {
        return new Matrix4f(parent)
                .translate(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                .rotateZ(bone.rotationZ()).rotateY(bone.rotationY()).rotateX(bone.rotationX())
                .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
    }

    private static Vector3f transformedPivot(GeometryDocument.Bone bone, Matrix4f parent,
                                             float horizontalScale, float verticalScale) {
        Vector3f result = new Vector3f(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                .mulPosition(parent).mul(horizontalScale, verticalScale, horizontalScale);
        return finite(result) ? result : null;
    }

    private static void transform(Vector3f destination, Vector3f source, Matrix4f transform,
                                  float horizontalScale, float verticalScale) {
        destination.set(source).mulPosition(transform)
                .mul(horizontalScale, verticalScale, horizontalScale);
    }

    private static Vector3f topOf(Map<Integer, TopRing> rings, int joint) {
        TopRing ring = rings.get(joint);
        return ring == null ? null : ring.value();
    }

    private static boolean validScale(float value) {
        return Float.isFinite(value) && value > 0.0F;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x()) && Float.isFinite(value.y())
                && Float.isFinite(value.z());
    }

    private static void putPair(Map<Integer, Vector3f> target, int first, int second,
                                Vector3f pivot) {
        if (pivot == null) {
            return;
        }
        target.put(first, new Vector3f(pivot));
        target.put(second, new Vector3f(pivot));
    }

    private static void put(Map<Integer, Vector3f> target, int joint, Vector3f pivot) {
        if (pivot != null) {
            target.put(joint, new Vector3f(pivot));
        }
    }

    private static void putTool(Map<Integer, Vector3f> pivots,
                                Map<Integer, GeometryDocument.Bone> sources,
                                int joint, ToolAnchor anchor) {
        if (anchor != null) {
            pivots.put(joint, new Vector3f(anchor.point()));
            sources.put(joint, anchor.source());
        }
    }
}
