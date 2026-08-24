package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.world.InteractionHand;

/** Resolved per-hand display state; no client configuration rules are included. */
public record HeldItemModelDisplayState(boolean mainHandYsm,
                                        boolean offHandYsm) {
    public static final HeldItemModelDisplayState DEFAULT =
            new HeldItemModelDisplayState(true, true);

    public boolean usesYsm(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? mainHandYsm : offHandYsm;
    }
}
