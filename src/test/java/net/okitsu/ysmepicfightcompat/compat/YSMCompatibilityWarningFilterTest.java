package net.okitsu.ysmepicfightcompat.compat;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.neoforgespi.language.IModInfo;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.config.ConfigTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YSMCompatibilityWarningFilterTest {
    @Test
    void neoForgeIssueMetadataRetainsTheWarningSourceAndContext() {
        var metadata = YSMCompatibilityWarningFilter.metadata(warning(
                "yes_steve_model", "error.yes_steve_model.incompatible_mod", "Epic Fight"));
        assertEquals("yes_steve_model", metadata.sourceModId());
        assertEquals("error.yes_steve_model.incompatible_mod", metadata.messageKey());
        assertEquals(List.of("Epic Fight"), metadata.context());
    }

    @Test
    void firstLaunchKeepsAndNextLaunchRemovesOnlyTheTarget(@TempDir Path directory) {
        Path path = directory.resolve("ysm-epicfight-compat-client.toml");
        // Match the loader's synchronous writes before simulating the next client launch.
        try (CommentedFileConfig first = ConfigTestSupport.fileConfigBuilder(path).sync().build()) {
            first.load();
            ConfigTestSupport.bind(ClientPreferences.CLIENT_SPEC, first);
            List<ModLoadingIssue> warnings = samples();
            assertEquals(0, YSMCompatibilityWarningFilter.processWarnings(warnings));
            assertEquals(3, warnings.size());
            assertTrue(ClientPreferences.YSM_WARNING_ACKNOWLEDGED.get());
        } finally {
            ConfigTestSupport.clear(ClientPreferences.CLIENT_SPEC);
        }
        try (CommentedFileConfig next = ConfigTestSupport.fileConfigBuilder(path).sync().build()) {
            next.load();
            ConfigTestSupport.bind(ClientPreferences.CLIENT_SPEC, next);
            List<ModLoadingIssue> warnings = samples();
            assertEquals(1, YSMCompatibilityWarningFilter.processWarnings(warnings));
            assertEquals(2, warnings.size());
        } finally {
            ConfigTestSupport.clear(ClientPreferences.CLIENT_SPEC);
        }
    }

    @Test
    void errorsWithTheSameMessageNeverAcknowledgeOrSuppressWarnings() {
        ConfigTestSupport.bind(ClientPreferences.CLIENT_SPEC, CommentedConfig.inMemory());
        try {
            ModLoadingIssue error = warning("yes_steve_model",
                    "error.yes_steve_model.incompatible_mod", "Epic Fight")
                    .withSeverity(ModLoadingIssue.Severity.ERROR);
            List<ModLoadingIssue> issues = new ArrayList<>(List.of(error));
            assertEquals(0, YSMCompatibilityWarningFilter.processWarnings(issues));
            assertFalse(ClientPreferences.YSM_WARNING_ACKNOWLEDGED.get());
            ClientPreferences.YSM_WARNING_ACKNOWLEDGED.set(true);
            assertEquals(0, YSMCompatibilityWarningFilter.processWarnings(issues));
            assertSame(error, issues.getFirst());
        } finally {
            ConfigTestSupport.clear(ClientPreferences.CLIENT_SPEC);
        }
    }

    @Test
    void replacesTheLoaderSnapshotWithoutDroppingErrorsOrUnrelatedWarnings() {
        ConfigTestSupport.bind(ClientPreferences.CLIENT_SPEC, CommentedConfig.inMemory());
        List<ModLoadingIssue> original = ModLoader.getLoadingIssues();
        try {
            ClientPreferences.YSM_WARNING_ACKNOWLEDGED.set(true);
            List<ModLoadingIssue> issues = samples();
            ModLoadingIssue error = issues.getFirst().withSeverity(ModLoadingIssue.Severity.ERROR)
                    .withCause(new IllegalStateException("Keep the original diagnostic"));
            issues.add(1, error);
            ModLoader.clearLoadingIssues();
            issues.forEach(ModLoader::addLoadingIssue);

            YSMCompatibilityWarningFilter.processRegisteredWarnings();

            List<ModLoadingIssue> retained = ModLoader.getLoadingIssues();
            assertEquals(issues.subList(1, issues.size()), retained);
            assertSame(error, retained.getFirst());
            assertTrue(ModLoader.hasErrors());
        } finally {
            ModLoader.clearLoadingIssues();
            original.forEach(ModLoader::addLoadingIssue);
            ConfigTestSupport.clear(ClientPreferences.CLIENT_SPEC);
        }
    }

    private static List<ModLoadingIssue> samples() {
        return new ArrayList<>(List.of(
                warning("yes_steve_model", "error.yes_steve_model.incompatible_mod", "Epic Fight"),
                warning("yes_steve_model", "error.yes_steve_model.incompatible_mod", "Another Mod"),
                warning("another_mod", "error.yes_steve_model.incompatible_mod", "Epic Fight")));
    }

    private static ModLoadingIssue warning(String modId, String message, Object... context) {
        IModInfo info = (IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(),
                new Class<?>[]{IModInfo.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getModId") || method.getName().equals("toString")) {
                        return modId;
                    }
                    return defaultValue(method.getReturnType());
                });
        return new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, message,
                List.of(context), null, null, null, info);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        return 0.0D;
    }
}
