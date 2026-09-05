package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;

/** Render-thread context connecting the selected mesh, its body draw, and item layers. */
public final class RenderFrameContext {
    public static final class Frame {
        private final LivingEntity entity;
        private final boolean firstPerson;
        private final Map<String, Boolean> visibleParts;
        private final boolean showUnlistedParts;
        @Nullable
        private final Float epicModelYaw;
        private final boolean epicFightActionActive;
        @Nullable
        private final MovementAnimationType ysmMovement;
        private CompatHumanoidMesh mesh;
        private OpenMatrix4f[] inputPoses;
        private Vector3f rightFist;
        private Vector3f leftFist;
        private OpenMatrix4f rightAuthoredItemPose;
        private OpenMatrix4f leftAuthoredItemPose;
        private OpenMatrix4f elytraLocatorPose;
        private OpenMatrix4f[] attachmentPoses;
        private boolean suppressRightHeldItem;
        private boolean suppressLeftHeldItem;
        private boolean mainHandItemSwitchUsesOffArmTool;
        private boolean naturalLadderPose;
        private Set<InteractionHand> ladderItemsInHand = Set.of();

        private Frame(LivingEntity entity, boolean firstPerson,
                      Map<String, Boolean> visibleParts, boolean showUnlistedParts,
                      @Nullable Float epicModelYaw,
                      boolean epicFightActionActive,
                      @Nullable MovementAnimationType ysmMovement) {
            this.entity = entity;
            this.firstPerson = firstPerson;
            this.visibleParts = Map.copyOf(visibleParts);
            this.showUnlistedParts = showUnlistedParts;
            this.epicModelYaw = epicModelYaw != null && Float.isFinite(epicModelYaw)
                    ? epicModelYaw : null;
            this.epicFightActionActive = epicFightActionActive;
            this.ysmMovement = ysmMovement;
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

        /** Epic Fight's interpolated outer model yaw for this exact render. */
        @Nullable
        public Float epicModelYaw() {
            return epicModelYaw;
        }

        /** Whether an attack, guard, dodge, aim, hurt, or other Epic Fight action owns pose. */
        public boolean epicFightActionActive() {
            return epicFightActionActive;
        }

        /** Configured YSM full-body movement that owns this exact rendered frame. */
        @Nullable
        public MovementAnimationType ysmMovement() {
            return ysmMovement;
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
        return pushThirdPerson(entity, null);
    }

    public static Frame pushThirdPerson(LivingEntity entity,
                                        @Nullable Float epicModelYaw) {
        return pushThirdPerson(entity, epicModelYaw, false);
    }

    public static Frame pushThirdPerson(LivingEntity entity,
                                        @Nullable Float epicModelYaw,
                                        boolean epicFightActionActive) {
        return pushThirdPerson(entity, epicModelYaw, epicFightActionActive, null);
    }

    public static Frame pushThirdPerson(LivingEntity entity,
                                        @Nullable Float epicModelYaw,
                                        boolean epicFightActionActive,
                                        @Nullable MovementAnimationType ysmMovement) {
        return push(new Frame(entity, false, Map.of(), true, epicModelYaw,
                epicFightActionActive, ysmMovement));
    }

    public static Frame pushFirstPerson(LivingEntity entity,
                                        Map<String, Boolean> visibleParts,
                                        boolean showUnlistedParts) {
        return pushFirstPerson(entity, visibleParts, showUnlistedParts, null);
    }

    public static Frame pushFirstPerson(LivingEntity entity,
                                        Map<String, Boolean> visibleParts,
                                        boolean showUnlistedParts,
                                        @Nullable Float epicModelYaw) {
        return pushFirstPerson(entity, visibleParts, showUnlistedParts,
                epicModelYaw, false);
    }

    public static Frame pushFirstPerson(LivingEntity entity,
                                        Map<String, Boolean> visibleParts,
                                        boolean showUnlistedParts,
                                        @Nullable Float epicModelYaw,
                                        boolean epicFightActionActive) {
        return push(new Frame(entity, true, visibleParts, showUnlistedParts,
                epicModelYaw, epicFightActionActive, null));
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

    /** Publishes copies of the final attachment state produced by this exact body draw. */
    public static void publishHeldItemPoints(LivingEntity entity, CompatHumanoidMesh mesh,
                                             OpenMatrix4f[] inputPoses,
                                             @Nullable Vector3f rightFist,
                                             @Nullable Vector3f leftFist,
                                             @Nullable OpenMatrix4f rightAuthoredItemPose,
                                             @Nullable OpenMatrix4f leftAuthoredItemPose,
                                             @Nullable OpenMatrix4f elytraLocatorPose,
                                             @Nullable OpenMatrix4f[] attachmentPoses,
                                             boolean suppressRightHeldItem,
                                             boolean suppressLeftHeldItem,
                                             boolean mainHandItemSwitchUsesOffArmTool,
                                             boolean naturalLadderPose,
                                             Set<InteractionHand> ladderItemsInHand) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || inputPoses == null) {
            return;
        }
        frame.inputPoses = inputPoses;
        frame.rightFist = finite(rightFist) ? new Vector3f(rightFist) : null;
        frame.leftFist = finite(leftFist) ? new Vector3f(leftFist) : null;
        frame.rightAuthoredItemPose = finite(rightAuthoredItemPose)
                ? new OpenMatrix4f(rightAuthoredItemPose) : null;
        frame.leftAuthoredItemPose = finite(leftAuthoredItemPose)
                ? new OpenMatrix4f(leftAuthoredItemPose) : null;
        frame.elytraLocatorPose = finite(elytraLocatorPose)
                ? new OpenMatrix4f(elytraLocatorPose) : null;
        frame.attachmentPoses = copyMatrices(attachmentPoses, inputPoses.length);
        frame.suppressRightHeldItem = suppressRightHeldItem;
        frame.suppressLeftHeldItem = suppressLeftHeldItem;
        frame.mainHandItemSwitchUsesOffArmTool =
                mainHandItemSwitchUsesOffArmTool;
        frame.naturalLadderPose = naturalLadderPose;
        frame.ladderItemsInHand = ladderItemsInHand == null
                ? Set.of() : Set.copyOf(ladderItemsInHand);
    }

    @Nullable
    public static Vector3f displayedFist(LivingEntity entity, CompatHumanoidMesh mesh,
                                         OpenMatrix4f[] inputPoses, int toolJoint) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || !sameBodyPoseSource(frame.inputPoses, inputPoses)) {
            return null;
        }
        Vector3f point = toolJoint == HumanoidRig.RIGHT_TOOL ? frame.rightFist
                : toolJoint == HumanoidRig.LEFT_TOOL ? frame.leftFist : null;
        return point == null ? null : new Vector3f(point);
    }

    /** Full YSM locator frame, published only while an item-switch pose owns this hand. */
    @Nullable
    public static OpenMatrix4f authoredHeldItemPose(
            LivingEntity entity, CompatHumanoidMesh mesh,
            OpenMatrix4f[] inputPoses, int toolJoint) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || !sameBodyPoseSource(frame.inputPoses, inputPoses)) {
            return null;
        }
        OpenMatrix4f pose = toolJoint == HumanoidRig.RIGHT_TOOL
                ? frame.rightAuthoredItemPose : toolJoint == HumanoidRig.LEFT_TOOL
                ? frame.leftAuthoredItemPose : null;
        return pose == null ? null : new OpenMatrix4f(pose);
    }

    /** Animated official-YSM ElytraLocator frame from this exact converted body draw. */
    @Nullable
    public static OpenMatrix4f elytraLocatorPose(
            LivingEntity entity, CompatHumanoidMesh mesh,
            OpenMatrix4f[] requestedPoses) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || requestedPoses == null
                || (!sameBodyPoseSource(frame.inputPoses, requestedPoses)
                && requestedPoses != frame.attachmentPoses
                && !AttachmentArmatureScope.isDisplayedPoseArray(requestedPoses))) {
            return null;
        }
        return frame.elytraLocatorPose == null
                ? null : new OpenMatrix4f(frame.elytraLocatorPose);
    }

    /**
     * Returns the projected final YSM pose for one Epic Fight attachment joint.
     * Both the body input (including a complete shallow copy) and the already-projected
     * layer array identify this frame.
     */
    @Nullable
    public static OpenMatrix4f displayedAttachmentPose(
            LivingEntity entity, CompatHumanoidMesh mesh,
            OpenMatrix4f[] requestedPoses, int joint) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || requestedPoses == null
                || (!sameBodyPoseSource(frame.inputPoses, requestedPoses)
                && requestedPoses != frame.attachmentPoses)
                || frame.attachmentPoses == null
                || joint < 0 || joint >= frame.attachmentPoses.length) {
            return null;
        }
        OpenMatrix4f pose = frame.attachmentPoses[joint];
        return pose == null ? null : new OpenMatrix4f(pose);
    }

    /** Replaces only poses sharing this converted body's complete input skeleton. */
    public static OpenMatrix4f[] resolvePatchedLayerPoses(OpenMatrix4f[] requestedPoses) {
        Frame frame = current();
        return frame != null && requestedPoses != null
                && frame.mesh != null && sameBodyPoseSource(frame.inputPoses, requestedPoses)
                && frame.attachmentPoses != null
                ? frame.attachmentPoses : requestedPoses;
    }

    /**
     * Render integrations may shallow-copy the armature array before drawing the body,
     * while passing the original array to its layers. Array identity alone loses the
     * completed YSM pose in that case. Accept only a complete shared skeleton: every
     * matrix must be the very same object at the very same joint index. Equal numeric
     * values, partial sharing, and a replaced joint do not establish render provenance.
     * Entity, mesh, and render-frame checks remain the caller's responsibility.
     */
    static boolean sameBodyPoseSource(@Nullable OpenMatrix4f[] body,
                                      @Nullable OpenMatrix4f[] requested) {
        if (body == null || requested == null) {
            return false;
        }
        if (body == requested) {
            return true;
        }
        if (body.length < HumanoidRig.EPIC_JOINT_COUNT || body.length != requested.length) {
            return false;
        }
        for (int joint = 0; joint < body.length; joint++) {
            if (body[joint] == null || body[joint] != requested[joint]) {
                return false;
            }
        }
        return true;
    }

    public static OpenMatrix4f[] resolvePatchedLayerPoses(
            LivingEntity entity, OpenMatrix4f[] requestedPoses) {
        Frame frame = current();
        return frame != null && frame.entity == entity
                ? resolvePatchedLayerPoses(requestedPoses) : requestedPoses;
    }

    /** Covers armature re-reads only for this exact body's subsequent layer call. */
    public static AttachmentArmatureScope openAttachmentScope(
            LivingEntity entity, Armature armature, OpenMatrix4f[] requestedPoses) {
        OpenMatrix4f[] displayed = resolvePatchedLayerPoses(entity, requestedPoses);
        return AttachmentArmatureScope.open(armature, requestedPoses,
                displayed == requestedPoses ? null : displayed);
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

    /** True only inside the exact converted body render that owns this held prop. */
    public static boolean suppressesHeldItem(LivingEntity entity, InteractionHand hand) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh == null) {
            return false;
        }
        boolean right = physicalRightForLogicalHand(
                hand, entity.getMainArm(),
                frame.mainHandItemSwitchUsesOffArmTool);
        return shouldSuppressHeldItem(frame.naturalLadderPose,
                frame.mesh.replacesHeldItem(entity, hand),
                right ? frame.suppressRightHeldItem : frame.suppressLeftHeldItem);
    }

    static boolean shouldSuppressHeldItem(
            boolean naturalLadderPose, boolean modelReplaces,
            boolean authoredLocatorSuppresses) {
        // Natural ladder mode hides the YSM prop. Keep Epic Fight's back item only
        // when the selected model does not replace it; otherwise both render paths
        // stay hidden while both hands climb.
        return naturalLadderPose ? modelReplaces
                : modelReplaces || authoredLocatorSuppresses;
    }

    /** True only when ladder mode is configured to keep this ordinary item in hand. */
    public static boolean keepsLadderItemInHand(
            LivingEntity entity, InteractionHand hand) {
        Frame frame = current();
        return frame != null && frame.entity == entity && frame.mesh != null
                && frame.ladderItemsInHand.contains(hand);
    }

    static boolean physicalRightForLogicalHand(
            InteractionHand hand, HumanoidArm mainArm,
            boolean mainHandUsesOffArmTool) {
        boolean logicalMain = hand == InteractionHand.MAIN_HAND;
        boolean usesMainArm = logicalMain && !mainHandUsesOffArmTool;
        return usesMainArm == (mainArm == HumanoidArm.RIGHT);
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static void clearPublished(Frame frame) {
        frame.inputPoses = null;
        frame.rightFist = null;
        frame.leftFist = null;
        frame.rightAuthoredItemPose = null;
        frame.leftAuthoredItemPose = null;
        frame.elytraLocatorPose = null;
        frame.attachmentPoses = null;
        frame.suppressRightHeldItem = false;
        frame.suppressLeftHeldItem = false;
        frame.mainHandItemSwitchUsesOffArmTool = false;
        frame.naturalLadderPose = false;
        frame.ladderItemsInHand = Set.of();
    }

    private static boolean finite(@Nullable Vector3f value) {
        return value != null && Float.isFinite(value.x())
                && Float.isFinite(value.y()) && Float.isFinite(value.z());
    }

    private static boolean finite(@Nullable OpenMatrix4f value) {
        return value != null
                && Float.isFinite(value.m00) && Float.isFinite(value.m01)
                && Float.isFinite(value.m02) && Float.isFinite(value.m03)
                && Float.isFinite(value.m10) && Float.isFinite(value.m11)
                && Float.isFinite(value.m12) && Float.isFinite(value.m13)
                && Float.isFinite(value.m20) && Float.isFinite(value.m21)
                && Float.isFinite(value.m22) && Float.isFinite(value.m23)
                && Float.isFinite(value.m30) && Float.isFinite(value.m31)
                && Float.isFinite(value.m32) && Float.isFinite(value.m33);
    }

    @Nullable
    private static OpenMatrix4f[] copyMatrices(
            @Nullable OpenMatrix4f[] source, int expectedLength) {
        if (source == null || source.length != expectedLength) {
            return null;
        }
        OpenMatrix4f[] copy = new OpenMatrix4f[source.length];
        for (int index = 0; index < source.length; index++) {
            if (!finite(source[index])) {
                return null;
            }
            copy[index] = new OpenMatrix4f(source[index]);
        }
        return copy;
    }
}
