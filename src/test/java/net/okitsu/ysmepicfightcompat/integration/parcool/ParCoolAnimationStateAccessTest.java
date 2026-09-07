package net.okitsu.ysmepicfightcompat.integration.parcool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParCoolAnimationStateAccessTest {
    private static final ParCoolAnimationStateAccess.RuntimeAccess ACCESS = fixtureAccess();

    @AfterEach
    void clearState() {
        ParCoolAnimationStateAccess.clear();
    }

    @Test
    void mapsAllThirtyEightOfficialDefaultClipNames() {
        Set<String> clips = new HashSet<>();
        for (String name : new String[]{"BackwardWallJumpAnimator", "CatLeapAnimator",
                "ClimbUpAnimator", "DiveAnimationHostAnimator", "DiveIntoWaterAnimator",
                "FastRunningAnimator", "FastSwimAnimator", "VerticalWallRunAnimator",
                "JumpFromBarAnimator", "KongVaultAnimator", "SlidingAnimator", "TapAnimator",
                "JumpChargingAnimator", "ChargeJumpAnimator", "RideZiplineAnimator"}) {
            clips.add(ParCoolAnimationClips.simpleClip(name));
        }
        for (String name : new String[]{"DodgeAnimator", "RollAnimator"}) {
            for (String direction : new String[]{"Front", "Back", "Left", "Right"}) {
                clips.add(ParCoolAnimationClips.variantClip(name, direction));
            }
        }
        for (String direction : new String[]{"Front", "Back"}) {
            clips.add(ParCoolAnimationClips.variantClip("FlippingAnimator", direction));
        }
        for (String name : new String[]{"SpeedVaultAnimator", "HorizontalWallRunAnimator",
                "WallJumpAnimator", "WallSlideAnimator"}) {
            for (String direction : new String[]{"Left", "Right"}) {
                clips.add(ParCoolAnimationClips.variantClip(name, direction));
            }
        }
        for (String direction : new String[]{"ToWall", "LeftAgainstWall", "RightAgainstWall"}) {
            clips.add(ParCoolAnimationClips.variantClip("ClingToCliffAnimator", direction));
        }
        clips.add(ParCoolAnimationClips.variantClip("HangAnimator", "Across"));
        clips.add(ParCoolAnimationClips.variantClip("HangAnimator", "Along"));

        assertEquals(Set.of("parcool:backward_wall_jump", "parcool:cat_leap",
                "parcool:climb_up", "parcool:cling_to_cliff", "parcool:cling_to_cliff_left",
                "parcool:cling_to_cliff_right", "parcool:dive_animation_host",
                "parcool:dive_into_water", "parcool:dodge_front", "parcool:dodge_back",
                "parcool:dodge_left", "parcool:dodge_right", "parcool:fast_running",
                "parcool:fast_swim", "parcool:flipping_front", "parcool:flipping_back",
                "parcool:horizontal_wall_run_right", "parcool:horizontal_wall_run_left",
                "parcool:vertical_wall_run", "parcool:jump_from_bar", "parcool:hang",
                "parcool:hang_vertical", "parcool:kong_vault", "parcool:speed_vault_left",
                "parcool:speed_vault_right", "parcool:roll_front", "parcool:roll_back",
                "parcool:roll_left", "parcool:roll_right", "parcool:sliding", "parcool:tap",
                "parcool:wall_jump_left", "parcool:wall_jump_right", "parcool:wall_slide_left",
                "parcool:wall_slide_right", "parcool:jump_charging", "parcool:charge_jump",
                "parcool:ride_zipline"), clips);
    }

    @Test
    void readsNamedDirectionWithoutMutatingNativeAnimator() {
        DuckPlayer player = new DuckPlayer();
        DirectionAnimator animator = new DirectionAnimator(Direction.Left);
        animator.tick = 7;

        var snapshot = sample(player, animator, "DodgeAnimator", 0.5F);

        assertNotNull(snapshot);
        assertEquals("parcool:dodge_left", snapshot.clipName());
        assertEquals(0.375D, snapshot.elapsedSeconds());
        assertEquals(1L, snapshot.generation());
        assertEquals(7, animator.tick);
        assertEquals(Direction.Left, animator.direction);
    }

    @Test
    void preservesNativePhaseAndDetectsSameClipRestart() {
        DuckPlayer player = new DuckPlayer();
        DuckAnimator first = new DuckAnimator();
        first.tick = 40;
        var initial = sample(player, first, "FastRunningAnimator", 0.25F);
        first.tick++;
        var continued = sample(player, first, "FastRunningAnimator", 0.75F);
        DuckAnimator repeated = new DuckAnimator();
        var restart = sample(player, repeated, "FastRunningAnimator", 0.25F);

        assertNotNull(initial);
        assertNotNull(continued);
        assertNotNull(restart);
        assertEquals(2.0125D, initial.elapsedSeconds());
        assertEquals(2.0875D, continued.elapsedSeconds());
        assertEquals(initial.generation(), continued.generation());
        assertEquals(initial.generation() + 1L, restart.generation());
        assertEquals(0.0125D, restart.elapsedSeconds());
    }

    @Test
    void observesNativeOneShotEndEvenWhenItsActionRemainsActive() {
        DuckAnimator animator = new DuckAnimator();
        DuckPlayer player = new DuckPlayer();
        assertNotNull(sample(player, animator, "ChargeJumpAnimator", 0.0F));
        animator.finished = true;
        assertNull(sample(player, animator, "ChargeJumpAnimator", 0.0F));
        assertTrue(animator.finished);
    }

    @Test
    void remotePlayersHaveIndependentClocksAndRestartTokens() {
        DuckPlayer local = new DuckPlayer();
        DuckPlayer remote = new DuckPlayer();
        DuckAnimator localAnimator = new DuckAnimator();
        DuckAnimator remoteAnimator = new DuckAnimator();
        localAnimator.tick = 5;
        remoteAnimator.tick = 3;
        assertEquals(0.25D, sample(local, localAnimator, "CatLeapAnimator", 0).elapsedSeconds());
        assertEquals(0.15D, sample(remote, remoteAnimator, "CatLeapAnimator", 0).elapsedSeconds());
        assertEquals(2L, sample(local, new DuckAnimator(), "CatLeapAnimator", 0).generation());
        assertEquals(1L, sample(remote, remoteAnimator, "CatLeapAnimator", 0).generation());
    }

    @Test
    void resetClockRestartsAndReleaseDropsOnlyThatPlayer() {
        DuckPlayer player = new DuckPlayer();
        DuckAnimator animator = new DuckAnimator();
        animator.tick = 5;
        sample(player, animator, "CatLeapAnimator", 0);
        animator.tick = 0;
        assertEquals(2L, sample(player, animator, "CatLeapAnimator", 0).generation());
        ParCoolAnimationStateAccess.release(player);
        assertEquals(1L, sample(player, animator, "CatLeapAnimator", 0).generation());
    }

    @Test
    void malformedOrUnknownOptionalStateFallsBack() {
        DuckPlayer player = new DuckPlayer();
        assertNull(ParCoolAnimationStateAccess.sample(null, 0));
        assertNull(sample(player, new DuckAnimator(), "DodgeAnimator", 0));
        assertNull(sample(player, new WrongDirectionAnimator(), "DodgeAnimator", 0));
        assertNull(sample(player, new DirectionAnimator(null), "DodgeAnimator", 0));
        assertNull(sample(player, new DuckAnimator(), "UnknownAnimator", 0));
        assertNull(sample(player, new DuckAnimator(), "CrawlAnimator", 0));
        assertNull(ParCoolAnimationClips.variantClip("FlippingAnimator", "Left"));
        assertNull(ParCoolAnimationClips.variantClip("DodgeAnimator", "FutureDirection"));
        DuckAnimator failing = new DuckAnimator();
        failing.fail = true;
        assertNull(sample(player, failing, "CatLeapAnimator", 0));
    }

    @Test
    void clampsPartialTicksWithoutInvalidTimesOrChangingNativeClock() {
        DuckPlayer player = new DuckPlayer();
        DuckAnimator animator = new DuckAnimator();
        animator.tick = 1;
        assertEquals(0.05D, sample(player, animator, "TapAnimator", Float.NaN).elapsedSeconds());
        assertEquals(0.05D, sample(player, animator, "TapAnimator", -1).elapsedSeconds());
        assertEquals(0.10D, sample(player, animator, "TapAnimator", 2).elapsedSeconds());
        animator.tick = -1;
        assertNull(sample(player, animator, "TapAnimator", 0));
    }

    @Test
    void wallSideTracksBodyYawAndRejectsInvalidVectors() {
        assertEquals("Right", ParCoolAnimationStateAccess.wallSide(0, -1, 0));
        assertEquals("Left", ParCoolAnimationStateAccess.wallSide(0, 1, 0));
        assertEquals("Right", ParCoolAnimationStateAccess.wallSide(90, 0, -1));
        assertEquals("Left", ParCoolAnimationStateAccess.wallSide(90, 0, 1));
        assertNull(ParCoolAnimationStateAccess.wallSide(0, 0, 0));
        assertNull(ParCoolAnimationStateAccess.wallSide(Float.NaN, 1, 0));
        assertNull(ParCoolAnimationStateAccess.wallSide(0, Double.POSITIVE_INFINITY, 0));
    }

    @Test
    void hangVariantMatchesTheOfficialArmSwingAxis() {
        assertEquals("parcool:hang_vertical",
                ParCoolAnimationClips.variantClip("HangAnimator", "Across"));
        assertEquals("parcool:hang",
                ParCoolAnimationClips.variantClip("HangAnimator", "Along"));
    }

    private static ParCoolAnimationStateAccess.Snapshot sample(
            DuckPlayer player, DuckAnimator animator, String name, float partialTick) {
        return ParCoolAnimationStateAccess.readAnimator(player, animator,
                new DuckParkour(), 0, partialTick, ACCESS,
                ParCoolAnimationStateAccess.discoverClip(animator.getClass(), name));
    }

    private static ParCoolAnimationStateAccess.RuntimeAccess fixtureAccess() {
        try {
            return ParCoolAnimationStateAccess.RuntimeAccess.discover(DuckAnimation.class,
                    DuckParkour.class, DuckAnimator.class, DuckPlayer.class);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static final class DuckPlayer {
    }

    public static final class DuckAnimation {
        private DuckAnimator animator;

        public static DuckAnimation get(DuckPlayer player) {
            return null;
        }
    }

    public static final class DuckParkour {
        public static DuckParkour get(DuckPlayer player) {
            return null;
        }

        public Object get(Class<?> type) {
            return null;
        }
    }

    public static class DuckAnimator {
        int tick;
        boolean finished;
        boolean fail;

        protected int getTick() {
            return tick;
        }

        public boolean shouldRemoved(DuckPlayer player, DuckParkour parkour) {
            if (fail) {
                throw new IllegalStateException("optional API mismatch");
            }
            return finished;
        }
    }

    public enum Direction { Front, Back, Left, Right }

    public static final class DirectionAnimator extends DuckAnimator {
        private final Direction direction;

        DirectionAnimator(Direction direction) {
            this.direction = direction;
        }
    }

    public static final class WrongDirectionAnimator extends DuckAnimator {
        private final String direction = "Left";
    }
}
