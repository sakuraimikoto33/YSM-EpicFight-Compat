package net.okitsu.ysmepicfightcompat.compat;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static net.okitsu.ysmepicfightcompat.compat.YSMCompatibilityWarningState.Decision.IGNORE;
import static net.okitsu.ysmepicfightcompat.compat.YSMCompatibilityWarningState.Decision.SHOW_AND_REMEMBER;
import static net.okitsu.ysmepicfightcompat.compat.YSMCompatibilityWarningState.Decision.SUPPRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class YSMCompatibilityWarningStateTest {
    private static final String YSM = "yes_steve_model";
    private static final String MESSAGE = "error.yes_steve_model.incompatible_mod";

    @Test
    void changesOnlyTheExactOfficialYsmEpicFightWarning() {
        assertEquals(SHOW_AND_REMEMBER,
                YSMCompatibilityWarningState.decide(false, YSM, MESSAGE, List.of("Epic Fight")));
        assertEquals(SUPPRESS,
                YSMCompatibilityWarningState.decide(true, YSM, MESSAGE, List.of("Epic Fight")));
        assertEquals(IGNORE,
                YSMCompatibilityWarningState.decide(true, "another_mod", MESSAGE, List.of("Epic Fight")));
        assertEquals(IGNORE,
                YSMCompatibilityWarningState.decide(true, YSM, MESSAGE, List.of("Another Mod")));
    }

    @Test
    void acknowledgementSurvivesAClientConfigReload(@TempDir Path directory) {
        Path path = directory.resolve("ysm-epicfight-compat-client.toml");
        try (CommentedFileConfig config = CommentedFileConfig.of(path)) {
            config.load();
            ClientPreferences.CLIENT_SPEC.setConfig(config);
            assertFalse(ClientPreferences.YSM_WARNING_ACKNOWLEDGED.get());
            ClientPreferences.YSM_WARNING_ACKNOWLEDGED.set(true);
            ClientPreferences.YSM_WARNING_ACKNOWLEDGED.save();
        } finally {
            ClientPreferences.CLIENT_SPEC.setConfig(null);
        }
        try (CommentedFileConfig config = CommentedFileConfig.of(path)) {
            config.load();
            assertEquals(Boolean.TRUE,
                    config.<Boolean>get(List.of("client", "epicFightCompatibilityWarningShown")));
        }
    }
}
