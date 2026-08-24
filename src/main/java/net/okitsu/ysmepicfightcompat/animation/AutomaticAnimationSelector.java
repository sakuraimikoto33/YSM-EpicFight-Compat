package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Selects official YSM state, held-item, equipment, and riding clips. */
final class AutomaticAnimationSelector {
    private static final double MOVING_SPEED_SQUARED = 0.0001D;
    private static final double VERTICAL_EPSILON = 0.01D;
    private static final double MIN_LATCH_SECONDS = 0.05D;

    record ClipInfo(float duration) {
    }

    record ActiveClip(String name, double elapsed, boolean restarted) {
    }

    static final class State {
        private final Map<String, Channel> channels = new HashMap<>();
        private final Map<InteractionHand, SwingObservation> swingObservations =
                new EnumMap<>(InteractionHand.class);
        private int lastHurtTime;
        private double attackedStartedAt = -1.0D;
        private long nextSwingSequence;

        void reset() {
            channels.clear();
            swingObservations.clear();
            lastHurtTime = 0;
            attackedStartedAt = -1.0D;
            nextSwingSequence = 0L;
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

    private final Map<String, ClipInfo> clips;
    private final List<String> orderedNames;

    AutomaticAnimationSelector(Map<String, ClipInfo> clips) {
        this.clips = Map.copyOf(clips);
        orderedNames = clips.keySet().stream().sorted().toList();
    }

    List<ActiveClip> select(LivingEntity entity, double now, State state) {
        observeAttacked(entity, now, state);

        List<ActiveClip> result = new ArrayList<>();
        String main = mainState(entity, now, state);
        add(result, track(state, "main", main, main, now));

        addHand(result, entity, InteractionHand.MAIN_HAND, state, now);
        addHand(result, entity, InteractionHand.OFF_HAND, state, now);
        addArmor(result, entity, EquipmentSlot.HEAD, "head", state, now);
        addArmor(result, entity, EquipmentSlot.CHEST, "chest", state, now);
        addArmor(result, entity, EquipmentSlot.LEGS, "legs", state, now);
        addArmor(result, entity, EquipmentSlot.FEET, "feet", state, now);
        addRide(result, entity, state, now);
        return List.copyOf(result);
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

    private String mainState(LivingEntity entity, double now, State state) {
        if (entity.isDeadOrDying()) {
            return firstAvailable("death", "idle", "new_idle_empty");
        }
        if (entity.isAutoSpinAttack()) {
            return firstAvailable("riptide", "idle", "new_idle_empty");
        }
        if (entity.isSleeping()) {
            return firstAvailable("sleep", "idle", "new_idle_empty");
        }
        if (state.attackedStartedAt >= 0.0D && has("attacked")
                && now - state.attackedStartedAt <= latchDuration("attacked")) {
            return "attacked";
        }
        if (entity.isSwimming()) {
            return firstAvailable("swim", "idle", "new_idle_empty");
        }
        if (entity.onClimbable()) {
            double vertical = entity.getDeltaMovement().y;
            if (vertical > VERTICAL_EPSILON) {
                return firstAvailable("ladder_up", "climb", "idle", "new_idle_empty");
            }
            if (vertical < -VERTICAL_EPSILON) {
                return firstAvailable("ladder_down", "climb", "idle", "new_idle_empty");
            }
            return firstAvailable("ladder_stillness", "climbing", "idle",
                    "new_idle_empty");
        }
        if (isCrawling(entity)) {
            return firstAvailable(isMoving(entity) ? "climb" : "climbing",
                    "idle", "new_idle_empty");
        }
        if (entity.isPassenger()) {
            Entity vehicle = entity.getVehicle();
            if (vehicle instanceof Boat) {
                return firstAvailable("boat", "ride", "sit", "idle", "new_idle_empty");
            }
            if (vehicle instanceof Pig) {
                return firstAvailable("ride_pig", "ride", "sit", "idle",
                        "new_idle_empty");
            }
            return firstAvailable("ride", "sit", "idle", "new_idle_empty");
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return firstAvailable("fly", "idle", "new_idle_empty");
        }
        if (entity.isFallFlying()) {
            return firstAvailable("elytra_fly", "fly", "idle", "new_idle_empty");
        }
        if (entity.isInWaterOrBubble()) {
            return firstAvailable("swim_stand", "idle", "new_idle_empty");
        }
        if (!entity.onGround() && entity.getDeltaMovement().y > VERTICAL_EPSILON) {
            return firstAvailable("jump", "idle", "new_idle_empty");
        }
        if (entity.isShiftKeyDown()) {
            return firstAvailable(isMoving(entity) ? "sneak" : "sneaking",
                    "idle", "new_idle_empty");
        }
        if (entity.isSprinting()) {
            return firstAvailable("run", "walk", "idle", "new_idle_empty");
        }
        if (isMoving(entity)) {
            return firstAvailable("walk", "idle", "new_idle_empty");
        }
        return firstAvailable("idle", "new_idle_empty");
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
        String holdPrefix = hand == InteractionHand.MAIN_HAND
                ? "hold_mainhand" : "hold_offhand";
        String hold = itemCondition(holdPrefix, entity, stack,
                AnimationConditionMatcher.ItemAction.HOLD, hand);
        if (hold == null && has(holdPrefix)) {
            hold = holdPrefix;
        }
        add(result, track(state, "hand_" + handName + "_hold", hold,
                AnimationConditionMatcher.itemToken(stack), now));

        boolean using = AnimationConditionMatcher.isUsing(entity, hand);
        AnimationConditionMatcher.SwingSignal swing = using
                ? new AnimationConditionMatcher.SwingSignal(false, "", 0.0F)
                : AnimationConditionMatcher.swingSignal(entity, hand);
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
                         double now) {
        Entity vehicle = entity.getVehicle();
        String vehicleClip = entityCondition("vehicle", vehicle);
        String vehicleToken = vehicle == null ? "" : channelToken(vehicle);
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

    private static boolean isMoving(LivingEntity entity) {
        return entity.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQUARED;
    }

    private static boolean isCrawling(LivingEntity entity) {
        return entity.getPose() == Pose.SWIMMING && !entity.isInWaterOrBubble()
                && !entity.isFallFlying();
    }

    private static String entityToken(Entity entity) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "unknown" : id.toString();
    }

    private static String channelToken(Entity entity) {
        return entityToken(entity) + '@' + entity.getId();
    }
}
