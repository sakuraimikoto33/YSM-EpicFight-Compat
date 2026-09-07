package net.okitsu.ysmepicfightcompat.integration.swem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwemAnimationAccessTest {
    @AfterEach
    void clearTokens() {
        SwemAnimationAccess.clear();
    }

    @Test
    void mapsOnlyTheElevenVerifiedNativeRiderNames() {
        Map<String, String> names = Map.ofEntries(
                Map.entry("StandIdlePlayer", "swem:idle"),
                Map.entry("WalkPlayer", "swem:walk"),
                Map.entry("TrotPlayer", "swem:trot"),
                Map.entry("CanterPlayer", "swem:canter"),
                Map.entry("ExtendedCanterPlayer", "swem:canter_ext"),
                Map.entry("GallopPlayer", "swem:gallop"),
                Map.entry("JumpLvl1Player", "swem:jump_lv1"),
                Map.entry("JumpLvl2Player", "swem:jump_lv2"),
                Map.entry("JumpLvl3Player", "swem:jump_lv3"),
                Map.entry("JumpLvl4Player", "swem:jump_lv4"),
                Map.entry("JumpLvl5Player", "swem:jump_lv5"));
        names.forEach((name, clip) -> {
            assertEquals(clip, SwemAnimationAccess.canonicalClip(name));
            assertEquals(clip, SwemAnimationAccess.canonicalClip(name.toUpperCase(Locale.ROOT)));
            assertEquals(clip, SwemAnimationAccess.readAnimation(new NativePlayer(name), 0).clipName());
        });
        for (String unsupported : new String[]{"", "StandIdle", "Walk", "SadWalkPlayer",
                "WalkLeftPlayer", "FlyPlayer", "JumpLvl6Player", "swem:idle", " WalkPlayer"}) {
            assertNull(SwemAnimationAccess.canonicalClip(unsupported));
        }
        assertNull(SwemAnimationAccess.canonicalClip(null));
    }

    @Test
    void observesNativePlaybackPhaseWithoutAdvancingOrMutatingIt() {
        NativePlayer animation = new NativePlayer("JumpLvl3Player");
        animation.tick = 7;
        NativeLayer layer = new NativeLayer(animation);

        SwemAnimationAccess.Snapshot sample = SwemAnimationAccess.readLayer(layer, 0.5F);

        assertNotNull(sample);
        assertEquals("swem:jump_lv3", sample.clipName());
        assertEquals(0.375, sample.elapsedSeconds());
        assertEquals(7, animation.tick);
        assertTrue(animation.active);
        assertEquals("JumpLvl3Player", animation.data.extraData.get("name"));
        assertEquals(animation, layer.animation);
    }

    @Test
    void sameNativePlayerKeepsRestartTokenAcrossTicksAndLoopWrap() {
        NativePlayer animation = new NativePlayer("WalkPlayer");
        animation.tick = 20;
        SwemAnimationAccess.Snapshot first = SwemAnimationAccess.readLayer(new NativeLayer(animation), 0);
        animation.tick = 21;
        SwemAnimationAccess.Snapshot advanced = SwemAnimationAccess.readLayer(new NativeLayer(animation), 0);
        animation.tick = 2;
        SwemAnimationAccess.Snapshot looped = SwemAnimationAccess.readLayer(new NativeLayer(animation), 0);

        assertEquals(first.restartToken(), advanced.restartToken());
        assertEquals(first.restartToken(), looped.restartToken());
        assertEquals(1.0, first.elapsedSeconds());
        assertEquals(1.05, advanced.elapsedSeconds());
        assertEquals(0.1, looped.elapsedSeconds());
    }

    @Test
    void replacementNativePlayerStartsNewTokenEvenWhenClipAndDataAreShared() {
        NativePlayer first = new NativePlayer("JumpLvl1Player");
        NativePlayer restarted = new NativePlayer("JumpLvl1Player");
        restarted.data = first.data;

        assertNotEquals(SwemAnimationAccess.readAnimation(first, 0).restartToken(),
                SwemAnimationAccess.readAnimation(restarted, 0).restartToken());
    }

    @Test
    void clearingCacheDoesNotReuseAnOldRestartToken() {
        NativePlayer animation = new NativePlayer("GallopPlayer");
        long first = SwemAnimationAccess.readAnimation(animation, 0).restartToken();
        SwemAnimationAccess.clear();
        assertNotEquals(first, SwemAnimationAccess.readAnimation(animation, 0).restartToken());
    }

    @Test
    void finishedAnimationAndMissingLayerFailOpen() {
        NativePlayer animation = new NativePlayer("JumpLvl5Player");
        animation.active = false;
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        assertNull(SwemAnimationAccess.readAnimation(null, 0));
        assertNull(SwemAnimationAccess.readLayer(null, 0));
        assertNull(SwemAnimationAccess.readLayer(new NativeLayer(null), 0));
        assertNull(SwemAnimationAccess.sample(null));
        assertFalse(SwemAnimationAccess.isSwemHorseType(null));
        assertFalse(SwemAnimationAccess.isSwemHorseType(NativePlayer.class));
    }

    @Test
    void invalidMetadataAndNegativeTickFailOpen() {
        NativePlayer animation = new NativePlayer("WalkPlayer");
        animation.tick = -1;
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.tick = 0;
        animation.data.extraData.put("name", 1);
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.data.extraData.put("name", "FlyPlayer");
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.data.extraData.clear();
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.data.extraData = null;
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.data = null;
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
    }

    @Test
    void partialTickIsFiniteAndClamped() {
        NativePlayer animation = new NativePlayer("TrotPlayer");
        animation.tick = 4;
        assertEquals(0.2, SwemAnimationAccess.readAnimation(animation, -2).elapsedSeconds());
        assertEquals(0.25, SwemAnimationAccess.readAnimation(animation, 2).elapsedSeconds());
        assertEquals(0.2, SwemAnimationAccess.readAnimation(animation, Float.NaN).elapsedSeconds());
        assertEquals(0.2, SwemAnimationAccess.readAnimation(animation, Float.POSITIVE_INFINITY).elapsedSeconds());
        assertEquals(0.2, SwemAnimationAccess.readAnimation(animation, Float.NEGATIVE_INFINITY).elapsedSeconds());
    }

    @Test
    void absentPrivateStaticAndWrongSignatureGettersFailOpen() {
        assertNull(SwemAnimationAccess.readLayer(new Object(), 0));
        assertNull(SwemAnimationAccess.readLayer(new StaticLayer(), 0));
        assertNull(SwemAnimationAccess.readLayer(new PrivateLayer(), 0));
        assertNull(SwemAnimationAccess.readLayer(new PrimitiveLayer(), 0));
        assertNull(SwemAnimationAccess.readAnimation(new Object(), 0));
        assertNull(SwemAnimationAccess.readAnimation(new WrongTickType(), 0));
        assertNull(SwemAnimationAccess.readAnimation(new StaticTick(), 0));
        assertNull(SwemAnimationAccess.readAnimation(new WrongMetadataType(), 0));
    }

    @Test
    void publicRuntimeAndLinkageFailuresFailOpenWithoutHidingFatalErrors() {
        assertNull(SwemAnimationAccess.readLayer(new ThrowingLayer(), 0));
        NativePlayer animation = new NativePlayer("StandIdlePlayer");
        animation.failure = new IllegalStateException("Unavailable animator");
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.failure = new NoClassDefFoundError("Optional dependency");
        assertNull(SwemAnimationAccess.readAnimation(animation, 0));
        animation.failure = new AssertionError("Fatal error");
        assertThrows(AssertionError.class, () -> SwemAnimationAccess.readAnimation(animation, 0));
    }

    public static final class NativeLayer {
        final Object animation;

        NativeLayer(Object animation) {
            this.animation = animation;
        }

        public Object getAnimation() {
            return animation;
        }
    }

    public static class NativePlayer {
        boolean active = true;
        int tick;
        NativeData data;
        Throwable failure;

        NativePlayer(String name) {
            data = new NativeData();
            data.extraData.put("name", name);
        }

        public boolean isActive() throws Throwable {
            if (failure != null) {
                throw failure;
            }
            return active;
        }

        public int getTick() {
            return tick;
        }

        public NativeData getData() {
            return data;
        }
    }

    public static final class NativeData {
        public HashMap<String, Object> extraData = new HashMap<>();
    }

    public static final class StaticLayer {
        public static Object getAnimation() {
            return null;
        }
    }

    public static final class PrivateLayer {
        private Object getAnimation() {
            return null;
        }
    }

    public static final class PrimitiveLayer {
        public int getAnimation() {
            return 0;
        }
    }

    public static final class ThrowingLayer {
        public Object getAnimation() {
            throw new IllegalStateException("Unavailable layer");
        }
    }

    public static final class WrongTickType {
        public boolean isActive() {
            return true;
        }

        public long getTick() {
            return 0;
        }

        public NativeData getData() {
            return new NativeData();
        }
    }

    public static final class StaticTick {
        public boolean isActive() {
            return true;
        }

        public static int getTick() {
            return 0;
        }

        public NativeData getData() {
            return new NativeData();
        }
    }

    public static final class WrongMetadataType {
        public boolean isActive() {
            return true;
        }

        public int getTick() {
            return 0;
        }

        public Object getData() {
            return new NativeData();
        }
    }
}
