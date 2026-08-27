package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.network.message.MaidMovementPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidMovementPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceUpdateMessage;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Server state machine for owner-resolved settings on tracked TLM maids. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidPreferenceBroadcaster {
    static final int MAX_TRACKED_MAIDS = 2048;
    static final int MAX_OWNER_DECISIONS = 256;
    static final int MAX_OWNER_PENDING_QUERIES = 256;
    static final int MAX_QUERIES_PER_OWNER_TICK = 16;
    static final int MAX_RESPONSES_PER_OWNER_TICK = 64;
    static final int MAX_QUERIES_PER_SERVER_TICK = 256;
    static final int QUERY_TIMEOUT_TICKS = 100;
    static final int EPOCH_COOLDOWN_TICKS = 10;

    private static final AtomicLong REVISION = new AtomicLong();
    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<UUID, OwnerSession> OWNERS = new LinkedHashMap<>();
    private static long globalQueryTick = Long.MIN_VALUE;
    private static int globalQueriesThisTick;

    private record HeldKey(String modelId,
                           ResourceLocation mainHandItem,
                           ResourceLocation offHandItem) {
    }

    private record MovementKey(String modelId,
                               MovementAnimationType movement) {
    }

    private record Source(UUID ownerUuid, HeldKey held,
                          MovementKey movement) {
    }

    private record Pending(UUID queryId, UUID maidUuid,
                           long sourceRevision, long sentTick) {
    }

    private record EpochChange(boolean heldItems, boolean movement) {
        private boolean any() {
            return heldItems || movement;
        }
    }

    private static final class Entry {
        private LivingEntity entity;
        private final Set<UUID> trackers = new HashSet<>();
        private long heldSourceRevision = nextRevision();
        private long movementSourceRevision = nextRevision();
        private long revision = nextRevision();
        private Source source;
        private final MaidPreferenceResolution resolution =
                new MaidPreferenceResolution();

        private Entry(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private static final class OwnerSession {
        private UUID activeHeldItemEpoch;
        private UUID pendingHeldItemEpoch;
        private long nextHeldItemEpochTick;
        private UUID activeMovementEpoch;
        private UUID pendingMovementEpoch;
        private long nextMovementEpochTick;
        private long queryTick = Long.MIN_VALUE;
        private int queriesThisTick;
        private long responseTick = Long.MIN_VALUE;
        private int responsesThisTick;
        private final LinkedHashMap<HeldKey, HeldItemModelDisplayState>
                heldDecisions = new LinkedHashMap<>(16, 0.75F, true);
        private final LinkedHashMap<MovementKey, Boolean> movementDecisions =
                new LinkedHashMap<>(16, 0.75F, true);
        private final Map<HeldKey, Pending> heldPending = new LinkedHashMap<>();
        private final Map<MovementKey, Pending> movementPending =
                new LinkedHashMap<>();

        private void offerEpochs(UUID heldItemEpoch, UUID movementEpoch) {
            if (!heldItemEpoch.equals(activeHeldItemEpoch)) {
                pendingHeldItemEpoch = heldItemEpoch;
            }
            if (!movementEpoch.equals(activeMovementEpoch)) {
                pendingMovementEpoch = movementEpoch;
            }
        }

        private EpochChange applyPendingEpochs(long tick) {
            boolean heldChanged = false;
            if (pendingHeldItemEpoch != null && tick >= nextHeldItemEpochTick) {
                UUID next = pendingHeldItemEpoch;
                pendingHeldItemEpoch = null;
                nextHeldItemEpochTick = tick + EPOCH_COOLDOWN_TICKS;
                if (!next.equals(activeHeldItemEpoch)) {
                    activeHeldItemEpoch = next;
                    heldDecisions.clear();
                    heldPending.clear();
                    heldChanged = true;
                }
            }
            boolean movementChanged = false;
            if (pendingMovementEpoch != null && tick >= nextMovementEpochTick) {
                UUID next = pendingMovementEpoch;
                pendingMovementEpoch = null;
                nextMovementEpochTick = tick + EPOCH_COOLDOWN_TICKS;
                if (!next.equals(activeMovementEpoch)) {
                    activeMovementEpoch = next;
                    movementDecisions.clear();
                    movementPending.clear();
                    movementChanged = true;
                }
            }
            return new EpochChange(heldChanged, movementChanged);
        }

        private boolean allowQuery(long tick) {
            if (queryTick != tick) {
                queryTick = tick;
                queriesThisTick = 0;
            }
            return queriesThisTick++ < MAX_QUERIES_PER_OWNER_TICK;
        }

        private boolean allowResponse(long tick) {
            if (responseTick != tick) {
                responseTick = tick;
                responsesThisTick = 0;
            }
            return responsesThisTick++ < MAX_RESPONSES_PER_OWNER_TICK;
        }

        private int pendingCount() {
            return heldPending.size() + movementPending.size();
        }

        private void remember(HeldKey key, HeldItemModelDisplayState decision) {
            heldDecisions.put(key, decision);
            trim(heldDecisions);
        }

        private void remember(MovementKey key, boolean decision) {
            movementDecisions.put(key, decision);
            trim(movementDecisions);
        }

        private static <K, V> void trim(LinkedHashMap<K, V> decisions) {
            while (decisions.size() > MAX_OWNER_DECISIONS) {
                decisions.remove(decisions.keySet().iterator().next());
            }
        }
    }

    private MaidPreferenceBroadcaster() {
    }

    @SubscribeEvent
    public static void startedTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer recipient)
                || !(event.getTarget() instanceof LivingEntity maid)
                || !TouhouMaidSelectionAccess.integrationLoaded()
                || !TouhouMaidSelectionAccess.isSupportedMaid(maid)) {
            return;
        }
        Entry entry = ENTRIES.get(maid.getUUID());
        if (entry == null) {
            if (ENTRIES.size() >= MAX_TRACKED_MAIDS) {
                return;
            }
            entry = new Entry(maid);
            ENTRIES.put(maid.getUUID(), entry);
        }
        entry.entity = maid;
        entry.trackers.add(recipient.getUUID());
        refresh(entry);
        resolveOrQuery(recipient.server, entry, recipient.server.getTickCount());
        send(recipient, snapshot(entry));
    }

    @SubscribeEvent
    public static void stoppedTracking(PlayerEvent.StopTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer)
                || !(event.getTarget() instanceof LivingEntity maid)
                || !TouhouMaidSelectionAccess.isSupportedMaid(maid)) {
            return;
        }
        Entry entry = ENTRIES.get(maid.getUUID());
        if (entry == null) {
            return;
        }
        entry.trackers.remove(event.getEntity().getUUID());
        if (entry.trackers.isEmpty()) {
            removeEntry(maid.getUUID(), entry);
        }
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.getEntity().getUUID();
        OWNERS.remove(playerUuid);
        Iterator<Map.Entry<UUID, Entry>> entries = ENTRIES.entrySet().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next().getValue();
            entry.trackers.remove(playerUuid);
            if (entry.trackers.isEmpty()) {
                removePending(entry);
                entries.remove();
                continue;
            }
            if (entry.source != null
                    && playerUuid.equals(entry.source.ownerUuid())) {
                invalidate(entry, true, true);
            }
        }
    }

    @SubscribeEvent
    public static void entityLeftLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof LivingEntity maid)
                || !TouhouMaidSelectionAccess.isSupportedMaid(maid)) {
            return;
        }
        Entry entry = ENTRIES.get(maid.getUUID());
        if (entry != null) {
            removeEntry(maid.getUUID(), entry);
        }
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        for (Map.Entry<UUID, OwnerSession> owner : OWNERS.entrySet()) {
            EpochChange change = owner.getValue().applyPendingEpochs(tick);
            if (change.any()) {
                invalidateOwner(owner.getKey(), change);
            }
        }
        for (Entry entry : ENTRIES.values()) {
            if (entry.trackers.isEmpty() || entry.entity == null
                    || entry.entity.isRemoved()) {
                continue;
            }
            refresh(entry);
            resolveOrQuery(server, entry, tick);
        }
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        ENTRIES.clear();
        OWNERS.clear();
        REVISION.set(0L);
        globalQueryTick = Long.MIN_VALUE;
        globalQueriesThisTick = 0;
    }

    public static void acceptEpoch(ServerPlayer sender,
                                   UUID heldItemEpoch,
                                   UUID movementEpoch) {
        if (sender == null || heldItemEpoch == null || movementEpoch == null) {
            return;
        }
        OWNERS.computeIfAbsent(sender.getUUID(), ignored -> new OwnerSession())
                .offerEpochs(heldItemEpoch, movementEpoch);
    }

    public static void accept(ServerPlayer sender,
                              MaidPreferenceUpdateMessage message) {
        OwnerSession session = responseSession(sender);
        Entry entry = responseEntry(sender, message == null ? null
                : message.entityUuid(), session);
        if (entry == null || message == null) {
            return;
        }
        LivingEntity maid = currentMaid(sender.server, entry, message.entityUuid());
        if (maid == null) {
            return;
        }
        entry.entity = maid;
        refresh(entry);
        Source source = entry.source;
        Pending pending = source == null ? null
                : session.heldPending.get(source.held());
        UUID actualOwner = TouhouMaidSelectionAccess.ownerUuid(maid);
        boolean exactQuery = source != null
                && sender.getUUID().equals(source.ownerUuid())
                && pending != null
                && message.queryId().equals(pending.queryId())
                && message.entityUuid().equals(pending.maidUuid())
                && message.revision() == pending.sourceRevision()
                && message.policyEpoch().equals(session.activeHeldItemEpoch);
        if (!MaidPreferenceAuthorization.permits(
                sender.getUUID(), message.entityId(), message.entityUuid(),
                message.revision(), maid.getId(), maid.getUUID(), actualOwner,
                entry.heldSourceRevision, exactQuery)) {
            return;
        }
        session.heldPending.remove(source.held(), pending);
        session.remember(source.held(), message.heldItems());
        applyHeldDecision(source.ownerUuid(), source.held(), message.heldItems());
    }

    public static void accept(ServerPlayer sender,
                              MaidMovementPreferenceUpdateMessage message) {
        OwnerSession session = responseSession(sender);
        Entry entry = responseEntry(sender, message == null ? null
                : message.entityUuid(), session);
        if (entry == null || message == null) {
            return;
        }
        LivingEntity maid = currentMaid(sender.server, entry, message.entityUuid());
        if (maid == null) {
            return;
        }
        entry.entity = maid;
        refresh(entry);
        Source source = entry.source;
        Pending pending = source == null ? null
                : session.movementPending.get(source.movement());
        UUID actualOwner = TouhouMaidSelectionAccess.ownerUuid(maid);
        boolean exactQuery = source != null
                && source.movement().movement() != null
                && sender.getUUID().equals(source.ownerUuid())
                && pending != null
                && message.queryId().equals(pending.queryId())
                && message.entityUuid().equals(pending.maidUuid())
                && message.revision() == pending.sourceRevision()
                && message.policyEpoch().equals(session.activeMovementEpoch);
        if (!MaidPreferenceAuthorization.permits(
                sender.getUUID(), message.entityId(), message.entityUuid(),
                message.revision(), maid.getId(), maid.getUUID(), actualOwner,
                entry.movementSourceRevision, exactQuery)) {
            return;
        }
        session.movementPending.remove(source.movement(), pending);
        session.remember(source.movement(), message.ysmMovement());
        applyMovementDecision(source.ownerUuid(), source.movement(),
                message.ysmMovement());
    }

    private static OwnerSession responseSession(ServerPlayer sender) {
        if (sender == null) {
            return null;
        }
        OwnerSession session = OWNERS.get(sender.getUUID());
        return session != null
                && session.allowResponse(sender.server.getTickCount())
                ? session : null;
    }

    private static Entry responseEntry(ServerPlayer sender, UUID entityUuid,
                                       OwnerSession session) {
        return sender == null || entityUuid == null || session == null
                ? null : ENTRIES.get(entityUuid);
    }

    private static LivingEntity currentMaid(MinecraftServer server,
                                            Entry entry, UUID entityUuid) {
        Entity actual = entry.entity != null && !entry.entity.isRemoved()
                && entityUuid.equals(entry.entity.getUUID())
                ? entry.entity : findEntity(server, entityUuid);
        return actual instanceof LivingEntity maid
                && TouhouMaidSelectionAccess.isSupportedMaid(maid) ? maid : null;
    }

    private static void refresh(Entry entry) {
        Source current = source(entry.entity);
        if (Objects.equals(current, entry.source)) {
            return;
        }
        Source previous = entry.source;
        boolean ownerChanged = previous == null || current == null
                || !Objects.equals(previous.ownerUuid(), current.ownerUuid());
        boolean heldChanged = ownerChanged
                || !Objects.equals(previous.held(), current.held());
        boolean movementChanged = ownerChanged
                || !Objects.equals(previous.movement(), current.movement());
        if (heldChanged) {
            removeHeldPending(entry, previous);
        }
        if (movementChanged) {
            removeMovementPending(entry, previous);
        }
        entry.source = current;
        invalidate(entry, heldChanged, movementChanged);
    }

    private static Source source(LivingEntity maid) {
        if (maid == null || maid.isRemoved()
                || !TouhouMaidSelectionAccess.isSupportedMaid(maid)) {
            return null;
        }
        UUID ownerUuid = TouhouMaidSelectionAccess.ownerUuid(maid);
        TouhouMaidSelectionAccess.Selection selection =
                TouhouMaidSelectionAccess.resolve(maid);
        String modelId = selection == null ? ""
                : MovementAnimationPolicy.normalizeModelId(selection.modelId());
        if (!modelId.isEmpty()
                && !MovementAnimationPolicy.isValidModelId(modelId)) {
            modelId = "";
        }
        return new Source(ownerUuid,
                new HeldKey(modelId, itemId(maid.getMainHandItem()),
                        itemId(maid.getOffhandItem())),
                new MovementKey(modelId, MovementAnimationType.resolve(maid)));
    }

    private static void invalidateOwner(UUID ownerUuid, EpochChange change) {
        for (Entry entry : ENTRIES.values()) {
            if (entry.source != null
                    && ownerUuid.equals(entry.source.ownerUuid())) {
                invalidate(entry, change.heldItems(), change.movement());
            }
        }
    }

    private static void invalidate(Entry entry,
                                   boolean heldChanged,
                                   boolean movementChanged) {
        if (!heldChanged && !movementChanged) {
            return;
        }
        if (heldChanged) {
            entry.heldSourceRevision = nextRevision();
            entry.resolution.invalidateHeld();
        }
        if (movementChanged) {
            entry.movementSourceRevision = nextRevision();
            entry.resolution.invalidateMovement();
        }
        entry.revision = nextRevision();
        if (!entry.trackers.isEmpty()) {
            broadcast(entry);
        }
    }

    private static void resolveOrQuery(MinecraftServer server,
                                       Entry entry, long tick) {
        Source source = entry.source;
        if (source == null || source.ownerUuid() == null
                || source.held().modelId().isEmpty()) {
            return;
        }
        OwnerSession session = OWNERS.get(source.ownerUuid());
        if (session == null) {
            return;
        }
        applyCachedDecisions(entry, session);
        ServerPlayer owner = server.getPlayerList().getPlayer(source.ownerUuid());
        if (!CompatNetwork.isConnected(owner)) {
            return;
        }
        if (!entry.resolution.heldResolved()
                && session.activeHeldItemEpoch != null) {
            queryHeld(owner, session, entry, tick);
        }
        if (!entry.resolution.movementResolved()
                && source.movement().movement() != null
                && session.activeMovementEpoch != null) {
            queryMovement(owner, session, entry, tick);
        }
    }

    private static void applyCachedDecisions(Entry entry, OwnerSession session) {
        Source source = entry.source;
        boolean changed = false;
        if (!entry.resolution.heldResolved()
                && session.activeHeldItemEpoch != null) {
            HeldItemModelDisplayState held =
                    session.heldDecisions.get(source.held());
            if (held != null) {
                changed |= entry.resolution.resolveHeld(held);
                removeHeldPending(entry, source);
            }
        }
        if (!entry.resolution.movementResolved()) {
            if (source.movement().movement() == null) {
                changed |= entry.resolution.resolveMovement(false);
                removeMovementPending(entry, source);
            } else if (session.activeMovementEpoch != null) {
                Boolean movement =
                        session.movementDecisions.get(source.movement());
                if (movement != null) {
                    changed |= entry.resolution.resolveMovement(movement);
                    removeMovementPending(entry, source);
                }
            }
        }
        stateChanged(entry, changed);
    }

    private static void queryHeld(ServerPlayer owner, OwnerSession session,
                                  Entry entry, long tick) {
        HeldKey key = entry.source.held();
        Pending pending = session.heldPending.get(key);
        if (pending != null && tick - pending.sentTick() < QUERY_TIMEOUT_TICKS) {
            return;
        }
        if (pending != null) {
            session.heldPending.remove(key, pending);
        }
        if (!allowQuery(session, tick)) {
            return;
        }
        UUID queryId = UUID.randomUUID();
        session.heldPending.put(key, new Pending(
                queryId, entry.entity.getUUID(),
                entry.heldSourceRevision, tick));
        CompatNetwork.toPlayer(owner, new MaidPreferenceQueryMessage(
                queryId, entry.entity.getId(), entry.entity.getUUID(),
                entry.source.ownerUuid(), session.activeHeldItemEpoch,
                entry.heldSourceRevision, key.modelId(),
                key.mainHandItem(), key.offHandItem()));
    }

    private static void queryMovement(ServerPlayer owner, OwnerSession session,
                                      Entry entry, long tick) {
        MovementKey key = entry.source.movement();
        Pending pending = session.movementPending.get(key);
        if (pending != null && tick - pending.sentTick() < QUERY_TIMEOUT_TICKS) {
            return;
        }
        if (pending != null) {
            session.movementPending.remove(key, pending);
        }
        if (!allowQuery(session, tick)) {
            return;
        }
        UUID queryId = UUID.randomUUID();
        session.movementPending.put(key, new Pending(
                queryId, entry.entity.getUUID(),
                entry.movementSourceRevision, tick));
        CompatNetwork.toPlayer(owner, new MaidMovementPreferenceQueryMessage(
                queryId, entry.entity.getId(), entry.entity.getUUID(),
                entry.source.ownerUuid(), session.activeMovementEpoch,
                entry.movementSourceRevision, key.modelId(), key.movement()));
    }

    private static boolean allowQuery(OwnerSession session, long tick) {
        return session.pendingCount() < MAX_OWNER_PENDING_QUERIES
                && session.allowQuery(tick) && allowGlobalQuery(tick);
    }

    private static void applyHeldDecision(
            UUID ownerUuid, HeldKey key,
            HeldItemModelDisplayState decision) {
        for (Entry candidate : ENTRIES.values()) {
            Source source = candidate.source;
            if (source == null || !ownerUuid.equals(source.ownerUuid())
                    || !key.equals(source.held())) {
                continue;
            }
            boolean changed = candidate.resolution.resolveHeld(decision);
            removeHeldPending(candidate, source);
            stateChanged(candidate, changed);
        }
    }

    private static void applyMovementDecision(
            UUID ownerUuid, MovementKey key, boolean decision) {
        for (Entry candidate : ENTRIES.values()) {
            Source source = candidate.source;
            if (source == null || !ownerUuid.equals(source.ownerUuid())
                    || !key.equals(source.movement())) {
                continue;
            }
            boolean changed = candidate.resolution.resolveMovement(decision);
            removeMovementPending(candidate, source);
            stateChanged(candidate, changed);
        }
    }

    private static void stateChanged(Entry entry, boolean changed) {
        if (!changed) {
            return;
        }
        entry.revision = nextRevision();
        if (!entry.trackers.isEmpty()) {
            broadcast(entry);
        }
    }

    private static MaidPreferenceDisplayState snapshot(Entry entry) {
        Source source = entry.source;
        UUID entityUuid = entry.entity.getUUID();
        UUID ownerUuid = source != null && source.ownerUuid() != null
                ? source.ownerUuid() : entityUuid;
        HeldKey held = source == null
                ? new HeldKey("", air(), air()) : source.held();
        MovementKey movement = source == null
                ? new MovementKey("", null) : source.movement();
        return new MaidPreferenceDisplayState(
                entityUuid, ownerUuid, entry.revision, held.modelId(),
                held.mainHandItem(), held.offHandItem(), movement.movement(),
                entry.resolution.heldResolved()
                        ? entry.resolution.heldItems()
                        : HeldItemModelDisplayState.UNKNOWN,
                entry.resolution.movementResolved()
                        && entry.resolution.ysmMovement());
    }

    private static void broadcast(Entry entry) {
        if (entry.entity != null && !entry.entity.isRemoved()) {
            CompatNetwork.toTrackers(entry.entity,
                    new MaidPreferenceSnapshotMessage(snapshot(entry)));
        }
    }

    private static void send(ServerPlayer recipient,
                             MaidPreferenceDisplayState state) {
        CompatNetwork.toPlayer(recipient,
                new MaidPreferenceSnapshotMessage(state));
    }

    private static void removeEntry(UUID maidUuid, Entry entry) {
        if (ENTRIES.remove(maidUuid, entry)) {
            removePending(entry);
        }
    }

    private static void removePending(Entry entry) {
        removeHeldPending(entry, entry.source);
        removeMovementPending(entry, entry.source);
    }

    private static void removeHeldPending(Entry entry, Source source) {
        if (source == null || source.ownerUuid() == null) {
            return;
        }
        OwnerSession session = OWNERS.get(source.ownerUuid());
        if (session == null) {
            return;
        }
        Pending pending = session.heldPending.get(source.held());
        if (pending != null && entry.entity != null
                && entry.entity.getUUID().equals(pending.maidUuid())) {
            session.heldPending.remove(source.held(), pending);
        }
    }

    private static void removeMovementPending(Entry entry, Source source) {
        if (source == null || source.ownerUuid() == null) {
            return;
        }
        OwnerSession session = OWNERS.get(source.ownerUuid());
        if (session == null) {
            return;
        }
        Pending pending = session.movementPending.get(source.movement());
        if (pending != null && entry.entity != null
                && entry.entity.getUUID().equals(pending.maidUuid())) {
            session.movementPending.remove(source.movement(), pending);
        }
    }

    private static Entity findEntity(MinecraftServer server, UUID entityUuid) {
        if (server == null || entityUuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityUuid);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static ResourceLocation itemId(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? air() : BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ResourceLocation air() {
        return ResourceLocation.fromNamespaceAndPath("minecraft", "air");
    }

    private static boolean allowGlobalQuery(long tick) {
        if (globalQueryTick != tick) {
            globalQueryTick = tick;
            globalQueriesThisTick = 0;
        }
        return globalQueriesThisTick++ < MAX_QUERIES_PER_SERVER_TICK;
    }

    private static long nextRevision() {
        long next = REVISION.incrementAndGet();
        if (next <= 0L) {
            throw new IllegalStateException("Maid preference revision exhausted");
        }
        return next;
    }
}
