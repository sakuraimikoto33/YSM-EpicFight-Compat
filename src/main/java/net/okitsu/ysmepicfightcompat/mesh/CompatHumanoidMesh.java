package net.okitsu.ysmepicfightcompat.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.DefaultPoseProgram;
import net.okitsu.ysmepicfightcompat.animation.ParallelAnimationProgram;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.EpicFightRenderTypes;
import yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared in-memory Epic Fight mesh for one official YSM model. */
public final class CompatHumanoidMesh extends HumanoidMesh {
    private static final Field COMPUTE_SETUP = locateComputeSetup();
    private static final AtomicBoolean CPU_FALLBACK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean AUXILIARY_FALLBACK_LOGGED = new AtomicBoolean();

    private final String modelId;
    private final DefaultPoseProgram poseProgram;
    private final ParallelAnimationProgram parallelAnimations;
    private final AuxiliaryPoseMatrices auxiliaryPoses;
    private ResourceLocation texture;

    public CompatHumanoidMesh(String modelId, DefaultPoseProgram poseProgram,
                              ParallelAnimationProgram parallelAnimations,
                              AuxiliaryBoneLayout auxiliaryBones,
                              Map<String, Number[]> arrays,
                              Map<MeshPartDefinition, List<VertexBuilder>> parts,
                              @Nullable SkinnedMesh parent, RenderProperties properties) {
        super(arrays, parts, parent, properties);
        this.modelId = modelId;
        this.poseProgram = poseProgram;
        this.parallelAnimations = parallelAnimations;
        auxiliaryPoses = auxiliaryBones.isEmpty() ? null
                : new AuxiliaryPoseMatrices(auxiliaryBones);
    }

    public String modelId() {
        return modelId;
    }

    public void texture(ResourceLocation value) {
        texture = value;
    }

    /** Returns whether the model authors the held prop for this item condition. */
    public boolean replacesHeldItem(LivingEntity entity, InteractionHand hand) {
        return parallelAnimations.replacesHeldItem(entity, hand);
    }

    /** Returns whether this item has a YSM replacement rule for an attack. */
    public boolean replacesAttackItem(LivingEntity entity, InteractionHand hand) {
        return parallelAnimations.replacesAttackItem(entity, hand);
    }

    /** Returns whether the current replacement attack has an authored YSM sound route. */
    public boolean hasAttackSoundRoute(LivingEntity entity, InteractionHand hand) {
        return parallelAnimations.hasAttackSoundRoute(entity, hand);
    }

    /** Advances sound/controller outputs when this player was not rendered during the tick. */
    public void advanceAnimationOutputs(LivingEntity entity, boolean firstPerson) {
        parallelAnimations.advanceOutputs(entity, firstPerson);
    }

    /** Returns whether an authored custom-bow action owns this model's complete pose. */
    public boolean replacesBodyPose(LivingEntity entity) {
        return parallelAnimations.replacesBodyPose(entity);
    }

    /** Returns the current model-space pose of a held-item locator when one was derived. */
    @Nullable
    public OpenMatrix4f heldItemPose(@Nullable Armature armature,
                                    @Nullable OpenMatrix4f[] poses, int joint,
                                    @Nullable Vector3f displayedFist) {
        return auxiliaryPoses == null ? null : auxiliaryPoses.heldItemPose(
                armature, poses, joint, displayedFist);
    }

    @SuppressWarnings("unchecked")
    public Set<Map.Entry<String, MeshPart>> partsView() {
        return (Set<Map.Entry<String, MeshPart>>) (Set<?>) getPartEntry();
    }

    @Override
    public void draw(PoseStack matrices, MultiBufferSource buffers, RenderType requestedType,
                     Mesh.DrawingFunction drawingFunction, int light,
                     float red, float green, float blue, float alpha, int overlay,
                     @Nullable Armature armature, OpenMatrix4f[] poses) {
        RenderFrameContext.Frame frame = RenderFrameContext.current();
        if (frame != null && !frame.isBoundTo(this)) {
            frame = null;
        }
        ParallelAnimationProgram.Frame animationFrame = frame == null
                || parallelAnimations.isEmpty() ? null
                : parallelAnimations.sample(frame.entity(),
                Minecraft.getInstance().getFrameTime(), frame.firstPerson(),
                frame.epicModelYaw());
        poseProgram.apply(this, frame == null ? Map.of() : frame.visibleParts(),
                frame == null || frame.showUnlistedParts(), frame != null && frame.firstPerson(),
                animationFrame == null ? null : animationFrame.hiddenBones());
        if (auxiliaryPoses != null) {
            OpenMatrix4f[] inputPoses = poses;
            OpenMatrix4f[] complete = auxiliaryPoses.compose(armature, poses,
                    animationFrame == null ? null : animationFrame.parallelDeltas(),
                    animationFrame == null ? null : animationFrame.wholeModelDeltas(),
                    animationFrame == null ? null : animationFrame.heldItemDeltas(),
                    animationFrame != null && animationFrame.replaceEpicFightPose(),
                    animationFrame == null ? null
                            : animationFrame.replaceEpicFightAnchors(),
                    animationFrame == null ? null
                            : animationFrame.suppressParallelDeltas(),
                    animationFrame == null ? null
                            : animationFrame.heldItemAnchorJoints(),
                    animationFrame == null ? null
                            : animationFrame.fullBodyBlendSource(),
                    animationFrame == null ? 0.0F
                            : animationFrame.fullBodyBlendWeight());
            if (complete != null) {
                if (frame != null) {
                    Vector3f rightFist = auxiliaryPoses.displayedFist(
                            complete, HumanoidRig.RIGHT_TOOL);
                    Vector3f leftFist = auxiliaryPoses.displayedFist(
                            complete, HumanoidRig.LEFT_TOOL);
                    RenderFrameContext.publishHeldItemPoints(
                            frame.entity(), this, inputPoses, rightFist, leftFist);
                }
                poses = complete;
                armature = null;
            } else {
                if (frame != null) {
                    RenderFrameContext.publishHeldItemPoints(
                            frame.entity(), this, inputPoses, null, null);
                }
                if (AUXILIARY_FALLBACK_LOGGED.compareAndSet(false, true)) {
                    CompatMod.LOG.warn("YSM-EF Compat: auxiliary pose matrices are unavailable");
                }
            }
        } else if (armature != null && poses != null && poses.length > armature.getJointNumber()) {
            poses = Arrays.copyOf(poses, armature.getJointNumber());
        }
        ResourceLocation selectedTexture = texture != null ? texture
                : getRenderProperties() == null ? null : getRenderProperties().customTexturePath();
        RenderType actualType = selectedTexture == null ? requestedType
                : EpicFightRenderTypes.replaceTexture(selectedTexture, requestedType);
        ComputeShaderSetup compute = computeSetup();
        if (compute != null) {
            compute.drawWithShader(this, matrices, buffers, actualType, light,
                    red, green, blue, alpha, overlay, armature, poses);
        } else {
            if (CPU_FALLBACK_LOGGED.compareAndSet(false, true)) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: compute skinning is unavailable; using Epic Fight's CPU path");
            }
            // replaceTexture and getTriangulated share Epic Fight's render-type cache.
            // A texture replacement can therefore leave the original QUADS mode in that
            // cache, even though this mesh emits triangle triplets. Use the independent
            // conversion so CPU skinning never regroups every four vertices into a quad.
            drawPosed(matrices, buffers.getBuffer(EpicFightRenderTypes.makeTriangulated(actualType)),
                    drawingFunction, light, red, green, blue, alpha, overlay, armature, poses);
        }
    }

    @Nullable
    private ComputeShaderSetup computeSetup() {
        if (COMPUTE_SETUP == null) {
            return null;
        }
        try {
            return (ComputeShaderSetup) COMPUTE_SETUP.get(this);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Field locateComputeSetup() {
        try {
            Field field = SkinnedMesh.class.getDeclaredField("computerShaderSetup");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
