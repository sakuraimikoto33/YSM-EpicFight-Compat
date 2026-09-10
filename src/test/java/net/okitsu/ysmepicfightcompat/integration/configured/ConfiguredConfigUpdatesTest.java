package net.okitsu.ysmepicfightcompat.integration.configured;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.config.ServerPreferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredConfigUpdatesTest {
    private static final List<String> HELD_RULES =
            List.of("common", "models", "exclusions", "heldItemModelExclusions");
    private static final List<String> PROJECTILE_RULES =
            List.of("common", "models", "exclusions", "projectileModelExclusions");

    @Test
    void scalarSavePreservesWarningCacheAndOtherClientSettings() {
        ModConfigSpec spec = ClientPreferences.CLIENT_SPEC;
        CommentedConfig data = defaults(spec);
        data.set("client.epicFightCompatibilityWarningShown", true);
        data.set("client.suppressBattleModeOverlay", false);
        data.set("client.cache.clientModelMemoryCacheTargetCount", 128);
        data.set("client.cache.clientModelDiskCacheMiB", 512);
        data.set("client.cache.remoteModelDiskCacheMiB", 1024);
        data.set("common.models.useYsmHeldItemModels", false);
        CommentedConfig changes = CommentedConfig.inMemory();
        changes.set("client.animationEvaluationRateLimitHz", 30);

        ConfiguredConfigUpdates.putAll(spec, data, changes);
        spec.correct(data);

        assertEquals(30, data.<Integer>get("client.animationEvaluationRateLimitHz"));
        assertTrue(data.<Boolean>get("client.epicFightCompatibilityWarningShown"));
        assertFalse(data.<Boolean>get("client.suppressBattleModeOverlay"));
        assertEquals(128, data.<Integer>get("client.cache.clientModelMemoryCacheTargetCount"));
        assertEquals(512, data.<Integer>get("client.cache.clientModelDiskCacheMiB"));
        assertEquals(1024, data.<Integer>get("client.cache.remoteModelDiskCacheMiB"));
        assertFalse(data.<Boolean>get("common.models.useYsmHeldItemModels"));
    }

    @Test
    void dynamicMapLeavesReplaceDeletedRulesAndKeepSiblingSettings() {
        ModConfigSpec spec = ClientPreferences.CLIENT_SPEC;
        CommentedConfig data = defaults(spec);
        Config original = rules("synthetic/kept", "minecraft:stick");
        original.set(List.of("synthetic/deleted"), List.of("minecraft:stone"));
        data.set(HELD_RULES, original);
        data.set(PROJECTILE_RULES, rules("synthetic/projectile", "minecraft:arrow"));
        data.set("common.models.useYsmHeldItemModels", false);
        CommentedConfig changes = CommentedConfig.inMemory();
        changes.set(HELD_RULES, rules("synthetic/kept", "minecraft:diamond_sword"));

        ConfiguredConfigUpdates.putAll(spec, data, changes);
        spec.correct(data);

        UnmodifiableConfig remaining = data.get(HELD_RULES);
        assertEquals(1, remaining.size());
        assertEquals(List.of("minecraft:diamond_sword"), remaining.get(List.of("synthetic/kept")));
        assertFalse(remaining.contains(List.of("synthetic/deleted")));
        assertEquals(List.of("minecraft:arrow"), data.<UnmodifiableConfig>get(PROJECTILE_RULES)
                .get(List.of("synthetic/projectile")));
        assertFalse(data.<Boolean>get("common.models.useYsmHeldItemModels"));

        changes.set(HELD_RULES, Config.inMemory());
        ConfiguredConfigUpdates.putAll(spec, data, changes);
        spec.correct(data);

        assertTrue(data.<UnmodifiableConfig>get(HELD_RULES).isEmpty());
        assertEquals(List.of("minecraft:arrow"), data.<UnmodifiableConfig>get(PROJECTILE_RULES)
                .get(List.of("synthetic/projectile")));
    }

    @Test
    void commonCacheSavePreservesTheUnchangedSibling() {
        ModConfigSpec spec = ServerPreferences.COMMON_SPEC;
        CommentedConfig data = defaults(spec);
        data.set("server.cache.serverModelDiskCacheEnabled", false);
        CommentedConfig changes = CommentedConfig.inMemory();
        changes.set("server.cache.serverModelDiskCacheMiB", 1024);

        ConfiguredConfigUpdates.putAll(spec, data, changes);
        spec.correct(data);

        assertEquals(1024, data.<Integer>get("server.cache.serverModelDiskCacheMiB"));
        assertFalse(data.<Boolean>get("server.cache.serverModelDiskCacheEnabled"));
    }

    @Test
    void unrelatedSpecsKeepConfiguredsOriginalPutAllBehavior() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.define("client.changed", 1);
        builder.define("client.unchanged", 2);
        ModConfigSpec foreign = builder.build();
        CommentedConfig data = defaults(foreign);
        CommentedConfig changes = CommentedConfig.inMemory();
        changes.set("client.changed", 3);

        ConfiguredConfigUpdates.putAll(foreign, data, changes);

        assertEquals(3, data.<Integer>get("client.changed"));
        assertFalse(data.contains("client.unchanged"));
    }

    private static CommentedConfig defaults(ModConfigSpec spec) {
        CommentedConfig data = CommentedConfig.inMemory();
        spec.correct(data);
        return data;
    }

    private static Config rules(String modelId, String selector) {
        Config data = Config.inMemory();
        data.set(List.of(modelId), List.of(selector));
        return data;
    }
}
