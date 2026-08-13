package net.okitsu.ysmepicfightcompat.compat;

import net.okitsu.ysmepicfightcompat.CompatMod;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

/**
 * Processes Forge's registered loading warnings after every mod has completed
 * sided setup.
 *
 * <p>{@link ModLoader} is initialized before ordinary mod mixin configs are
 * applied, so intercepting {@code addWarning} with a mod mixin is not reliable.
 * Forge 47 has no public removal API; the warning list and warning metadata are
 * therefore accessed reflectively at load complete. These are Forge classes,
 * not obfuscated official-YSM internals.</p>
 */
public final class YSMCompatibilityWarningFilter {
    private static final String LOADING_WARNINGS_FIELD = "loadingWarnings";
    private static final String MOD_INFO_FIELD = "modInfo";
    private static final String MESSAGE_FIELD = "i18nMessage";
    private static final String CONTEXT_FIELD = "context";

    private YSMCompatibilityWarningFilter() {
    }

    /**
     * Retains and remembers the target warning on its first launch, then
     * removes only that exact warning on later launches. Any reflection or
     * config failure is fail-open and leaves all Forge warnings untouched.
     */
    public static void processRegisteredWarnings() {
        try {
            ModLoader modLoader = ModLoader.get();
            synchronized (modLoader) {
                processWarnings(mutableWarnings(modLoader));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: could not process the official YSM/Epic Fight compatibility warning; retaining it",
                    exception);
        }
    }

    static int processWarnings(List<ModLoadingWarning> warnings) throws ReflectiveOperationException {
        int removed = 0;
        Iterator<ModLoadingWarning> iterator = warnings.iterator();
        while (iterator.hasNext()) {
            ModLoadingWarning warning = iterator.next();
            WarningMetadata metadata = metadata(warning);
            if (YSMCompatibilityWarningState.shouldSuppress(
                    metadata.sourceModId(), metadata.messageKey(), metadata.context())) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            CompatMod.LOG.info(
                    "YSM-EF Compat: removed {} already-shown official YSM/Epic Fight compatibility warning(s)",
                    removed);
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    private static List<ModLoadingWarning> mutableWarnings(ModLoader modLoader)
            throws ReflectiveOperationException {
        return (List<ModLoadingWarning>) readField(
                ModLoader.class, LOADING_WARNINGS_FIELD, modLoader);
    }

    @SuppressWarnings("unchecked")
    static WarningMetadata metadata(ModLoadingWarning warning) throws ReflectiveOperationException {
        IModInfo modInfo = (IModInfo) readField(ModLoadingWarning.class, MOD_INFO_FIELD, warning);
        String sourceModId = modInfo == null ? null : modInfo.getModId();
        String messageKey = (String) readField(ModLoadingWarning.class, MESSAGE_FIELD, warning);
        List<Object> context = (List<Object>) readField(
                ModLoadingWarning.class, CONTEXT_FIELD, warning);
        return new WarningMetadata(sourceModId, messageKey, context);
    }

    private static Object readField(Class<?> owner, String fieldName, Object instance)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        if (!field.trySetAccessible()) {
            throw new IllegalAccessException("Cannot access " + owner.getName() + "." + fieldName);
        }
        return field.get(instance);
    }

    record WarningMetadata(String sourceModId, String messageKey, List<?> context) {
    }
}
