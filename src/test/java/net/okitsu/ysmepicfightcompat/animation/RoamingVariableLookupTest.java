package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoamingVariableLookupTest {
    @Test
    void readsNamesEncodedWithTheOfficialSuffixConvention() {
        RoamingVariableLookup lookup = new RoamingVariableLookup(String::hashCode);
        Map<Integer, Object> values = Map.of("jacket".hashCode(), 2.0F);

        RoamingVariableLookup.Lookup result = lookup.lookup(
                "v.roaming.jacket", values::get);

        assertTrue(result.present());
        assertEquals(2.0D, result.value(), 0.0001D);
        assertFalse(lookup.lookup("v.local.jacket", values::get).present());
    }

    @Test
    void checksAllSupportedHashConventionsBeforeAcceptingZero() {
        RoamingVariableLookup lookup = new RoamingVariableLookup(String::hashCode);
        Map<Integer, Object> values = Map.of(
                "v.roaming.jacket".hashCode(), 0.0F,
                "jacket".hashCode(), 1.0F);

        RoamingVariableLookup.Lookup result = lookup.lookup(
                "v.roaming.jacket", hash -> values.getOrDefault(hash, 0.0F));

        assertTrue(result.present());
        assertEquals(1.0D, result.value(), 0.0001D);
    }

    @Test
    void preservesAnExplicitZeroValue() {
        RoamingVariableLookup lookup = new RoamingVariableLookup(String::hashCode);

        RoamingVariableLookup.Lookup result = lookup.lookup(
                "v.roaming.jacket", ignored -> 0.0F);

        assertTrue(result.present());
        assertEquals(0.0D, result.value(), 0.0001D);
    }

    @Test
    void readsTheCurrentProviderValueOnEveryLookup() {
        RoamingVariableLookup lookup = new RoamingVariableLookup(String::hashCode);
        AtomicReference<Float> jacket = new AtomicReference<>(0.0F);
        int jacketHash = "jacket".hashCode();

        assertEquals(0.0D, lookup.lookup("v.roaming.jacket",
                hash -> hash == jacketHash ? jacket.get() : 0.0F).value(), 0.0001D);
        jacket.set(1.0F);
        assertEquals(1.0D, lookup.lookup("v.roaming.jacket",
                hash -> hash == jacketHash ? jacket.get() : 0.0F).value(), 0.0001D);
        jacket.set(2.0F);
        assertEquals(2.0D, lookup.lookup("v.roaming.jacket",
                hash -> hash == jacketHash ? jacket.get() : 0.0F).value(), 0.0001D);
    }

    @Test
    void ignoresNonNumericProviderResults() {
        RoamingVariableLookup lookup = new RoamingVariableLookup(String::hashCode);

        assertFalse(lookup.lookup("v.roaming.jacket", ignored -> null).present());
    }
}
