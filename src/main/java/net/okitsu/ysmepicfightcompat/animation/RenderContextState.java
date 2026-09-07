package net.okitsu.ysmepicfightcompat.animation;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Two persistent contexts: a GUI preview never borrows world clocks, variables or poses. */
final class RenderContextState<T> {
    private T world;
    private T inventory;

    T get(boolean preview) {
        return preview ? inventory : world;
    }

    T getOrCreate(boolean preview, Supplier<T> create) {
        T existing = get(preview);
        if (existing != null) return existing;
        T created = Objects.requireNonNull(create.get());
        if (preview) inventory = created;
        else world = created;
        return created;
    }

    void release(Consumer<T> dispose) {
        T previousWorld = world;
        T previousInventory = inventory;
        world = null;
        inventory = null;
        if (previousWorld != null) dispose.accept(previousWorld);
        if (previousInventory != null) dispose.accept(previousInventory);
    }
}
