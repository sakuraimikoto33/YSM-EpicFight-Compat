package net.okitsu.ysmepicfightcompat.mesh;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Chooses inactive LRU victims toward a total-model target without evicting protected models. */
final class ModelCacheRetention {
    private ModelCacheRetention() {
    }

    /**
     * Inputs describe currently retained models, ordered from least to most recently used.
     * Duplicate IDs count once, keeping their first position; protected IDs not retained here
     * do not count toward the target. Neither input nor its recency order is changed.
     * The immutable result can be empty even above target when every remaining model is protected.
     */
    static List<String> victims(Collection<String> leastToMostRecent,
                                Set<String> protectedIds, int target) {
        if (target <= 0) {
            throw new IllegalArgumentException("The retained-model target must be positive");
        }
        Objects.requireNonNull(leastToMostRecent, "leastToMostRecent");
        Objects.requireNonNull(protectedIds, "protectedIds");
        LinkedHashSet<String> retained = new LinkedHashSet<>(leastToMostRecent);
        retained.forEach(modelId -> Objects.requireNonNull(modelId, "modelId"));
        int remaining = retained.size();
        List<String> victims = new ArrayList<>();
        for (String modelId : retained) {
            if (remaining <= target) {
                break;
            }
            if (!protectedIds.contains(modelId)) {
                victims.add(modelId);
                remaining--;
            }
        }
        return List.copyOf(victims);
    }
}
