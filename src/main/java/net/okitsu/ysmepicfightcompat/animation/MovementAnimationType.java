package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;

/** Stable, user-facing movement states that may hand full-body pose ownership to YSM. */
public enum MovementAnimationType {
    WALK("walk"),
    RUN("run"),
    SNEAK_IDLE("sneak_idle"),
    SNEAK_MOVE("sneak_move"),
    JUMP("jump"),
    CREATIVE_FLIGHT("creative_flight"),
    ELYTRA_FLIGHT("elytra_flight"),
    SWIM("swim"),
    WATER_IDLE("water_idle"),
    CRAWL_IDLE("crawl_idle"),
    CRAWL_MOVE("crawl_move"),
    LADDER_IDLE("ladder_idle"),
    LADDER_UP("ladder_up"),
    LADDER_DOWN("ladder_down");

    private static final double MOVING_SPEED_SQUARED = 0.0001D;
    private static final double VERTICAL_EPSILON = 0.01D;

    private final String configKey;

    MovementAnimationType(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    @Nullable
    public static MovementAnimationType fromConfigKey(Object source) {
        if (!(source instanceof String value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.configKey.equals(normalized))
                .findFirst().orElse(null);
    }

    /**
     * Resolves only locomotion. Death, hurt, sleeping, riding, riptide, ordinary falling,
     * held-item actions, and Epic Fight actions deliberately remain outside this policy.
     */
    @Nullable
    public static MovementAnimationType resolve(LivingEntity entity) {
        if (entity == null || entity.isDeadOrDying() || entity.isAutoSpinAttack()
                || entity.isSleeping() || entity.isPassenger() || entity.hurtTime > 0) {
            return null;
        }
        if (entity.isSwimming()) {
            return SWIM;
        }
        if (entity.onClimbable()) {
            double vertical = entity.getDeltaMovement().y;
            return vertical > VERTICAL_EPSILON ? LADDER_UP
                    : vertical < -VERTICAL_EPSILON ? LADDER_DOWN : LADDER_IDLE;
        }
        if (isCrawling(entity)) {
            return isMoving(entity) ? CRAWL_MOVE : CRAWL_IDLE;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return CREATIVE_FLIGHT;
        }
        if (entity.isFallFlying()) {
            return ELYTRA_FLIGHT;
        }
        if (entity.isInWaterOrBubble()) {
            return WATER_IDLE;
        }
        if (!entity.onGround()) {
            return entity.getDeltaMovement().y > VERTICAL_EPSILON ? JUMP : null;
        }
        if (entity.isShiftKeyDown()) {
            return isMoving(entity) ? SNEAK_MOVE : SNEAK_IDLE;
        }
        if (entity.isSprinting()) {
            return RUN;
        }
        return isMoving(entity) ? WALK : null;
    }

    private static boolean isMoving(LivingEntity entity) {
        return entity.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQUARED;
    }

    private static boolean isCrawling(LivingEntity entity) {
        return entity.getPose() == Pose.SWIMMING && !entity.isInWaterOrBubble()
                && !entity.isFallFlying();
    }
}
