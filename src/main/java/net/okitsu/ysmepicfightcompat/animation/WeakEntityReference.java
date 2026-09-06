package net.okitsu.ysmepicfightcompat.animation;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** An opaque session-local handle. Validators and resolvers must not capture the target. */
final class WeakEntityReference<T> implements ExpressionEngine.EntityReference {
    private final WeakReference<T> target;
    private final Predicate<T> available;
    private final Function<T, ExpressionEngine.Environment> resolver;
    private final int identityHash;

    WeakEntityReference(T target, Predicate<T> available,
                        Function<T, ExpressionEngine.Environment> resolver) {
        this.target = new WeakReference<>(Objects.requireNonNull(target));
        this.available = Objects.requireNonNull(available);
        this.resolver = Objects.requireNonNull(resolver);
        identityHash = System.identityHashCode(target);
    }

    @Override
    public boolean isValid() {
        T value = target.get();
        return value != null && available.test(value);
    }

    @Override
    public ExpressionEngine.Environment resolve() {
        T value = target.get();
        return value != null && available.test(value) ? resolver.apply(value) : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        T value = target.get();
        return value != null && other instanceof WeakEntityReference<?> reference
                && value == reference.target.get();
    }

    @Override
    public int hashCode() {
        return identityHash;
    }
}
