package net.okitsu.ysmepicfightcompat.render;

import java.util.Objects;

/** Marks one primary third-person draw inside an explicit inventory preview. */
public final class InventoryRenderScope {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();

    private InventoryRenderScope() {
    }

    public static Token open(Object entity) {
        Token token = new Token(Objects.requireNonNull(entity, "entity"), CURRENT.get());
        CURRENT.set(token);
        return token;
    }

    /** Supports pose selection before its frame consumes the preview claim. */
    public static boolean pending(Object entity) {
        Token token = CURRENT.get();
        return token != null && token.entity == entity && !token.claimed;
    }

    /** A nested world draw must not inherit the inventory status of its caller. */
    public static boolean claim(Object entity, boolean firstPerson) {
        Token token = CURRENT.get();
        if (token == null || firstPerson || token.entity != entity || token.claimed) {
            return false;
        }
        token.claimed = true;
        return true;
    }

    public static final class Token implements AutoCloseable {
        private final Object entity;
        private final Token parent;
        private boolean claimed;
        private boolean closed;

        private Token(Object entity, Token parent) {
            this.entity = entity;
            this.parent = parent;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (CURRENT.get() != this) {
                throw new IllegalStateException("Inventory render scopes must close in order on their opening thread");
            }
            closed = true;
            if (parent == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(parent);
            }
        }
    }
}
