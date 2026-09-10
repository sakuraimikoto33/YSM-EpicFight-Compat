package net.okitsu.ysmepicfightcompat.compat;

import net.okitsu.ysmepicfightcompat.CompatMod;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Processes NeoForge's registered loading warnings after every mod has completed
 * sided setup.
 *
 * <p>The loader exposes an immutable snapshot of all loading issues. Replace
 * that snapshot only when an acknowledged warning was removed, retaining every
 * error and unrelated warning in its original order.</p>
 */
public final class YSMCompatibilityWarningFilter {
    private YSMCompatibilityWarningFilter() {
    }

    /**
     * Retains and remembers the target warning on its first launch, then
     * removes only that exact warning on later launches. A config failure
     * leaves the warning available.
     */
    public static void processRegisteredWarnings() {
        try {
            synchronized (ModLoader.class) {
                List<ModLoadingIssue> retained = new ArrayList<>(ModLoader.getLoadingIssues());
                if (processWarnings(retained) > 0) {
                    ModLoader.clearLoadingIssues();
                    retained.forEach(ModLoader::addLoadingIssue);
                }
            }
        } catch (RuntimeException exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: could not process the official YSM/Epic Fight compatibility warning; retaining it",
                    exception);
        }
    }

    static int processWarnings(List<ModLoadingIssue> warnings) {
        int removed = 0;
        Iterator<ModLoadingIssue> iterator = warnings.iterator();
        while (iterator.hasNext()) {
            ModLoadingIssue warning = iterator.next();
            if (warning.severity() != ModLoadingIssue.Severity.WARNING) {
                continue;
            }
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

    static WarningMetadata metadata(ModLoadingIssue warning) {
        IModInfo modInfo = warning.affectedMod();
        String sourceModId = modInfo == null ? null : modInfo.getModId();
        return new WarningMetadata(sourceModId, warning.translationKey(), warning.translationArgs());
    }

    record WarningMetadata(String sourceModId, String messageKey, List<?> context) {
    }
}
