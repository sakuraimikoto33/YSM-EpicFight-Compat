package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerOrderTest {
    @Test
    void fixedSlotsKeepTheirBareAndPlayerNames() {
        Map<String, Integer> phases = Map.ofEntries(
                Map.entry("pre_main", 5), Map.entry("main", 10),
                Map.entry("post_main", 15), Map.entry("pre_hold", 20),
                Map.entry("hold_mainhand", 25), Map.entry("hold_offhand", 25),
                Map.entry("post_hold", 30), Map.entry("pre_swing", 35),
                Map.entry("swing", 40), Map.entry("post_swing", 45),
                Map.entry("pre_use", 50), Map.entry("use", 55),
                Map.entry("post_use", 60), Map.entry("passenger", 65));

        phases.forEach((slot, stage) -> {
            for (String name : List.of(slot, "player." + slot)) {
                assertTrue(ControllerOrder.slot(name, slot), name);
                assertEquals(stage.intValue(), ControllerOrder.stage(name), name);
            }
        });
    }

    @Test
    void dynamicPreAndPostSlotsJoinTheirDocumentedPhases() {
        Map<String, Integer> phases = Map.of(
                "pre_main", 5, "post_main", 15, "pre_hold", 20, "post_hold", 30,
                "pre_swing", 35, "post_swing", 45, "pre_use", 50, "post_use", 60);

        phases.forEach((slot, stage) -> {
            for (String suffix : List.of("custom", "10", "two_words", "効果")) {
                String name = "player." + slot + '_' + suffix;
                assertTrue(ControllerOrder.slot(name, slot), name);
                assertTrue(ControllerOrder.dynamic(name), name);
                assertEquals(stage.intValue(), ControllerOrder.stage(name), name);
            }
        });
    }

    @Test
    void dynamicSlotsRejectEmptySuffixesChildrenAndOtherNamespaces() {
        for (String slot : List.of("pre_main", "post_main", "pre_hold", "post_hold",
                "pre_swing", "post_swing", "pre_use", "post_use")) {
            for (String name : List.of("player." + slot + '_', slot + "_custom",
                    "player." + slot + "_custom.child", "player." + slot + ".child",
                    "other." + slot, "other." + slot + "_custom",
                    "player.parent." + slot, "player.parent." + slot + "_custom")) {
                assertFalse(ControllerOrder.slot(name, slot), name);
                assertFalse(ControllerOrder.dynamic(name), name);
                assertEquals(70, ControllerOrder.stage(name), name);
            }
        }
    }

    @Test
    void ordinarySlotsDoNotGainDynamicSuffixesOrNestedMatches() {
        for (String slot : List.of("main", "hold_mainhand", "hold_offhand",
                "swing", "use", "passenger")) {
            for (String name : List.of(slot + "_custom", "player." + slot + "_custom",
                    "player." + slot + ".child", "player.parent." + slot,
                    "other." + slot)) {
                assertFalse(ControllerOrder.slot(name, slot), name);
                assertFalse(ControllerOrder.dynamic(name), name);
                assertEquals(70, ControllerOrder.stage(name), name);
            }
        }
        assertFalse(ControllerOrder.slot("player.pre_use_custom", "use"));
        assertEquals(70, ControllerOrder.stage("player.hold"));
    }

    @Test
    void parallelGroupsRetainAllLegacyNumberSpellings() {
        for (String prefix : List.of("player.parallel", "player.pre_parallel")) {
            for (int number = 0; number <= 7; number++) {
                for (String separator : List.of("", "_")) {
                    String name = prefix + separator + number;
                    assertTrue(ControllerOrder.parallel(name), name);
                    assertEquals(prefix.contains("pre_"), ControllerOrder.preParallel(name), name);
                    assertEquals(prefix.contains("pre_") ? 0 : 100, ControllerOrder.stage(name), name);
                }
            }
        }
    }

    @Test
    void parallelGroupsAcceptArbitraryNonemptyDynamicSuffixes() {
        for (String prefix : List.of("player.parallel", "player.pre_parallel")) {
            for (String suffix : List.of("custom", "8", "100", "two_words", "効果")) {
                String name = prefix + '_' + suffix;
                assertTrue(ControllerOrder.parallel(name), name);
                assertTrue(ControllerOrder.dynamic(name), name);
                assertEquals(prefix.contains("pre_"), ControllerOrder.preParallel(name), name);
                assertEquals(prefix.contains("pre_") ? 0 : 100, ControllerOrder.stage(name), name);
            }
        }
    }

    @Test
    void parallelGroupsRejectInvalidLegacyNamesAndChildControllers() {
        for (String name : List.of("player.parallel", "player.pre_parallel",
                "player.parallel_", "player.pre_parallel_", "player.parallel8",
                "player.pre_parallel8", "player.parallel10", "player.parallelcustom",
                "parallel0", "pre_parallel0", "parallel_custom", "pre_parallel_custom",
                "other.parallel0", "player.parent.parallel0", "player.parallel0.child",
                "player.parallel_custom.child", "player.pre_parallel_custom.child",
                "player.not_parallel_custom", "player.pre_parallelcustom")) {
            assertFalse(ControllerOrder.parallel(name), name);
            assertFalse(ControllerOrder.preParallel(name), name);
            assertFalse(ControllerOrder.dynamic(name), name);
            assertEquals(70, ControllerOrder.stage(name), name);
        }
    }

    @Test
    void classificationIsCaseInsensitiveButDoesNotTrimOrAcceptNullSlots() {
        assertTrue(ControllerOrder.slot("PLAYER.PRE_MAIN_Custom", "pre_main"));
        assertEquals(5, ControllerOrder.stage("PLAYER.PRE_MAIN_Custom"));
        assertEquals(10, ControllerOrder.stage("PLAYER.MAIN"));
        assertTrue(ControllerOrder.parallel("PLAYER.PARALLEL_Custom"));
        assertTrue(ControllerOrder.preParallel("PLAYER.PRE_PARALLEL_Custom"));
        assertTrue(ControllerOrder.dynamic("PLAYER.POST_HOLD_Custom"));
        assertEquals(70, ControllerOrder.stage(" player.main"));
        assertEquals(70, ControllerOrder.stage(null));
        assertFalse(ControllerOrder.parallel(null));
        assertFalse(ControllerOrder.preParallel(null));
        assertFalse(ControllerOrder.dynamic(null));
        assertFalse(ControllerOrder.slot(null, "main"));
        assertFalse(ControllerOrder.slot("", ""));
        assertFalse(ControllerOrder.slot(null, null));
        assertFalse(ControllerOrder.slot("player.main", "player.main"));
    }

    @Test
    void dynamicOnlyClassifiesSuffixedTopLevelNames() {
        for (String phase : List.of("pre_main", "post_main", "pre_hold", "post_hold",
                "pre_swing", "post_swing", "pre_use", "post_use")) {
            assertFalse(ControllerOrder.dynamic(phase), phase);
            assertFalse(ControllerOrder.dynamic("player." + phase), phase);
        }
        assertFalse(ControllerOrder.dynamic("player.parallel0"));
        assertFalse(ControllerOrder.dynamic("player.pre_parallel7"));
        assertTrue(ControllerOrder.dynamic("player.parallel_0"));
        assertTrue(ControllerOrder.dynamic("player.pre_parallel_7"));
    }

    @Test
    void comparatorOrdersByPhaseBeforeOriginalName() {
        List<String> names = List.of("player.parallel_z", "player.pre_main_z", "player.use",
                "player.main", "player.post_main_z", "player.pre_parallel_z",
                "player.passenger", "other", "player.pre_hold_z", "player.hold_offhand",
                "player.post_hold_z", "player.pre_swing_z", "player.swing",
                "player.post_swing_z", "player.pre_use_z", "player.post_use_z");

        assertEquals(List.of("player.pre_parallel_z", "player.pre_main_z", "player.main",
                "player.post_main_z", "player.pre_hold_z", "player.hold_offhand",
                "player.post_hold_z", "player.pre_swing_z", "player.swing",
                "player.post_swing_z", "player.pre_use_z", "player.use",
                "player.post_use_z", "player.passenger", "other", "player.parallel_z"),
                names.stream().sorted(ControllerOrder.comparator()).toList());
    }

    @Test
    void comparatorKeepsCaseAndLexicographicNumericSuffixOrderWithinAPhase() {
        List<String> names = List.of("player.pre_main_a", "player.pre_main_2",
                "player.pre_main_10", "player.pre_main_Z", "Player.Pre_Main_z");
        assertEquals(List.of("Player.Pre_Main_z", "player.pre_main_10",
                        "player.pre_main_2", "player.pre_main_Z", "player.pre_main_a"),
                names.stream().sorted(ControllerOrder.comparator()).toList());
        assertEquals(List.of("player.parallel0", "player.parallel7", "player.parallel_10",
                        "player.parallel_2", "player.parallel_z"),
                List.of("player.parallel_z", "player.parallel_2", "player.parallel7",
                                "player.parallel_10", "player.parallel0")
                        .stream().sorted(ControllerOrder.comparator()).toList());
    }
}
