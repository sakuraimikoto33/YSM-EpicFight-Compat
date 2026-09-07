package net.okitsu.ysmepicfightcompat.animation;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

/** Composition phases for top-level player controllers, independent of script support. */
final class ControllerOrder {
    private static final Set<String> DYNAMIC_PHASES = Set.of(
            "pre_main", "post_main", "pre_hold", "post_hold",
            "pre_swing", "post_swing", "pre_use", "post_use");
    private static final Comparator<String> COMPARATOR = Comparator
            .comparingInt(ControllerOrder::stage)
            .thenComparing(Comparator.naturalOrder());

    private ControllerOrder() {
    }

    static int stage(String name) {
        if (preParallel(name)) return 0;
        if (parallel(name)) return 100;
        if (slot(name, "pre_main")) return 5;
        if (slot(name, "main")) return 10;
        if (slot(name, "post_main")) return 15;
        if (slot(name, "pre_hold")) return 20;
        if (slot(name, "hold_mainhand") || slot(name, "hold_offhand")) return 25;
        if (slot(name, "post_hold")) return 30;
        if (slot(name, "pre_swing")) return 35;
        if (slot(name, "swing")) return 40;
        if (slot(name, "post_swing")) return 45;
        if (slot(name, "pre_use")) return 50;
        if (slot(name, "use")) return 55;
        if (slot(name, "post_use")) return 60;
        if (slot(name, "passenger")) return 65;
        return 70;
    }

    /** Only documented pre/post phases accept a dynamic top-level player suffix. */
    static boolean slot(String name, String slot) {
        String normalized = normalize(name);
        String phase = normalize(slot);
        if (phase.isEmpty() || phase.indexOf('.') >= 0) return false;
        if (normalized.equals(phase) || normalized.equals("player." + phase)) return true;
        return DYNAMIC_PHASES.contains(phase)
                && dynamicSuffix(normalized, "player." + phase + '_');
    }

    /** Includes both the pre-parallel and post-parallel groups. */
    static boolean parallel(String name) {
        String normalized = normalize(name);
        return parallelGroup(normalized, "player.parallel")
                || parallelGroup(normalized, "player.pre_parallel");
    }

    static boolean preParallel(String name) {
        return parallelGroup(normalize(name), "player.pre_parallel");
    }

    /** Dynamic controllers are independent layers, not fixed hand-item slots. */
    static boolean dynamic(String name) {
        String normalized = normalize(name);
        for (String phase : DYNAMIC_PHASES) {
            if (dynamicSuffix(normalized, "player." + phase + '_')) return true;
        }
        return dynamicSuffix(normalized, "player.parallel_")
                || dynamicSuffix(normalized, "player.pre_parallel_");
    }

    /** Within a phase, official order is the original name's lexicographic order. */
    static Comparator<String> comparator() {
        return COMPARATOR;
    }

    private static boolean parallelGroup(String name, String prefix) {
        if (dynamicSuffix(name, prefix + '_')) return true;
        if (!name.startsWith(prefix) || name.length() != prefix.length() + 1) return false;
        char number = name.charAt(prefix.length());
        return number >= '0' && number <= '7';
    }

    private static boolean dynamicSuffix(String name, String prefix) {
        return name.startsWith(prefix) && name.length() > prefix.length()
                && name.indexOf('.', prefix.length()) < 0;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
