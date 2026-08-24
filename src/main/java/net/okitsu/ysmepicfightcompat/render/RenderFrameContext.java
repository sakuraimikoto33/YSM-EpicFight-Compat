package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Map;

/** Render-thread context connecting the selected mesh, its body draw, and item layers. */
public final class RenderFrameContext {
    public static final class Frame {
        private final LivingEntity entity;
        private final boolean firstPerson;
        private final Map<String, Boolean> visibleParts;
        private final boolean showUnlistedParts;
        private CompatHumanoidMesh mesh;
        private OpenMatrix4f[] inputPoses;
        private Vector3f rightFist;
        private Vector3f leftFist;

        private Frame(LivingEntity entity, boolean firstPerson,
                      Map<String, Boolean> visibleParts, boolean showUnlistedParts) {
            this.entity = entity;
            this.firstPerson = firstPerson;
            this.visibleParts = Map.copyOf(visibleParts);
            this.showUnlistedParts = showUnlistedParts;
        }

        public LivingEntity entity() {
            return entity;
        }

        public boolean firstPerson() {
            return firstPerson;
        }

        public Map<String, Boolean> visibleParts() {
            return visibleParts;
        }

        public boolean showUnlistedParts() {
            return showUnlistedParts;
        }

        public boolean isBoundTo(CompatHumanoidMesh expected) {
            return mesh == expected;
        }
    }

    private static final ThreadLocal<ArrayDeque<Frame>> CURRENT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private RenderFrameContext() {
    }

    public static Frame pushThirdPerson(LivingEntity entity) {
        return push(new Frame(entity, false, Map.of(), true));
    }

    public static Frame pushFirstPerson(LivingEntity entity,
                                        Map<String, Boolean> visibleParts,
                                        boolean showUnlistedParts) {
        return push(new Frame(entity, true, visibleParts, showUnlistedParts));
    }

    private static Frame push(Frame frame) {
        CURRENT.get().push(frame);
        return frame;
    }

    /** Ends one exact render scope while preserving any outer player render. */
    public static void pop(@Nullable Frame frame) {
        if (frame == null) {
            return;
        }
        ArrayDeque<Frame> frames = CURRENT.get();
        if (frames.peek() == frame) {
            frames.pop();
        } else {
            frames.removeFirstOccurrence(frame);
        }
        if (frames.isEmpty()) {
            CURRENT.remove();
        }
    }

    /** Binds only the actual converted mesh returned inside the active render scope. */
    public static boolean bindMesh(LivingEntity entity, boolean firstPerson,
                                   CompatHumanoidMesh mesh) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.firstPerson != firstPerson) {
            return false;
        }
        if (frame.mesh != null && frame.mesh != mesh) {
            frame.mesh = null;
            clearPublished(frame);
            return false;
        }
        frame.mesh = mesh;
        return true;
    }

    /** Publishes copies of points produced by the exact matrices used for this body draw. */
    public static void publishHeldItemPoints(LivingEntity entity, CompatHumanoidMesh mesh,
                                             OpenMatrix4f[] inputPoses,
                                             @Nullable Vector3f rightFist,
                                             @Nullable Vector3f leftFist) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || inputPoses == null) {
            return;
        }
        frame.inputPoses = inputPoses;
        frame.rightFist = finite(rightFist) ? new Vector3f(rightFist) : null;
        frame.leftFist = finite(leftFist) ? new Vector3f(leftFist) : null;
    }

    @Nullable
    public static Vector3f displayedFist(LivingEntity entity, CompatHumanoidMesh mesh,
                                         OpenMatrix4f[] inputPoses, int toolJoint) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || frame.inputPoses != inputPoses) {
            return null;
        }
        Vector3f point = toolJoint == HumanoidRig.RIGHT_TOOL ? frame.rightFist
                : toolJoint == HumanoidRig.LEFT_TOOL ? frame.leftFist : null;
        return point == null ? null : new Vector3f(point);
    }

    @Nullable
    public static Frame current() {
        ArrayDeque<Frame> frames = CURRENT.get();
        Frame frame = frames.peek();
        if (frame == null) {
            CURRENT.remove();
        }
        return frame;
    }

    @Nullable
    public static LivingEntity currentEntity() {
        Frame frame = current();
        return frame == null ? null : frame.entity;
    }

    @Nullable
    public static CompatHumanoidMesh currentMeshFor(LivingEntity entity) {
        Frame frame = current();
        return frame != null && frame.entity == entity ? frame.mesh : null;
    }

    public static boolean isFirstPersonFor(LivingEntity entity) {
        Frame frame = current();
        return frame != null && frame.firstPerson && frame.entity == entity && frame.mesh != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static void clearPublished(Frame frame) {
        frame.inputPoses = null;
        frame.rightFist = null;
        frame.leftFist = null;
    }

    private static boolean finite(@Nullable Vector3f value) {
        return value != null && Float.isFinite(value.x())
                && Float.isFinite(value.y()) && Float.isFinite(value.z());
    }
}
