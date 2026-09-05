package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.function.ToDoubleFunction;

/** Pure validation shared by official-YSM read-only queries and their tests. */
final class OfficialQueryValues {
    private OfficialQueryValues() {
    }

    static double sumLevels(String[] identifiers, int first,
                            ToDoubleFunction<String> lookup) {
        if (identifiers == null || first < 0 || lookup == null) {
            return 0.0D;
        }
        double total = 0.0D;
        for (int index = first; index < identifiers.length; index++) {
            String id = identifiers[index];
            if (id != null && !id.isBlank()) {
                double value = lookup.applyAsDouble(id);
                if (Double.isFinite(value) && value > 0.0D) {
                    total += value;
                }
            }
        }
        return Double.isFinite(total) ? total : 0.0D;
    }

    static boolean permitsLocalInput(boolean currentLocalEntity, boolean sameLevel,
                                     boolean focused, boolean guiOpen) {
        return currentLocalEntity && sameLevel && focused && !guiOpen;
    }

    @Nullable
    static BlockPos relativeBlockPosition(double x, double y, double z,
                                          double dx, double dy, double dz) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !relativeOffset(dx) || !relativeOffset(dy) || !relativeOffset(dz)) {
            return null;
        }
        // Fractional offsets are documented (e.g. -0.5 below the feet). Apply them
        // before flooring into the target block, not after truncating each offset.
        return BlockPos.containing(x + dx, y + dy, z + dz);
    }

    private static boolean relativeOffset(double offset) {
        return Double.isFinite(offset) && Math.abs(offset) <= 8.0D;
    }

    static boolean mouseKey(double key) {
        return integer(key) && key >= 0.0D && key <= 7.0D;
    }

    static boolean keyboardKey(double key) {
        if (!integer(key)) {
            return false;
        }
        int code = (int) key;
        // GLFW named key ranges only: holes are invalid native calls, not released keys.
        return code == 32 || code == 39 || code >= 44 && code <= 57
                || code == 59 || code == 61 || code >= 65 && code <= 93
                || code == 96 || code == 161 || code == 162
                || code >= 256 && code <= 269 || code >= 280 && code <= 284
                || code >= 290 && code <= 314 || code >= 320 && code <= 336
                || code >= 340 && code <= 348;
    }

    private static boolean integer(double value) {
        return Double.isFinite(value) && value == Math.rint(value);
    }
}
