package net.okitsu.ysmepicfightcompat.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.DefaultPoseProgram;
import net.okitsu.ysmepicfightcompat.animation.ParallelAnimationProgram;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
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
import java.util.LinkedHashSet;
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
    private final MovementPoseTransition movementPoseTransition;
    @Nullable
    private final SkinnedMesh glowMesh;
    private final boolean hasBaseGeometry;
    private final Set<Map.Entry<String, MeshPart>> allParts;
    private ResourceLocation texture;

    public CompatHumanoidMesh(String modelId, DefaultPoseProgram poseProgram,
                              ParallelAnimationProgram parallelAnimations,
                              AuxiliaryBoneLayout auxiliaryBones,
                              Map<String, Number[]> arrays,
                              Map<MeshPartDefinition, List<VertexBuilder>> parts,
                              Map<MeshPartDefinition, List<VertexBuilder>> glowParts,
                              @Nullable SkinnedMesh parent, RenderProperties properties) {
        super(arrays, parts, parent, properties);
        this.modelId = modelId;
        this.poseProgram = poseProgram;
        this.parallelAnimations = parallelAnimations;
        hasBaseGeometry = hasGeometry(parts);
        // A parent-backed SkinnedMesh reuses the parent's parts as well as its arrays.
        // Keep this mesh independent so the glow-only part definitions are retained.
        glowMesh = glowParts.isEmpty() ? null
                : new SkinnedMesh(arrays, glowParts, null, properties);
        allParts = collectParts(this, glowMesh);
        auxiliaryPoses = auxiliaryBones.isEmpty() ? null
                : new AuxiliaryPoseMatrices(auxiliaryBones);
        movementPoseTransition = auxiliaryPoses == null ? null
                : new MovementPoseTransition();
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

    /** Returns whether this model authors steady held geometry for an arbitrary item. */
    public boolean authorsHeldItem(LivingEntity entity, ItemStack stack) {
        return parallelAnimations.authorsHeldItem(entity, stack);
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
        advanceAnimationOutputs(entity, firstPerson, true);
    }

    /**
     * Advances non-rendered outputs while retaining Epic Fight's ownership of action
     * frames. The action bit must be derived from the same patch used by rendering.
     */
    public void advanceAnimationOutputs(LivingEntity entity, boolean firstPerson,
                                        boolean epicFightActionActive) {
        parallelAnimations.advanceOutputs(entity, firstPerson,
                epicFightActionActive);
    }

    /** Drops sounds, particles, and controller state owned by one unloaded entity. */
    public void releaseAnimationState(LivingEntity entity) {
        parallelAnimations.releaseEntity(entity);
    }

    /** Drops all entity-scoped outputs before this converted mesh leaves the cache. */
    public void releaseAllAnimationStates() {
        parallelAnimations.releaseAllEntities();
    }

    /** Returns whether an authored custom-bow action owns this model's complete pose. */
    public boolean replacesBodyPose(LivingEntity entity) {
        return parallelAnimations.replacesBodyPose(entity);
    }

    /** Whether a held-item switch temporarily owns the complete YSM body pose. */
    public boolean itemSwitchOwnsPose(LivingEntity entity) {
        return parallelAnimations.itemSwitchOwnsPose(entity);
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
        return allParts;
    }

    @Override
    public void initialize() {
        super.initialize();
        if (glowMesh != null) {
            glowMesh.initialize();
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (glowMesh != null) {
            glowMesh.destroy();
        }
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
        float partialTick = frame == null ? 0.0F
                : Minecraft.getInstance().getFrameTime();
        ParallelAnimationProgram.Frame animationFrame = frame == null
                || parallelAnimations.isEmpty() ? null
                : parallelAnimations.sample(frame.entity(),
                partialTick, frame.firstPerson(), frame.epicModelYaw(),
                frame.epicFightActionActive(), frame.ysmMovement());
        poseProgram.apply(this, frame == null ? Map.of() : frame.visibleParts(),
                frame == null || frame.showUnlistedParts(), frame != null && frame.firstPerson(),
                animationFrame == null ? null : animationFrame.hiddenBones());
        float meshScale = TouhouMaidRenderBridge.meshDrawScale(this);
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
                Set<InteractionHand> currentItemSwitchHands = animationFrame == null
                        ? Set.of() : animationFrame.itemSwitchHands();
                boolean rawRightItemSwitch = ownsItemSwitchTool(
                        frame == null ? null : frame.entity(), currentItemSwitchHands,
                        HumanoidRig.RIGHT_TOOL);
                boolean rawLeftItemSwitch = ownsItemSwitchTool(
                        frame == null ? null : frame.entity(), currentItemSwitchHands,
                        HumanoidRig.LEFT_TOOL);
                boolean rawSuppressRightItem = rawRightItemSwitch && collapsed(
                        auxiliaryPoses.authoredHeldItemPose(
                                complete, HumanoidRig.RIGHT_TOOL));
                boolean rawSuppressLeftItem = rawLeftItemSwitch && collapsed(
                        auxiliaryPoses.authoredHeldItemPose(
                                complete, HumanoidRig.LEFT_TOOL));
                Set<InteractionHand> displayedItemSwitchHands = currentItemSwitchHands;
                if (frame != null && movementPoseTransition != null) {
                    displayedItemSwitchHands = movementPoseTransition.apply(
                            frame.entity(), frame.firstPerson(),
                            frame.entity().tickCount + partialTick,
                            animationFrame == null ? null
                                    : animationFrame.movementPoseKey(),
                            currentItemSwitchHands,
                            frame.epicFightActionActive(), auxiliaryPoses, complete);
                }
                if (frame != null) {
                    parallelAnimations.publishBoneQueries(frame.entity(), complete,
                            animationFrame == null ? Set.of() : animationFrame.hiddenBones());
                    boolean rightItemSwitch = ownsItemSwitchTool(
                            frame.entity(), displayedItemSwitchHands,
                            HumanoidRig.RIGHT_TOOL);
                    boolean leftItemSwitch = ownsItemSwitchTool(
                            frame.entity(), displayedItemSwitchHands,
                            HumanoidRig.LEFT_TOOL);
                    Vector3f rightFist = auxiliaryPoses.displayedFist(
                            complete, HumanoidRig.RIGHT_TOOL);
                    Vector3f leftFist = auxiliaryPoses.displayedFist(
                            complete, HumanoidRig.LEFT_TOOL);
                    OpenMatrix4f rightAuthoredItemPose = rightItemSwitch
                            ? auxiliaryPoses.authoredHeldItemPose(
                            complete, HumanoidRig.RIGHT_TOOL) : null;
                    OpenMatrix4f leftAuthoredItemPose = leftItemSwitch
                            ? auxiliaryPoses.authoredHeldItemPose(
                            complete, HumanoidRig.LEFT_TOOL) : null;
                    OpenMatrix4f elytraLocatorPose =
                            auxiliaryPoses.elytraLocatorPose(complete);
                    OpenMatrix4f[] attachmentPoses = projectsDisplayedAttachments(
                            frame.epicFightActionActive(),
                            animationFrame != null
                                    && animationFrame.replaceEpicFightPose())
                            ? auxiliaryPoses.displayedAttachmentPoses(
                            armature, complete, inputPoses, meshScale,
                            rightItemSwitch, leftItemSwitch) : null;
                    boolean suppressRightItem = rightItemSwitch
                            && (rawRightItemSwitch ? rawSuppressRightItem
                            : collapsed(rightAuthoredItemPose));
                    boolean suppressLeftItem = leftItemSwitch
                            && (rawLeftItemSwitch ? rawSuppressLeftItem
                            : collapsed(leftAuthoredItemPose));
                    boolean mainHandItemSwitchUsesOffArmTool =
                            ordinaryMainhandBowSwitch(
                                    frame.entity(), displayedItemSwitchHands);
                    RenderFrameContext.publishHeldItemPoints(
                            frame.entity(), this, inputPoses, rightFist, leftFist,
                            rightAuthoredItemPose, leftAuthoredItemPose,
                            elytraLocatorPose,
                            attachmentPoses,
                            suppressRightItem, suppressLeftItem,
                            mainHandItemSwitchUsesOffArmTool,
                            animationFrame != null
                                    && animationFrame.naturalLadderPose(),
                            animationFrame == null ? Set.of()
                                    : animationFrame.ladderItemsInHand());
                }
                poses = complete;
                armature = null;
            } else {
                if (frame != null) {
                    RenderFrameContext.publishHeldItemPoints(
                            frame.entity(), this, inputPoses, null, null, null, null,
                            null,
                            null,
                            false, false, false, false, Set.of());
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
        boolean restoreScale = meshScale != 1.0F;
        if (restoreScale) {
            matrices.pushPose();
            matrices.scale(meshScale, meshScale, meshScale);
        }
        try {
            if (hasBaseGeometry) {
                drawSkinned(this, matrices, buffers, actualType, drawingFunction, light,
                        red, green, blue, alpha, overlay, armature, poses);
            }
            if (glowMesh != null) {
                drawSkinned(glowMesh, matrices, buffers, actualType, drawingFunction,
                        LightTexture.FULL_BRIGHT, red, green, blue, alpha, overlay,
                        armature, poses);
            }
        } finally {
            if (restoreScale) {
                matrices.popPose();
            }
        }
    }

    private boolean ownsItemSwitchTool(
            @Nullable LivingEntity entity, Set<InteractionHand> itemSwitchHands,
            int toolJoint) {
        if (entity == null || itemSwitchHands == null) {
            return false;
        }
        boolean right = toolJoint == HumanoidRig.RIGHT_TOOL;
        if (!right && toolJoint != HumanoidRig.LEFT_TOOL) {
            return false;
        }
        boolean mirroredMainhandBow = ordinaryMainhandBowSwitch(
                entity, itemSwitchHands);
        return ownsItemSwitchTool(itemSwitchHands, toolJoint,
                entity.getMainArm(), mirroredMainhandBow);
    }

    static boolean ownsItemSwitchTool(
            Set<InteractionHand> itemSwitchHands, int toolJoint,
            HumanoidArm mainArm, boolean mirroredMainhandBow) {
        if (itemSwitchHands == null || mainArm == null
                || toolJoint != HumanoidRig.RIGHT_TOOL
                && toolJoint != HumanoidRig.LEFT_TOOL) {
            return false;
        }
        boolean right = toolJoint == HumanoidRig.RIGHT_TOOL;
        boolean toolUsesMainArm = right == (mainArm == HumanoidArm.RIGHT);
        if (mirroredMainhandBow
                && itemSwitchHands.contains(InteractionHand.MAIN_HAND)
                && !toolUsesMainArm) {
            return true;
        }
        InteractionHand logicalHand = toolUsesMainArm
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        return !(mirroredMainhandBow && logicalHand == InteractionHand.MAIN_HAND)
                && itemSwitchHands.contains(logicalHand);
    }

    private static boolean hasGeometry(
            Map<MeshPartDefinition, List<VertexBuilder>> parts) {
        return parts.values().stream().anyMatch(vertices -> !vertices.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Set<Map.Entry<String, MeshPart>> collectParts(
            SkinnedMesh base, @Nullable SkinnedMesh glow) {
        Set<Map.Entry<String, MeshPart>> result = new LinkedHashSet<>();
        result.addAll((Set<Map.Entry<String, MeshPart>>) (Set<?>) base.getPartEntry());
        if (glow != null) {
            result.addAll((Set<Map.Entry<String, MeshPart>>) (Set<?>) glow.getPartEntry());
        }
        return Set.copyOf(result);
    }

    private static void drawSkinned(
            SkinnedMesh mesh, PoseStack matrices, MultiBufferSource buffers,
            RenderType type, Mesh.DrawingFunction drawingFunction, int light,
            float red, float green, float blue, float alpha, int overlay,
            @Nullable Armature armature, OpenMatrix4f[] poses) {
        ComputeShaderSetup compute = computeSetup(mesh);
        if (compute != null) {
            compute.drawWithShader(mesh, matrices, buffers, type, light,
                    red, green, blue, alpha, overlay, armature, poses);
            return;
        }
        if (CPU_FALLBACK_LOGGED.compareAndSet(false, true)) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: compute skinning is unavailable; using Epic Fight's CPU path");
        }
        // replaceTexture and getTriangulated share Epic Fight's render-type cache.
        // A texture replacement can therefore leave the original QUADS mode in that
        // cache, even though this mesh emits triangle triplets. Use the independent
        // conversion so CPU skinning never regroups every four vertices into a quad.
        mesh.drawPosed(matrices,
                buffers.getBuffer(EpicFightRenderTypes.makeTriangulated(type)),
                drawingFunction, light, red, green, blue, alpha, overlay,
                armature, poses);
    }

    /** Keeps Epic Fight's action matrices unless YSM owns the complete displayed pose. */
    static boolean projectsDisplayedAttachments(
            boolean epicFightActionActive, boolean ysmReplacesEpicFightPose) {
        return !epicFightActionActive || ysmReplacesEpicFightPose;
    }

    /** Epic Fight deliberately renders its ordinary bow at the off-arm Tool joint. */
    private boolean ordinaryMainhandBowSwitch(
            LivingEntity entity, Set<InteractionHand> itemSwitchHands) {
        return itemSwitchHands.contains(InteractionHand.MAIN_HAND)
                && entity.getMainHandItem().getUseAnimation() == UseAnim.BOW
                && !parallelAnimations.replacesHeldItem(
                entity, InteractionHand.MAIN_HAND);
    }

    private static boolean collapsed(@Nullable OpenMatrix4f matrix) {
        if (matrix == null) {
            return false;
        }
        float determinant = matrix.m00 * (matrix.m11 * matrix.m22 - matrix.m12 * matrix.m21)
                - matrix.m10 * (matrix.m01 * matrix.m22 - matrix.m02 * matrix.m21)
                + matrix.m20 * (matrix.m01 * matrix.m12 - matrix.m02 * matrix.m11);
        return Float.isFinite(determinant) && Math.abs(determinant) < 1.0E-8F;
    }

    @Nullable
    private static ComputeShaderSetup computeSetup(SkinnedMesh mesh) {
        if (COMPUTE_SETUP == null) {
            return null;
        }
        try {
            return (ComputeShaderSetup) COMPUTE_SETUP.get(mesh);
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
