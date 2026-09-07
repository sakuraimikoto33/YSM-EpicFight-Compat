package net.okitsu.ysmepicfightcompat.animation;

/** Native mod clock. NaN requests a local monotonic clock; the token marks restarts. */
public record ModAnimationSample(ModAnimationType type, String clipName,
                                 double elapsedSeconds, long restartToken) {
    public ModAnimationSample {
        if (type == null || ModAnimationClips.type(clipName) != type) {
            throw new IllegalArgumentException("Unsupported mod animation");
        }
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0.0D) {
            elapsedSeconds = Double.NaN;
        }
    }
}
