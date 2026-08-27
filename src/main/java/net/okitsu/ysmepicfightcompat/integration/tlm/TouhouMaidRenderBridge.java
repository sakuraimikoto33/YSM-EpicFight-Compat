package net.okitsu.ysmepicfightcompat.integration.tlm;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.render.CombatMeshResolver;
import net.okitsu.ysmepicfightcompat.render.EpicFightPoseOwnership;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Render-thread state joining EFTLM's renderer to the normal converted-mesh path. */
@OnlyIn(Dist.CLIENT)
public final class TouhouMaidRenderBridge {
    private static final String EFTLM_MAID_PATCH =
            "net.EFTLM.EF.Capability.MaidPatch";
    private static final String EFTLM_MAID_RENDERER =
            "net.EFTLM.EF.Render.PatchedLivingMaidRenderer";
    /** Inverse of EFTLM MaidPatch's model-matrix scale (0.8). */
    static final float EFTLM_SCALE_COMPENSATION = 1.25F;

    private static final class Invocation {
        private final Object renderer;
        private final LivingEntity entity;
        private final LivingEntityPatch<?> patch;
        @Nullable
        private final AssetAccessor<HumanoidMesh> provider;
        @Nullable
        private final CompatHumanoidMesh mesh;
        @Nullable
        private final RenderFrameContext.Frame frame;
        private boolean meshDrawn;

        private Invocation(Object renderer, LivingEntity entity,
                           LivingEntityPatch<?> patch,
                           @Nullable AssetAccessor<HumanoidMesh> provider,
                           @Nullable CompatHumanoidMesh mesh,
                           @Nullable RenderFrameContext.Frame frame) {
            this.renderer = renderer;
            this.entity = entity;
            this.patch = patch;
            this.provider = provider;
            this.mesh = mesh;
            this.frame = frame;
        }

        private Object renderer() {
            return renderer;
        }

        private LivingEntity entity() {
            return entity;
        }

        private LivingEntityPatch<?> patch() {
            return patch;
        }

        @Nullable
        private AssetAccessor<HumanoidMesh> provider() {
            return provider;
        }

        @Nullable
        private CompatHumanoidMesh mesh() {
            return mesh;
        }

        @Nullable
        private RenderFrameContext.Frame frame() {
            return frame;
        }
    }

    private static final ThreadLocal<ArrayDeque<Invocation>> INVOCATIONS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ClassValue<Set<String>> SUPERCLASS_NAMES =
            new ClassValue<>() {
                @Override
                protected Set<String> computeValue(Class<?> type) {
                    Set<String> names = new HashSet<>();
                    for (Class<?> current = type; current != null;
                         current = current.getSuperclass()) {
                        names.add(current.getName());
                    }
                    return Set.copyOf(names);
                }
            };

    private TouhouMaidRenderBridge() {
    }

    /** Called at the exact Epic Fight living-render boundary. */
    public static void enter(Object renderer, LivingEntity entity,
                             LivingEntityPatch<?> patch,
                             float partialTick) {
        if (!TouhouMaidSelectionAccess.integrationLoaded()
                || !hasNamedSuperclass(renderer, EFTLM_MAID_RENDERER)
                || !supportsPatch(patch) || patch.getOriginal() != entity) {
            return;
        }
        TouhouMaidSelectionAccess.Selection selection =
                TouhouMaidSelectionAccess.resolve(entity);
        CombatMeshCache.observeEntitySelection(entity,
                selection == null ? null : selection.modelId());
        if (selection == null) {
            return;
        }

        AssetAccessor<HumanoidMesh> provider = CombatMeshResolver.forSelection(
                entity, selection.modelId(), selection.textureName(),
                entity.getDisplayName().getString());
        CompatHumanoidMesh mesh = convertedMesh(provider);
        RenderFrameContext.Frame frame = null;
        if (mesh == null) {
            provider = null;
        } else {
            frame = RenderFrameContext.pushThirdPerson(
                    entity, modelYaw(entity, partialTick),
                    EpicFightPoseOwnership.actionOwnsPose(entity, patch));
            if (!RenderFrameContext.bindMesh(entity, false, mesh)) {
                RenderFrameContext.pop(frame);
                frame = null;
                provider = null;
                mesh = null;
            }
        }
        INVOCATIONS.get().push(new Invocation(
                renderer, entity, patch, provider, mesh, frame));
    }

    /** Returns the provider only inside the matching EFTLM maid render. */
    @Nullable
    public static AssetAccessor<?> meshProvider(
            @Nullable Object renderer, @Nullable Object patchObject) {
        if (!(patchObject instanceof LivingEntityPatch<?> patch)) {
            return null;
        }
        Invocation invocation = current(renderer, patch.getOriginal(), patch);
        return invocation == null ? null : invocation.provider();
    }

    /**
     * Returns the mesh-local inverse scale for the converted mesh in the active maid render.
     * Epic Fight layers keep EFTLM's normal transform because each mesh draw restores the stack.
     */
    public static float meshDrawScale(@Nullable CompatHumanoidMesh candidate) {
        Invocation invocation = INVOCATIONS.get().peek();
        if (candidate == null || invocation == null
                || invocation.mesh() != candidate) {
            return 1.0F;
        }
        invocation.meshDrawn = true;
        return EFTLM_SCALE_COMPENSATION;
    }

    /** Applies item-anchor compensation only after that converted mesh actually drew. */
    public static float heldItemTranslationScale(
            @Nullable CompatHumanoidMesh candidate) {
        Invocation invocation = INVOCATIONS.get().peek();
        return candidate != null && invocation != null
                && invocation.mesh() == candidate && invocation.meshDrawn
                ? EFTLM_SCALE_COMPENSATION : 1.0F;
    }

    /** Ends the matching render while preserving any outer/nested render frame. */
    public static void exit(Object renderer, LivingEntity entity,
                            LivingEntityPatch<?> patch) {
        ArrayDeque<Invocation> invocations = INVOCATIONS.get();
        Invocation invocation = invocations.peek();
        if (invocation == null || invocation.renderer() != renderer
                || invocation.entity() != entity || invocation.patch() != patch) {
            return;
        }
        invocations.pop();
        RenderFrameContext.pop(invocation.frame());
        if (invocations.isEmpty()) {
            INVOCATIONS.remove();
        }
    }

    /** Session/reload cleanup and a guard against a render aborted by another mod. */
    public static void clear() {
        ArrayDeque<Invocation> invocations = INVOCATIONS.get();
        invocations.forEach(invocation -> RenderFrameContext.pop(invocation.frame()));
        INVOCATIONS.remove();
    }

    /** Clears a render scope abandoned by an exception another renderer swallowed. */
    public static void endClientTick() {
        ArrayDeque<Invocation> invocations = INVOCATIONS.get();
        if (invocations.isEmpty()) {
            INVOCATIONS.remove();
            return;
        }
        clear();
    }

    /** Runtime-only patch identity check without linking EFTLM classes. */
    public static boolean supportsPatch(@Nullable Object patch) {
        return hasNamedSuperclass(patch, EFTLM_MAID_PATCH);
    }

    @Nullable
    private static Invocation current(@Nullable Object renderer,
                                      LivingEntity entity,
                                      LivingEntityPatch<?> patch) {
        Invocation invocation = INVOCATIONS.get().peek();
        return invocation != null && invocation.renderer() == renderer
                && invocation.entity() == entity && invocation.patch() == patch
                ? invocation : null;
    }

    @Nullable
    private static CompatHumanoidMesh convertedMesh(
            @Nullable AssetAccessor<HumanoidMesh> provider) {
        if (provider == null) {
            return null;
        }
        try {
            HumanoidMesh mesh = provider.get();
            return mesh instanceof CompatHumanoidMesh compat ? compat : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean hasNamedSuperclass(@Nullable Object source,
                                              String expectedName) {
        if (source == null) {
            return false;
        }
        return SUPERCLASS_NAMES.get(source.getClass()).contains(expectedName);
    }

    private static float modelYaw(LivingEntity entity, float partialTick) {
        Entity vehicle = entity.getVehicle();
        LivingEntity facing = vehicle instanceof LivingEntity living
                ? living : entity;
        return Mth.rotLerp(partialTick, facing.yBodyRotO, facing.yBodyRot);
    }
}
