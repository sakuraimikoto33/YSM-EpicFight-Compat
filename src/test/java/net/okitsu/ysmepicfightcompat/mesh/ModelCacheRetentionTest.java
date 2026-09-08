package net.okitsu.ysmepicfightcompat.mesh;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCacheRetentionTest {
    @Test
    void emptyOrUnderTargetCachesRetainEverything() {
        assertTrue(ModelCacheRetention.victims(List.of(), Set.of(), 8).isEmpty());
        assertTrue(ModelCacheRetention.victims(List.of("a", "b"), Set.of(), 8).isEmpty());
    }

    @Test
    void exactlyAtTargetDoesNotEvictAnInactiveModel() {
        assertTrue(ModelCacheRetention.victims(
                List.of("inactive", "active"), Set.of("active"), 2).isEmpty());
    }

    @Test
    void choosesOnlyTheOldestExcessModels() {
        assertEquals(List.of("oldest", "older"), ModelCacheRetention.victims(
                List.of("oldest", "older", "recent", "newest"), Set.of(), 2));
    }

    @Test
    void skipsProtectedModelsWithoutChangingTheLruOrderOfOtherVictims() {
        assertEquals(List.of("older-inactive", "recent-inactive"), ModelCacheRetention.victims(
                List.of("oldest-active", "older-inactive", "other-active",
                        "recent-inactive", "newest"),
                Set.of("oldest-active", "other-active"), 3));
    }

    @Test
    void allProtectedModelsRemainEvenAboveTarget() {
        assertTrue(ModelCacheRetention.victims(
                List.of("a", "b", "c"), Set.of("a", "b", "c"), 1).isEmpty());
    }

    @Test
    void protectedModelsExceedingTargetAllowAllInactiveModelsToBeRemoved() {
        assertEquals(List.of("inactive-old", "inactive-new"), ModelCacheRetention.victims(
                List.of("active-a", "inactive-old", "active-b", "inactive-new", "active-c"),
                Set.of("active-a", "active-b", "active-c"), 2));
    }

    @Test
    void targetAppliesToTotalRetainedModelsNotOnlyTheInactiveCache() {
        List<String> retained = List.of("inactive-a", "active-a", "inactive-b", "active-b");
        assertEquals(List.of("inactive-a", "inactive-b"),
                ModelCacheRetention.victims(retained, Set.of("active-a", "active-b"), 2));
    }

    @Test
    void releasingTheLastPinMakesTheOldModelEligibleAtTheNextEvaluation() {
        List<String> retained = List.of("old-selected", "new-selected", "inactive");
        assertEquals(List.of("inactive"), ModelCacheRetention.victims(
                retained, Set.of("old-selected", "new-selected"), 2));
        assertEquals(List.of("old-selected"), ModelCacheRetention.victims(
                retained, Set.of("new-selected"), 2));
    }

    @Test
    void aSharedModelCountsOnceAndRemainsProtectedByAnyRemainingUser() {
        List<String> retained = List.of("shared", "inactive", "newest");
        Set<String> modelsSelectedByTwoUsers = new LinkedHashSet<>(List.of("shared", "shared"));
        assertEquals(List.of("inactive"),
                ModelCacheRetention.victims(retained, modelsSelectedByTwoUsers, 2));
        assertEquals(List.of("inactive"),
                ModelCacheRetention.victims(retained, Set.of("shared"), 2));
        assertEquals(List.of("shared"),
                ModelCacheRetention.victims(retained, Set.of(), 2));
    }

    @Test
    void reducingTheTargetReevaluatesOnlyUnprotectedModels() {
        List<String> retained = List.of("active", "old", "recent", "newest");
        assertTrue(ModelCacheRetention.victims(retained, Set.of("active"), 4).isEmpty());
        assertEquals(List.of("old", "recent"),
                ModelCacheRetention.victims(retained, Set.of("active"), 2));
        assertEquals(List.of("old", "recent", "newest"),
                ModelCacheRetention.victims(retained, Set.of("active"), 1));
    }

    @Test
    void aSelectedModelCompletingConversionIsProtectedBeforeAnyFurtherLookup() {
        List<String> before = List.of("inactive-old", "active");
        Set<String> selected = Set.of("active", "just-completed");
        assertTrue(ModelCacheRetention.victims(before, selected, 2).isEmpty());

        List<String> after = List.of("inactive-old", "active", "just-completed");
        assertEquals(List.of("inactive-old"), ModelCacheRetention.victims(after, selected, 2));
    }

    @Test
    void aPendingAuthorshipProbeMayTemporarilyExceedTheTarget() {
        List<String> retained = List.of("active-a", "active-b", "probe-result");
        assertTrue(ModelCacheRetention.victims(retained,
                Set.of("active-a", "active-b", "probe-result"), 2).isEmpty());
        assertEquals(List.of("probe-result"), ModelCacheRetention.victims(
                retained, Set.of("active-a", "active-b"), 2));
    }

    @Test
    void nonresidentProtectedModelsDoNotConsumeTheTarget() {
        assertEquals(List.of("a"), ModelCacheRetention.victims(
                List.of("a", "b", "c"), Set.of("pending-conversion", "not-loaded"), 2));
    }

    @Test
    void duplicateModelIdsAreCountedOnceAtTheirFirstLruPosition() {
        List<String> repeated = List.of("old", "recent", "old", "newest", "recent");
        assertEquals(List.of("old"), ModelCacheRetention.victims(repeated, Set.of(), 2));
        assertTrue(ModelCacheRetention.victims(repeated, Set.of(), 3).isEmpty());
    }

    @Test
    void acceptsUnmodifiableInputsAndReturnsAnImmutableResult() {
        List<String> retained = List.of("old", "active", "newest");
        Set<String> protectedIds = Set.of("active");
        List<String> result = ModelCacheRetention.victims(retained, protectedIds, 2);

        assertEquals(List.of("old"), result);
        assertEquals(List.of("old", "active", "newest"), retained);
        assertEquals(Set.of("active"), protectedIds);
        assertThrows(UnsupportedOperationException.class, () -> result.add("active"));
    }

    @Test
    void evaluationDoesNotTouchAccessOrderedCacheRecencyOrRemoveEntries() {
        LinkedHashMap<String, Boolean> recency = new LinkedHashMap<>(4, 0.75F, true);
        recency.put("a", true);
        recency.put("b", true);
        recency.put("c", true);
        recency.get("a");
        List<String> before = new ArrayList<>(recency.keySet());

        assertEquals(List.of("c"), ModelCacheRetention.victims(recency.keySet(), Set.of("b"), 2));
        assertEquals(before, new ArrayList<>(recency.keySet()));
        assertEquals(3, recency.size());
    }

    @Test
    void rejectsNonpositiveTargets() {
        for (int target : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelCacheRetention.victims(List.of("a"), Set.of(), target));
        }
    }
}
