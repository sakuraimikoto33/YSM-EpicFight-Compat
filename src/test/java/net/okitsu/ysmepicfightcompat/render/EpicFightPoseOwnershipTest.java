package net.okitsu.ysmepicfightcompat.render;

import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpicFightPoseOwnershipTest {
    @Test
    void ordinaryLocomotionDoesNotClaimThePose() {
        assertFalse(EpicFightPoseOwnership.actionFlagsRequireEpicPose(
                false, false, false, false, false, false, false, false));
        assertFalse(EpicFightPoseOwnership.isActionAnimation(
                new ClassifiedAnimation(false, false)));
    }

    @Test
    void everyActionFlagClaimsThePoseIndependently() {
        for (int active = 0; active < 8; active++) {
            boolean[] flags = new boolean[8];
            flags[active] = true;
            assertTrue(EpicFightPoseOwnership.actionFlagsRequireEpicPose(
                    flags[0], flags[1], flags[2], flags[3],
                    flags[4], flags[5], flags[6], flags[7]));
        }
    }

    @Test
    void mainFrameAndReboundAnimationsClaimThePose() {
        assertTrue(EpicFightPoseOwnership.isActionAnimation(
                new ClassifiedAnimation(true, false)));
        assertTrue(EpicFightPoseOwnership.isActionAnimation(
                new ClassifiedAnimation(false, true)));
    }

    @Test
    void ordinaryEpicFightClimbLocksDoNotClaimTheConfiguredYsmPose() {
        assertTrue(EpicFightPoseOwnership.isOrdinaryClimbMotion(
                true, LivingMotions.CLIMB, LivingMotions.IDLE));
        assertFalse(EpicFightPoseOwnership.isOrdinaryClimbMotion(
                false, LivingMotions.CLIMB, LivingMotions.IDLE));
        assertFalse(EpicFightPoseOwnership.actionFlagsRequireEpicPose(
                true, false, false, false,
                true, false, false, false, true));
    }

    @Test
    void ordinaryClimbExceptionNeverMasksARealActionFlag() {
        for (int active = 0; active < 6; active++) {
            boolean[] actions = new boolean[6];
            actions[active] = true;
            assertTrue(EpicFightPoseOwnership.actionFlagsRequireEpicPose(
                    true, actions[0], actions[1], actions[2], true,
                    actions[3], actions[4], actions[5], true));
        }
    }

    @Test
    void idleAliasDoesNotBecomeInactionPoseOwnership() {
        assertFalse(EpicFightPoseOwnership.isActionMotion(LivingMotions.IDLE));
        assertFalse(EpicFightPoseOwnership.isActionMotion(LivingMotions.RUN));
        assertTrue(EpicFightPoseOwnership.isActionMotion(LivingMotions.INACTION));
    }

    private static final class ClassifiedAnimation extends DynamicAnimation {
        private final boolean mainFrame;
        private final boolean rebound;

        private ClassifiedAnimation(boolean mainFrame, boolean rebound) {
            this.mainFrame = mainFrame;
            this.rebound = rebound;
        }

        @Override
        public boolean isMainFrameAnimation() {
            return mainFrame;
        }

        @Override
        public boolean isReboundAnimation() {
            return rebound;
        }

        @Override
        public AnimationManager.AnimationAccessor<? extends DynamicAnimation> getAccessor() {
            return null;
        }

        @Override
        public AssetAccessor<? extends StaticAnimation> getRealAnimation() {
            return null;
        }
    }
}
