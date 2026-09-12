package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.network.EntityModelPolicy;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelPolicy;
import net.okitsu.ysmepicfightcompat.network.ModAnimationPolicy;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecodedRuleCacheTest {
    private static final String MODEL = "author/model.v2";

    @Test
    void unchangedContentDecodesOnceEvenWhenConfigAndListInstancesChange() {
        AtomicInteger calls = new AtomicInteger();
        DecodedRuleCache cache = cache(calls);
        Map<String, List<String>> first = cache.get(table(MODEL, List.of("minecraft:stick")), true);

        for (int read = 0; read < 20; read++) {
            assertSame(first, cache.get(table(MODEL, new ArrayList<>(List.of("minecraft:stick"))), true));
        }

        assertEquals(1, calls.get());
        assertEquals(Map.of(MODEL, List.of("minecraft:stick")), first);
        assertThrows(UnsupportedOperationException.class, () -> first.put("other/model", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> first.get(MODEL).add("minecraft:air"));
    }

    @Test
    void directListAndTableMutationsCannotAlterTheSnapshotOrPreviouslyReturnedRules() {
        AtomicInteger calls = new AtomicInteger();
        DecodedRuleCache cache = cache(calls);
        List<String> selectors = new ArrayList<>(List.of("minecraft:stick"));
        Config raw = table(MODEL, selectors);
        Map<String, List<String>> first = cache.get(raw, true);

        selectors.set(0, "minecraft:air");
        Map<String, List<String>> changed = cache.get(raw, true);
        assertNotSame(first, changed);
        assertEquals(List.of("minecraft:stick"), first.get(MODEL));
        assertEquals(List.of("minecraft:air"), changed.get(MODEL));
        assertSame(changed, cache.get(raw, true));

        raw.valueMap().put("other/model", List.of("minecraft:bow"));
        assertEquals(2, cache.get(raw, true).size());
        raw.valueMap().remove(MODEL);
        assertEquals(Map.of("other/model", List.of("minecraft:bow")), cache.get(raw, true));
        assertEquals(4, calls.get());
    }

    @Test
    void equivalentNormalizationReusesDecodedIdentityAfterOneDecodeOfChangedRawContent() {
        AtomicInteger calls = new AtomicInteger();
        DecodedRuleCache cache = cache(calls);
        Map<String, List<String>> first = cache.get(table(MODEL, List.of("minecraft:stick")), true);
        Config edited = table(" AUTHOR/MODEL.V2 ", List.of(" MINECRAFT:STICK "));

        assertSame(first, cache.get(edited, true));
        assertSame(first, cache.get(edited, true));
        assertEquals(2, calls.get());
    }

    @Test
    void invalidSelectorsKeepTheWholeTableFallbackAndRecoverAfterInPlaceRepair() {
        AtomicInteger calls = new AtomicInteger();
        DecodedRuleCache cache = cache(calls);
        List<String> selectors = new ArrayList<>(List.of("invalid selector"));
        Config raw = table(MODEL, selectors);
        raw.valueMap().put("other/model", List.of("minecraft:bow"));

        assertEquals(Map.of(), cache.get(raw, true));
        assertEquals(Map.of(), cache.get(raw, true));
        assertEquals(1, calls.get());
        selectors.set(0, "minecraft:stick");
        assertEquals(Map.of(MODEL, List.of("minecraft:stick"),
                "other/model", List.of("minecraft:bow")), cache.get(raw, true));
        assertEquals(2, calls.get());
    }

    @Test
    void unsupportedMutableValuesAreNeverRetainedAsSnapshotReferences() {
        AtomicInteger calls = new AtomicInteger();
        DecodedRuleCache cache = cache(calls);
        List<Object> selectors = new ArrayList<>(List.of(new StringBuilder("minecraft:stick")));
        Config raw = table(MODEL, selectors);

        assertEquals(Map.of(), cache.get(raw, true));
        assertEquals(Map.of(), cache.get(raw, true));
        selectors.set(0, "minecraft:stick");
        Map<String, List<String>> repaired = cache.get(raw, true);
        assertEquals(Map.of(MODEL, List.of("minecraft:stick")), repaired);
        assertSame(repaired, cache.get(raw, true));
        assertEquals(3, calls.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidRawKeyTypesStillUseTheExistingDecoderFallback() {
        DecodedRuleCache cache = new DecodedRuleCache(
                value -> ModAnimationPolicy.decodeConfiguration(ModAnimationType.SWEM, value),
                ModAnimationPolicy.MAX_MODELS, ModAnimationPolicy.maxSelectorsPerModel(ModAnimationType.SWEM));
        Config raw = Config.inMemory();
        Map<Object, Object> values = (Map<Object, Object>) (Map<?, ?>) raw.valueMap();
        values.put(42, List.of("gallop"));

        assertEquals(ModAnimationPolicy.decodeConfiguration(ModAnimationType.SWEM, raw), cache.get(raw, true));
        values.clear();
        values.put(MODEL, List.of("gallop"));
        assertEquals(Map.of(MODEL, List.of("gallop")), cache.get(raw, true));
    }

    @Test
    void unloadedDefaultsAreNotCachedAndLoadingOrUnloadingRequiresFreshDecoding() {
        AtomicInteger calls = new AtomicInteger();
        DecodedRuleCache cache = cache(calls);
        Config empty = Config.inMemory();
        assertEquals(Map.of(), cache.get(empty, false));
        assertEquals(Map.of(), cache.get(empty, false));
        assertEquals(2, calls.get());

        assertEquals(Map.of(), cache.get(empty, true));
        assertEquals(Map.of(), cache.get(empty, true));
        assertEquals(3, calls.get());
        Config loaded = table(MODEL, List.of("minecraft:stick"));
        assertEquals(Map.of(MODEL, List.of("minecraft:stick")), cache.get(loaded, true));
        assertEquals(Map.of(), cache.get(empty, false));
        assertEquals(Map.of(MODEL, List.of("minecraft:stick")), cache.get(loaded, true));
        assertEquals(6, calls.get());
    }

    @Test
    void oversizedTablesAreRejectedWithoutEnumeratingEntriesWithOrWithoutACachedSnapshot() {
        UnmodifiableConfig oversized = (UnmodifiableConfig) Proxy.newProxyInstance(
                UnmodifiableConfig.class.getClassLoader(), new Class<?>[]{UnmodifiableConfig.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("size")) {
                        return Integer.MAX_VALUE;
                    }
                    throw new AssertionError("Oversized table must not be enumerated: " + method.getName());
                });
        DecodedRuleCache cache = cache(new AtomicInteger());
        assertEquals(HeldItemModelPolicy.decodeConfiguration(oversized), cache.get(oversized, true));
        Config valid = table(MODEL, List.of("minecraft:stick"));
        assertEquals(Map.of(MODEL, List.of("minecraft:stick")), cache.get(valid, true));
        assertEquals(Map.of(), cache.get(oversized, true));
        assertEquals(Map.of(MODEL, List.of("minecraft:stick")), cache.get(valid, true));
    }

    @Test
    void everyPolicyRejectsOversizedListsBeforeSnapshotCopyOrComparisonReadsTheirElements() {
        for (RuleFixture rule : ruleFixtures()) {
            DecodedRuleCache cache = new DecodedRuleCache(rule.decoder(), rule.maxModels(), rule.maxSelectors());
            List<String> oversized = new AbstractList<>() {
                @Override
                public String get(int index) {
                    throw new AssertionError("Oversized selector list must not be traversed");
                }

                @Override
                public int size() {
                    return rule.maxSelectors() + 1;
                }
            };
            Config raw = table(MODEL, oversized);
            assertEquals(rule.decoder().apply(raw), cache.get(raw, true));

            raw.valueMap().put(MODEL, List.of(rule.selector()));
            Map<String, List<String>> valid = cache.get(raw, true);
            assertEquals(Map.of(MODEL, List.of(rule.selector())), valid);
            raw.valueMap().put(MODEL, oversized);
            assertEquals(Map.of(), cache.get(raw, true));
            raw.valueMap().put(MODEL, List.of(rule.selector()));
            assertEquals(valid, cache.get(raw, true));
        }
    }

    private static DecodedRuleCache cache(AtomicInteger calls) {
        return new DecodedRuleCache(value -> {
            calls.incrementAndGet();
            return HeldItemModelPolicy.decodeConfiguration(value);
        }, HeldItemModelPolicy.MAX_MODELS, HeldItemModelPolicy.MAX_SELECTORS_PER_MODEL);
    }

    private static Config table(String model, List<?> selectors) {
        Config result = Config.inMemory();
        result.valueMap().put(model, selectors);
        return result;
    }

    private static List<RuleFixture> ruleFixtures() {
        return List.of(
                new RuleFixture(HeldItemModelPolicy::decodeConfiguration, HeldItemModelPolicy.MAX_MODELS,
                        HeldItemModelPolicy.MAX_SELECTORS_PER_MODEL, "minecraft:stick"),
                new RuleFixture(EntityModelPolicy::decodeConfiguration, EntityModelPolicy.MAX_MODELS,
                        EntityModelPolicy.MAX_SELECTORS_PER_MODEL, "minecraft:arrow"),
                new RuleFixture(MovementAnimationPolicy::decodeConfiguration, MovementAnimationPolicy.MAX_MODELS,
                        MovementAnimationPolicy.MAX_SELECTORS_PER_MODEL, "run"),
                new RuleFixture(value -> ModAnimationPolicy.decodeConfiguration(ModAnimationType.PARCOOL, value),
                        ModAnimationPolicy.MAX_MODELS, ModAnimationPolicy.maxSelectorsPerModel(ModAnimationType.PARCOOL),
                        "roll_front"),
                new RuleFixture(value -> ModAnimationPolicy.decodeConfiguration(ModAnimationType.SWEM, value),
                        ModAnimationPolicy.MAX_MODELS, ModAnimationPolicy.maxSelectorsPerModel(ModAnimationType.SWEM),
                        "gallop"));
    }

    private record RuleFixture(Function<Object, Map<String, List<String>>> decoder,
                               int maxModels, int maxSelectors, String selector) {
    }
}
