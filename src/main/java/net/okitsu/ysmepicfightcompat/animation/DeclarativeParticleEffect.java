package net.okitsu.ysmepicfightcompat.animation;

/** Immutable Bedrock particle-effect output shared by animations and controllers. */
public record DeclarativeParticleEffect(String effect, String locator,
                                        String preEffectScript, boolean bindToActor) {
    public DeclarativeParticleEffect {
        effect = effect == null ? "" : effect;
        locator = locator == null ? "" : locator;
        preEffectScript = preEffectScript == null ? "" : preEffectScript;
    }
}
