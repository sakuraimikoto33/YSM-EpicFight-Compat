package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;

/** Model-authorship lookup kept separate from user display preferences. */
public final class SubEntityModelAuthorship {
    public enum State {
        UNKNOWN,
        PRESENT,
        ABSENT
    }

    private SubEntityModelAuthorship() {
    }

    /**
     * Resolves steady held geometry from the converted player model. UNKNOWN asks
     * the caller to retry after asynchronous conversion rather than treating a
     * not-yet-ready model as projectile-only.
     */
    public static State heldItem(String modelId, LivingEntity owner, ItemStack stack) {
        if (modelId == null || modelId.isBlank() || owner == null
                || stack == null || stack.isEmpty()) {
            return State.ABSENT;
        }
        CompatHumanoidMesh mesh = CombatMeshCache.readyMesh(modelId);
        if (mesh == null) {
            CombatMeshCache.find(modelId, owner);
            return State.UNKNOWN;
        }
        return mesh.authorsHeldItem(owner, stack) ? State.PRESENT : State.ABSENT;
    }
}
