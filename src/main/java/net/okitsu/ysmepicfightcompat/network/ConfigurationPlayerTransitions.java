package net.okitsu.ysmepicfightcompat.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread player replacement identities, independent of variable values and ACK scopes. */
final class ConfigurationPlayerTransitions<P> {
    record Pending<P>(UUID playerId, P replacement, boolean wasDeath,
                      boolean deathNotificationRequired, boolean ready) {
    }

    private final Map<UUID, Pending<P>> pending = new LinkedHashMap<>();

    boolean begin(UUID id, P original, P replacement, P onlinePlayer, boolean wasDeath) {
        if (id == null || original == null || replacement == null
                || original == replacement || onlinePlayer != original) {
            return false;
        }
        Pending<P> previous = pending.get(id);
        if (previous != null && previous.replacement() != original) {
            return false;
        }
        boolean deathNotificationRequired = wasDeath
                || previous != null && previous.deathNotificationRequired();
        pending.put(id, new Pending<>(id, replacement, wasDeath, deathNotificationRequired, false));
        return true;
    }

    /** Tracking can see the replacement before the server's UUID lookup is updated. */
    boolean allows(UUID id, P candidate, P onlinePlayer) {
        if (id == null || candidate == null) {
            return false;
        }
        Pending<P> transition = pending.get(id);
        return transition == null ? candidate == onlinePlayer
                : candidate == transition.replacement();
    }

    void respawned(UUID id, P player, boolean endConquered) {
        Pending<P> transition = pending.get(id);
        if (transition != null && transition.replacement() == player
                && transition.wasDeath() == !endConquered && !transition.ready()) {
            pending.put(id, new Pending<>(id, player, transition.wasDeath(),
                    transition.deathNotificationRequired(), true));
        }
    }

    /** Detached immutable snapshot: callers remove an entry only after handling its replacement. */
    List<Pending<P>> ready() {
        return pending.values().stream().filter(Pending::ready).toList();
    }

    void remove(UUID id, P candidate) {
        Pending<P> transition = pending.get(id);
        if (transition != null && transition.replacement() == candidate) {
            pending.remove(id);
        }
    }

    void clear() {
        pending.clear();
    }
}
