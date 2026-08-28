package net.okitsu.ysmepicfightcompat.integration.configured;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredHeldItemRulesTest {
    @Test
    void recognizesEveryDynamicClientRuleTable() {
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "heldItemModelExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "projectileModelExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "vehicleModelExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "heldItemSwitchAnimationExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "movementAnimationExclusions"));
        assertFalse(ConfiguredHeldItemRules.recognizesRuleEntry(
                "unknownDynamicRules"));
    }
}
