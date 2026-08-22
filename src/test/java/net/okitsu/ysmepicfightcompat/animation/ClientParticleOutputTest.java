package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientParticleOutputTest {
    @Test
    void appliesOfficialDefaultsAndRetainsTheParticleCommand() {
        ClientParticleOutput.Request request = ClientParticleOutput.request(
                new String[]{"minecraft:dust 1 0 0 1"}, new double[1], false);

        assertEquals("minecraft:dust 1 0 0 1", request.particle());
        assertEquals(0.0D, request.offsetX());
        assertEquals(0, request.count());
        assertEquals(20, request.lifetime());
        assertFalse(request.absolute());
    }

    @Test
    void boundsUntrustedParticleArguments() {
        ClientParticleOutput.Request request = ClientParticleOutput.request(
                new String[]{"minecraft:note"},
                new double[]{0.0D, 100.0D, -100.0D, Double.NaN,
                        500.0D, -500.0D, Double.POSITIVE_INFINITY,
                        -30.0D, 100_000.0D, 100_000.0D}, true);

        assertEquals(ClientParticleOutput.MAX_OFFSET, request.offsetX());
        assertEquals(-ClientParticleOutput.MAX_OFFSET, request.offsetY());
        assertEquals(0.0D, request.offsetZ());
        assertEquals(ClientParticleOutput.MAX_DELTA, request.deltaX());
        assertEquals(-ClientParticleOutput.MAX_DELTA, request.deltaY());
        assertEquals(0.0D, request.deltaZ());
        assertEquals(-ClientParticleOutput.MAX_SPEED, request.speed());
        assertEquals(ClientParticleOutput.MAX_COUNT, request.count());
        assertEquals(ClientParticleOutput.MAX_LIFETIME, request.lifetime());
        assertTrue(request.absolute());
    }

    @Test
    void rejectsAnAbsentParticleIdentifier() {
        assertNull(ClientParticleOutput.request(new String[0], new double[0], false));
        assertNull(ClientParticleOutput.request(new String[]{" "}, new double[1], false));
    }

    @Test
    void rotatesRelativeOffsetsButLeavesAbsoluteOffsetsInWorldAxes() {
        Vec3 relative = ClientParticleOutput.rotateOffset(1.0D, 2.0D, 0.0D,
                90.0F, false);
        Vec3 absolute = ClientParticleOutput.rotateOffset(1.0D, 2.0D, 0.0D,
                90.0F, true);

        assertEquals(0.0D, relative.x, 1.0E-6D);
        assertEquals(2.0D, relative.y, 1.0E-6D);
        assertEquals(1.0D, relative.z, 1.0E-6D);
        assertEquals(1.0D, absolute.x, 1.0E-6D);
        assertEquals(2.0D, absolute.y, 1.0E-6D);
        assertEquals(0.0D, absolute.z, 1.0E-6D);
    }
}
