package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ClientAttackSoundRouter;
import net.okitsu.ysmepicfightcompat.animation.ClientShieldBlockState;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;
import net.okitsu.ysmepicfightcompat.animation.OfficialRoamingVariables;
import net.okitsu.ysmepicfightcompat.animation.OfficialGroundSpeedQuery;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.ClientHeldItemModelPreferences;
import net.okitsu.ysmepicfightcompat.network.ClientMovementAnimationPreferences;
import net.okitsu.ysmepicfightcompat.network.ClientMaidPreferenceSync;
import net.okitsu.ysmepicfightcompat.network.ClientSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.RemoteMaidPreferences;
import net.okitsu.ysmepicfightcompat.network.RemoteSelectionState;
import net.okitsu.ysmepicfightcompat.network.RemoteSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

/** Clears session state and schedules conversion refreshes after official YSM reloads. */
@EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientMaintenanceEvents {
    private static final int RELOAD_DELAY = 40;
    private static final int FAILURE_RECHECK_INTERVAL = 100;
    private static int reloadCountdown = -1;
    private static int failureCountdown;

    private ClientMaintenanceEvents() {
    }

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        PlayerSelectionResolver.clear();
        RemoteSelectionState.beginConnection();
        ClientModelTransfers.clear();
        OfficialConfigurationVariables.clear();
        OfficialRoamingVariables.clear();
        OfficialGroundSpeedQuery.clear();
        ClientAttackSoundRouter.clear();
        ClientShieldBlockState.clear();
        ClientHeldItemModelPreferences.beginConnection();
        ClientMovementAnimationPreferences.beginConnection();
        ClientMaidPreferenceSync.beginConnection();
        ClientSubEntityModelPreferences.beginConnection();
        TouhouMaidRenderBridge.clear();
        reloadCountdown = -1;
        failureCountdown = 0;
        Minecraft.getInstance().execute(CombatMeshCache::clear);
    }

    @SubscribeEvent
    public static void playerLeftLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity removed = event.getEntity();
        RemoteSubEntityModelPreferences.remove(removed.getUUID());
        if (removed instanceof LivingEntity entity) {
            ClientShieldBlockState.remove(entity);
            CombatMeshCache.releaseEntity(entity);
            if (TouhouMaidSelectionAccess.isSupportedMaid(entity)) {
                RemoteMaidPreferences.remove(entity.getUUID());
            }
            if (entity instanceof Player player) {
                OfficialConfigurationVariables.reset(player);
                OfficialGroundSpeedQuery.remove(player);
            }
        }
    }

    @SubscribeEvent
    public static void commandFinished(CommandEvent event) {
        String input = event.getParseResults().getReader().getString();
        if (isYsmReload(input)) {
            reloadCountdown = RELOAD_DELAY;
        }
    }

    @SubscribeEvent
    public static void tagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause()
                == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
            ClientMaidPreferenceSync.itemTagsUpdated();
            ClientSubEntityModelPreferences.entityTypesUpdated();
        }
    }

    @SubscribeEvent
    public static void renderFrame(RenderFrameEvent.Pre event) {
        CombatMeshCache.uploadReadyTextures();
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        TouhouMaidRenderBridge.endClientTick();
        CombatMeshCache.advanceAnimationOutputs();
        ClientAttackSoundRouter.tick();
        OfficialConfigurationVariables.tickSync();
        ClientHeldItemModelPreferences.tickSync();
        ClientMovementAnimationPreferences.tickSync();
        ClientMaidPreferenceSync.tickSync();
        ClientSubEntityModelPreferences.tickSync();
        CombatMeshCache.maintainModelCache();
        CombatMeshCache.releaseExpiredTextures();
        if (++failureCountdown >= FAILURE_RECHECK_INTERVAL) {
            failureCountdown = 0;
            CombatMeshCache.retryChangedFailures();
        }
        if (reloadCountdown > 0) {
            reloadCountdown--;
        } else if (reloadCountdown == 0) {
            reloadCountdown = -1;
            Minecraft.getInstance().execute(() -> {
                ClientSubEntityModelPreferences.modelDefinitionsUpdated();
                PlayerSelectionResolver.clear();
                ClientModelTransfers.clear();
                TouhouMaidRenderBridge.clear();
                CombatMeshCache.clear();
            });
        }
    }

    private static boolean isYsmReload(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim().replaceAll("\\s+", " ");
        return normalized.equals("ysm reload") || normalized.startsWith("ysm reload ")
                || normalized.equals("ysm model reload")
                || normalized.startsWith("ysm model reload ");
    }
}
