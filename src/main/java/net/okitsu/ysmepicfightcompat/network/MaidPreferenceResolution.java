package net.okitsu.ysmepicfightcompat.network;

/** Independently tracks resolved held-item and movement decisions for one maid. */
final class MaidPreferenceResolution {
    private HeldItemModelDisplayState heldItems =
            HeldItemModelDisplayState.UNKNOWN;
    private boolean heldResolved;
    private boolean ysmMovement;
    private boolean movementResolved;

    HeldItemModelDisplayState heldItems() {
        return heldItems;
    }

    boolean heldResolved() {
        return heldResolved;
    }

    boolean ysmMovement() {
        return ysmMovement;
    }

    boolean movementResolved() {
        return movementResolved;
    }

    void invalidateHeld() {
        heldItems = HeldItemModelDisplayState.UNKNOWN;
        heldResolved = false;
    }

    void invalidateMovement() {
        ysmMovement = false;
        movementResolved = false;
    }

    boolean resolveHeld(HeldItemModelDisplayState decision) {
        if (decision == null) {
            return false;
        }
        boolean changed = !heldResolved || !decision.equals(heldItems);
        heldItems = decision;
        heldResolved = true;
        return changed;
    }

    boolean resolveMovement(boolean decision) {
        boolean changed = !movementResolved || decision != ysmMovement;
        ysmMovement = decision;
        movementResolved = true;
        return changed;
    }

    boolean fullyResolved() {
        return heldResolved && movementResolved;
    }
}
