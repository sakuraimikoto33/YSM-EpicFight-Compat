package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.compat.YSMCompatibilityWarningFilter;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.ClientSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.render.CombatPlayerRenderer;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent;

/** Registers the compatibility renderer and its client lifecycle hooks. */
@EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {
    }

    @SubscribeEvent
    public static void finishLoading(FMLLoadCompleteEvent event) {
        event.enqueueWork(YSMCompatibilityWarningFilter::processRegisteredWarnings);
    }

    @SubscribeEvent
    public static void registerEpicFightHooks(FMLClientSetupEvent event) {
        // Setup work completes before the initial renderer resource reload.
        // Epic Fight keys listeners by priority, so reserve a distinct final slot.
        event.enqueueWork(() -> EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(
                ClientLifecycleEvents::installPlayerRenderer,
                CompatMod.MOD_ID + ":combat_player_renderer", Integer.MIN_VALUE));
    }

    public static void installPlayerRenderer(RegisterPatchedRenderersEvent.AddEntity event) {
        event.addPatchedEntityRenderer(EntityType.PLAYER, type ->
                new CombatPlayerRenderer(event.getContext(), type)
                        .initLayerLast(event.getContext(), type));
        CompatMod.LOG.info("YSM-EF Compat: installed combat player renderer");
    }

    @SubscribeEvent
    public static void installReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> {
            ClientSubEntityModelPreferences.modelDefinitionsUpdated();
            PlayerSelectionResolver.clear();
            ClientModelTransfers.clear();
            CombatMeshCache.clear();
        });
    }
}
