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
        private final boolean renderingInInventory;
        private final Map<String, Boolean> visibleParts;
        private final boolean showUnlistedParts;
        @Nullable
        private final Float epicModelYaw;
        @Nullable
        private final OpenMatrix4f fullBodyPoseTransform;
        private final boolean epicFightActionActive;
        @Nullable
        private final MovementAnimationType ysmMovement;
        private boolean ysmModAnimation;
        private CompatHumanoidMesh mesh;
        private ModelLayerOrder layerOrder;
        private OpenMatrix4f[] inputPoses;
        private Vector3f rightFist;
        private Vector3f leftFist;
        private OpenMatrix4f rightAuthoredItemPose;
        private OpenMatrix4f leftAuthoredItemPose;
        private OpenMatrix4f elytraLocatorPose;
        private OpenMatrix4f[] attachmentPoses;
        private OpenMatrix4f[] heldItemPoses;
        private boolean suppressRightHeldItem;
        private boolean suppressLeftHeldItem;
        private boolean mainHandItemSwitchUsesOffArmTool;
        private boolean suppressHeldItemPose;
        private Set<InteractionHand> ladderItemsInHand = Set.of();
        private OpenMatrix4f formMainHandPose;
        private OpenMatrix4f formOffHandPose;
        private boolean hideFormMainHand;
        private boolean hideFormOffHand;
        private int formItemDrawDepth;

        private Frame(LivingEntity entity, boolean firstPerson,
                      Map<String, Boolean> visibleParts, boolean showUnlistedParts,
                      @Nullable Float epicModelYaw,
                      boolean epicFightActionActive,
                      @Nullable MovementAnimationType ysmMovement,
                      @Nullable OpenMatrix4f fullBodyPoseTransform) {
            this.entity = entity;
            this.firstPerson = firstPerson;
            this.renderingInInventory = InventoryRenderScope.claim(entity, firstPerson);
            this.visibleParts = Map.copyOf(visibleParts);
            this.showUnlistedParts = showUnlistedParts;
            this.epicModelYaw = epicModelYaw != null && Float.isFinite(epicModelYaw)
                    ? epicModelYaw : null;
            this.fullBodyPoseTransform = firstPerson && fullBodyPoseTransform != null
                    ? new OpenMatrix4f(fullBodyPoseTransform) : null;
            this.epicFightActionActive = epicFightActionActive;
            this.ysmMovement = ysmMovement;
        }

        public LivingEntity entity() {
            return entity;
        }

        public boolean firstPerson() {
            return firstPerson;
        }

        /** True only for the entity draw claimed by an inventory preview scope. */
        public boolean renderingInInventory() {
            return renderingInInventory;
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

        /** Converts a canonical YSM full-body skin into this first-person draw's basis. */
        @Nullable
        public OpenMatrix4f fullBodyPoseTransform() {
            return fullBodyPoseTransform == null ? null : new OpenMatrix4f(fullBodyPoseTransform);
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

        /** Optional native mod animation owns the canonical outer model transform. */
        public boolean ysmModAnimation() {
            return ysmModAnimation;
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
                epicFightActionActive, ysmMovement, null));
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
        return pushFirstPerson(entity, visibleParts, showUnlistedParts,
                epicModelYaw, epicFightActionActive, null);
    }

    public static Frame pushFirstPerson(LivingEntity entity,
                                        Map<String, Boolean> visibleParts,
                                        boolean showUnlistedParts,
                                        @Nullable Float epicModelYaw,
                                        boolean epicFightActionActive,
                                        @Nullable OpenMatrix4f fullBodyPoseTransform) {
        return push(new Frame(entity, true, visibleParts, showUnlistedParts,
                epicModelYaw, epicFightActionActive, null, fullBodyPoseTransform));
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

    /** Scopes the normal body draw; decoration overlays never enter this scope. */
    @Nullable
    public static ModelLayerOrder beginBodyDraw(LivingEntity entity,
                                                CompatHumanoidMesh mesh,
                                                @Nullable Runnable earlyLayers) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.firstPerson
                || !frame.isBoundTo(mesh)
                || frame.layerOrder != null && frame.layerOrder.bodyActive()) {
            return null;
        }
        frame.layerOrder = new ModelLayerOrder(earlyLayers);
        return frame.layerOrder;
    }

    /** Runs before any body/glow vertices are emitted, with the final pose available. */
    public static void renderLayersBeforeBody(CompatHumanoidMesh mesh) {
        Frame frame = current();
        if (frame != null && frame.isBoundTo(mesh) && frame.layerOrder != null) {
            frame.layerOrder.beforeBodyGeometry();
        }
    }

    public static boolean hasPendingEarlyLayers(CompatHumanoidMesh mesh) {
        Frame frame = current();
        return frame != null && frame.isBoundTo(mesh) && frame.layerOrder != null
                && frame.layerOrder.hasPendingLayers();
    }

    public static boolean layersAlreadyRendered(LivingEntity entity) {
        Frame frame = current();
        return frame != null && frame.entity == entity
                && frame.layerOrder != null && frame.layerOrder.layersRendered();
    }

    public static boolean isPrimaryBodyDraw(CompatHumanoidMesh mesh) {
        Frame frame = current();
        return frame != null && frame.isBoundTo(mesh)
                && (frame.firstPerson || frame.layerOrder != null
                && frame.layerOrder.bodyActive());
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
                                             boolean suppressHeldItemPose,
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
        frame.heldItemPoses = null;
        frame.suppressRightHeldItem = suppressRightHeldItem;
        frame.suppressLeftHeldItem = suppressLeftHeldItem;
        frame.mainHandItemSwitchUsesOffArmTool =
                mainHandItemSwitchUsesOffArmTool;
        frame.suppressHeldItemPose = suppressHeldItemPose;
        frame.ladderItemsInHand = ladderItemsInHand == null
                ? Set.of() : Set.copyOf(ladderItemsInHand);
        frame.formMainHandPose = null;
        frame.formOffHandPose = null;
        frame.hideFormMainHand = false;
        frame.hideFormOffHand = false;
    }

    /**
     * Publishes action-time Tool compensation separately from the general layer
     * skeleton. Armor and other layers must keep their existing action-pose reads.
     */
    public static void publishHeldItemPoses(
            LivingEntity entity, CompatHumanoidMesh mesh, OpenMatrix4f[] inputPoses,
            @Nullable OpenMatrix4f[] heldItemPoses) {
        Frame frame = current();
        if (frame != null && frame.entity == entity && frame.mesh == mesh
                && frame.epicFightActionActive && frame.attachmentPoses == null
                && inputPoses != null && sameBodyPoseSource(frame.inputPoses, inputPoses)) {
            frame.heldItemPoses = copyMatrices(heldItemPoses, inputPoses.length);
        }
    }

    public static Frame pushThirdPerson(LivingEntity entity,
                                        @Nullable Float epicModelYaw,
                                        boolean epicFightActionActive,
                                        @Nullable MovementAnimationType ysmMovement,
                                        boolean ysmModAnimation) {
        Frame frame = pushThirdPerson(entity, epicModelYaw, epicFightActionActive, ysmMovement);
        frame.ysmModAnimation = ysmModAnimation;
        return frame;
    }

    /**
     * Physical model locators become logical hands at this single boundary. Epic Fight's
     * Tool_R/Tool_L slots are not swapped by a player's dominant-arm preference.
     * Only third-person form attachments use these model-space snapshots.
     */
    public static void publishFormHeldItemPoints(
            LivingEntity entity, CompatHumanoidMesh mesh, HumanoidArm mainArm,
            @Nullable OpenMatrix4f rightPose, @Nullable OpenMatrix4f leftPose,
            boolean hideRight, boolean hideLeft, float translationScale) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh
                || frame.inputPoses == null || frame.firstPerson || mainArm == null) {
            return;
        }
        boolean mainRight = mainArm == HumanoidArm.RIGHT;
        frame.formMainHandPose = formPoseCopy(mainRight ? rightPose : leftPose, translationScale);
        frame.formOffHandPose = formPoseCopy(mainRight ? leftPose : rightPose, translationScale);
        frame.hideFormMainHand = mainRight ? hideRight : hideLeft;
        frame.hideFormOffHand = mainRight ? hideLeft : hideRight;
    }

    @Nullable
    private static OpenMatrix4f formPoseCopy(@Nullable OpenMatrix4f source, float scale) {
        if (!finite(source) || !Float.isFinite(scale) || scale <= 0.0F) {
            return null;
        }
        OpenMatrix4f copy = new OpenMatrix4f(source);
        copy.m30 *= scale;
        copy.m31 *= scale;
        copy.m32 *= scale;
        return finite(copy) ? copy : null;
    }

    /** One item's pose arguments and armature re-reads share the same temporary view. */
    public interface HeldItemDraw extends AutoCloseable {
        OpenMatrix4f[] poses();

        @Override
        void close();
    }

    private record ArmatureHeldItemDraw(OpenMatrix4f[] poses,
                                        AttachmentArmatureScope scope) implements HeldItemDraw {
        @Override
        public void close() {
            scope.close();
        }
    }

    /** A per-item, read-only view: a main-hand bow may request the off-hand Tool internally. */
    public static final class FormHeldItemDraw implements HeldItemDraw {
        private final Frame frame;
        private final OpenMatrix4f[] poses;
        private final AttachmentArmatureScope armatureScope;
        private boolean closed;

        private FormHeldItemDraw(Frame frame, OpenMatrix4f[] poses,
                                 AttachmentArmatureScope armatureScope) {
            this.frame = frame;
            this.poses = poses;
            this.armatureScope = armatureScope;
            frame.formItemDrawDepth++;
        }

        public OpenMatrix4f[] poses() {
            return poses;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    armatureScope.close();
                } finally {
                    frame.formItemDrawDepth--;
                }
            }
        }
    }

    /**
     * Forms retain priority. Otherwise apply action-time Tool compensation only
     * around the selected item renderer, including renderers that re-read its
     * armature instead of consuming the layer's pose argument.
     */
    @Nullable
    public static HeldItemDraw openHeldItem(
            LivingEntity entity, InteractionHand hand, Armature armature,
            OpenMatrix4f[] requestedPoses) {
        FormHeldItemDraw form = openFormHeldItem(entity, hand, armature, requestedPoses);
        if (form != null) {
            return form;
        }
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh == null
                || hand == null || frame.heldItemPoses == null
                || !sameBodyPoseSource(frame.inputPoses, requestedPoses)
                || AttachmentArmatureScope.isDisplayedPoseArray(armature, requestedPoses)) {
            return null;
        }
        OpenMatrix4f[] copy = copyMatrices(frame.heldItemPoses, requestedPoses.length);
        if (copy == null) {
            return null;
        }
        AttachmentArmatureScope scope = AttachmentArmatureScope.open(armature, requestedPoses, copy);
        if (!AttachmentArmatureScope.isDisplayedPoseArray(armature, copy)) {
            scope.close();
            return null;
        }
        return new ArmatureHeldItemDraw(copy, scope);
    }

    @Nullable
    public static FormHeldItemDraw openFormHeldItem(
            LivingEntity entity, InteractionHand hand, Armature armature,
            OpenMatrix4f[] requestedPoses) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.firstPerson
                || frame.suppressHeldItemPose || hand == null || requestedPoses == null
                || requestedPoses.length < HumanoidRig.EPIC_JOINT_COUNT
                || (!sameBodyPoseSource(frame.inputPoses, requestedPoses)
                && requestedPoses != frame.attachmentPoses
                && !AttachmentArmatureScope.isDisplayedPoseArray(requestedPoses))) {
            return null;
        }
        OpenMatrix4f pose = hand == InteractionHand.MAIN_HAND
                ? frame.formMainHandPose : frame.formOffHandPose;
        if (!finite(pose)) {
            return null;
        }
        OpenMatrix4f[] copy = copyMatrices(requestedPoses, requestedPoses.length);
        if (copy == null) {
            return null;
        }
        // Each item owns this temporary pair, so a two-handed renderer cannot move
        // another logical hand. Distinct objects preserve correction-matrix provenance.
        copy[HumanoidRig.RIGHT_TOOL] = new OpenMatrix4f(pose);
        copy[HumanoidRig.LEFT_TOOL] = new OpenMatrix4f(pose);
        AttachmentArmatureScope scope = AttachmentArmatureScope.open(armature, requestedPoses, copy);
        if (!AttachmentArmatureScope.isDisplayedPoseArray(armature, copy)) {
            scope.close();
            return null;
        }
        return new FormHeldItemDraw(frame, copy, scope);
    }

    public static boolean formHeldItemActive(LivingEntity entity) {
        Frame frame = current();
        return frame != null && frame.entity == entity && frame.formItemDrawDepth > 0;
    }

    /**
     * Whether this body supplied a model-space origin for the selected Tool.
     * Use only with a matching pose source or attachment scope:
     * projected arrays may retain an original Tool when its model anchor is missing.
     */
    static boolean hasModelToolAnchor(LivingEntity entity, CompatHumanoidMesh mesh, int joint) {
        Frame frame = current();
        if (frame == null || frame.entity != entity || frame.mesh != mesh) {
            return false;
        }
        return switch (joint) {
            case HumanoidRig.RIGHT_TOOL -> frame.formItemDrawDepth > 0
                    || frame.rightFist != null || frame.rightAuthoredItemPose != null;
            case HumanoidRig.LEFT_TOOL -> frame.formItemDrawDepth > 0
                    || frame.leftFist != null || frame.leftAuthoredItemPose != null;
            default -> false;
        };
    }

    static boolean formHidesHeldItem(LivingEntity entity, InteractionHand hand) {
        Frame frame = current();
        return frame != null && frame.entity == entity && !frame.suppressHeldItemPose
                && (hand == InteractionHand.MAIN_HAND
                ? frame.hideFormMainHand : frame.hideFormOffHand);
    }

    static boolean hasVisibleFormHeldItem(LivingEntity entity, InteractionHand hand) {
        Frame frame = current();
        return frame != null && frame.entity == entity && !frame.firstPerson
                && !frame.suppressHeldItemPose && finite(hand == InteractionHand.MAIN_HAND
                ? frame.formMainHandPose : frame.formOffHandPose);
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
        return shouldSuppressHeldItem(frame.suppressHeldItemPose,
                frame.mesh.replacesHeldItem(entity, hand),
                right ? frame.suppressRightHeldItem : frame.suppressLeftHeldItem,
                hasVisibleFormHeldItem(entity, hand), formHidesHeldItem(entity, hand));
    }

    static boolean shouldSuppressHeldItem(
            boolean suppressHeldItemPose, boolean modelReplaces,
            boolean authoredLocatorSuppresses, boolean visibleFormLocator,
            boolean hiddenFormLocator) {
        // A visible form's mouth supersedes a collapsed inactive human locator, but
        // never resurrects an item hidden by authored geometry or movement policy.
        return shouldSuppressHeldItem(suppressHeldItemPose, modelReplaces,
                hiddenFormLocator || !visibleFormLocator && authoredLocatorSuppresses);
    }

    static boolean shouldSuppressHeldItem(
            boolean suppressHeldItemPose, boolean modelReplaces,
            boolean authoredLocatorSuppresses) {
        // Natural ladder and Epic ParCool running poses hide the YSM prop. Keep
        // Epic Fight's back item only when the selected model does not replace it;
        // an inactive hand/form locator must not hide that stowed ordinary item.
        return suppressHeldItemPose ? modelReplaces
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
        frame.heldItemPoses = null;
        frame.suppressRightHeldItem = false;
        frame.suppressLeftHeldItem = false;
        frame.mainHandItemSwitchUsesOffArmTool = false;
        frame.suppressHeldItemPose = false;
        frame.ladderItemsInHand = Set.of();
        frame.formMainHandPose = null;
        frame.formOffHandPose = null;
        frame.hideFormMainHand = false;
        frame.hideFormOffHand = false;
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
