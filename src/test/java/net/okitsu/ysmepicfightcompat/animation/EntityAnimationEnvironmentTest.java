package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void yawSpeedUsesTheShortestWrappedTickDelta() {
        assertEquals(200.0F, EntityAnimationEnvironment.yawSpeed(10.0F, 0.0F));
        assertEquals(400.0F, EntityAnimationEnvironment.yawSpeed(-170.0F, 170.0F));
    }

    @Test
    void officialAngleMathPreservesFractionsAndUsesTheShortestArc() {
        assertEquals(60.25D, EntityAnimationEnvironment.minimumAngle(780.25D),
                1.0E-12D);
        assertEquals(-179.75D, EntityAnimationEnvironment.minimumAngle(180.25D),
                1.0E-12D);
        assertEquals(360.0D, EntityAnimationEnvironment.lerpRotate(350.0D, 10.0D, 0.5D),
                1.0E-12D);
        assertEquals(0.0D, EntityAnimationEnvironment.lerpRotate(10.0D, 350.0D, 0.5D),
                1.0E-12D);
    }

    @Test
    void dieRollFunctionsAreBoundedAndIntegerRollsIncludeBothEnds() {
        Random minimum = new Random() {
            @Override
            public double nextDouble() {
                return 0.0D;
            }
        };
        Random maximum = new Random() {
            @Override
            public double nextDouble() {
                return Math.nextDown(1.0D);
            }
        };

        assertEquals(6.0D, EntityAnimationEnvironment.dieRoll(minimum,
                new double[]{3.0D, 2.0D, 5.0D}, false), 1.0E-12D);
        assertEquals(15.0D, EntityAnimationEnvironment.dieRoll(maximum,
                new double[]{3.0D, 2.0D, 5.0D}, true), 1.0E-12D);
        assertEquals(0.0D, EntityAnimationEnvironment.dieRoll(minimum,
                new double[]{-1.0D, 2.0D, 5.0D}, true), 1.0E-12D);
    }

    @Test
    void timeAndCardinalQueriesMatchOfficialYsmConventions() {
        assertEquals(0.0D, EntityAnimationEnvironment.timeOfDay(18000L), 1.0E-12D);
        assertEquals(0.25D, EntityAnimationEnvironment.timeOfDay(0L), 1.0E-12D);
        assertEquals(0.5D, EntityAnimationEnvironment.timeOfDay(6000L), 1.0E-12D);
        assertEquals(0.75D, EntityAnimationEnvironment.timeOfDay(12000L), 1.0E-12D);
        assertEquals(2.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.NORTH));
        assertEquals(3.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.SOUTH));
        assertEquals(4.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.WEST));
        assertEquals(5.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.EAST));
    }

    @Test
    void cameraRotationReturnsWorldPitchAndYawWithAttachedCameraFallback() {
        Vec3 origin = new Vec3(0.0D, 1.0D, 0.0D);
        assertEquals(0.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                new Vec3(0.0D, 1.0D, 5.0D), 12.0F, 34.0F, 0), 1.0E-12D);
        assertEquals(-90.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                new Vec3(5.0D, 1.0D, 0.0D), 12.0F, 34.0F, 1), 1.0E-12D);
        assertEquals(12.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                origin, 12.0F, 34.0F, 0), 1.0E-12D);
        assertEquals(34.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                origin, 12.0F, 34.0F, 1), 1.0E-12D);
    }

    @Test
    void relativeBlockQueriesRejectOffsetsOutsideYsmSafetyLimit() {
        assertEquals(8, EntityAnimationEnvironment.relativeOffset(8.0D));
        assertEquals(-8, EntityAnimationEnvironment.relativeOffset(-8.0D));
        assertNull(EntityAnimationEnvironment.relativeOffset(9.0D));
        assertNull(EntityAnimationEnvironment.relativeOffset(Double.NaN));
    }
}
