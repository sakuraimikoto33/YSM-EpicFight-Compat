package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Gives Epic Fight ownership of combat frames before official YSM's normal renderer runs. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CombatRenderInterceptor {
    private CombatRenderInterceptor() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforePlayer(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                player, LivingEntityPatch.class);
        ClientEngine engine = ClientEngine.getInstance();
        if (patch == null || !patch.overrideRender() || engine.isVanillaModelDebuggingMode()) {
            return;
        }

        float frameTime = event.getPartialTick();
        if ((frameTime == 0.0F || frameTime == 1.0F) && patch instanceof LocalPlayerPatch local) {
            renderGuiPlayer(event, engine, player, patch, local, frameTime);
        } else {
            engine.renderEngine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), frameTime);
        }
        event.setCanceled(true);
    }

    private static void renderGuiPlayer(RenderPlayerEvent.Pre event, ClientEngine engine,
                                        Player player, LivingEntityPatch<?> patch,
                                        LocalPlayerPatch local, float frameTime) {
        float savedYaw = local.getModelYRot();
        local.setModelYRotInGui(player.getYRot());
        event.getPoseStack().translate(0.0D, 0.1D, 0.0D);
        try {
            engine.renderEngine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), frameTime);
        } finally {
            local.disableModelYRotInGui(savedYaw);
        }
    }
}
