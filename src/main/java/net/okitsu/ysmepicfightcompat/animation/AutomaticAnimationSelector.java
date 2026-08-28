package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.okitsu.ysmepicfightcompat.render.SubEntityRenderPolicy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Selects official YSM state, held-item, equipment, and riding clips. */
final class AutomaticAnimationSelector {
    private static final double MIN_LATCH_SECONDS = 0.05D;

    record ClipInfo(float duration) {
    }

    record ActiveClip(String name, double elapsed, boolean restarted) {
    }

    record Selection(List<ActiveClip> clips, ActiveClip main,
                     MovementAnimationType movement,
                     Set<InteractionHand> heldItemChanges) {
        Selection {
            clips = List.copyOf(clips);
            heldItemChanges = Set.copyOf(heldItemChanges);
        }
    }

    private record MainState(String clip, MovementAnimationType movement) {
    }

    static final class State {
        private final Map<String, Channel> channels = new HashMap<>();
        private final Map<InteractionHand, SwingObservation> swingObservations =
                new EnumMap<>(InteractionHand.class);
        private final Map<InteractionHand, ItemStack> heldItemObservations =
                new EnumMap<>(InteractionHand.class);
        private final Map<InteractionHand, Long> heldItemSequences =
                new EnumMap<>(InteractionHand.class);
        private final Set<InteractionHand> heldItemChanges =
                java.util.EnumSet.noneOf(InteractionHand.class);
        private int lastHurtTime;
        private double attackedStartedAt = -1.0D;
        private long nextSwingSequence;
        private long nextHeldItemSequence;

        void reset() {
            channels.clear();
            swingObservations.clear();
            heldItemObservations.clear();
            heldItemSequences.clear();
            heldItemChanges.clear();
            lastHurtTime = 0;
            attackedStartedAt = -1.0D;
            nextSwingSequence = 0L;
            nextHeldItemSequence = 0L;
        }

        String heldItemToken(InteractionHand hand, ItemStack stack) {
            ItemStack current = stack == null ? ItemStack.EMPTY : stack;
            return AnimationConditionMatcher.itemToken(current) + '@'
                    + heldItemSequence(hand, current);
        }

        long heldItemSequence(InteractionHand hand, ItemStack stack) {
            ItemStack current = stack == null ? ItemStack.EMPTY : stack;
            ItemStack previous = heldItemObservations.getOrDefault(
                    hand, ItemStack.EMPTY);
            if (!officialHeldItemMatches(previous, current)) {
                // Official YSM retains the live ItemStack reference. This deliberately
                // avoids treating in-place count/NBT/damage mutations as a new switch.
                heldItemObservations.put(hand, current);
                heldItemSequences.put(hand, ++nextHeldItemSequence);
                heldItemChanges.add(hand);
            }
            return heldItemSequences.getOrDefault(hand, 0L);
        }

        String swingToken(InteractionHand hand,
                          AnimationConditionMatcher.SwingSignal signal) {
            if (!signal.active()) {
                swingObservations.remove(hand);
                return "";
            }
            SwingObservation previous = swingObservations.get(hand);
            boolean restarted = previous == null
                    || !previous.source().equals(signal.source())
                    || signal.elapsed() + 1.0E-4F < previous.elapsed();
            long sequence = restarted ? ++nextSwingSequence : previous.sequence();
            swingObservations.put(hand, new SwingObservation(
                    signal.source(), signal.elapsed(), sequence));
            return signal.source() + '@' + sequence;
        }
    }

    private record Channel(String name, String token, double startedAt) {
    }

    private record SwingObservation(String source, float elapsed, long sequence) {
    }

    /** Mirrors official YSM's held-item animation provider comparison. */
    static boolean officialHeldItemMatches(ItemStack previous, ItemStack current) {
        ItemStack cached = previous == null ? ItemStack.EMPTY : previous;
        ItemStack candidate = current == null ? ItemStack.EMPTY : current;
        return cached.isDamaged()
                ? officialHeldItemMatches(true,
                ItemStack.isSameItem(candidate, cached), false)
                : officialHeldItemMatches(false, false,
                ItemStack.matches(candidate, cached));
    }

    static boolean officialHeldItemMatches(boolean cachedDamaged,
                                           boolean sameItem,
                                           boolean fullStackMatch) {
        return cachedDamaged ? sameItem : fullStackMatch;
    }

    private final Map<String, ClipInfo> clips;
    private final List<String> orderedNames;

    AutomaticAnimationSelector(Map<String, ClipInfo> clips) {
        this.clips = Map.copyOf(clips);
        orderedNames = clips.keySet().stream().sorted().toList();
    }

    Selection select(LivingEntity entity, double now, State state) {
        return select(entity, now, state, null);
    }

    Selection select(LivingEntity entity, double now, State state,
                     MovementAnimationType synchronizedMovement) {
        boolean ysmVehicle = !entity.isPassenger()
                || SubEntityRenderPolicy.usesYsmVehicleForRider(entity);
        return select(entity, now, state, synchronizedMovement, ysmVehicle);
    }

    Selection select(LivingEntity entity, double now, State state,
                     MovementAnimationType synchronizedMovement,
                     boolean ysmVehicle) {
        boolean ysmMountedAnimations = usesYsmMountedAnimations(
                entity.isPassenger(), ysmVehicle);
        observeAttacked(entity, now, state);
        state.heldItemChanges.clear();

        List<ActiveClip> result = new ArrayList<>();
        MainState selectedMain = mainState(
                entity, now, state, synchronizedMovement, ysmMountedAnimations);
        ActiveClip main = track(state, "main", selectedMain.clip(),
                selectedMain.clip(), now);
        add(result, main);

        addHand(result, entity, InteractionHand.OFF_HAND, state, now);
        addHand(result, entity, InteractionHand.MAIN_HAND, state, now);
        addArmor(result, entity, EquipmentSlot.HEAD, "head", state, now);
        addArmor(result, entity, EquipmentSlot.CHEST, "chest", state, now);
        addArmor(result, entity, EquipmentSlot.LEGS, "legs", state, now);
        addArmor(result, entity, EquipmentSlot.FEET, "feet", state, now);
        addRide(result, entity, state, now, ysmMountedAnimations);
        return new Selection(result, main, selectedMain.movement(),
                state.heldItemChanges);
    }

    static boolean usesYsmMountedAnimations(boolean passenger,
                                            boolean ysmVehicle) {
        return !passenger || ysmVehicle;
    }

    boolean hasUnobservedHeldItemChange(LivingEntity entity,
                                        InteractionHand hand, State state) {
        if (entity == null || hand == null || state == null
                || AnimationConditionMatcher.isUsing(entity, hand)
                || AnimationConditionMatcher.swingSignal(entity, hand).active()) {
            return false;
        }
        ItemStack previous = state.heldItemObservations.getOrDefault(
                hand, ItemStack.EMPTY);
        return !officialHeldItemMatches(previous,
                AnimationConditionMatcher.item(entity, hand));
    }

    Set<String> names() {
        return clips.keySet();
    }

    private void observeAttacked(LivingEntity entity, double now, State state) {
        if (entity.hurtTime > 0
                && (state.lastHurtTime <= 0 || entity.hurtTime > state.lastHurtTime)) {
            state.attackedStartedAt = now;
        }
        state.lastHurtTime = entity.hurtTime;
    }

    private MainState mainState(LivingEntity entity, double now, State state,
                                MovementAnimationType synchronizedMovement,
                                boolean ysmMountedAnimations) {
        if (entity.isDeadOrDying()) {
            return main(firstAvailable("death", "idle", "new_idle_empty"));
        }
        if (entity.isAutoSpinAttack()) {
            return main(firstAvailable("riptide", "idle", "new_idle_empty"));
        }
        if (entity.isSleeping()) {
            return main(firstAvailable("sleep", "idle", "new_idle_empty"));
        }
        if (state.attackedStartedAt >= 0.0D && has("attacked")
                && now - state.attackedStartedAt <= latchDuration("attacked")) {
            return main("attacked");
        }
        if (entity.isPassenger()) {
            if (!ysmMountedAnimations) {
                return main(firstAvailable("idle", "new_idle_empty"));
            }
            Entity vehicle = entity.getVehicle();
            if (vehicle instanceof Boat) {
                return main(firstAvailable(
                        "boat", "ride", "sit", "idle", "new_idle_empty"));
            }
            if (vehicle instanceof Pig) {
                return main(firstAvailable(
                        "ride_pig", "ride", "sit", "idle", "new_idle_empty"));
            }
            return main(firstAvailable("ride", "sit", "idle", "new_idle_empty"));
        }
        MovementAnimationType movement = synchronizedMovement == null
                ? MovementAnimationType.resolve(entity) : synchronizedMovement;
        if (movement == null) {
            return main(firstAvailable("idle", "new_idle_empty"));
        }
        String clip = switch (movement) {
            case SWIM -> firstAvailable("swim", "idle", "new_idle_empty");
            case LADDER_UP -> firstAvailable(
                    "ladder_up", "climb", "idle", "new_idle_empty");
            case LADDER_DOWN -> firstAvailable(
                    "ladder_down", "climb", "idle", "new_idle_empty");
            case LADDER_IDLE -> firstAvailable(
                    "ladder_stillness", "climbing", "idle", "new_idle_empty");
            case CRAWL_MOVE -> firstAvailable("climb", "idle", "new_idle_empty");
            case CRAWL_IDLE -> firstAvailable("climbing", "idle", "new_idle_empty");
            case CREATIVE_FLIGHT -> firstAvailable("fly", "idle", "new_idle_empty");
            case ELYTRA_FLIGHT -> firstAvailable(
                    "elytra_fly", "fly", "idle", "new_idle_empty");
            case WATER_IDLE -> firstAvailable("swim_stand", "idle", "new_idle_empty");
            case JUMP -> firstAvailable("jump", "idle", "new_idle_empty");
            case SNEAK_MOVE -> firstAvailable("sneak", "idle", "new_idle_empty");
            case SNEAK_IDLE -> firstAvailable("sneaking", "idle", "new_idle_empty");
            case RUN -> firstAvailable("run", "walk", "idle", "new_idle_empty");
            case WALK -> firstAvailable("walk", "idle", "new_idle_empty");
        };
        return new MainState(clip, movement);
    }

    private static MainState main(String clip) {
        return new MainState(clip, null);
    }

    private void addArmor(List<ActiveClip> result, LivingEntity entity, EquipmentSlot slot,
                          String prefix, State state, double now) {
        ItemStack stack = entity.getItemBySlot(slot);
        String clip = itemCondition(prefix, entity, stack,
                AnimationConditionMatcher.ItemAction.HOLD, null);
        add(result, track(state, "armor_" + prefix, clip,
                AnimationConditionMatcher.itemToken(stack), now));
    }

    private void addHand(List<ActiveClip> result, LivingEntity entity,
                         InteractionHand hand, State state, double now) {
        ItemStack stack = AnimationConditionMatcher.item(entity, hand);
        String handName = hand == InteractionHand.MAIN_HAND ? "main" : "off";
        boolean using = AnimationConditionMatcher.isUsing(entity, hand);
        AnimationConditionMatcher.SwingSignal swing = using
                ? new AnimationConditionMatcher.SwingSignal(false, "", 0.0F)
                : AnimationConditionMatcher.swingSignal(entity, hand);
        String holdPrefix = hand == InteractionHand.MAIN_HAND
                ? "hold_mainhand" : "hold_offhand";
        if (using || swing.active()) {
            add(result, paused(state, "hand_" + handName + "_hold", now));
        } else {
            String hold = itemCondition(holdPrefix, entity, stack,
                    AnimationConditionMatcher.ItemAction.HOLD, hand);
            if (hold == null && has(holdPrefix)) {
                hold = holdPrefix;
            }
            add(result, track(state, "hand_" + handName + "_hold", hold,
                    state.heldItemToken(hand, stack), now));
        }

        String swingToken = state.swingToken(hand, swing);
        AnimationConditionMatcher.ItemAction action = null;
        String prefix = null;
        String generic = null;
        if (using) {
            action = AnimationConditionMatcher.ItemAction.USE;
            prefix = hand == InteractionHand.MAIN_HAND
                    ? "use_mainhand" : "use_offhand";
            generic = prefix;
        } else if (swing.active()) {
            action = AnimationConditionMatcher.ItemAction.SWING;
            prefix = hand == InteractionHand.MAIN_HAND ? "swing" : "swing_offhand";
            generic = hand == InteractionHand.MAIN_HAND ? "swing_hand" : "swing_offhand";
        }
        String clip = action == null ? null
                : itemCondition(prefix, entity, stack, action, hand);
        if (clip == null && generic != null && has(generic)) {
            clip = generic;
        }
        String token = action == null ? ""
                : action.name() + ':' + AnimationConditionMatcher.itemToken(stack)
                + (action == AnimationConditionMatcher.ItemAction.SWING
                ? ':' + swingToken : "");
        add(result, track(state, "hand_" + handName + "_action", clip, token, now));
    }

    private void addRide(List<ActiveClip> result, LivingEntity entity, State state,
                         double now, boolean ysmMountedAnimations) {
        Entity vehicle = entity.getVehicle();
        String vehicleClip = ysmMountedAnimations
                ? entityCondition("vehicle", vehicle) : null;
        String vehicleToken = !ysmMountedAnimations || vehicle == null
                ? "" : channelToken(vehicle);
        add(result, track(state, "vehicle", vehicleClip, vehicleToken, now));

        Entity matchingPassenger = null;
        String passengerClip = null;
        for (Entity passenger : entity.getPassengers()) {
            passengerClip = entityCondition("passenger", passenger);
            if (passengerClip != null) {
                matchingPassenger = passenger;
                break;
            }
        }
        String passengerToken = matchingPassenger == null
                ? "" : channelToken(matchingPassenger);
        add(result, track(state, "passenger", passengerClip, passengerToken, now));
    }

    private String itemCondition(String prefix, LivingEntity entity, ItemStack stack,
                                 AnimationConditionMatcher.ItemAction action,
                                 InteractionHand hand) {
        String exact = prefix + "$" + AnimationConditionMatcher.itemToken(stack);
        if (!stack.isEmpty() && has(exact)) {
            return exact;
        }
        String tag = matchingSelector(prefix, '#', selector ->
                AnimationConditionMatcher.matchesItem(entity, stack, selector,
                        action, hand));
        if (tag != null) {
            return tag;
        }
        for (String category : AnimationConditionMatcher.categories(entity, stack,
                action, hand)) {
            String candidate = prefix + ":" + category;
            if (has(candidate)) {
                return candidate;
            }
        }
        String defaultClip = prefix + ":default";
        return !stack.isEmpty() && has(defaultClip) ? defaultClip : null;
    }

    private String entityCondition(String prefix, Entity entity) {
        if (entity == null) {
            return null;
        }
        String exact = prefix + "$" + entityToken(entity);
        if (has(exact)) {
            return exact;
        }
        return matchingSelector(prefix, '#', selector ->
                AnimationConditionMatcher.matchesEntity(entity, selector));
    }

    private String matchingSelector(String prefix, char separator,
                                    java.util.function.Predicate<String> matcher) {
        String start = prefix + separator;
        for (String name : orderedNames) {
            if (name.startsWith(start) && matcher.test(name.substring(prefix.length()))) {
                return name;
            }
        }
        return null;
    }

    private ActiveClip track(State state, String channel, String name, String token,
                             double now) {
        Channel previous = state.channels.get(channel);
        if (name == null) {
            state.channels.remove(channel);
            return null;
        }
        String normalizedToken = token == null ? "" : token;
        boolean restarted = previous == null || !previous.name().equals(name)
                || !previous.token().equals(normalizedToken);
        Channel current = restarted
                ? new Channel(name, normalizedToken, now) : previous;
        state.channels.put(channel, current);
        return new ActiveClip(name, Math.max(0.0D, now - current.startedAt()), restarted);
    }

    private ActiveClip paused(State state, String channel, double now) {
        Channel previous = state.channels.get(channel);
        return previous == null ? null : new ActiveClip(previous.name(),
                Math.max(0.0D, now - previous.startedAt()), false);
    }

    private void add(List<ActiveClip> target, ActiveClip clip) {
        if (clip != null) {
            target.add(clip);
        }
    }

    private String firstAvailable(String... names) {
        for (String name : names) {
            if (has(name)) {
                return name;
            }
        }
        return null;
    }

    private boolean has(String name) {
        return name != null && clips.containsKey(name.toLowerCase(Locale.ROOT));
    }

    private double latchDuration(String name) {
        ClipInfo info = clips.get(name);
        return Math.max(MIN_LATCH_SECONDS, info == null ? 0.0D : info.duration());
    }

    private static String entityToken(Entity entity) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "unknown" : id.toString();
    }

    private static String channelToken(Entity entity) {
        return entityToken(entity) + '@' + entity.getId();
    }
}
