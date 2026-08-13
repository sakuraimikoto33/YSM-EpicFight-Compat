package net.okitsu.ysmepicfightcompat.compat;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.forgespi.language.IModInfo;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YSMCompatibilityWarningFilterTest {
    @Test
    void forgeFieldsStillMatchTheReflectionBoundary() throws ReflectiveOperationException {
        assertEquals(List.class, ModLoader.class.getDeclaredField("loadingWarnings").getType());
        assertEquals(IModInfo.class, ModLoadingWarning.class.getDeclaredField("modInfo").getType());
        assertEquals(String.class, ModLoadingWarning.class.getDeclaredField("i18nMessage").getType());
        assertEquals(List.class, ModLoadingWarning.class.getDeclaredField("context").getType());
    }

    @Test
    void firstLaunchKeepsAndNextLaunchRemovesOnlyTheTarget(@TempDir Path directory)
            throws ReflectiveOperationException {
        Path path = directory.resolve("ysm-epicfight-compat-client.toml");
        try (CommentedFileConfig first = CommentedFileConfig.of(path)) {
            first.load();
            ClientPreferences.CLIENT_SPEC.setConfig(first);
            List<ModLoadingWarning> warnings = samples();
            assertEquals(0, YSMCompatibilityWarningFilter.processWarnings(warnings));
            assertEquals(3, warnings.size());
            assertTrue(ClientPreferences.YSM_WARNING_ACKNOWLEDGED.get());
        } finally {
            ClientPreferences.CLIENT_SPEC.setConfig(null);
        }
        try (CommentedFileConfig next = CommentedFileConfig.of(path)) {
            next.load();
            ClientPreferences.CLIENT_SPEC.setConfig(next);
            List<ModLoadingWarning> warnings = samples();
            assertEquals(1, YSMCompatibilityWarningFilter.processWarnings(warnings));
            assertEquals(2, warnings.size());
        } finally {
            ClientPreferences.CLIENT_SPEC.setConfig(null);
        }
    }

    private static List<ModLoadingWarning> samples() {
        return new ArrayList<>(List.of(
                warning("yes_steve_model", "error.yes_steve_model.incompatible_mod", "Epic Fight"),
                warning("yes_steve_model", "error.yes_steve_model.incompatible_mod", "Another Mod"),
                warning("another_mod", "error.yes_steve_model.incompatible_mod", "Epic Fight")));
    }

    private static ModLoadingWarning warning(String modId, String message, Object... context) {
        IModInfo info = (IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(),
                new Class<?>[]{IModInfo.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getModId") || method.getName().equals("toString")) {
                        return modId;
                    }
                    return defaultValue(method.getReturnType());
                });
        return new ModLoadingWarning(info, ModLoadingStage.SIDED_SETUP, message, context);
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
