package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;

/** Render-thread context connecting Epic Fight's renderer selection to the shared mesh draw. */
public final class RenderFrameContext {
    public record Frame(LivingEntity entity, boolean firstPerson,
                        Map<String, Boolean> visibleParts, boolean showUnlistedParts) {
        public Frame {
            visibleParts = Map.copyOf(visibleParts);
        }
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private RenderFrameContext() {
    }

    public static void thirdPerson(LivingEntity entity) {
        CURRENT.set(new Frame(entity, false, Map.of(), true));
    }

    public static void firstPerson(LivingEntity entity, Map<String, Boolean> visibleParts,
                                   boolean showUnlistedParts) {
        CURRENT.set(new Frame(entity, true, visibleParts, showUnlistedParts));
    }

    public static Frame current() {
        return CURRENT.get();
    }

    public static LivingEntity currentEntity() {
        Frame frame = CURRENT.get();
        return frame == null ? null : frame.entity();
    }

    public static boolean isFirstPersonFor(LivingEntity entity) {
        Frame frame = CURRENT.get();
        return frame != null && frame.firstPerson() && frame.entity() == entity;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
