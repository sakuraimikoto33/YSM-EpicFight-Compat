package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.okitsu.ysmepicfightcompat.CompatMod;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Gives Epic Fight ownership of combat frames before official YSM's normal renderer runs. */
@EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CombatRenderInterceptor {
    private CombatRenderInterceptor() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforePlayer(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                player, LivingEntityPatch.class);
        if (patch == null || !patch.overrideRender()
                || ClientEngine.getInstance().isVanillaModelDebuggingMode()) {
            return;
        }

        float frameTime = event.getPartialTick();
        RenderEngine engine = RenderEngine.getInstance();
        if ((frameTime == 0.0F || frameTime == 1.0F) && patch instanceof LocalPlayerPatch local) {
            renderGuiPlayer(event, engine, player, patch, local, frameTime);
        } else {
            engine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), frameTime);
        }
        event.setCanceled(true);
    }

    private static void renderGuiPlayer(RenderPlayerEvent.Pre event, RenderEngine engine,
                                        Player player, LivingEntityPatch<?> patch,
                                        LocalPlayerPatch local, float frameTime) {
        float savedYaw = local.getModelYRot();
        local.setModelYRotInGui(player.getYRot());
        event.getPoseStack().translate(0.0D, 0.1D, 0.0D);
        try {
            engine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), frameTime);
        } finally {
            local.disableModelYRotInGui(savedYaw);
        }
    }
}
