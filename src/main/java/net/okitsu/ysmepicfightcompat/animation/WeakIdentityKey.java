package net.okitsu.ysmepicfightcompat.animation;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;

/** Weak map key that never conflates entities with equal/reused Minecraft entity IDs. */
final class WeakIdentityKey<T> extends WeakReference<T> {
    private final int identityHash;

    WeakIdentityKey(T target, ReferenceQueue<? super T> queue) {
        super(Objects.requireNonNull(target), queue);
        identityHash = System.identityHashCode(target);
    }

    @Override
    public int hashCode() {
        return identityHash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        T value = get();
        return value != null && other instanceof WeakIdentityKey<?> key && value == key.get();
    }
}
