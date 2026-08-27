package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ClientAttackSoundRouter;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;
import net.okitsu.ysmepicfightcompat.animation.OfficialRoamingVariables;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.ClientHeldItemModelPreferences;
import net.okitsu.ysmepicfightcompat.network.ClientMovementAnimationPreferences;
import net.okitsu.ysmepicfightcompat.network.ClientMaidPreferenceSync;
import net.okitsu.ysmepicfightcompat.network.RemoteMaidPreferences;
import net.okitsu.ysmepicfightcompat.network.RemoteSelectionState;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

/** Clears session state and schedules conversion refreshes after official YSM reloads. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
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
        ClientAttackSoundRouter.clear();
        ClientHeldItemModelPreferences.beginConnection();
        ClientMovementAnimationPreferences.beginConnection();
        ClientMaidPreferenceSync.beginConnection();
        TouhouMaidRenderBridge.clear();
        reloadCountdown = -1;
        failureCountdown = 0;
        Minecraft.getInstance().execute(CombatMeshCache::clear);
    }

    @SubscribeEvent
    public static void playerLeftLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()
                || !(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        CombatMeshCache.releaseEntity(entity);
        if (TouhouMaidSelectionAccess.isSupportedMaid(entity)) {
            RemoteMaidPreferences.remove(entity.getUUID());
        }
        if (entity instanceof Player player) {
            OfficialConfigurationVariables.reset(player);
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
        }
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        TouhouMaidRenderBridge.endClientTick();
        CombatMeshCache.advanceAnimationOutputs();
        ClientAttackSoundRouter.tick();
        ClientHeldItemModelPreferences.tickSync();
        ClientMovementAnimationPreferences.tickSync();
        ClientMaidPreferenceSync.tickSync();
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
