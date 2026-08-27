package net.okitsu.ysmepicfightcompat.mesh;

import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementPoseTransitionTest {
    @Test
    void leavingConfiguredMovementBlendsToOrdinaryLocomotionButNotToAnAction() {
        Fixture fixture = new Fixture();
        MovementPoseTransition.Channel channel = new MovementPoseTransition.Channel();
        OpenMatrix4f[] epic = fixture.pose(2.0F);
        channel.apply(0.0D, null, false, fixture.composer, epic);
        OpenMatrix4f[] runStart = fixture.pose(10.0F);
        channel.apply(1.0D, "run", false, fixture.composer, runStart);
        OpenMatrix4f[] runEnd = fixture.pose(14.0F);
        channel.apply(4.0D, "run", false, fixture.composer, runEnd);

        OpenMatrix4f[] ordinaryStart = fixture.pose(30.0F);
        channel.apply(5.0D, null, false, fixture.composer, ordinaryStart);
        assertEquals(14.0F, fixture.x(ordinaryStart), 0.0001F);
        OpenMatrix4f[] ordinaryMiddle = fixture.pose(30.0F);
        channel.apply(6.5D, null, false, fixture.composer, ordinaryMiddle);
        assertEquals(22.0F, fixture.x(ordinaryMiddle), 0.0001F);

        OpenMatrix4f[] action = fixture.pose(100.0F);
        channel.apply(7.0D, null, true, fixture.composer, action);
        assertEquals(100.0F, fixture.x(action), 0.0001F);
    }

    @Test
    void entersAndSwitchesYsmMovementSmoothlyButLeavesItImmediately() {
        Fixture fixture = new Fixture();
        MovementPoseTransition.Channel channel = new MovementPoseTransition.Channel();

        OpenMatrix4f[] epic = fixture.pose(2.0F);
        channel.apply(0.0D, null, false, fixture.composer, epic);
        assertEquals(2.0F, fixture.x(epic), 0.0001F);

        OpenMatrix4f[] runStart = fixture.pose(10.0F);
        channel.apply(1.0D, "run", false, fixture.composer, runStart);
        assertEquals(2.0F, fixture.x(runStart), 0.0001F,
                "YSM ownership must begin at the previously displayed Epic Fight pose");

        OpenMatrix4f[] runMiddle = fixture.pose(14.0F);
        channel.apply(2.5D, "run", false, fixture.composer, runMiddle);
        assertEquals(8.0F, fixture.x(runMiddle), 0.0001F);

        OpenMatrix4f[] runEnd = fixture.pose(14.0F);
        channel.apply(4.0D, "run", false, fixture.composer, runEnd);
        assertEquals(14.0F, fixture.x(runEnd), 0.0001F);

        OpenMatrix4f[] flightStart = fixture.pose(20.0F);
        channel.apply(5.0D, "creative_flight", false, fixture.composer, flightStart);
        assertEquals(14.0F, fixture.x(flightStart), 0.0001F,
                "A movement-to-movement change must start at the last displayed pose");

        OpenMatrix4f[] flightMiddle = fixture.pose(20.0F);
        channel.apply(6.5D, "creative_flight", false, fixture.composer, flightMiddle);
        assertEquals(17.0F, fixture.x(flightMiddle), 0.0001F);

        OpenMatrix4f[] action = fixture.pose(100.0F);
        channel.apply(7.0D, null, true, fixture.composer, action);
        assertEquals(100.0F, fixture.x(action), 0.0001F,
                "Epic Fight action ownership must not be delayed by a blend");

        OpenMatrix4f[] resume = fixture.pose(25.0F);
        channel.apply(8.0D, "creative_flight", false, fixture.composer, resume);
        assertEquals(100.0F, fixture.x(resume), 0.0001F,
                "Returning from an action must blend from its final displayed pose");
    }

    @Test
    void tickRewindAndPoseCountChangesResetStaleTransitionState() {
        Fixture fixture = new Fixture();
        MovementPoseTransition.Channel channel = new MovementPoseTransition.Channel();
        OpenMatrix4f[] epic = fixture.pose(3.0F);
        channel.apply(100.0D, null, false, fixture.composer, epic);
        OpenMatrix4f[] run = fixture.pose(12.0F);
        channel.apply(101.0D, "run", false, fixture.composer, run);
        assertEquals(3.0F, fixture.x(run), 0.0001F);

        OpenMatrix4f[] rewound = fixture.pose(30.0F);
        channel.apply(20.0D, "run", false, fixture.composer, rewound);
        assertEquals(30.0F, fixture.x(rewound), 0.0001F,
                "A rewound clock must not reuse matrices from the old timeline");

        OpenMatrix4f[] mismatched = AuxiliaryPoseMatrices.allocate(1);
        mismatched[0].translate(77.0F, 0.0F, 0.0F);
        channel.apply(21.0D, "creative_flight", false, fixture.composer, mismatched);
        assertEquals(77.0F, mismatched[0].m30, 0.0001F);

        OpenMatrix4f[] restored = fixture.pose(40.0F);
        channel.apply(22.0D, "creative_flight", false, fixture.composer, restored);
        assertEquals(40.0F, fixture.x(restored), 0.0001F,
                "A restored pose count must start from its live matrices");
    }

    @Test
    void heldItemLocatorOwnershipSurvivesTheBodyExitBlendButNotAnAction() {
        Fixture fixture = new Fixture();
        MovementPoseTransition.Channel channel = new MovementPoseTransition.Channel();
        Set<InteractionHand> mainHand = Set.of(InteractionHand.MAIN_HAND);

        channel.apply(0.0D, null, Set.of(), false,
                fixture.composer, fixture.pose(2.0F));
        assertEquals(mainHand, channel.apply(1.0D, "item-switch:1", mainHand,
                false, fixture.composer, fixture.pose(10.0F)));
        assertEquals(mainHand, channel.apply(4.0D, "item-switch:1", mainHand,
                false, fixture.composer, fixture.pose(14.0F)));

        assertEquals(mainHand, channel.apply(5.0D, null, Set.of(), false,
                fixture.composer, fixture.pose(30.0F)),
                "the locator must remain attached while the body leaves the YSM pose");
        assertEquals(mainHand, channel.apply(6.5D, null, Set.of(), false,
                fixture.composer, fixture.pose(30.0F)));
        assertEquals(Set.of(), channel.apply(8.0D, null, Set.of(), false,
                fixture.composer, fixture.pose(30.0F)),
                "locator ownership ends together with the three-tick body blend");

        assertEquals(Set.of(), channel.apply(9.0D, "item-switch:2", mainHand,
                true, fixture.composer, fixture.pose(100.0F)),
                "Epic Fight actions take item and body ownership immediately");
    }

    private static final class Fixture {
        private final AuxiliaryBoneLayout layout;
        private final AuxiliaryBoneLayout.Entry entry;
        private final AuxiliaryPoseMatrices composer;

        private Fixture() {
            GeometryDocument geometry = new GeometryDocument();
            geometry.add(new GeometryDocument.Bone("body"));
            geometry.linkHierarchy();
            layout = AuxiliaryBoneLayout.create(geometry);
            entry = layout.entries().get(0);
            composer = new AuxiliaryPoseMatrices(layout);
        }

        private OpenMatrix4f[] pose(float x) {
            OpenMatrix4f[] matrices = AuxiliaryPoseMatrices.allocate(
                    layout.totalPoseCount());
            matrices[entry.poseIndex()].translate(x, 0.0F, 0.0F);
            return matrices;
        }

        private float x(OpenMatrix4f[] matrices) {
            return matrices[entry.poseIndex()].m30;
        }
    }
}
