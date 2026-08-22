package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSoundOutputTest {
    @Test
    void parsesOfficialPlaybackFlagsAndBounds() {
        ClientSoundOutput.PlayRequest request = ClientSoundOutput.request(
                new String[]{"loop", "model.bell", null, null, null},
                new double[]{0.0D, 0.0D, 7.0D, 0.0D, 2000.0D});

        assertEquals("text:loop", request.id());
        assertEquals("model.bell", request.sound());
        assertTrue(request.forceReplace());
        assertTrue(request.global());
        assertTrue(request.looping());
        assertEquals(0.001F, request.volume());
        assertEquals(1000.0F, request.pitch());
    }

    @Test
    void acceptsNumericIdsAndRejectsInvalidCalls() {
        ClientSoundOutput.PlayRequest request = ClientSoundOutput.request(
                new String[]{null, "minecraft:bell"}, new double[]{12.8D, 0.0D});

        assertEquals("number:12", request.id());
        assertFalse(request.looping());
        assertNull(ClientSoundOutput.request(
                new String[]{null, "model.bell"}, new double[]{0.0D, 0.0D}).id());
        assertNull(ClientSoundOutput.request(
                new String[]{null, "model.bell"}, new double[]{-1.0D, 0.0D}));
        assertNull(ClientSoundOutput.request(
                new String[]{"id", "model.bell", null}, new double[]{0.0D, 0.0D, 8.0D}));
    }
}
