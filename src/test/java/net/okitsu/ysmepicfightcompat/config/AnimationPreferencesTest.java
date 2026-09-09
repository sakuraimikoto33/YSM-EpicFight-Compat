package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationPreferencesTest {
    private static final List<String> RATE_PATH =
            List.of("client", "animationEvaluationRateLimitHz");

    @Test
    void defaultsToSixtyWithAnExplicitUnlimitedOptionAndStrictIntegerRange() {
        assertEquals(RATE_PATH, ClientPreferences.ANIMATION_EVALUATION_RATE_LIMIT_HZ.getPath());
        assertEquals(60, ClientPreferences.ANIMATION_EVALUATION_RATE_LIMIT_HZ.getDefault().intValue());
        ForgeConfigSpec.ValueSpec rate = assertInstanceOf(ForgeConfigSpec.ValueSpec.class,
                ClientPreferences.CLIENT_SPEC.getRaw(RATE_PATH));
        assertEquals("config.ysm_epicfight_compat.animation_evaluation_rate_limit_hz",
                rate.getTranslationKey());
        assertTrue(rate.getComment().contains("zero to disable this added rate limit"));
        assertTrue(rate.getComment().contains("original evaluation cadence"));
        assertTrue(rate.getComment().contains("animation, script, and controller evaluations"));
        assertTrue(rate.getComment().contains("not a game-tick or rendering-FPS cap"));
        assertTrue(rate.getComment().endsWith("Range: 0 (unlimited), 30 ~ 240\nDefault: 60"));
        for (int valid : List.of(0, 30, 31, 60, 120, 240)) {
            assertTrue(rate.test(valid), "Valid rate rejected: " + valid);
        }
        for (Object invalid : List.of(-1, 1, 20, 29, 241, 1000, Integer.MAX_VALUE,
                0L, 60L, 60.0D, 60.0F, "60", false)) {
            assertFalse(rate.test(invalid), "Invalid rate accepted: " + invalid);
        }
        assertFalse(rate.test(null));
    }

    @Test
    void invalidPersistedValuesAreCorrectedToTheDefaultInsteadOfCoerced() {
        for (Object invalid : List.of(-1, 1, 20, 29, 241, 1000, 60L, 60.5D, "60", false)) {
            CommentedConfig config = CommentedConfig.inMemory();
            config.set(RATE_PATH, invalid);

            ClientPreferences.CLIENT_SPEC.correct(config);

            assertEquals(60, config.<Integer>get(RATE_PATH), "Invalid rate was not corrected: " + invalid);
            assertTrue(ClientPreferences.CLIENT_SPEC.isCorrect(config));
        }
    }

    @Test
    void persistsUnlimitedAndCustomRatesAndReadsLiveReloadsWithoutRestarting(@TempDir Path directory)
            throws IOException {
        Path path = directory.resolve("ysm-epicfight-compat-client.toml");
        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            ClientPreferences.CLIENT_SPEC.setConfig(config);
            assertEquals(60, ClientPreferences.animationEvaluationRateLimitHz());

            for (int rate : List.of(0, 30, 120, 240, 60)) {
                config.set(RATE_PATH, rate);
                config.save();
                config.load();
                ClientPreferences.CLIENT_SPEC.afterReload();

                assertEquals(rate, ClientPreferences.animationEvaluationRateLimitHz());
                assertTrue(Files.readString(path).contains("animationEvaluationRateLimitHz = " + rate));
            }
        } finally {
            ClientPreferences.CLIENT_SPEC.setConfig(null);
        }
    }
}
