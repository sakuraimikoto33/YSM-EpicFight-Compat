package net.okitsu.ysmepicfightcompat.network.geometry;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Keeps preparation and delivery within one budget, without copying packet payloads. */
final class ModelDeliveryQueue<R, P> {
    static final class Reservation {
        private final long retainedBytes;
        private boolean invalidated;

        private Reservation(long retainedBytes) {
            this.retainedBytes = retainedBytes;
        }
    }

    private static final class Recipient<R> {
        final R value;
        int nextPacket;

        Recipient(R value) {
            this.value = value;
        }
    }

    private static final class Transfer<R, P> {
        final Reservation reservation;
        final ArrayDeque<Recipient<R>> recipients = new ArrayDeque<>();
        final List<P> packets;

        Transfer(Reservation reservation, List<R> recipients, List<P> packets) {
            this.reservation = reservation;
            this.packets = List.copyOf(packets);
            for (R recipient : recipients) {
                this.recipients.addLast(new Recipient<>(Objects.requireNonNull(recipient)));
            }
        }
    }

    private final int maximumTransfers;
    private final long maximumBytes;
    // A null value is a reservation whose worker has not published its packets yet.
    private final Map<Reservation, Transfer<R, P>> reservations = new IdentityHashMap<>();
    private final ArrayDeque<Transfer<R, P>> pending = new ArrayDeque<>();
    private long reservedBytes;

    ModelDeliveryQueue(int maximumTransfers, long maximumBytes) {
        if (maximumTransfers <= 0 || maximumBytes < 0) {
            throw new IllegalArgumentException("Invalid delivery queue limits");
        }
        this.maximumTransfers = maximumTransfers;
        this.maximumBytes = maximumBytes;
    }

    synchronized Reservation reserve(long retainedBytes) {
        if (retainedBytes < 0) {
            throw new IllegalArgumentException("Negative retained byte count");
        }
        if (reservations.size() >= maximumTransfers || retainedBytes > maximumBytes - reservedBytes) {
            return null;
        }
        Reservation reservation = new Reservation(retainedBytes);
        reservations.put(reservation, null);
        reservedBytes += retainedBytes;
        return reservation;
    }

    synchronized boolean isCurrent(Reservation reservation) {
        return reservation != null && !reservation.invalidated
                && reservations.containsKey(reservation);
    }

    synchronized boolean publish(Reservation reservation, List<R> recipients, List<P> packets) {
        if (reservation != null && reservation.invalidated) {
            // The old worker has now finished; only its own retained bytes can be released.
            cancel(reservation);
            return false;
        }
        if (!isCurrent(reservation) || reservations.get(reservation) != null) {
            return false;
        }
        Objects.requireNonNull(recipients);
        Objects.requireNonNull(packets);
        if (recipients.isEmpty() || packets.isEmpty()) {
            cancel(reservation);
            return false;
        }
        Transfer<R, P> transfer = new Transfer<>(reservation, recipients, packets);
        reservations.put(reservation, transfer);
        pending.addLast(transfer);
        return true;
    }

    synchronized void cancel(Reservation reservation) {
        if (!reservations.containsKey(reservation)) {
            return;
        }
        Transfer<R, P> transfer = reservations.remove(reservation);
        if (transfer != null) {
            pending.remove(transfer);
        }
        reservedBytes -= reservation.retainedBytes;
    }

    synchronized void clear() {
        var entries = reservations.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            Reservation reservation = entry.getKey();
            reservation.invalidated = true;
            if (entry.getValue() != null) {
                reservedBytes -= reservation.retainedBytes;
                entries.remove();
            }
            // Unpublished workers still own source/chunk data until cancel or stale publish.
        }
        pending.clear();
    }

    /** Each step attempts one packet; false abandons that recipient, exceptions cancel its transfer. */
    synchronized int drain(int maximumSteps, BiPredicate<R, P> deliver) {
        if (maximumSteps < 0) {
            throw new IllegalArgumentException("Negative delivery step count");
        }
        Objects.requireNonNull(deliver);
        int steps = 0;
        while (steps < maximumSteps && !pending.isEmpty()) {
            Transfer<R, P> transfer = pending.removeFirst();
            Recipient<R> recipient = transfer.recipients.removeFirst();
            boolean accepted;
            try {
                accepted = deliver.test(recipient.value, transfer.packets.get(recipient.nextPacket));
            } catch (RuntimeException | Error failure) {
                cancel(transfer.reservation);
                throw failure;
            }
            steps++;
            // A callback can clear or cancel reentrantly, then reserve a new transfer.
            if (!isCurrent(transfer.reservation)) {
                continue;
            }
            if (accepted && ++recipient.nextPacket < transfer.packets.size()) {
                transfer.recipients.addLast(recipient);
            }
            if (transfer.recipients.isEmpty()) {
                cancel(transfer.reservation);
            } else {
                pending.addLast(transfer);
            }
        }
        return steps;
    }

    synchronized long reservedBytes() {
        return reservedBytes;
    }

    synchronized int reservationCount() {
        return reservations.size();
    }
}
