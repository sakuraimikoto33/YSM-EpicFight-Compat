package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityAnimationEnvironmentTest {
    @Test
    void matchesOfficialYsmHeadQueryDirections() {
        assertEquals(-30.0F, EntityAnimationEnvironment.officialHeadPitch(30.0F));
        assertEquals(25.0F, EntityAnimationEnvironment.officialHeadPitch(-25.0F));
        assertEquals(-45.0F,
                EntityAnimationEnvironment.officialHeadYaw(65.0F, 20.0F));
        assertEquals(-85.0F,
                EntityAnimationEnvironment.officialHeadYaw(170.0F, 0.0F));
    }
}
