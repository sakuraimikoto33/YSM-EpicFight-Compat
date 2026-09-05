package net.okitsu.ysmepicfightcompat.render;

import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A read-only armature view lasting exactly one attachment-layer call. Some item
 * renderers ignore the layer's pose array and ask the player's armature again.
 * Keep those reads on the body pose already drawn without replacing the concrete
 * armature type, changing its stored gameplay matrices, or re-evaluating YSM.
 */
public final class AttachmentArmatureScope implements AutoCloseable {
    private static final String[] BODY_JOINT_NAMES = {
            "Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
            "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
            "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"
    };
    private static final ThreadLocal<ArrayDeque<AttachmentArmatureScope>> CURRENT =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Nullable
    private final Armature armature;
    @Nullable
    private final RenderFrameContext.Frame frame;
    @Nullable
    private final OpenMatrix4f[] displayedBody;
    private final Set<OpenMatrix4f[]> displayedWorldArrays =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    private AttachmentArmatureScope(@Nullable Armature armature,
                                    @Nullable RenderFrameContext.Frame frame,
                                    @Nullable OpenMatrix4f[] displayedBody) {
        this.armature = armature;
        this.frame = frame;
        this.displayedBody = displayedBody;
    }

    /**
     * Invalid or pass-through input still opens an inactive nested barrier. This
     * prevents an outer player's attachment scope from reaching another render.
     */
    public static AttachmentArmatureScope open(
            @Nullable Armature armature, @Nullable OpenMatrix4f[] originalBodyPoses,
            @Nullable OpenMatrix4f[] displayedLayerPoses) {
        RenderFrameContext.Frame frame = RenderFrameContext.current();
        OpenMatrix4f[] snapshot = frame == null ? null
                : snapshot(armature, originalBodyPoses, displayedLayerPoses);
        AttachmentArmatureScope scope = new AttachmentArmatureScope(armature, frame, snapshot);
        CURRENT.get().push(scope);
        return scope;
    }

    /** An attachment's re-pose must not overwrite the player's gameplay armature. */
    public static boolean suppressPoseWrite(Armature armature) {
        return active(armature) != null;
    }

    /**
     * Overlays only the fixed humanoid body joints. Add-on joints retain the
     * caller's matrices and array extent, including independently animated ones.
     * Returned matrices are copies so a layer cannot damage another layer's view.
     */
    public static OpenMatrix4f[] resolvePoseMatrices(
            Armature armature, OpenMatrix4f[] requestedPoses, boolean applyToOrigin) {
        AttachmentArmatureScope scope = active(armature);
        if (scope == null || requestedPoses == null
                || requestedPoses.length < HumanoidRig.EPIC_JOINT_COUNT) {
            return requestedPoses;
        }
        OpenMatrix4f[] result = new OpenMatrix4f[requestedPoses.length];
        for (int joint = 0; joint < result.length; joint++) {
            if (joint < HumanoidRig.EPIC_JOINT_COUNT) {
                result[joint] = new OpenMatrix4f(scope.displayedBody[joint]);
                if (applyToOrigin) {
                    result[joint].mulBack(armature.searchJointById(joint).getToOrigin());
                }
            } else {
                result[joint] = requestedPoses[joint] == null ? null
                        : new OpenMatrix4f(requestedPoses[joint]);
            }
        }
        if (!applyToOrigin) {
            scope.displayedWorldArrays.add(result);
        }
        return result;
    }

    /** Recognizes this scope's world-pose copies so item correction is applied once. */
    public static boolean isDisplayedPoseArray(Armature armature, OpenMatrix4f[] poses) {
        AttachmentArmatureScope scope = active(armature);
        return scope != null && scope.displayedWorldArrays.contains(poses);
    }

    /** Same-frame provenance for locator consumers that do not receive an armature. */
    public static boolean isDisplayedPoseArray(OpenMatrix4f[] poses) {
        AttachmentArmatureScope scope = CURRENT.get().peek();
        if (scope == null) {
            CURRENT.remove();
            return false;
        }
        return active(scope.armature) == scope && scope.displayedWorldArrays.contains(poses);
    }

    /** A single world-space joint lookup, preserving unknown/add-on joints. */
    public static OpenMatrix4f resolveJointPose(
            Armature armature, @Nullable Joint joint, OpenMatrix4f requestedPose) {
        AttachmentArmatureScope scope = active(armature);
        if (scope == null || joint == null) {
            return requestedPose;
        }
        // Epic Fight's getter resolves the supplied joint by name in this
        // armature, so an add-on may pass a joint from the canonical biped.
        Joint ownJoint = armature.searchJointByName(joint.getName());
        int id = ownJoint == null ? -1 : ownJoint.getId();
        if (id < 0 || id >= HumanoidRig.EPIC_JOINT_COUNT) {
            return requestedPose;
        }
        return new OpenMatrix4f(scope.displayedBody[id]);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        displayedWorldArrays.clear();
        ArrayDeque<AttachmentArmatureScope> scopes = CURRENT.get();
        if (scopes.peek() == this) {
            scopes.pop();
        } else {
            scopes.removeFirstOccurrence(this);
        }
        if (scopes.isEmpty()) {
            CURRENT.remove();
        }
    }

    @Nullable
    private static AttachmentArmatureScope active(Armature armature) {
        ArrayDeque<AttachmentArmatureScope> scopes = CURRENT.get();
        AttachmentArmatureScope scope = scopes.peek();
        if (scope == null) {
            CURRENT.remove();
            return null;
        }
        return !scope.closed && scope.armature == armature && scope.displayedBody != null
                && scope.frame == RenderFrameContext.current() ? scope : null;
    }

    @Nullable
    private static OpenMatrix4f[] snapshot(
            @Nullable Armature armature, @Nullable OpenMatrix4f[] original,
            @Nullable OpenMatrix4f[] displayed) {
        if (armature == null || original == null || displayed == null
                || original == displayed || original.length != displayed.length
                || displayed.length < HumanoidRig.EPIC_JOINT_COUNT) {
            return null;
        }
        OpenMatrix4f[] copy = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        for (int id = 0; id < copy.length; id++) {
            Joint joint = armature.searchJointById(id);
            if (joint == null || !BODY_JOINT_NAMES[id].equals(joint.getName())
                    || !finite(original[id]) || !finite(displayed[id])
                    || !finite(joint.getToOrigin())) {
                return null;
            }
            copy[id] = new OpenMatrix4f(displayed[id]);
        }
        return copy;
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
}
