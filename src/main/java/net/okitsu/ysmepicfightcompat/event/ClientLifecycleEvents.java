package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.compat.YSMCompatibilityWarningFilter;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.render.CombatPlayerRenderer;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;
import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;

/** Registers the compatibility renderer and its client lifecycle hooks. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {
    }

    @SubscribeEvent
    public static void finishLoading(FMLLoadCompleteEvent event) {
        event.enqueueWork(YSMCompatibilityWarningFilter::processRegisteredWarnings);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void installPlayerRenderer(PatchedRenderersEvent.Add event) {
        event.addPatchedEntityRenderer(EntityType.PLAYER, type ->
                new CombatPlayerRenderer(event.getContext(), type)
                        .initLayerLast(event.getContext(), type));
        CompatMod.LOG.info("YSM-EF Compat: installed combat player renderer");
    }

    @SubscribeEvent
    public static void installReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> {
            PlayerSelectionResolver.clear();
            ClientModelTransfers.clear();
            CombatMeshCache.clear();
        });
    }
}
