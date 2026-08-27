package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.network.message.MaidMovementPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidMovementPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceUpdateMessage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Publishes an opaque policy generation and answers server-issued maid queries. */
public final class ClientMaidPreferenceSync {
    private record HeldPolicyFingerprint(
            boolean heldItems,
            Map<String, List<String>> heldItemExclusions,
            boolean switchAnimations,
            Map<String, List<String>> switchAnimationExclusions,
            long itemTagGeneration) {
    }

    private record MovementPolicyFingerprint(
            boolean movementAnimations,
            Map<String, List<String>> movementExclusions) {
    }

    private static HeldPolicyFingerprint lastHeldPolicy;
    private static MovementPolicyFingerprint lastMovementPolicy;
    private static UUID heldItemPolicyEpoch;
    private static UUID movementPolicyEpoch;
    private static UUID lastSentHeldItemEpoch;
    private static UUID lastSentMovementEpoch;
    private static volatile long itemTagGeneration;

    private ClientMaidPreferenceSync() {
    }

    public static void accept(MaidPreferenceQueryMessage query) {
        if (query == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local == null || minecraft.getConnection() == null
                || !local.getUUID().equals(query.ownerUuid())) {
            return;
        }
        refreshAndPublish();
        if (!query.policyEpoch().equals(heldItemPolicyEpoch)) {
            return;
        }
        CompatNetwork.sendMaidPreferences(new MaidPreferenceUpdateMessage(
                query.queryId(), query.entityId(), query.entityUuid(),
                query.policyEpoch(), query.revision(),
                ClientHeldItemModelPreferences.resolveState(
                        query.modelId(), stack(query.mainHandItem()),
                        stack(query.offHandItem()))));
    }

    public static void accept(MaidMovementPreferenceQueryMessage query) {
        if (query == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local == null || minecraft.getConnection() == null
                || !local.getUUID().equals(query.ownerUuid())) {
            return;
        }
        refreshAndPublish();
        if (!query.policyEpoch().equals(movementPolicyEpoch)) {
            return;
        }
        CompatNetwork.sendMaidMovementPreferences(
                new MaidMovementPreferenceUpdateMessage(
                        query.queryId(), query.entityId(), query.entityUuid(),
                        query.policyEpoch(), query.revision(),
                        ClientMovementAnimationPreferences.resolveState(
                                query.modelId(), query.movement()).ysmOwned()));
    }

    public static void tickSync() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            beginConnection();
            return;
        }
        refreshAndPublish();
    }

    public static void beginConnection() {
        lastHeldPolicy = null;
        lastMovementPolicy = null;
        heldItemPolicyEpoch = null;
        movementPolicyEpoch = null;
        lastSentHeldItemEpoch = null;
        lastSentMovementEpoch = null;
        RemoteMaidPreferences.beginConnection();
    }

    /** Invalidates only the opaque generation after client item tags change. */
    public static synchronized void itemTagsUpdated() {
        itemTagGeneration = itemTagGeneration == Long.MAX_VALUE
                ? 0L : itemTagGeneration + 1L;
    }

    private static void refreshAndPublish() {
        HeldPolicyFingerprint held = heldFingerprint();
        if (!held.equals(lastHeldPolicy) || heldItemPolicyEpoch == null) {
            lastHeldPolicy = held;
            heldItemPolicyEpoch = UUID.randomUUID();
        }
        MovementPolicyFingerprint movement = movementFingerprint();
        if (!movement.equals(lastMovementPolicy) || movementPolicyEpoch == null) {
            lastMovementPolicy = movement;
            movementPolicyEpoch = UUID.randomUUID();
        }
        if (!heldItemPolicyEpoch.equals(lastSentHeldItemEpoch)
                || !movementPolicyEpoch.equals(lastSentMovementEpoch)) {
            lastSentHeldItemEpoch = heldItemPolicyEpoch;
            lastSentMovementEpoch = movementPolicyEpoch;
            CompatNetwork.sendOwnerPreferenceEpoch(
                    heldItemPolicyEpoch, movementPolicyEpoch);
        }
    }

    private static HeldPolicyFingerprint heldFingerprint() {
        return new HeldPolicyFingerprint(
                ClientPreferences.USE_YSM_HELD_ITEM_MODELS.get(),
                ClientPreferences.heldItemModelExclusions(),
                ClientPreferences.USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS.get(),
                ClientPreferences.heldItemSwitchAnimationExclusions(),
                itemTagGeneration);
    }

    private static MovementPolicyFingerprint movementFingerprint() {
        return new MovementPolicyFingerprint(
                ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS.get(),
                ClientPreferences.movementAnimationExclusions());
    }

    private static ItemStack stack(ResourceLocation itemId) {
        return BuiltInRegistries.ITEM.getOptional(itemId)
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
    }
}
