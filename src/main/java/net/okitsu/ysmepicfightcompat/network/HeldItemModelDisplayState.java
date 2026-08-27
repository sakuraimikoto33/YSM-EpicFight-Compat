package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.world.InteractionHand;

/** Resolved per-hand held-item state; no client configuration rules are included. */
public record HeldItemModelDisplayState(boolean mainHandYsm,
                                        boolean offHandYsm,
                                        boolean mainHandYsmSwitchAnimation,
                                        boolean offHandYsmSwitchAnimation) {
    /** Fail-closed value used only until a remote owner's resolved state arrives. */
    public static final HeldItemModelDisplayState UNKNOWN =
            new HeldItemModelDisplayState(false, false, false, false);

    public boolean usesYsm(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? mainHandYsm : offHandYsm;
    }

    public boolean usesYsmSwitchAnimation(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? mainHandYsmSwitchAnimation : offHandYsmSwitchAnimation;
    }
}
