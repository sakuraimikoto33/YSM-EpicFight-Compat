package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.config.ConfigTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientModAnimationPreferencesTest {
    private static final String MODEL = "wine_fox/21_saint";
    private static final String OTHER_MODEL = "wine_fox/05_magical";
    private static final String RUN = "parcool:fast_running";
    private static final String ROLL = "parcool:roll_front";
    private static final String GALLOP = "swem:gallop";
    private static final String WALK = "swem:walk";
    private final UUID localPlayerId = UUID.randomUUID();
    private final UUID remotePlayerId = UUID.randomUUID();
    private CommentedFileConfig config;

    @BeforeEach
    void attachClientConfig(@TempDir Path directory) {
        config = ConfigTestSupport.fileConfigBuilder(directory.resolve("client.toml")).sync().build();
        config.load();
        ConfigTestSupport.bind(ClientPreferences.CLIENT_SPEC, config);
        ClientMovementAnimationPreferences.beginConnection();
    }

    @AfterEach
    void clearSessionAndClientConfig() {
        ClientMovementAnimationPreferences.beginConnection();
        ConfigTestSupport.clear(ClientPreferences.CLIENT_SPEC);
        if (config != null) {
            config.close();
        }
    }

    @Test
    void modDefaultsIgnoreOrdinaryMovementAndLadderRules() {
        ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS.set(false);
        ClientPreferences.USE_NATURAL_LADDER_ANIMATIONS.set(false);
        ClientPreferences.setMovementAnimationExclusions(Map.of(
                MODEL, List.of("run", "ladder_up")));
        assertEquals(Map.of(), ClientPreferences.parCoolAnimationExclusions());
        assertEquals(Map.of(), ClientPreferences.swemAnimationExclusions());

        for (ModAnimationType family : ModAnimationType.values()) {
            String clip = family == ModAnimationType.PARCOOL ? RUN : GALLOP;
            MovementAnimationDisplayState state = ClientMovementAnimationPreferences.resolveState(
                    MODEL, MovementAnimationType.RUN, family, clip);
            assertTrue(state.modAnimationOwned());
            assertEquals(clip, state.modAnimationClip());
            assertTrue(ClientMovementAnimationPreferences.usesYsmMod(
                    localPlayerId, localPlayerId, MODEL, family, clip));
            assertFalse(state.ysmOwned());
            assertFalse(state.naturalLadderPose());
        }
        assertFalse(MovementAnimationPolicy.isValidSelector("parcool"));
        assertFalse(MovementAnimationPolicy.isValidSelector("swem"));
    }

    @Test
    void settingsReloadIndependentlyAndRetainDisabledActiveFamily() {
        MovementAnimationDisplayState previous = ClientMovementAnimationPreferences.resolveState(
                MODEL, null, ModAnimationType.PARCOOL, RUN);
        config.set(List.of("common", "animations", "useYsmParCoolAnimations"), false);
        config.save();
        ClientPreferences.CLIENT_SPEC.afterReload();
        MovementAnimationDisplayState changed = ClientMovementAnimationPreferences.resolveState(
                MODEL, null, ModAnimationType.PARCOOL, RUN);

        assertNotEquals(previous, changed);
        assertEquals(ModAnimationType.PARCOOL, changed.modAnimation());
        assertEquals(RUN, changed.modAnimationClip());
        assertFalse(changed.modAnimationOwned());
        assertTrue(ClientMovementAnimationPreferences.resolveState(
                MODEL, null, ModAnimationType.SWEM, GALLOP).modAnimationOwned());

        config.set(List.of("common", "animations", "useYsmParCoolAnimations"), true);
        config.set(List.of("common", "animations", "useYsmSwemAnimations"), false);
        config.save();
        ClientPreferences.CLIENT_SPEC.afterReload();
        assertTrue(ClientMovementAnimationPreferences.resolveState(
                MODEL, null, ModAnimationType.PARCOOL, RUN).modAnimationOwned());
        assertFalse(ClientMovementAnimationPreferences.resolveState(
                MODEL, null, ModAnimationType.SWEM, GALLOP).modAnimationOwned());
    }

    @Test
    void modelClipExclusionsKeepOtherClipsFamiliesAndOrdinaryMovementIndependent() {
        ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS.set(true);
        ClientPreferences.setMovementAnimationExclusions(Map.of(MODEL, List.of("run")));
        ClientPreferences.setParCoolAnimationExclusions(Map.of(MODEL, List.of("fast_running")));
        ClientPreferences.setSwemAnimationExclusions(Map.of(MODEL, List.of("gallop")));

        MovementAnimationDisplayState excluded = ClientMovementAnimationPreferences.resolveState(
                MODEL, MovementAnimationType.WALK, ModAnimationType.PARCOOL, RUN);
        assertFalse(excluded.modAnimationOwned());
        assertEquals(RUN, excluded.modAnimationClip());
        assertTrue(excluded.ysmOwned());
        MovementAnimationDisplayState allowed = ClientMovementAnimationPreferences.resolveState(
                MODEL, MovementAnimationType.RUN, ModAnimationType.PARCOOL, ROLL);
        assertTrue(allowed.modAnimationOwned());
        assertFalse(allowed.ysmOwned());

        assertFalse(local(MODEL, ModAnimationType.PARCOOL, RUN));
        assertTrue(local(MODEL, ModAnimationType.PARCOOL, ROLL));
        assertTrue(local(OTHER_MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(local(MODEL, ModAnimationType.SWEM, GALLOP));
        assertTrue(local(MODEL, ModAnimationType.SWEM, WALK));
        assertTrue(local(OTHER_MODEL, ModAnimationType.SWEM, GALLOP));
    }

    @Test
    void cachedPoliciesObserveRuleEditsAndReloadEachFamilyIndependently() {
        assertTrue(local(MODEL, ModAnimationType.PARCOOL, RUN));
        assertTrue(local(MODEL, ModAnimationType.SWEM, GALLOP));
        ClientPreferences.setParCoolAnimationExclusions(Map.of(MODEL, List.of("fast_running")));
        ClientPreferences.setSwemAnimationExclusions(Map.of(MODEL, List.of("gallop")));
        assertFalse(local(MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(local(MODEL, ModAnimationType.SWEM, GALLOP));

        config.set(List.of("common", "animations", "exclusions", "parcoolAnimationExclusions"),
                ModAnimationPolicy.encodeConfiguration(ModAnimationType.PARCOOL,
                        Map.of(MODEL, List.of("roll_front"))));
        config.save();
        config.load();
        ClientPreferences.CLIENT_SPEC.afterReload();
        assertTrue(local(MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(local(MODEL, ModAnimationType.PARCOOL, ROLL));
        assertFalse(local(MODEL, ModAnimationType.SWEM, GALLOP));

        config.set(List.of("common", "animations", "exclusions", "swemAnimationExclusions"),
                ModAnimationPolicy.encodeConfiguration(ModAnimationType.SWEM,
                        Map.of(MODEL, List.of("walk"))));
        config.save();
        config.load();
        ClientPreferences.CLIENT_SPEC.afterReload();
        assertTrue(local(MODEL, ModAnimationType.SWEM, GALLOP));
        assertFalse(local(MODEL, ModAnimationType.SWEM, WALK));
        assertFalse(local(MODEL, ModAnimationType.PARCOOL, ROLL));

        ClientPreferences.setParCoolAnimationExclusions(Map.of());
        ClientPreferences.setSwemAnimationExclusions(Map.of());
        assertTrue(local(MODEL, ModAnimationType.PARCOOL, ROLL));
        assertTrue(local(MODEL, ModAnimationType.SWEM, WALK));
    }

    @Test
    void exclusionsCannotEnableADisabledFamily() {
        ClientPreferences.USE_YSM_PARCOOL_ANIMATIONS.set(false);
        ClientPreferences.USE_YSM_SWEM_ANIMATIONS.set(false);
        ClientPreferences.setParCoolAnimationExclusions(Map.of(MODEL, List.of("fast_running")));
        ClientPreferences.setSwemAnimationExclusions(Map.of(MODEL, List.of("gallop")));
        for (String model : List.of(MODEL, OTHER_MODEL)) {
            assertFalse(local(model, ModAnimationType.PARCOOL, RUN));
            assertFalse(local(model, ModAnimationType.PARCOOL, ROLL));
            assertFalse(local(model, ModAnimationType.SWEM, GALLOP));
            assertFalse(local(model, ModAnimationType.SWEM, WALK));
        }
        ClientPreferences.setParCoolAnimationExclusions(Map.of());
        ClientPreferences.setSwemAnimationExclusions(Map.of());
        assertFalse(local(MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(local(MODEL, ModAnimationType.SWEM, GALLOP));
    }

    @Test
    void remoteDisplayUsesOwnerDecisionRatherThanObserverSettings() {
        ClientPreferences.USE_YSM_PARCOOL_ANIMATIONS.set(false);
        ClientPreferences.setParCoolAnimationExclusions(Map.of(MODEL, List.of("fast_running")));
        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.PARCOOL, true, RUN));

        assertTrue(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                localPlayerId, localPlayerId, MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.SWEM, GALLOP));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, OTHER_MODEL, ModAnimationType.PARCOOL, RUN));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.PARCOOL, ROLL));

        ClientPreferences.USE_YSM_PARCOOL_ANIMATIONS.set(true);
        ClientPreferences.setParCoolAnimationExclusions(Map.of());
        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.PARCOOL, false, RUN));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.PARCOOL, RUN));
        assertTrue(local(MODEL, ModAnimationType.PARCOOL, RUN));
    }

    @Test
    void remoteClipTransitionsWaitForTheMatchingOwnerDecision() {
        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.SWEM, true, WALK));
        assertTrue(remote(MODEL, ModAnimationType.SWEM, WALK));
        assertFalse(remote(MODEL, ModAnimationType.SWEM, GALLOP));

        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.SWEM, false, GALLOP));
        assertFalse(remote(MODEL, ModAnimationType.SWEM, GALLOP));
        assertFalse(remote(MODEL, ModAnimationType.SWEM, WALK));

        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.SWEM, true, WALK));
        assertTrue(remote(MODEL, ModAnimationType.SWEM, WALK));
        assertFalse(remote(MODEL, ModAnimationType.SWEM, GALLOP));
    }

    @Test
    void sessionResetAndMissingOwnerSnapshotsFailClosed() {
        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.SWEM, true, GALLOP));
        assertTrue(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.SWEM, GALLOP));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                UUID.randomUUID(), localPlayerId, MODEL, ModAnimationType.SWEM, GALLOP));
        ClientPreferences.setSwemAnimationExclusions(Map.of(MODEL, List.of("gallop")));
        assertFalse(local(MODEL, ModAnimationType.SWEM, GALLOP));

        ClientMovementAnimationPreferences.beginConnection();
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.SWEM, GALLOP));
        assertEquals(MovementAnimationDisplayState.DEFAULT,
                RemoteMovementAnimationPreferences.find(remotePlayerId));
        assertFalse(local(MODEL, ModAnimationType.SWEM, GALLOP));
        assertTrue(local(MODEL, ModAnimationType.PARCOOL, RUN));
    }

    @Test
    void inactiveOrUnselectedModelsCannotClaimModPoseOwnership() {
        MovementAnimationDisplayState inactive =
                ClientMovementAnimationPreferences.resolveState(MODEL, null);
        assertNull(inactive.modAnimation());
        assertEquals("", inactive.modAnimationClip());
        assertFalse(inactive.modAnimationOwned());
        assertFalse(ClientMovementAnimationPreferences.resolveState(
                "", null, ModAnimationType.PARCOOL, RUN).modAnimationOwned());
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                localPlayerId, localPlayerId, MODEL, null, ""));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                localPlayerId, localPlayerId, "", ModAnimationType.SWEM, GALLOP));
        assertFalse(local(MODEL, ModAnimationType.PARCOOL, GALLOP));
        assertFalse(local(MODEL, ModAnimationType.PARCOOL, "parcool:unknown"));
        assertFalse(local(MODEL, ModAnimationType.SWEM, "swem:*"));
        assertFalse(local(MODEL, ModAnimationType.SWEM, null));
    }

    @Test
    void legacyFamilyOnlyCallsCannotBypassPerClipRules() {
        RemoteMovementAnimationPreferences.accept(remotePlayerId,
                new MovementAnimationDisplayState(MODEL, null, false, false,
                        ModAnimationType.PARCOOL, true, RUN));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, MODEL, ModAnimationType.PARCOOL));
        assertFalse(ClientMovementAnimationPreferences.usesYsmMod(
                localPlayerId, localPlayerId, MODEL, ModAnimationType.PARCOOL));
        MovementAnimationDisplayState legacy = ClientMovementAnimationPreferences.resolveState(
                MODEL, null, ModAnimationType.PARCOOL);
        assertEquals(ModAnimationType.PARCOOL, legacy.modAnimation());
        assertEquals("", legacy.modAnimationClip());
        assertFalse(legacy.modAnimationOwned());
    }

    private boolean local(String model, ModAnimationType family, String clip) {
        return ClientMovementAnimationPreferences.usesYsmMod(
                localPlayerId, localPlayerId, model, family, clip);
    }

    private boolean remote(String model, ModAnimationType family, String clip) {
        return ClientMovementAnimationPreferences.usesYsmMod(
                remotePlayerId, localPlayerId, model, family, clip);
    }
}
