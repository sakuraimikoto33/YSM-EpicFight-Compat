package net.okitsu.ysmepicfightcompat.input;

/** Decides whether a click that closed a GUI must be kept out of gameplay input. */
public final class ClosingScreenClickPolicy {
    private ClosingScreenClickPolicy() {
    }

    public static boolean shouldConsume(boolean screenClosed, boolean screenHandled,
                                        boolean resultUnmodified) {
        return screenClosed && !screenHandled && resultUnmodified;
    }
}
