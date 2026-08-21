package net.okitsu.ysmepicfightcompat.render;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatOverlayPolicyTest {
    @Test
    void defaultsToSuppressedAndObservesLivePersistedConfigChanges(@TempDir Path directory)
            throws IOException {
        Path path = directory.resolve("ysm-epicfight-compat-client.toml");
        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            ClientPreferences.CLIENT_SPEC.setConfig(config);

            assertTrue(CombatOverlayPolicy.shouldSuppress(true));
            assertFalse(CombatOverlayPolicy.shouldSuppress(false));
            assertEquals("config.ysm_epicfight_compat.client",
                    ClientPreferences.CLIENT_SPEC.getLevelTranslationKey(List.of("client")));
            assertEquals("config.ysm_epicfight_compat.suppress_battle_overlay",
                    ((ForgeConfigSpec.ValueSpec) ClientPreferences.CLIENT_SPEC
                            .getRaw(List.of("client", "suppressBattleModeOverlay")))
                            .getTranslationKey());

            config.set(List.of("client", "suppressBattleModeOverlay"), false);
            config.save();
            ClientPreferences.CLIENT_SPEC.afterReload();
            assertFalse(CombatOverlayPolicy.shouldSuppress(true));
            assertTrue(Files.readString(path).contains("suppressBattleModeOverlay = false"));

            config.set(List.of("client", "suppressBattleModeOverlay"), true);
            config.save();
            ClientPreferences.CLIENT_SPEC.afterReload();
            assertTrue(CombatOverlayPolicy.shouldSuppress(true));
            assertTrue(Files.readString(path).contains("suppressBattleModeOverlay = true"));
        } finally {
            ClientPreferences.CLIENT_SPEC.setConfig(null);
        }
    }
}
