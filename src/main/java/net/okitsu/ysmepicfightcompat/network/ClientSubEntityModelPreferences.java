package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.render.SubEntityModelAuthorship;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Answers server-issued sub-entity queries without transmitting local config rules. */
public final class ClientSubEntityModelPreferences {
    private static final int MAX_PENDING = 256;
    private static final int MAX_RETRIES_PER_TICK = 16;
    private static final long QUERY_TIMEOUT_TICKS = 100L;

    private record Pending(SubEntityPreferenceQueryMessage query,
                           long receivedTick) {
    }

    private enum Resolution {
        UNKNOWN,
        EPIC_FIGHT,
        YSM
    }

    private static final LinkedHashMap<UUID, Pending> PENDING =
            new LinkedHashMap<>();
    private static EntityModelPolicy projectilePolicy = EntityModelPolicy.DEFAULT;
    private static EntityModelPolicy vehiclePolicy = EntityModelPolicy.DEFAULT;
    private static Map<String, List<String>> projectileRules = Map.of();
    private static Map<String, List<String>> vehicleRules = Map.of();
    private static boolean projectileEnabled = true;
    private static boolean vehicleEnabled = true;
    private static boolean projectileInitialized;
    private static boolean vehicleInitialized;
    private static boolean invalidRulesLogged;
    private static long clientTick;
    private static volatile long entityTagGeneration;
    private static volatile long modelDefinitionGeneration;

    private ClientSubEntityModelPreferences() {
    }

    public static void accept(SubEntityPreferenceQueryMessage query) {
        if (!isCurrentOwner(query)) {
            return;
        }
        // Epoch refresh and policy/model evaluation are intentionally deferred to
        // the bounded tick loop so a server cannot amplify PLAY_TO_CLIENT queries
        // into unbounded client work or immediate responses.
        remember(query);
    }

    public static void tickSync() {
        clientTick++;
        if (Minecraft.getInstance().player == null
                || Minecraft.getInstance().getConnection() == null) {
            PENDING.clear();
            return;
        }
        UUID epoch = ClientMaidPreferenceSync.heldItemPolicyEpoch();
        int attempted = 0;
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next().getValue();
            SubEntityPreferenceQueryMessage query = pending.query();
            if (clientTick - pending.receivedTick() >= QUERY_TIMEOUT_TICKS
                    || epoch == null || !epoch.equals(query.policyEpoch())
                    || !isCurrentOwner(query)) {
                iterator.remove();
                continue;
            }
            if (attempted++ >= MAX_RETRIES_PER_TICK) {
                break;
            }
            Resolution result = resolve(query);
            if (result != Resolution.UNKNOWN) {
                iterator.remove();
                answer(query, result == Resolution.YSM);
            }
        }
    }

    public static void beginConnection() {
        PENDING.clear();
        clientTick = 0L;
        invalidRulesLogged = false;
        RemoteSubEntityModelPreferences.beginConnection();
    }

    /** Invalidates the opaque owner policy generation after entity tags change. */
    public static synchronized void entityTypesUpdated() {
        entityTagGeneration = entityTagGeneration == Long.MAX_VALUE
                ? 0L : entityTagGeneration + 1L;
    }

    public static long entityTagGeneration() {
        return entityTagGeneration;
    }

    /** Invalidates decisions whose held-prop authorship came from replaced model data. */
    public static synchronized void modelDefinitionsUpdated() {
        modelDefinitionGeneration = modelDefinitionGeneration == Long.MAX_VALUE
                ? 0L : modelDefinitionGeneration + 1L;
    }

    public static long modelDefinitionGeneration() {
        return modelDefinitionGeneration;
    }

    private static Resolution resolve(SubEntityPreferenceQueryMessage query) {
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE
                .getOptional(query.entityTypeId()).orElse(null);
        if (entityType == null) {
            return Resolution.EPIC_FIGHT;
        }
        return switch (query.kind()) {
            case VEHICLE -> decision(vehiclePolicy().usesYsm(
                    query.modelId(), entityType));
            case FISHING_HOOK -> decision(
                    ClientHeldItemModelPreferences.usesYsmLocal(
                            query.modelId(), stack(query)));
            case PROJECTILE -> projectile(query, entityType);
        };
    }

    private static Resolution projectile(SubEntityPreferenceQueryMessage query,
                                         EntityType<?> entityType) {
        ItemStack source = stack(query);
        if (isHeldItemControlledProjectile(source)) {
            Player local = Minecraft.getInstance().player;
            SubEntityModelAuthorship.State authorship =
                    SubEntityModelAuthorship.heldItem(
                            query.modelId(), local, source);
            if (authorship == SubEntityModelAuthorship.State.UNKNOWN) {
                return Resolution.UNKNOWN;
            }
            if (authorship == SubEntityModelAuthorship.State.PRESENT) {
                return decision(ClientHeldItemModelPreferences.usesYsmLocal(
                        query.modelId(), source));
            }
        }
        return decision(projectilePolicy().usesYsm(query.modelId(), entityType));
    }

    private static boolean isHeldItemControlledProjectile(ItemStack source) {
        return !source.isEmpty() && (source.getItem() instanceof TridentItem
                || source.getItem() instanceof BowItem);
    }

    private static ItemStack stack(SubEntityPreferenceQueryMessage query) {
        return BuiltInRegistries.ITEM.getOptional(query.sourceItemId())
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static Resolution decision(boolean ysm) {
        return ysm ? Resolution.YSM : Resolution.EPIC_FIGHT;
    }

    private static void answer(SubEntityPreferenceQueryMessage query,
                               boolean ysm) {
        CompatNetwork.sendSubEntityPreferences(
                new SubEntityPreferenceUpdateMessage(
                        query.queryId(), query.entityId(), query.entityUuid(),
                        query.ownerUuid(), query.policyEpoch(), query.revision(),
                        query.kind(), ysm));
    }

    private static boolean isCurrentOwner(SubEntityPreferenceQueryMessage query) {
        if (query == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        return local != null && minecraft.getConnection() != null
                && local.getUUID().equals(query.ownerUuid());
    }

    private static void remember(SubEntityPreferenceQueryMessage query) {
        PENDING.putIfAbsent(query.queryId(), new Pending(query, clientTick));
        while (PENDING.size() > MAX_PENDING) {
            PENDING.remove(PENDING.keySet().iterator().next());
        }
    }

    private static EntityModelPolicy projectilePolicy() {
        boolean enabled = ClientPreferences.USE_YSM_PROJECTILE_MODELS.get();
        Map<String, List<String>> rules =
                ClientPreferences.projectileModelExclusions();
        if (projectileInitialized && projectileEnabled == enabled
                && projectileRules.equals(rules)) {
            return projectilePolicy;
        }
        projectilePolicy = createPolicy(enabled, rules, "projectile");
        projectileEnabled = enabled;
        projectileRules = Map.copyOf(rules);
        projectileInitialized = true;
        return projectilePolicy;
    }

    private static EntityModelPolicy vehiclePolicy() {
        boolean enabled = ClientPreferences.USE_YSM_VEHICLE_MODELS.get();
        Map<String, List<String>> rules = ClientPreferences.vehicleModelExclusions();
        if (vehicleInitialized && vehicleEnabled == enabled
                && vehicleRules.equals(rules)) {
            return vehiclePolicy;
        }
        vehiclePolicy = createPolicy(enabled, rules, "vehicle");
        vehicleEnabled = enabled;
        vehicleRules = Map.copyOf(rules);
        vehicleInitialized = true;
        return vehiclePolicy;
    }

    private static EntityModelPolicy createPolicy(
            boolean enabled, Map<String, List<String>> rules, String label) {
        try {
            EntityModelPolicy result = EntityModelPolicy.create(enabled, rules);
            invalidRulesLogged = false;
            return result;
        } catch (IllegalArgumentException exception) {
            if (!invalidRulesLogged) {
                invalidRulesLogged = true;
                CompatMod.LOG.warn("YSM-EF Compat: invalid {} model exclusions; "
                        + "using the main setting without exclusions", label, exception);
            }
            return EntityModelPolicy.create(enabled, Map.of());
        }
    }
}
