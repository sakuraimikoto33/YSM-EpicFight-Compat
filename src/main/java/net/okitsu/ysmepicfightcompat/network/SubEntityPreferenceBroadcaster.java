package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.render.EpicFightMode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Server state machine for owner-resolved projectile, hook, and vehicle display. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SubEntityPreferenceBroadcaster {
    static final int MAX_ENTRIES = 4096;
    static final int MAX_OWNER_DECISIONS = 256;
    static final int MAX_OWNER_PENDING_QUERIES = 256;
    static final int MAX_QUERIES_PER_OWNER_TICK = 16;
    static final int MAX_RESPONSES_PER_OWNER_TICK = 64;
    static final int MAX_QUERIES_PER_SERVER_TICK = 256;
    static final int QUERY_TIMEOUT_TICKS = 100;
    static final int EPOCH_COOLDOWN_TICKS = 10;
    static final int VEHICLE_SOURCE_POLL_INTERVAL_TICKS = 10;
    static final int SOURCE_GRACE_TICKS = 40;

    private static final ResourceLocation AIR =
            ResourceLocation.fromNamespaceAndPath("minecraft", "air");
    private static final AtomicLong REVISION = new AtomicLong();
    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<UUID, OwnerSession> OWNERS = new LinkedHashMap<>();
    private static long globalQueryTick = Long.MIN_VALUE;
    private static int globalQueriesThisTick;

    private record SourceKey(UUID ownerUuid,
                             SubEntityModelKind kind,
                             String modelId,
                             ResourceLocation entityTypeId,
                             ResourceLocation sourceItemId) {
    }

    private record Pending(UUID queryId, UUID entityUuid,
                           long sourceRevision, long sentTick) {
    }

    private static final class Entry {
        private Entity entity;
        private final boolean projectile;
        private final Set<UUID> trackers = new HashSet<>();
        private SourceKey source;
        private long sourceRevision = nextRevision();
        private long revision = nextRevision();
        private boolean known;
        private boolean ysm;
        private boolean epicFightRendering;
        private long nextSourceRefreshTick;
        private long sourceMissingSinceTick;

        private Entry(Entity entity, boolean projectile, long tick) {
            this.entity = entity;
            this.projectile = projectile;
            this.sourceMissingSinceTick = tick;
        }
    }

    private static final class OwnerSession {
        private UUID activeEpoch;
        private UUID pendingEpoch;
        private long nextEpochTick;
        private long queryTick = Long.MIN_VALUE;
        private int queriesThisTick;
        private long responseTick = Long.MIN_VALUE;
        private int responsesThisTick;
        private final LinkedHashMap<SourceKey, Boolean> decisions =
                new LinkedHashMap<>(16, 0.75F, true);
        private final Map<SourceKey, Pending> pending = new LinkedHashMap<>();

        private void offerEpoch(UUID epoch) {
            if (!epoch.equals(activeEpoch)) {
                pendingEpoch = epoch;
            }
        }

        private boolean applyPendingEpoch(long tick) {
            if (pendingEpoch == null || tick < nextEpochTick) {
                return false;
            }
            UUID next = pendingEpoch;
            pendingEpoch = null;
            nextEpochTick = tick + EPOCH_COOLDOWN_TICKS;
            if (next.equals(activeEpoch)) {
                return false;
            }
            activeEpoch = next;
            decisions.clear();
            pending.clear();
            return true;
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

        private void remember(SourceKey key, boolean decision) {
            decisions.put(key, decision);
            while (decisions.size() > MAX_OWNER_DECISIONS) {
                decisions.remove(decisions.keySet().iterator().next());
            }
        }
    }

    private SubEntityPreferenceBroadcaster() {
    }

    @SubscribeEvent
    public static void entityJoined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof Projectile)) {
            return;
        }
        observe(event.getEntity(), true);
    }

    @SubscribeEvent
    public static void entityMounted(EntityMountEvent event) {
        if (event.getLevel().isClientSide()
                || event.getEntityBeingMounted() == null
                || !event.isMounting()
                || !(event.getEntityMounting() instanceof ServerPlayer)) {
            return;
        }
        Entity vehicle = event.getEntityBeingMounted();
        Entry entry = ENTRIES.get(vehicle.getUUID());
        if (entry == null && ensureCapacity()) {
            entry = new Entry(vehicle, false, currentTick(vehicle));
            ENTRIES.put(vehicle.getUUID(), entry);
        }
        if (entry != null) {
            entry.entity = vehicle;
        }
    }

    @SubscribeEvent
    public static void startedTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer recipient)) {
            return;
        }
        Entity target = event.getTarget();
        Entry entry = ENTRIES.get(target.getUUID());
        if (entry == null) {
            entry = observe(target, target instanceof Projectile);
        }
        if (entry == null) {
            return;
        }
        entry.entity = target;
        entry.trackers.add(recipient.getUUID());
        refresh(entry, recipient.server.getTickCount());
        resolveOrQuery(recipient.server, entry, recipient.server.getTickCount());
        if (entry.source != null) {
            send(recipient, snapshot(entry));
        }
    }

    @SubscribeEvent
    public static void stoppedTracking(PlayerEvent.StopTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        Entry entry = ENTRIES.get(event.getTarget().getUUID());
        if (entry != null) {
            entry.trackers.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.getEntity().getUUID();
        OwnerSession session = OWNERS.remove(playerUuid);
        for (Entry entry : ENTRIES.values()) {
            entry.trackers.remove(playerUuid);
            if (session != null && entry.source != null
                    && playerUuid.equals(entry.source.ownerUuid())) {
                removePending(entry, session);
            }
        }
    }

    @SubscribeEvent
    public static void entityLeftLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entry entry = ENTRIES.remove(event.getEntity().getUUID());
        if (entry != null) {
            removePending(entry);
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
            if (owner.getValue().applyPendingEpoch(tick)) {
                invalidateOwner(owner.getKey());
            }
        }
        Iterator<Map.Entry<UUID, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.entity == null || entry.entity.isRemoved()) {
                removePending(entry);
                iterator.remove();
                continue;
            }
            refresh(entry, tick);
            if (entry.source == null && sourceGraceExpired(entry, tick)) {
                removePending(entry);
                iterator.remove();
                continue;
            }
            refreshEpicFightRendering(server, entry);
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

    public static void acceptEpoch(ServerPlayer sender, UUID heldItemEpoch) {
        if (sender == null || heldItemEpoch == null) {
            return;
        }
        OWNERS.computeIfAbsent(sender.getUUID(), ignored -> new OwnerSession())
                .offerEpoch(heldItemEpoch);
    }

    public static void accept(ServerPlayer sender,
                              SubEntityPreferenceUpdateMessage message) {
        if (sender == null || message == null) {
            return;
        }
        OwnerSession session = OWNERS.get(sender.getUUID());
        if (session == null || !session.allowResponse(sender.server.getTickCount())) {
            return;
        }
        Entry entry = ENTRIES.get(message.entityUuid());
        Entity entity = entry == null ? null
                : currentEntity(sender.server, entry, message.entityUuid());
        SourceKey source = entry == null ? null : entry.source;
        Pending pending = source == null ? null : session.pending.get(source);
        boolean exact = source != null && entity != null
                && liveRelationMatches(entry, entity, source)
                && sender.getUUID().equals(source.ownerUuid())
                && message.ownerUuid().equals(source.ownerUuid())
                && message.kind() == source.kind()
                && pending != null
                && message.queryId().equals(pending.queryId())
                && message.entityUuid().equals(pending.entityUuid())
                && message.revision() == pending.sourceRevision()
                && message.policyEpoch().equals(session.activeEpoch);
        if (entry == null || !SubEntityPreferenceAuthorization.permits(
                sender.getUUID(), source == null ? null : source.ownerUuid(),
                message.entityId(), message.entityUuid(), message.revision(),
                entity == null ? -1 : entity.getId(),
                entity == null ? null : entity.getUUID(),
                entry == null ? -1L : entry.sourceRevision, exact)) {
            return;
        }
        session.pending.remove(source, pending);
        session.remember(source, message.ysm());
        // Resolve the entity named by the authenticated response immediately.
        // Other entries with the same immutable SourceKey consume the owner-session
        // decision from the bounded server-tick loop. This avoids an O(entries)
        // response path and a response-triggered broadcast burst.
        setDecision(entry, message.ysm());
    }

    private static Entry observe(Entity entity, boolean projectile) {
        if (entity == null || entity.level().isClientSide()) {
            return null;
        }
        Entry existing = ENTRIES.get(entity.getUUID());
        if (existing != null) {
            existing.entity = entity;
            return existing;
        }
        if (projectile && entity instanceof Projectile launched
                && launched.getOwner() != null
                && !(launched.getOwner() instanceof ServerPlayer)) {
            return null;
        }
        SourceKey source = source(entity, projectile);
        if (source == null && !projectile) {
            return null;
        }
        if (!ensureCapacity()) {
            return null;
        }
        Entry entry = new Entry(entity, projectile, currentTick(entity));
        entry.source = source;
        if (source != null) {
            entry.sourceMissingSinceTick = Long.MIN_VALUE;
            entry.epicFightRendering = epicFightRendering(entity, source);
        }
        ENTRIES.put(entity.getUUID(), entry);
        return entry;
    }

    private static void refresh(Entry entry, long tick) {
        // A projectile's model, source item, and owner are a launch snapshot. If the
        // spawn event ran before its owner was attached, allow one later capture.
        if (entry.projectile && entry.source != null) {
            return;
        }
        if (!entry.projectile && tick < entry.nextSourceRefreshTick) {
            return;
        }
        if (!entry.projectile) {
            entry.nextSourceRefreshTick = tick
                    + VEHICLE_SOURCE_POLL_INTERVAL_TICKS;
        }
        SourceKey current = retainedSource(entry.projectile, entry.source,
                source(entry.entity, entry.projectile));
        if (Objects.equals(current, entry.source)) {
            return;
        }
        removePending(entry);
        entry.source = current;
        entry.sourceMissingSinceTick = current == null ? tick : Long.MIN_VALUE;
        entry.epicFightRendering = current != null
                && epicFightRendering(entry.entity, current);
        entry.sourceRevision = nextRevision();
        entry.revision = nextRevision();
        entry.known = false;
        entry.ysm = false;
        if (current != null) {
            broadcast(entry);
        }
    }

    private static SourceKey source(Entity entity, boolean projectile) {
        if (entity == null || entity.isRemoved()) {
            return null;
        }
        ServerPlayer owner;
        SubEntityModelKind kind;
        ResourceLocation sourceItem;
        if (entity instanceof FishingHook hook) {
            owner = hook.getPlayerOwner() instanceof ServerPlayer player
                    ? player : null;
            kind = SubEntityModelKind.FISHING_HOOK;
            sourceItem = BuiltInRegistries.ITEM.getKey(Items.FISHING_ROD);
        } else if (projectile && entity instanceof Projectile launched) {
            owner = launched.getOwner() instanceof ServerPlayer player
                    ? player : null;
            kind = SubEntityModelKind.PROJECTILE;
            sourceItem = projectileItem(entity, owner);
        } else {
            owner = firstPlayerPassenger(entity);
            kind = SubEntityModelKind.VEHICLE;
            sourceItem = AIR;
        }
        if (owner == null) {
            return null;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(owner);
        String modelId = selection == null ? ""
                : MovementAnimationPolicy.normalizeModelId(selection.modelId());
        if (!MovementAnimationPolicy.isValidModelId(modelId)) {
            return null;
        }
        return new SourceKey(owner.getUUID(), kind, modelId,
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), sourceItem);
    }

    private static ServerPlayer firstPlayerPassenger(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                return player;
            }
        }
        return null;
    }

    private static boolean liveRelationMatches(Entry entry, Entity entity,
                                               SourceKey expected) {
        if (entry.projectile) {
            SubEntityModelKind liveKind = entity instanceof FishingHook
                    ? SubEntityModelKind.FISHING_HOOK
                    : SubEntityModelKind.PROJECTILE;
            return entity instanceof Projectile
                    && liveKind == expected.kind()
                    && BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                    .equals(expected.entityTypeId());
        }
        SourceKey live = source(entity, false);
        return live != null && live.equals(expected);
    }

    private static ResourceLocation projectileItem(Entity entity,
                                                   ServerPlayer owner) {
        if (entity instanceof ThrownTrident) {
            ItemStack source = tridentStack(owner);
            return source.isEmpty() ? BuiltInRegistries.ITEM.getKey(Items.TRIDENT)
                    : BuiltInRegistries.ITEM.getKey(source.getItem());
        }
        if (entity instanceof AbstractArrow) {
            ItemStack source = rangedWeaponStack(owner);
            // Crossbow projectiles deliberately use the projectile-only policy. The
            // client cannot reconstruct charged-stack state from an item ID alone.
            return source.isEmpty() ? BuiltInRegistries.ITEM.getKey(Items.BOW)
                    : BuiltInRegistries.ITEM.getKey(source.getItem());
        }
        return AIR;
    }

    private static ItemStack rangedWeaponStack(Player owner) {
        if (owner == null) {
            return ItemStack.EMPTY;
        }
        ItemStack using = owner.getUseItem();
        if (isRangedWeapon(using)) {
            return using;
        }
        // getUsedItemHand retains the launch hand across the short release/spawn
        // boundary. Prefer it before scanning the opposite hand so a carried bow
        // cannot turn a crossbow arrow into a bow-controlled projectile (and vice
        // versa).
        ItemStack lastUsed = owner.getItemInHand(owner.getUsedItemHand());
        if (isRangedWeapon(lastUsed)) {
            return lastUsed;
        }
        if (isRangedWeapon(owner.getMainHandItem())) {
            return owner.getMainHandItem();
        }
        return isRangedWeapon(owner.getOffhandItem())
                ? owner.getOffhandItem() : ItemStack.EMPTY;
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem);
    }

    private static ItemStack tridentStack(Player owner) {
        if (owner == null) {
            return ItemStack.EMPTY;
        }
        ItemStack using = owner.getUseItem();
        if (isTrident(using)) {
            return using;
        }
        if (isTrident(owner.getMainHandItem())) {
            return owner.getMainHandItem();
        }
        return isTrident(owner.getOffhandItem())
                ? owner.getOffhandItem() : ItemStack.EMPTY;
    }

    private static boolean isTrident(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof TridentItem;
    }

    private static void resolveOrQuery(MinecraftServer server,
                                       Entry entry, long tick) {
        SourceKey source = entry.source;
        if (source == null || entry.known) {
            return;
        }
        OwnerSession session = OWNERS.get(source.ownerUuid());
        if (session == null || session.activeEpoch == null) {
            return;
        }
        Boolean cached = session.decisions.get(source);
        if (cached != null) {
            setDecision(entry, cached);
            removePending(entry, session);
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(source.ownerUuid());
        if (!CompatNetwork.isConnected(owner)) {
            return;
        }
        Pending pending = session.pending.get(source);
        if (pending != null && tick - pending.sentTick() < QUERY_TIMEOUT_TICKS) {
            return;
        }
        if (pending != null) {
            session.pending.remove(source, pending);
        }
        if (session.pending.size() >= MAX_OWNER_PENDING_QUERIES
                || !session.allowQuery(tick) || !allowGlobalQuery(tick)) {
            return;
        }
        UUID queryId = UUID.randomUUID();
        session.pending.put(source, new Pending(
                queryId, entry.entity.getUUID(), entry.sourceRevision, tick));
        CompatNetwork.toPlayer(owner, new SubEntityPreferenceQueryMessage(
                queryId, entry.entity.getId(), entry.entity.getUUID(),
                source.ownerUuid(), session.activeEpoch, entry.sourceRevision,
                source.kind(), source.modelId(), source.entityTypeId(),
                source.sourceItemId()));
    }

    private static void setDecision(Entry entry, boolean ysm) {
        if (entry.known && entry.ysm == ysm) {
            return;
        }
        entry.known = true;
        entry.ysm = ysm;
        entry.revision = nextRevision();
        broadcast(entry);
    }

    private static void invalidateOwner(UUID ownerUuid) {
        for (Entry entry : ENTRIES.values()) {
            if (entry.source == null
                    || !ownerUuid.equals(entry.source.ownerUuid())) {
                continue;
            }
            removePending(entry);
            entry.known = false;
            entry.sourceRevision = nextRevision();
        }
    }

    private static SubEntityModelDisplayState snapshot(Entry entry) {
        SourceKey source = entry.source;
        return new SubEntityModelDisplayState(
                entry.entity.getId(), entry.entity.getUUID(), source.ownerUuid(),
                entry.revision, source.kind(), source.entityTypeId(),
                entry.epicFightRendering, entry.known, entry.ysm);
    }

    private static void broadcast(Entry entry) {
        if (entry.entity != null && !entry.entity.isRemoved()
                && entry.source != null) {
            SubEntityPreferenceSnapshotMessage message =
                    new SubEntityPreferenceSnapshotMessage(snapshot(entry));
            CompatNetwork.toTrackers(entry.entity, message);
            if (entry.entity.level() instanceof ServerLevel level) {
                ServerPlayer owner = level.getServer().getPlayerList()
                        .getPlayer(entry.source.ownerUuid());
                // PacketDistributor.TRACKING_ENTITY normally includes the owner,
                // but an explicit copy also covers launch/mount tracking races.
                CompatNetwork.toPlayer(owner, message);
            }
        }
    }

    static <T> T retainedSource(boolean projectile, T previous, T observed) {
        return projectile && previous != null ? previous : observed;
    }

    static boolean sourceGraceExpired(long missingSinceTick, long tick) {
        return missingSinceTick != Long.MIN_VALUE
                && tick - missingSinceTick >= SOURCE_GRACE_TICKS;
    }

    private static void send(ServerPlayer recipient,
                             SubEntityModelDisplayState state) {
        CompatNetwork.toPlayer(recipient,
                new SubEntityPreferenceSnapshotMessage(state));
    }

    private static void removePending(Entry entry) {
        if (entry.source == null) {
            return;
        }
        OwnerSession session = OWNERS.get(entry.source.ownerUuid());
        if (session != null) {
            removePending(entry, session);
        }
    }

    private static void removePending(Entry entry, OwnerSession session) {
        if (entry.source == null || session == null) {
            return;
        }
        Pending pending = session.pending.get(entry.source);
        if (pending != null && entry.entity != null
                && entry.entity.getUUID().equals(pending.entityUuid())) {
            session.pending.remove(entry.source, pending);
        }
    }

    private static Entity currentEntity(MinecraftServer server,
                                        Entry entry, UUID entityUuid) {
        if (entry.entity != null && !entry.entity.isRemoved()
                && entityUuid.equals(entry.entity.getUUID())) {
            return entry.entity;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityUuid);
            if (entity != null) {
                entry.entity = entity;
                return entity;
            }
        }
        return null;
    }

    private static boolean allowGlobalQuery(long tick) {
        if (globalQueryTick != tick) {
            globalQueryTick = tick;
            globalQueriesThisTick = 0;
        }
        return globalQueriesThisTick++ < MAX_QUERIES_PER_SERVER_TICK;
    }

    private static boolean sourceGraceExpired(Entry entry, long tick) {
        return sourceGraceExpired(entry.sourceMissingSinceTick, tick);
    }

    private static long currentTick(Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel level
                ? level.getServer().getTickCount() : 0L;
    }

    private static boolean epicFightRendering(Entity entity, SourceKey source) {
        if (entity == null || source == null
                || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        ServerPlayer owner = level.getServer().getPlayerList()
                .getPlayer(source.ownerUuid());
        return owner != null && EpicFightMode.active(owner);
    }

    private static void refreshEpicFightRendering(
            MinecraftServer server, Entry entry) {
        if (server == null || entry.source == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList()
                .getPlayer(entry.source.ownerUuid());
        if (owner == null) {
            return;
        }
        boolean current = EpicFightMode.active(owner);
        if (entry.epicFightRendering == current) {
            return;
        }
        entry.epicFightRendering = current;
        entry.revision = nextRevision();
        broadcast(entry);
    }

    private static boolean ensureCapacity() {
        if (ENTRIES.size() < MAX_ENTRIES) {
            return true;
        }
        Iterator<Map.Entry<UUID, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry candidate = iterator.next().getValue();
            if (candidate.entity == null || candidate.entity.isRemoved()
                    || candidate.source == null) {
                removePending(candidate);
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static long nextRevision() {
        long next = REVISION.incrementAndGet();
        if (next <= 0L) {
            throw new IllegalStateException("Sub-entity preference revision exhausted");
        }
        return next;
    }
}
