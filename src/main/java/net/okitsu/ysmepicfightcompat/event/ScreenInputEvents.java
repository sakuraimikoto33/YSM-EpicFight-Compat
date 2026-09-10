package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.input.ClosingScreenClickPolicy;

/** Keeps a click that closes a GUI from becoming a gameplay click in the same dispatch. */
@EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ScreenInputEvents {
    private ScreenInputEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void mousePressed(ScreenEvent.MouseButtonPressed.Post event) {
        boolean screenClosed = Minecraft.getInstance().screen == null;
        boolean resultUnmodified = event.getResult() == ScreenEvent.MouseButtonPressed.Post.Result.DEFAULT;
        if (ClosingScreenClickPolicy.shouldConsume(
                screenClosed, event.wasClickHandled(), resultUnmodified)) {
            event.setResult(ScreenEvent.MouseButtonPressed.Post.Result.FORCE_HANDLED);
        }
    }
}
