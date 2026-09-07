package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, geometry-scoped ownership of private YSM skins. Bone aliases still describe
 * their authored roles; an alias alone does not grant Epic Fight ownership of an alternate
 * skeleton. No entity, visibility, animation, or selected-locator state belongs in this plan.
 */
public final class RigBindingPlan {
    static final int MAX_AUTOMATIC_BONES = AuxiliaryBoneLayout.MAX_MODEL_BONES;
    private static final int MAX_ANALYSIS_STEPS = 1_000_000;

    public enum PoseDomain {
        EPIC_RETARGETED,
        AUTHORED_SUBTREE
    }

    private record Binding(PoseDomain domain, @Nullable GeometryDocument.Bone root,
                           int epicAnchor) {
    }

    private record Body(GeometryDocument.Bone root, List<GeometryDocument.Bone> bones,
                        boolean humanoidHands, boolean armHeldHand,
                        boolean headHeldTools) {
    }

    private static final class Analysis {
        private int remaining = MAX_ANALYSIS_STEPS;

        private void step() {
            if (remaining-- <= 0) {
                throw new AnalysisLimitException();
            }
        }
    }

    private static final class AnalysisLimitException extends RuntimeException {
        private AnalysisLimitException() {
            super(null, null, false, false);
        }
    }

    private static final int[] SKELETON_ROLES = {
            HumanoidRig.HEAD, HumanoidRig.LEFT_ARM, HumanoidRig.RIGHT_ARM,
            HumanoidRig.LEFT_THIGH, HumanoidRig.RIGHT_THIGH
    };

    private final Map<GeometryDocument.Bone, Binding> bindings;
    private final Set<GeometryDocument.Bone> authoredInfluences;
    private final Set<GeometryDocument.Bone> epicPoseControls;
    private final Map<Integer, GeometryDocument.Bone> primaryControls;
    private final boolean authoredBranches;

    private RigBindingPlan(Map<GeometryDocument.Bone, Binding> bindings,
                           Set<GeometryDocument.Bone> authoredInfluences,
                           Set<GeometryDocument.Bone> epicPoseControls,
                           Map<Integer, GeometryDocument.Bone> primaryControls,
                           boolean authoredBranches) {
        this.bindings = immutableIdentityMap(bindings);
        this.authoredInfluences = immutableIdentitySet(authoredInfluences);
        this.epicPoseControls = immutableIdentitySet(epicPoseControls);
        this.primaryControls = Map.copyOf(primaryControls);
        this.authoredBranches = authoredBranches;
    }

    public static RigBindingPlan create(GeometryDocument geometry) {
        return create(geometry, Map.of());
    }

    /**
     * Optional semantic profile seam for otherwise ambiguous rigs. Overrides name exact
     * geometry nodes, apply to their descendants, and never modify the supplied geometry.
     * A nearer override wins; malformed or foreign entries are rejected.
     */
    public static RigBindingPlan create(
            GeometryDocument geometry,
            Map<GeometryDocument.Bone, PoseDomain> explicitRoots) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(explicitRoots, "explicitRoots");
        List<GeometryDocument.Bone> bones = List.copyOf(geometry.bones().values());
        Set<GeometryDocument.Bone> known = identitySet();
        known.addAll(bones);
        explicitRoots.forEach((bone, domain) -> {
            if (bone == null || domain == null || !known.contains(bone)) {
                throw new IllegalArgumentException("Rig overrides must name existing geometry bones");
            }
        });
        if (bones.size() > MAX_AUTOMATIC_BONES) {
            if (!explicitRoots.isEmpty()) {
                throw new IllegalArgumentException("Explicit rig profiles exceed the private pose capacity");
            }
            // Conversion historically caps private skins instead of rejecting the model.
            // Preserve that fallback; do not analyze a potentially 65,536-bone transfer.
            return legacyPlan(bones);
        }
        int limit = bones.size();
        List<Body> bodies = List.of();
        Map<GeometryDocument.Bone, PoseDomain> roots = new IdentityHashMap<>();
        try {
            Analysis analysis = new Analysis();
            bodies = bodies(bones, limit, analysis);
            boolean hasAnatomicalControls = bones.stream().anyMatch(RigBindingPlan::isAnatomicalControl);
            if (!hasAnatomicalControls) {
                // With no humanoid anatomy there is nothing to retarget. Root/Center,
                // held-item attachments, and accessory aliases alone are not a skeleton.
                for (GeometryDocument.Bone bone : bones) {
                    int rootJoint = HumanoidRig.directJointFor(bone);
                    if ((bone.parent() == null || !known.contains(bone.parent()))
                            && (rootJoint < 0 || rootJoint == HumanoidRig.ROOT)) {
                        // A standalone Hand/Tool is an explicit attachment island,
                        // common in sparse humanoid/first-person models. Its missing
                        // torso is not evidence that the hand should stop retargeting.
                        roots.put(bone, PoseDomain.AUTHORED_SUBTREE);
                    }
                }
            } else {
                for (Body body : bodies) {
                    if (body.headHeldTools() && !body.armHeldHand()) {
                        roots.put(unsharedRoot(body, bones, limit, analysis), PoseDomain.AUTHORED_SUBTREE);
                    }
                }
            }
        } catch (AnalysisLimitException ignored) {
            // Adversarial deep/nested controls must not make resource loading cubic.
            // Exhaustion revokes all automatic conclusions, never just the later ones.
            roots.clear();
            bodies = List.of();
        }
        roots.keySet().removeIf(root -> nearestRoot(root, explicitRoots, limit) != null);
        roots.putAll(explicitRoots);
        Map<GeometryDocument.Bone, Binding> bindings = new IdentityHashMap<>();
        Set<GeometryDocument.Bone> influences = identitySet();
        boolean authored = false;
        for (GeometryDocument.Bone bone : bones) {
            GeometryDocument.Bone domainRoot = nearestRoot(bone, roots, limit);
            PoseDomain domain = domainRoot == null
                    ? PoseDomain.EPIC_RETARGETED : roots.get(domainRoot);
            int anchor = domain == PoseDomain.AUTHORED_SUBTREE
                    ? externalAnchor(domainRoot, limit) : legacyAnchor(bone, limit);
            bindings.put(bone, new Binding(domain, domainRoot, anchor));
            if (domain == PoseDomain.AUTHORED_SUBTREE) {
                authored = true;
                addAncestors(bone, influences, limit);
            }
        }
        Set<GeometryDocument.Bone> epicControls = identitySet();
        for (GeometryDocument.Bone bone : bones) {
            if (bindings.get(bone).domain() == PoseDomain.EPIC_RETARGETED
                    && HumanoidRig.isMajorBone(bone)) {
                addAncestors(bone, epicControls, limit);
            }
        }
        epicControls.removeIf(bone -> bindings.containsKey(bone)
                && bindings.get(bone).domain() == PoseDomain.AUTHORED_SUBTREE);
        return new RigBindingPlan(bindings, influences, epicControls,
                primaryControls(bodies, bindings), authored);
    }

    public PoseDomain domainFor(GeometryDocument.Bone bone) {
        Binding binding = bindings.get(bone);
        return binding == null ? PoseDomain.EPIC_RETARGETED : binding.domain();
    }

    public boolean usesAuthoredPose(GeometryDocument.Bone bone) {
        return domainFor(bone) == PoseDomain.AUTHORED_SUBTREE;
    }

    /** Includes shared ancestors whose sampled local transforms feed an authored subtree. */
    public boolean influencesAuthoredPose(GeometryDocument.Bone bone) {
        return authoredInfluences.contains(bone);
    }

    public boolean hasAuthoredBranches() {
        return authoredBranches;
    }

    /** Null denotes the implicit legacy domain, rather than a declared subtree root. */
    @Nullable
    public GeometryDocument.Bone domainRoot(GeometryDocument.Bone bone) {
        Binding binding = bindings.get(bone);
        return binding == null ? null : binding.root();
    }

    /**
     * Authored skins share one external attachment anchor, or -1 for model space. The
     * latter is not an Epic Fight pose-array index. Legacy skins retain their normal joint.
     */
    public int epicAnchorFor(GeometryDocument.Bone bone) {
        Binding binding = bindings.get(bone);
        return binding == null ? HumanoidRig.ROOT : binding.epicAnchor();
    }

    public boolean isEpicPoseControl(GeometryDocument.Bone bone) {
        return epicPoseControls.contains(bone);
    }

    /** A control from an unambiguous complete humanoid island, never an authored animal. */
    @Nullable
    public GeometryDocument.Bone primaryControl(int joint) {
        return primaryControls.get(joint);
    }

    private static boolean isAnatomicalControl(GeometryDocument.Bone bone) {
        if (!HumanoidRig.isMajorBone(bone)) {
            return false;
        }
        return switch (HumanoidRig.directJointFor(bone)) {
            case HumanoidRig.ROOT, HumanoidRig.LEFT_TOOL, HumanoidRig.RIGHT_TOOL -> false;
            case HumanoidRig.LEFT_HAND, HumanoidRig.RIGHT_HAND -> HumanoidRig.isForearmControl(bone);
            default -> true;
        };
    }

    private static List<Body> bodies(List<GeometryDocument.Bone> bones, int limit, Analysis analysis) {
        Set<GeometryDocument.Bone> possible = identitySet();
        for (GeometryDocument.Bone bone : bones) {
            analysis.step();
            if (HumanoidRig.isMajorBone(bone)
                    && HumanoidRig.directJointFor(bone) == HumanoidRig.TORSO
                    && hasSkeleton(bone, descendants(bone, bones, limit, analysis), limit, analysis)) {
                possible.add(bone);
            }
        }
        Map<GeometryDocument.Bone, List<GeometryDocument.Bone>> ownedByRoot = new IdentityHashMap<>();
        for (GeometryDocument.Bone bone : bones) {
            GeometryDocument.Bone owner = nearestAncestor(bone, possible, limit, analysis);
            if (owner != null) {
                ownedByRoot.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(bone);
            }
        }
        List<Body> result = new ArrayList<>();
        for (GeometryDocument.Bone root : bones) {
            analysis.step();
            if (!possible.contains(root)) {
                continue;
            }
            List<GeometryDocument.Bone> owned = ownedByRoot.getOrDefault(root, List.of());
            if (!hasSkeleton(root, owned, limit, analysis)) {
                continue;
            }
            boolean leftArmHand = hasArmHand(root, owned, true, limit, analysis);
            boolean rightArmHand = hasArmHand(root, owned, false, limit, analysis);
            boolean headHeldTools = hasHeadTool(root, owned, true, limit, analysis)
                    && hasHeadTool(root, owned, false, limit, analysis);
            result.add(new Body(root, List.copyOf(owned), leftArmHand && rightArmHand,
                    leftArmHand || rightArmHand, headHeldTools));
        }
        return List.copyOf(result);
    }

    private static boolean hasSkeleton(GeometryDocument.Bone root,
                                       List<GeometryDocument.Bone> bones, int limit, Analysis analysis) {
        for (int role : SKELETON_ROLES) {
            boolean found = false;
            for (GeometryDocument.Bone bone : bones) {
                analysis.step();
                if (HumanoidRig.directJointFor(bone) == role
                        && onCentralBranch(bone, root, role, limit, analysis)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean onCentralBranch(GeometryDocument.Bone bone,
                                           GeometryDocument.Bone root, int role, int limit,
                                           Analysis analysis) {
        GeometryDocument.Bone parent = bone.parent();
        for (int remaining = limit; parent != null && remaining-- > 0;
             parent = parent.parent()) {
            analysis.step();
            if (parent == root) {
                return true;
            }
            int joint = HumanoidRig.directJointFor(parent);
            if (joint >= 0 && joint != HumanoidRig.ROOT && joint != HumanoidRig.TORSO
                    && joint != HumanoidRig.CHEST && joint != role) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasArmHand(GeometryDocument.Bone root,
                                       List<GeometryDocument.Bone> bones,
                                       boolean left, int limit, Analysis analysis) {
        int hand = left ? HumanoidRig.LEFT_HAND : HumanoidRig.RIGHT_HAND;
        int tool = left ? HumanoidRig.LEFT_TOOL : HumanoidRig.RIGHT_TOOL;
        int arm = left ? HumanoidRig.LEFT_ARM : HumanoidRig.RIGHT_ARM;
        for (GeometryDocument.Bone bone : bones) {
            analysis.step();
            int role = HumanoidRig.directJointFor(bone);
            if (role != hand && role != tool) {
                continue;
            }
            GeometryDocument.Bone parent = bone.parent();
            for (int remaining = limit; parent != null && parent != root && remaining-- > 0;
                 parent = parent.parent()) {
                analysis.step();
                int joint = HumanoidRig.directJointFor(parent);
                if (joint == arm) {
                    return true;
                }
                if (joint >= 0 && joint != hand && joint != tool) {
                    break;
                }
            }
        }
        return false;
    }

    private static boolean hasHeadTool(GeometryDocument.Bone root,
                                        List<GeometryDocument.Bone> bones,
                                        boolean left, int limit, Analysis analysis) {
        int hand = left ? HumanoidRig.LEFT_HAND : HumanoidRig.RIGHT_HAND;
        int tool = left ? HumanoidRig.LEFT_TOOL : HumanoidRig.RIGHT_TOOL;
        for (GeometryDocument.Bone bone : bones) {
            analysis.step();
            if (HumanoidRig.directJointFor(bone) != tool) {
                continue;
            }
            boolean hasHand = false;
            GeometryDocument.Bone parent = bone.parent();
            for (int remaining = limit; parent != null && parent != root && remaining-- > 0;
                 parent = parent.parent()) {
                analysis.step();
                int joint = HumanoidRig.directJointFor(parent);
                if (joint == HumanoidRig.HEAD) {
                    if (hasHand) {
                        return true;
                    }
                    break;
                }
                if (joint == hand) {
                    hasHand = true;
                } else if (joint >= 0 && joint != tool) {
                    break;
                }
            }
        }
        return false;
    }

    private static GeometryDocument.Bone unsharedRoot(
            Body body, List<GeometryDocument.Bone> bones, int limit, Analysis analysis) {
        GeometryDocument.Bone root = body.root();
        GeometryDocument.Bone parent = root.parent();
        for (int remaining = limit; parent != null && remaining-- > 0;
             parent = parent.parent()) {
            analysis.step();
            int joint = HumanoidRig.directJointFor(parent);
            if (joint >= 0 && joint != HumanoidRig.ROOT) {
                break;
            }
            boolean shared = false;
            for (GeometryDocument.Bone bone : bones) {
                analysis.step();
                if (bone != parent && HumanoidRig.isMajorBone(bone)
                        && HumanoidRig.directJointFor(bone) != HumanoidRig.ROOT
                        && isDescendant(bone, parent, limit, analysis)
                        && !isDescendant(bone, root, limit, analysis)) {
                    shared = true;
                    break;
                }
            }
            if (shared) {
                break;
            }
            root = parent;
        }
        return root;
    }

    private static Map<Integer, GeometryDocument.Bone> primaryControls(
            List<Body> bodies, Map<GeometryDocument.Bone, Binding> bindings) {
        List<Body> candidates = bodies.stream().filter(Body::humanoidHands)
                .filter(body -> bindings.get(body.root()).domain() == PoseDomain.EPIC_RETARGETED)
                .toList();
        if (candidates.size() > 1) {
            List<Body> primary = candidates.stream()
                    .filter(body -> ModelJointPivots.isPrimaryVariant(body.root().name())).toList();
            if (primary.size() != 1) {
                return Map.of();
            }
            candidates = primary;
        }
        if (candidates.size() != 1) {
            return Map.of();
        }
        Map<Integer, GeometryDocument.Bone> result = new HashMap<>();
        for (GeometryDocument.Bone bone : candidates.get(0).bones()) {
            int joint = HumanoidRig.directJointFor(bone);
            if (joint < 0 || !HumanoidRig.isMajorBone(bone)
                    || bindings.get(bone).domain() != PoseDomain.EPIC_RETARGETED) {
                continue;
            }
            GeometryDocument.Bone previous = result.get(joint);
            if (previous == null || !ModelJointPivots.isPrimaryVariant(previous.name())
                    && ModelJointPivots.isPrimaryVariant(bone.name())) {
                result.put(joint, bone);
            }
        }
        return result;
    }

    private static List<GeometryDocument.Bone> descendants(
            GeometryDocument.Bone root, List<GeometryDocument.Bone> bones, int limit, Analysis analysis) {
        return bones.stream().filter(bone -> isDescendant(bone, root, limit, analysis)).toList();
    }

    private static boolean isDescendant(GeometryDocument.Bone bone,
                                         GeometryDocument.Bone root, int limit, Analysis analysis) {
        GeometryDocument.Bone current = bone;
        for (int remaining = limit; current != null && remaining-- > 0;
             current = current.parent()) {
            analysis.step();
            if (current == root) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static GeometryDocument.Bone nearestRoot(
            GeometryDocument.Bone bone, Map<GeometryDocument.Bone, PoseDomain> roots, int limit) {
        return nearestAncestor(bone, roots.keySet(), limit);
    }

    @Nullable
    private static GeometryDocument.Bone nearestAncestor(
            GeometryDocument.Bone bone, Set<GeometryDocument.Bone> candidates, int limit) {
        return nearestAncestor(bone, candidates, limit, null);
    }

    @Nullable
    private static GeometryDocument.Bone nearestAncestor(
            GeometryDocument.Bone bone, Set<GeometryDocument.Bone> candidates, int limit,
            @Nullable Analysis analysis) {
        GeometryDocument.Bone current = bone;
        for (int remaining = limit; current != null && remaining-- > 0;
             current = current.parent()) {
            if (analysis != null) {
                analysis.step();
            }
            if (candidates.contains(current)) {
                return current;
            }
        }
        return null;
    }

    private static int externalAnchor(GeometryDocument.Bone root, int limit) {
        GeometryDocument.Bone parent = root.parent();
        for (int remaining = limit; parent != null && remaining-- > 0;
             parent = parent.parent()) {
            int joint = HumanoidRig.directJointFor(parent);
            if (joint > HumanoidRig.ROOT) {
                return joint;
            }
        }
        return -1;
    }

    private static int legacyAnchor(GeometryDocument.Bone bone, int limit) {
        GeometryDocument.Bone current = bone;
        for (int remaining = limit; current != null && remaining-- > 0;
             current = current.parent()) {
            int joint = HumanoidRig.directJointFor(current);
            if (joint >= 0) {
                return joint;
            }
        }
        return HumanoidRig.ROOT;
    }

    private static void addAncestors(GeometryDocument.Bone bone,
                                     Set<GeometryDocument.Bone> target, int limit) {
        GeometryDocument.Bone current = bone;
        for (int remaining = limit; current != null && remaining-- > 0;
             current = current.parent()) {
            if (!target.add(current)) {
                break;
            }
        }
    }

    private static RigBindingPlan legacyPlan(List<GeometryDocument.Bone> bones) {
        Map<GeometryDocument.Bone, Integer> anchors = new IdentityHashMap<>();
        Map<GeometryDocument.Bone, Binding> bindings = new IdentityHashMap<>();
        Set<GeometryDocument.Bone> controls = identitySet();
        for (GeometryDocument.Bone bone : bones) {
            if (!anchors.containsKey(bone)) {
                List<GeometryDocument.Bone> path = new ArrayList<>();
                Set<GeometryDocument.Bone> seen = identitySet();
                GeometryDocument.Bone current = bone;
                int anchor = HumanoidRig.ROOT;
                for (int remaining = bones.size(); current != null && remaining-- > 0;
                     current = current.parent()) {
                    Integer cached = anchors.get(current);
                    if (cached != null) {
                        anchor = cached;
                        break;
                    }
                    if (!seen.add(current)) {
                        break;
                    }
                    path.add(current);
                    int direct = HumanoidRig.directJointFor(current);
                    if (direct >= 0) {
                        anchor = direct;
                        break;
                    }
                }
                for (GeometryDocument.Bone entry : path) {
                    anchors.put(entry, anchor);
                }
            }
            bindings.put(bone, new Binding(PoseDomain.EPIC_RETARGETED, null,
                    anchors.getOrDefault(bone, HumanoidRig.ROOT)));
            if (HumanoidRig.isMajorBone(bone)) {
                addAncestors(bone, controls, bones.size());
            }
        }
        return new RigBindingPlan(bindings, identitySet(), controls, Map.of(), false);
    }

    private static <V> Map<GeometryDocument.Bone, V> immutableIdentityMap(
            Map<GeometryDocument.Bone, V> source) {
        return Collections.unmodifiableMap(new IdentityHashMap<>(source));
    }

    private static Set<GeometryDocument.Bone> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<GeometryDocument.Bone> immutableIdentitySet(
            Set<GeometryDocument.Bone> source) {
        Set<GeometryDocument.Bone> result = identitySet();
        result.addAll(source);
        return Collections.unmodifiableSet(result);
    }
}
