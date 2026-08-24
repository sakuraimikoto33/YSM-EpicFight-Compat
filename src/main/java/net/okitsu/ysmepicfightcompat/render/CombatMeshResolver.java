package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Chooses and prepares one shared converted mesh for the player currently being drawn. */
public final class CombatMeshResolver {
    private static final Set<String> LOGGED_USES = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_MISSES = ConcurrentHashMap.newKeySet();

    private CombatMeshResolver() {
    }

    public static AssetAccessor<HumanoidMesh> forPlayer(AbstractClientPlayer player) {
        if (player == null) {
            return null;
        }
        PlayerSelectionResolver.Selection selection = PlayerSelectionResolver.current(player);
        return selection == null ? null : forSelection(player, selection.modelId(),
                selection.textureName(), player.getGameProfile().getName());
    }

    public static boolean hasReadyMesh(AbstractClientPlayer player) {
        PlayerSelectionResolver.Selection selection = player == null
                ? null : PlayerSelectionResolver.current(player);
        return selection != null && CombatMeshCache.isReady(selection.modelId());
    }

    public static AssetAccessor<HumanoidMesh> forSelection(
            LivingEntity entity, String modelId, String textureName, String displayName) {
        if (entity == null || modelId == null || modelId.isBlank()) {
            return null;
        }
        AssetAccessor<CompatHumanoidMesh> source = CombatMeshCache.find(modelId);
        if (source == null) {
            if (LOGGED_MISSES.add(entity.getUUID() + "|" + modelId)) {
                CompatMod.LOG.debug(
                        "YSM-EF Compat: waiting for combat mesh '{}' used by '{}'",
                        modelId, displayName);
            }
            return null;
        }
        try {
            CompatHumanoidMesh mesh = source.get();
            CombatMeshCache.markUsed(modelId);
            ResourceLocation texture = CombatMeshCache.texture(modelId, textureName);
            if (texture != null) {
                CombatMeshCache.requestTextureUpload(texture);
            }
            mesh.texture(texture);
            if (LOGGED_USES.add(entity.getUUID() + "|" + modelId + "|" + textureName)) {
                CompatMod.LOG.info(
                        "YSM-EF Compat: '{}' uses converted model '{}' texture '{}'",
                        displayName, modelId, textureName);
            }
        } catch (RuntimeException exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: unable to prepare model '{}'", modelId, exception);
            return null;
        }
        @SuppressWarnings("unchecked")
        AssetAccessor<HumanoidMesh> result = (AssetAccessor<HumanoidMesh>) (AssetAccessor<?>) source;
        return result;
    }
}
