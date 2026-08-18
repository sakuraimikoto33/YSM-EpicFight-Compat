package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.input.ClosingScreenClickPolicy;

/** Keeps a click that closes a GUI from becoming a gameplay click in the same dispatch. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ScreenInputEvents {
    private ScreenInputEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void mousePressed(ScreenEvent.MouseButtonPressed.Post event) {
        boolean screenClosed = Minecraft.getInstance().screen == null;
        boolean resultUnmodified = event.getResult() == Event.Result.DEFAULT;
        if (ClosingScreenClickPolicy.shouldConsume(
                screenClosed, event.wasHandled(), resultUnmodified)) {
            event.setResult(Event.Result.ALLOW);
        }
    }
}
