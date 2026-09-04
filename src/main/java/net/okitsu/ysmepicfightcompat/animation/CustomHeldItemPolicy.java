package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Detects item conditions for which an official YSM model authors its own held prop.
 *
 * <p>The decision is derived from model semantics instead of model ids or bone-name lists:
 * a hand condition is a replacement when it makes a normally hidden renderable bone subtree
 * visible, or when it hides that hand's authored item locator for the whole clip while animating
 * a renderable hand prop. The resulting hold selector stays active while using or swinging the
 * same item, while a replacement authored only by a use or swing clip suppresses Epic Fight only
 * for that action.</p>
 */
final class CustomHeldItemPolicy {
    private static final double HIDDEN_SCALE = 0.01D;

    private record Condition(InteractionHand hand, String selector,
                             AnimationConditionMatcher.ItemAction action) {
    }

    private record Rule(String selector, AnimationConditionMatcher.ItemAction action,
                        Set<String> roots) {
        private Rule {
            roots = Set.copyOf(roots);
        }
    }

    private record DetectedClip(String name, Condition condition, Set<String> roots) {
        private DetectedClip {
            roots = Set.copyOf(roots);
        }
    }

    private final Map<InteractionHand, List<Rule>> rules;
    private final Map<String, Set<String>> rootsByClip;
    private final Map<String, Set<String>> epicItemEffectRootsByClip;
    private final Set<String> fullBodyClips;

    private CustomHeldItemPolicy(Map<InteractionHand, List<Rule>> rules,
                                 Map<String, Set<String>> rootsByClip,
                                 Map<String, Set<String>> epicItemEffectRootsByClip,
                                 Set<String> fullBodyClips) {
        EnumMap<InteractionHand, List<Rule>> copy = new EnumMap<>(InteractionHand.class);
        rules.forEach((hand, values) -> copy.put(hand, List.copyOf(values)));
        this.rules = Map.copyOf(copy);
        this.rootsByClip = Map.copyOf(rootsByClip);
        this.epicItemEffectRootsByClip = Map.copyOf(epicItemEffectRootsByClip);
        this.fullBodyClips = Set.copyOf(fullBodyClips);
    }

    static CustomHeldItemPolicy create(GeometryDocument geometry,
                                       Map<String, AnimationClip> animations) {
        Map<String, GeometryDocument.Bone> geometryByName = new HashMap<>();
        geometry.bones().values().forEach(bone ->
                geometryByName.putIfAbsent(normalize(bone.name()), bone));
        Map<String, Boolean> hiddenByName = new HashMap<>();
        DefaultPoseProgram.calculateVisibility(geometry, animations).forEach((name, hidden) ->
                hiddenByName.putIfAbsent(normalize(name), hidden));

        EnumMap<InteractionHand, LinkedHashMap<String, Rule>> found =
                new EnumMap<>(InteractionHand.class);
        Map<String, Set<String>> rootsByClip = new HashMap<>();
        Map<String, Set<String>> epicItemEffectRootsByClip = new HashMap<>();
        Set<String> fullBodyClips = new LinkedHashSet<>();
        List<DetectedClip> detected = new ArrayList<>();
        for (AnimationClip clip : animations.values()) {
            Condition condition = condition(clip.name());
            if (condition == null || condition.selector().equals(":empty")) {
                continue;
            }
            Set<String> roots = replacementRoots(clip, condition.hand(), geometryByName,
                    hiddenByName);
            if (roots.isEmpty()) {
                continue;
            }
            detected.add(new DetectedClip(normalize(clip.name()), condition, roots));
        }

        Set<String> heldBowReplacements = new LinkedHashSet<>();
        for (DetectedClip clip : detected) {
            Condition condition = clip.condition();
            if (condition.action() == AnimationConditionMatcher.ItemAction.HOLD
                    && isBowSelector(condition.selector())) {
                heldBowReplacements.add(heldBowKey(condition));
            }
        }

        for (DetectedClip clip : detected) {
            Condition condition = clip.condition();
            // A bow-use clip that only reveals a hand-attached magic circle or other
            // effect is not a held-item replacement. Require that hand to have
            // a renderable hold replacement before suppressing Epic Fight's bow or
            // taking ownership of the complete body pose.
            if (isBowSelector(condition.selector())
                    && condition.action() != AnimationConditionMatcher.ItemAction.HOLD
                    && !heldBowReplacements.contains(heldBowKey(condition))) {
                if (condition.action() == AnimationConditionMatcher.ItemAction.USE) {
                    epicItemEffectRootsByClip.merge(clip.name(), clip.roots(),
                            CustomHeldItemPolicy::union);
                }
                continue;
            }
            String clipName = clip.name();
            Set<String> roots = clip.roots();
            rootsByClip.merge(clipName, roots, CustomHeldItemPolicy::union);
            if (isBowSelector(condition.selector())
                    && condition.action() != AnimationConditionMatcher.ItemAction.HOLD) {
                fullBodyClips.add(clipName);
            }
            LinkedHashMap<String, Rule> handRules = found.computeIfAbsent(
                    condition.hand(), ignored -> new LinkedHashMap<>());
            String key = ruleKey(condition);
            Rule existing = handRules.get(key);
            if (existing == null) {
                handRules.put(key, new Rule(condition.selector(), condition.action(), roots));
            } else {
                LinkedHashSet<String> combined = new LinkedHashSet<>(existing.roots());
                combined.addAll(roots);
                handRules.put(key, new Rule(existing.selector(), existing.action(), combined));
            }
        }

        EnumMap<InteractionHand, List<Rule>> result = new EnumMap<>(InteractionHand.class);
        found.forEach((hand, values) -> result.put(hand, new ArrayList<>(values.values())));
        return new CustomHeldItemPolicy(result, rootsByClip,
                epicItemEffectRootsByClip, fullBodyClips);
    }

    boolean replaces(LivingEntity entity, InteractionHand hand) {
        return !replacementRoots(entity, hand).isEmpty();
    }

    /** Whether this hand item has any authored replacement rule for an attack. */
    boolean replacesAttackItem(LivingEntity entity, InteractionHand hand) {
        if (entity == null || hand == null) {
            return false;
        }
        ItemStack stack = AnimationConditionMatcher.item(entity, hand);
        if (stack.isEmpty()) {
            return false;
        }
        return rules.getOrDefault(hand, List.of()).stream().anyMatch(rule ->
                AnimationConditionMatcher.matchesItem(entity, stack, rule.selector(),
                        rule.action(), hand));
    }

    /**
     * Whether the current item has model-authored geometry in its steady HOLD state.
     * USE/SWING-only effects do not turn an ordinary item switch into a held-model
     * replacement.
     */
    boolean replacesHeldItemAtRest(LivingEntity entity, InteractionHand hand) {
        return !heldItemReplacementRoots(entity, hand).isEmpty();
    }

    /** Renderable subtree roots for the matching steady HOLD replacement only. */
    Set<String> heldItemReplacementRoots(LivingEntity entity, InteractionHand hand) {
        if (entity == null || hand == null) {
            return Set.of();
        }
        ItemStack stack = AnimationConditionMatcher.item(entity, hand);
        if (stack.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rules.getOrDefault(hand, List.of()).stream()
                .filter(rule -> rule.action()
                        == AnimationConditionMatcher.ItemAction.HOLD)
                .filter(rule -> AnimationConditionMatcher.matchesItem(
                        entity, stack, rule.selector(), rule.action(), hand))
                .forEach(rule -> result.addAll(rule.roots()));
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    /**
     * Whether this model authors a steady held-item replacement for the supplied
     * item, independent of the entity's current hands and client display setting.
     *
     * <p>Projectile ownership is fixed when the projectile is created. Looking at
     * the live hand after that point is incorrect for thrown tridents and for bows
     * followed by an immediate item switch. The immutable HOLD rules already exclude
     * use-only effects such as a bow magic circle, so they are also the authoritative
     * distinction between a real held prop and a projectile-only model.</p>
     */
    boolean authorsHeldItemAtRest(LivingEntity entity, ItemStack stack) {
        if (entity == null || stack == null || stack.isEmpty()) {
            return false;
        }
        return authorsHeldItemAtRest((hand, selector) ->
                AnimationConditionMatcher.matchesItem(
                        entity, stack, selector,
                        AnimationConditionMatcher.ItemAction.HOLD, hand));
    }

    /** Structural HOLD-rule lookup shared by launch snapshots and focused tests. */
    boolean authorsHeldItemAtRest(
            BiPredicate<InteractionHand, String> selectorMatcher) {
        if (selectorMatcher == null) {
            return false;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            boolean authored = rules.getOrDefault(hand, List.of()).stream()
                    .filter(rule -> rule.action()
                            == AnimationConditionMatcher.ItemAction.HOLD)
                    .anyMatch(rule -> selectorMatcher.test(hand, rule.selector()));
            if (authored) {
                return true;
            }
        }
        return false;
    }

    /** Matches one automatic clip to the held item without depending on tick ordering. */
    boolean matchesClipItem(LivingEntity entity, String clipName) {
        Condition condition = condition(clipName);
        if (entity == null || condition == null) {
            return false;
        }
        ItemStack stack = AnimationConditionMatcher.item(entity, condition.hand());
        return !stack.isEmpty() && AnimationConditionMatcher.matchesItem(
                entity, stack, condition.selector(), condition.action(), condition.hand());
    }

    /** Renderable subtree roots authored for every currently matching replacement rule. */
    Set<String> replacementRoots(LivingEntity entity, InteractionHand hand) {
        if (entity == null || hand == null) {
            return Set.of();
        }
        ItemStack stack = AnimationConditionMatcher.item(entity, hand);
        if (stack.isEmpty()) {
            return Set.of();
        }
        boolean using = AnimationConditionMatcher.isUsing(entity, hand);
        boolean swinging = !using
                && AnimationConditionMatcher.swingSignal(entity, hand).active();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Rule rule : rules.getOrDefault(hand, List.of())) {
            if (rule.action() == AnimationConditionMatcher.ItemAction.USE && !using
                    || rule.action() == AnimationConditionMatcher.ItemAction.SWING
                    && !swinging) {
                continue;
            }
            if (AnimationConditionMatcher.matchesItem(entity, stack, rule.selector(),
                    rule.action(), hand)) {
                result.addAll(rule.roots());
            }
        }
        return Set.copyOf(result);
    }

    Set<String> selectors(InteractionHand hand) {
        Set<String> result = new LinkedHashSet<>();
        rules.getOrDefault(hand, List.of()).forEach(rule -> result.add(rule.selector()));
        return Set.copyOf(result);
    }

    Set<String> selectors(InteractionHand hand,
                          AnimationConditionMatcher.ItemAction action) {
        Set<String> result = new LinkedHashSet<>();
        rules.getOrDefault(hand, List.of()).stream()
                .filter(rule -> rule.action() == action)
                .forEach(rule -> result.add(rule.selector()));
        return Set.copyOf(result);
    }

    Set<String> replacementRoots(InteractionHand hand,
                                 AnimationConditionMatcher.ItemAction action,
                                 String selector) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rules.getOrDefault(hand, List.of()).stream()
                .filter(rule -> rule.action() == action && rule.selector().equals(selector))
                .forEach(rule -> result.addAll(rule.roots()));
        return Set.copyOf(result);
    }

    /** Renderable YSM prop roots whose authored parent chain belongs to this clip. */
    Set<String> replacementRoots(String clipName) {
        return rootsByClip.getOrDefault(normalize(clipName), Set.of());
    }

    /** Bow-use geometry that augments Epic Fight's retained item instead of replacing it. */
    Set<String> epicItemEffectRoots(String clipName) {
        return epicItemEffectRootsByClip.getOrDefault(normalize(clipName), Set.of());
    }

    /** Bow draw/release clips own their authored upper-body pose as well as the prop. */
    boolean replacesBodyPose(String clipName) {
        return fullBodyClips.contains(normalize(clipName));
    }

    /** Whether an authored bow use/swing clip currently owns the complete body. */
    boolean replacesBodyPose(LivingEntity entity) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (replacesBodyPose(entity, hand)) {
                return true;
            }
        }
        return false;
    }

    boolean replacesBodyPose(LivingEntity entity, InteractionHand hand) {
        if (entity == null) {
            return false;
        }
        for (String clipName : fullBodyClips) {
            Condition condition = condition(clipName);
            if (condition == null || condition.hand() != hand) {
                continue;
            }
            boolean active = switch (condition.action()) {
                case USE -> AnimationConditionMatcher.isUsing(entity, condition.hand());
                case SWING -> AnimationConditionMatcher.isSwinging(entity, condition.hand());
                case HOLD -> false;
            };
            if (active && AnimationConditionMatcher.matchesItem(entity,
                    AnimationConditionMatcher.item(entity, condition.hand()),
                    condition.selector(), condition.action(), condition.hand())) {
                return true;
            }
        }
        return false;
    }

    /** Logical hand that owns a detected automatic replacement clip. */
    InteractionHand replacementHand(String clipName) {
        Condition condition = condition(clipName);
        return condition == null ? null : condition.hand();
    }

    /** Whether this is the ordinary hold clip for the supplied logical hand. */
    boolean isHoldClipForHand(String clipName, InteractionHand hand) {
        Condition condition = condition(clipName);
        return condition != null && condition.hand() == hand
                && condition.action() == AnimationConditionMatcher.ItemAction.HOLD;
    }

    /** Automatic action family authored by a detected replacement clip. */
    AnimationConditionMatcher.ItemAction clipAction(String clipName) {
        Condition condition = condition(clipName);
        return condition == null ? null : condition.action();
    }

    /** Every renderable prop root that can replace an item in this model. */
    Set<String> allReplacementRoots() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rootsByClip.values().forEach(result::addAll);
        return Set.copyOf(result);
    }

    /** Every renderable replacement root authored for one logical hand. */
    Set<String> allReplacementRoots(InteractionHand hand) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rules.getOrDefault(hand, List.of()).forEach(rule -> result.addAll(rule.roots()));
        return Set.copyOf(result);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static boolean isBowSelector(String selector) {
        return ":bow".equals(selector) || "$minecraft:bow".equals(selector);
    }

    private static String heldBowKey(Condition condition) {
        // :bow and $minecraft:bow are equivalent for this policy and can legitimately
        // be mixed between a model's hold and use/swing clip declarations.
        return condition.hand().name();
    }

    private static Set<String> replacementRoots(
            AnimationClip clip, InteractionHand hand,
            Map<String, GeometryDocument.Bone> geometryByName,
            Map<String, Boolean> hiddenByName) {
        int toolJoint = hand == InteractionHand.MAIN_HAND
                ? HumanoidRig.RIGHT_TOOL : HumanoidRig.LEFT_TOOL;
        int handJoint = hand == InteractionHand.MAIN_HAND
                ? HumanoidRig.RIGHT_HAND : HumanoidRig.LEFT_HAND;
        boolean hidesLocatorForWholeClip = false;
        LinkedHashSet<GeometryDocument.Bone> shownHiddenRoots = new LinkedHashSet<>();
        LinkedHashSet<GeometryDocument.Bone> animatedHandRoots = new LinkedHashSet<>();
        for (Map.Entry<String, AnimationClip.BoneTracks> entry
                : clip.boneTracks().entrySet()) {
            GeometryDocument.Bone bone = geometryByName.get(normalize(entry.getKey()));
            if (bone == null) {
                continue;
            }
            AnimationClip.BoneTracks tracks = entry.getValue();
            int anchor = HumanoidRig.jointFor(bone);
            if (tracks.scale() != null) {
                if (HumanoidRig.isMajorBone(bone)
                        && HumanoidRig.jointFor(bone) == toolJoint
                        && alwaysHidden(tracks.scale())) {
                    hidesLocatorForWholeClip = true;
                }
                if (!HumanoidRig.isMajorBone(bone)
                        && (anchor == handJoint || anchor == toolJoint)
                        && hasRenderableSubtree(bone) && mayShow(tracks.scale())
                        && (hiddenByName.getOrDefault(normalize(bone.name()), false)
                        || mayHide(tracks.scale()))) {
                    shownHiddenRoots.add(bone);
                }
            }
            if (!HumanoidRig.isMajorBone(bone)
                    && (anchor == handJoint || anchor == toolJoint)
                    && hasRenderableSubtree(bone) && tracks.hasAnyTrack()) {
                animatedHandRoots.add(bone);
            }
        }
        if (shownHiddenRoots.isEmpty() && hidesLocatorForWholeClip) {
            shownHiddenRoots.addAll(animatedHandRoots);
        }
        if (shownHiddenRoots.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (GeometryDocument.Bone candidate : shownHiddenRoots) {
            boolean nested = false;
            for (GeometryDocument.Bone parent = candidate.parent(); parent != null;
                 parent = parent.parent()) {
                if (shownHiddenRoots.contains(parent)) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                result.add(normalize(candidate.name()));
            }
        }
        return Set.copyOf(result);
    }

    private static boolean alwaysHidden(AnimationClip.Track track) {
        boolean sampled = false;
        for (AnimationClip.Keyframe keyframe : track.keyframes()) {
            sampled = true;
            if (!definitelyHidden(keyframe.value())
                    || keyframe.incomingValue() != null
                    && !definitelyHidden(keyframe.incomingValue())) {
                return false;
            }
        }
        return sampled;
    }

    private static boolean mayShow(AnimationClip.Track track) {
        for (AnimationClip.Keyframe keyframe : track.keyframes()) {
            if (potentiallyVisible(keyframe.value())
                    || potentiallyVisible(keyframe.incomingValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayHide(AnimationClip.Track track) {
        for (AnimationClip.Keyframe keyframe : track.keyframes()) {
            if (definitelyHidden(keyframe.value())
                    || definitelyHidden(keyframe.incomingValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean definitelyHidden(AnimationClip.VectorValue value) {
        if (value == null) {
            return false;
        }
        for (int axis = 0; axis < 3; axis++) {
            if (value.expression(axis) != null
                    || Math.abs(value.constant(axis)) >= HIDDEN_SCALE) {
                return false;
            }
        }
        return true;
    }

    private static boolean potentiallyVisible(AnimationClip.VectorValue value) {
        if (value == null) {
            return false;
        }
        for (int axis = 0; axis < 3; axis++) {
            if (value.expression(axis) == null
                    && Math.abs(value.constant(axis)) < HIDDEN_SCALE) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasRenderableSubtree(GeometryDocument.Bone root) {
        ArrayDeque<GeometryDocument.Bone> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            GeometryDocument.Bone bone = pending.pop();
            if (!bone.faces().isEmpty()) {
                return true;
            }
            bone.children().forEach(pending::push);
        }
        return false;
    }

    private static Condition condition(String sourceName) {
        String name = normalize(sourceName);
        Condition result = condition(name, "hold_mainhand", InteractionHand.MAIN_HAND);
        if (result == null) {
            result = condition(name, "hold_offhand", InteractionHand.OFF_HAND);
        }
        if (result == null) {
            result = condition(name, "use_mainhand", InteractionHand.MAIN_HAND);
        }
        if (result == null) {
            result = condition(name, "use_offhand", InteractionHand.OFF_HAND);
        }
        if (result == null) {
            result = condition(name, "swing_offhand", InteractionHand.OFF_HAND);
        }
        if (result == null) {
            result = condition(name, "swing", InteractionHand.MAIN_HAND);
        }
        if (result == null && name.equals("swing_hand")) {
            result = new Condition(InteractionHand.MAIN_HAND, ":default",
                    AnimationConditionMatcher.ItemAction.SWING);
        }
        return result;
    }

    private static Condition condition(String name, String root, InteractionHand hand) {
        if (name.equals(root)) {
            return new Condition(hand, ":default", action(root));
        }
        if (!name.startsWith(root) || name.length() <= root.length()) {
            return null;
        }
        char separator = name.charAt(root.length());
        return separator == ':' || separator == '$' || separator == '#'
                ? new Condition(hand, name.substring(root.length()), action(root)) : null;
    }

    private static AnimationConditionMatcher.ItemAction action(String root) {
        if (root.startsWith("use_")) {
            return AnimationConditionMatcher.ItemAction.USE;
        }
        if (root.startsWith("swing")) {
            return AnimationConditionMatcher.ItemAction.SWING;
        }
        return AnimationConditionMatcher.ItemAction.HOLD;
    }

    private static String ruleKey(Condition condition) {
        return condition.action().name() + ':' + condition.selector();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
