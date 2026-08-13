package net.okitsu.ysmepicfightcompat.compat;

import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;

import java.util.List;

/**
 * Persists the one-shot state for official YSM's built-in Epic Fight warning.
 *
 * <p>YSM 2.6.5 registers this warning even when this compatibility mod is
 * installed. The first registration is retained so the user still sees the
 * upstream notice once; later launches suppress only that exact warning.</p>
 */
public final class YSMCompatibilityWarningState {
    static final String YSM_MOD_ID = "yes_steve_model";
    static final String INCOMPATIBLE_MOD_MESSAGE = "error.yes_steve_model.incompatible_mod";
    static final String EPIC_FIGHT_DISPLAY_NAME = "Epic Fight";

    enum Decision {
        IGNORE,
        SHOW_AND_REMEMBER,
        SUPPRESS
    }

    private YSMCompatibilityWarningState() {
    }

    /**
     * @return true only when the exact official-YSM Epic Fight warning has
     * already been shown on an earlier launch
     */
    public static synchronized boolean shouldSuppress(String sourceModId, String messageKey,
                                                       List<?> context) {
        if (!isEpicFightWarning(sourceModId, messageKey, context)) {
            return false;
        }

        try {
            boolean alreadyShown = ClientPreferences.YSM_WARNING_ACKNOWLEDGED.get();
            Decision decision = decide(alreadyShown, sourceModId, messageKey, context);
            if (decision == Decision.SUPPRESS) {
                CompatMod.LOG.debug(
                        "YSM-EF Compat: suppressed the already-shown official YSM/Epic Fight compatibility warning");
                return true;
            }
            if (decision == Decision.SHOW_AND_REMEMBER) {
                ClientPreferences.YSM_WARNING_ACKNOWLEDGED.set(true);
                ClientPreferences.YSM_WARNING_ACKNOWLEDGED.save();
                CompatMod.LOG.info(
                        "YSM-EF Compat: retaining the official YSM/Epic Fight compatibility warning once");
            }
        } catch (RuntimeException exception) {
            // Warning handling must never make mod loading fail. If the client
            // config is unexpectedly unavailable, retain YSM's warning.
            CompatMod.LOG.warn(
                    "YSM-EF Compat: could not persist compatibility-warning state; retaining the warning",
                    exception);
        }
        return false;
    }

    static Decision decide(boolean alreadyShown, String sourceModId, String messageKey,
                           List<?> context) {
        if (!isEpicFightWarning(sourceModId, messageKey, context)) {
            return Decision.IGNORE;
        }
        return alreadyShown ? Decision.SUPPRESS : Decision.SHOW_AND_REMEMBER;
    }

    static boolean isEpicFightWarning(String sourceModId, String messageKey, List<?> context) {
        return YSM_MOD_ID.equals(sourceModId)
                && INCOMPATIBLE_MOD_MESSAGE.equals(messageKey)
                && context != null
                && context.size() == 1
                && EPIC_FIGHT_DISPLAY_NAME.equals(context.get(0));
    }
}
