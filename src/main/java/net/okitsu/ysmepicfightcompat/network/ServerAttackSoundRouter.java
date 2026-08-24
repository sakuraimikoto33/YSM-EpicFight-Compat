package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.network.message.AttackSwingSoundMessage;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.Map;
import java.util.WeakHashMap;

/** Preserves the attacking player identity until each client chooses Epic Fight or YSM audio. */
public final class ServerAttackSoundRouter {
    private static final Map<LivingEntity, Integer> SEQUENCES = new WeakHashMap<>();

    private ServerAttackSoundRouter() {
    }

    public static void route(AttackAnimation animation, LivingEntityPatch<?> patch,
                             SoundEvent sound, float minimumPitch, float maximumPitch) {
        if (patch == null || sound == null) {
            return;
        }
        LivingEntity entity = patch.getOriginal();
        ResourceLocation soundId = BuiltInRegistries.SOUND_EVENT.getKey(sound);
        if (!(entity instanceof ServerPlayer player) || soundId == null) {
            patch.playSound(sound, minimumPitch, maximumPitch);
            return;
        }
        InteractionHand hand = attackHand(animation, patch);
        float pitch = 1.0F + (entity.getRandom().nextFloat() * 2.0F - 1.0F)
                * (maximumPitch - minimumPitch);
        int sequence;
        synchronized (SEQUENCES) {
            sequence = SEQUENCES.merge(entity, 1, (previous, one) -> previous + 1);
        }
        CompatNetwork.toTrackersAndSelf(player, new AttackSwingSoundMessage(
                player.getId(), player.getUUID(), hand, sequence, soundId,
                player.getX(), player.getY(), player.getZ(), 1.0F, pitch));
    }

    private static InteractionHand attackHand(AttackAnimation animation,
                                               LivingEntityPatch<?> patch) {
        if (animation == null || patch.getAnimator() == null) {
            return InteractionHand.MAIN_HAND;
        }
        try {
            float elapsed = patch.getAnimator().getPlayerFor(animation.getAccessor())
                    .getElapsedTime();
            AttackAnimation.Phase phase = animation.getPhaseByTime(elapsed);
            return phase == null ? InteractionHand.MAIN_HAND : phase.getHand();
        } catch (RuntimeException ignored) {
            return InteractionHand.MAIN_HAND;
        }
    }
}
