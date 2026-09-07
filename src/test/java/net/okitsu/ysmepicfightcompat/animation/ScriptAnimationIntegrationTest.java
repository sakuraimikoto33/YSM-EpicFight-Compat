package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises script hooks through selection and pose composition, without a Minecraft entity. */
class ScriptAnimationIntegrationTest {
    @Test
    void bedrockInventoryTransitionsDoNotConsumeTheWorldControllerStepAtTheSameTime() {
        AnimationController.BlendTransition immediate =
                new AnimationController.BlendTransition(0, List.of());
        AnimationController.State normal = new AnimationController.State("normal",
                List.of(new AnimationController.AnimationReference("world_pose", "1")),
                List.of(new AnimationController.Transition("gui", "ysm.rendering_in_inventory")),
                List.of("v.entries+=1;"), List.of(), immediate, false);
        AnimationController.State preview = new AnimationController.State("gui",
                List.of(new AnimationController.AnimationReference("gui_pose", "1")),
                List.of(), List.of("v.entries+=1;"), List.of(), immediate, false);
        AnimationController controller = new AnimationController("player.parallel_preview", "normal",
                Map.of("normal", normal, "gui", preview));
        RenderContextState<Fixture> contexts = new RenderContextState<>();
        java.util.function.Supplier<Fixture> create = () -> fixture(List.of(
                rotationClip("world_pose", "ear", 10), rotationClip("gui_pose", "ear", 55)),
                Map.of(controller.name(), controller), Map.of());
        Fixture world = contexts.getOrCreate(false, create);
        Fixture gui = contexts.getOrCreate(true, create);
        gui.environment.inventory = true;
        world.sample(0, emptySelection(), false);
        gui.sample(0, emptySelection(), false);
        assertRotationZ(10, world.sample(0.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(55, gui.sample(0.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(10, world.sample(0.1, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(1, world.environment.value("v.entries"));
        assertEquals(2, gui.environment.value("v.entries"));
    }

    @Test
    void worldInventoryWorldAtTheSameTimeKeepsIndependentPredicatesAndFrameEvents() {
        RenderContextState<Fixture> contexts = new RenderContextState<>();
        Map<String, String> sources = Map.of(
                "init@player_init", "v.init_count+=1;",
                "update@player_update", "v.update_count+=1;",
                "pose@player_ctrl_parallel0", "v.hook_count+=1;"
                        + "ctrl.set_animation(ysm.rendering_in_inventory ? 'gui_pose' : 'world_pose');"
                        + "return ctrl.state_continue;");
        java.util.function.Supplier<Fixture> create = () -> fixture(List.of(
                rotationClip("world_pose", "ear", 10),
                rotationClip("gui_pose", "ear", 55)), Map.of(), sources);
        Fixture world = contexts.getOrCreate(false, create);
        Fixture gui = contexts.getOrCreate(true, create);
        gui.environment.inventory = true;
        assertRotationZ(10, world.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(55, gui.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(10, contexts.get(false).sample(0, emptySelection(), false).parallelDeltas()[1]);
        for (Fixture context : List.of(world, gui)) {
            assertEquals(1, context.environment.value("v.init_count"));
            assertEquals(1, context.environment.value("v.update_count"));
            assertEquals(1, context.environment.value("v.hook_count"));
        }
        gui.sample(0.1, emptySelection(), false);
        assertEquals(2, gui.environment.value("v.update_count"));
        assertEquals(1, world.environment.value("v.update_count"));
        assertEquals(1, world.environment.value("v.hook_count"));
    }

    @Test
    void inventoryStopFadesPersistAcrossDrawsWithoutStoppingTheWorldController() {
        RenderContextState<Fixture> contexts = new RenderContextState<>();
        java.util.function.Supplier<Fixture> create = () -> fixture(
                List.of(rotationClip("pose", "ear", 45)), Map.of(),
                Map.of("pose@player_ctrl_parallel0", "ctrl.set_animation('pose');"
                        + "return ysm.rendering_in_inventory ? ctrl.state_stop : ctrl.state_continue;"));
        Fixture world = contexts.getOrCreate(false, create);
        Fixture gui = contexts.getOrCreate(true, create);
        gui.environment.inventory = true;
        assertRotationZ(45, world.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(45, gui.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(0, gui.sample(0.2, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(45, world.sample(0.2, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(0, gui.sample(0.3, emptySelection(), false).parallelDeltas()[1]);
    }

    @Test
    void builtinScalesSwitchImmediatelyButTheCustomStateKeepsItsScaleBlend() {
        AnimationController controller = builtinController("player.parallel0", 0.2F);
        Fixture fixture = fixture(List.of(scaleClip("parallel0", "ear", 0),
                scaleClip("custom_pose", "ear", 1)), Map.of(controller.name(), controller), Map.of());
        ParallelAnimationProgram.Frame initial = fixture.sample(0, emptySelection(), false);
        assertUniformScale(0, initial.parallelDeltas()[1]);
        assertTrue(initial.hiddenBones().contains("ear"));

        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
        assertUniformScale(0, fixture.sample(1, emptySelection(), false).parallelDeltas()[1]);
        ParallelAnimationProgram.Frame halfway = fixture.sample(1.1, emptySelection(), false);
        assertUniformScale(0.5, halfway.parallelDeltas()[1]);
        assertFalse(halfway.hiddenBones().contains("ear"));
        assertUniformScale(1, fixture.sample(1.2, emptySelection(), false).parallelDeltas()[1]);

        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 0);
        ParallelAnimationProgram.Frame returned = fixture.sample(2, emptySelection(), false);
        assertUniformScale(0, returned.parallelDeltas()[1]);
        assertTrue(returned.hiddenBones().contains("ear"),
                "Returning to the native provider must not fade hidden geometry into view");
    }

    @Test
    void departingBuiltinScaleFadesToTheLivePrecedingLayerWhenTheCustomClipHasNoScale() {
        AnimationController controller = builtinController("player.parallel0", 0.2F);
        Fixture fixture = fixture(List.of(scaleClip("pre_parallel0", "ear", 2),
                scaleClip("parallel0", "ear", 0), rotationClip("custom_pose", "ear", 0)),
                Map.of(controller.name(), controller), Map.of());
        assertUniformScale(0, fixture.sample(0, emptySelection(), false).parallelDeltas()[1]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
        assertUniformScale(0, fixture.sample(1, emptySelection(), false).parallelDeltas()[1]);
        assertUniformScale(1, fixture.sample(1.1, emptySelection(), false).parallelDeltas()[1]);
        assertUniformScale(2, fixture.sample(1.2, emptySelection(), false).parallelDeltas()[1]);
    }

    @Test
    void builtinHeldSlotKeepsToolAttachmentMetadataWhileOnlyTheCustomPoseRuns() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = new GeometryDocument.Bone("RightArm");
        GeometryDocument.Bone locator = new GeometryDocument.Bone("RightHandLocator");
        GeometryDocument.Bone prop = new GeometryDocument.Bone("test_tool");
        locator.parentName(arm.name());
        prop.parentName(locator.name());
        prop.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0)},
                new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}, new Vector3f(0, 0, 1)));
        geometry.add(arm);
        geometry.add(locator);
        geometry.add(prop);
        geometry.linkHierarchy();
        int propIndex = AuxiliaryBoneLayout.create(geometry).entryForBoneName(prop.name()).auxiliaryIndex();
        AnimationClip nativeHold = scaleClip("hold_mainhand:sword", prop.name(), 1);
        nativeHold.boneTracks().get(prop.name()).rotation(
                rotationClip("unused", prop.name(), 20).boneTracks().get(prop.name()).rotation());
        nativeHold.boneTracks().get(prop.name()).rotation().keyframes().get(0).value()
                .setExpression(0, "v.native_calls+=1;return 0;");
        AnimationClip custom = scaleClip("custom_pose", prop.name(), 1);
        custom.boneTracks().get(prop.name()).rotation(
                rotationClip("unused", prop.name(), 60).boneTracks().get(prop.name()).rotation());
        AnimationController controller = builtinController("player.hold_mainhand", 0.2F);
        Fixture fixture = fixture(geometry,
                List.of(scaleClip("pre_parallel0", prop.name(), 0), nativeHold, custom),
                Map.of(controller.name(), controller), Map.of());
        AutomaticAnimationSelector.Selection selected = new AutomaticAnimationSelector.Selection(
                List.of(active(nativeHold.name())), null, null, Set.of());

        assertHeldTool(20, propIndex, fixture.sample(0, selected, false));
        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
        assertHeldTool(20, propIndex, fixture.sample(1, selected, false));
        assertHeldTool(40, propIndex, fixture.sample(1.1, selected, false));
        assertHeldTool(60, propIndex, fixture.sample(1.2, selected, false));
        assertEquals(1.0D, fixture.environment.value("v.native_calls"),
                "Copying attachment descriptors must not reevaluate the suppressed native pose");
        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 0);
        assertHeldTool(60, propIndex, fixture.sample(2, selected, false));
        assertHeldTool(40, propIndex, fixture.sample(2.1, selected, false));
        assertHeldTool(20, propIndex, fixture.sample(2.2, selected, false));
    }

    @Test
    void builtinStateDelegatesNativeParallelOnceAndIgnoresItsAnimationReferences() {
        AnimationController controller = builtinController("player.parallel_0", 0.2F);
        Fixture fixture = fixture(List.of(
                countedRotationClip("parallel0", 10, "v.native_calls"),
                countedRotationClip("custom_pose", 50, "v.custom_calls"),
                countedRotationClip("ignored_pose", 120, "v.ignored_calls")),
                Map.of(controller.name(), controller), Map.of());

        assertRotationZ(10, fixture.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(1.0D, fixture.environment.value("v.native_calls"));
        assertEquals(0.0D, fixture.environment.value("v.custom_calls"));
        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
        assertRotationZ(10, fixture.sample(1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(30, fixture.sample(1.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(50, fixture.sample(1.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(1.0D, fixture.environment.value("v.native_calls"),
                "The outgoing native clip must not run again to obtain the blend source");
        assertEquals(3.0D, fixture.environment.value("v.custom_calls"));

        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 0);
        assertRotationZ(50, fixture.sample(2, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(30, fixture.sample(2.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(10, fixture.sample(2.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(4.0D, fixture.environment.value("v.native_calls"));
        assertEquals(3.0D, fixture.environment.value("v.custom_calls"),
                "Returning to native playback must use the saved custom pose, not reevaluate it");
        assertEquals(0.0D, fixture.environment.value("v.ignored_calls"));
        assertEquals(0.0D, fixture.environment.value("v.ignored_weight"));
        assertEquals(2.0D, fixture.environment.value("v.builtin_entries"));
        assertEquals(1.0D, fixture.environment.value("v.builtin_exits"));
        assertEquals(1.0D, fixture.environment.value("v.custom_entries"));
        assertEquals(1.0D, fixture.environment.value("v.custom_exits"));
    }

    @Test
    void builtinStateRunsItsScriptOncePerSampleAndComposesScriptAndStateEntryBlends() {
        AnimationController controller = builtinController("player.parallel_0", 0.2F);
        Fixture fixture = fixture(List.of(
                countedRotationClip("parallel0", 10, "v.native_calls"),
                countedRotationClip("custom_pose", 50, "v.custom_calls"),
                countedRotationClip("scripted_pose", 30, "v.script_pose_calls")),
                Map.of(controller.name(), controller),
                Map.of("provider@player_ctrl_parallel_0", """
                        v.callback_calls+=1;
                        ctrl.set_beginning_transition_length(0.2);
                        v.bypass ? {return ctrl.state_bypass;};
                        ctrl.set_animation('scripted_pose');return ctrl.state_continue;
                        """));

        assertRotationZ(0, fixture.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(15, fixture.sample(0.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(15, fixture.sample(0.1, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(2.0D, fixture.environment.value("v.callback_calls"));
        assertRotationZ(30, fixture.sample(0.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(0.0D, fixture.environment.value("v.native_calls"));

        fixture.environment.writeVariable(ExpressionEngine.slot("v.bypass"), 1);
        assertRotationZ(30, fixture.sample(1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(20, fixture.sample(1.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(10, fixture.sample(1.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(6.0D, fixture.environment.value("v.callback_calls"));
        assertEquals(3.0D, fixture.environment.value("v.native_calls"));

        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
        assertRotationZ(10, fixture.sample(2, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(30, fixture.sample(2.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(50, fixture.sample(2.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(6.0D, fixture.environment.value("v.callback_calls"),
                "A non-builtin state owns the slot and must not invoke the script provider");

        fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 0);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.bypass"), 0);
        assertRotationZ(50, fixture.sample(3, emptySelection(), false).parallelDeltas()[1]);
        // The inner script is halfway to 30, and the outer state blends 50 toward that 15.
        assertRotationZ(32.5, fixture.sample(3.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(30, fixture.sample(3.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(9.0D, fixture.environment.value("v.callback_calls"));
        assertEquals(3.0D, fixture.environment.value("v.native_calls"));
        assertEquals(3.0D, fixture.environment.value("v.custom_calls"));
    }

    @Test
    void builtinMainDelegationPreservesMovementSettingsAndEpicFightBodyOwnership() {
        AnimationController controller = builtinController("player.main", 0);
        Map<String, String> functions = Map.of("provider@player_ctrl_main",
                "ctrl.set_animation('scripted_pose');return ctrl.state_continue;");
        List<AnimationClip> clips = List.of(rotationClip("walk", "head", 5),
                rotationClip("scripted_pose", "head", 40),
                rotationClip("custom_pose", "head", 80));
        for (boolean movementEnabled : new boolean[]{true, false}) {
            Fixture fixture = fixture(clips, Map.of(controller.name(), controller), functions);
            ParallelAnimationProgram.Frame builtin = fixture.sample(0, selection("walk"), movementEnabled);
            assertEquals(movementEnabled, builtin.replaceEpicFightPose());
            assertRotationZ(movementEnabled ? 40 : 0, builtin.wholeModelDeltas()[0]);
            assertIdentity(builtin.parallelDeltas()[0]);

            fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
            ParallelAnimationProgram.Frame custom = fixture.sample(1, selection("walk"), movementEnabled);
            assertEquals(movementEnabled, custom.replaceEpicFightPose());
            assertRotationZ(movementEnabled ? 80 : 0, custom.wholeModelDeltas()[0]);
            assertIdentity(custom.parallelDeltas()[0]);
        }
        Fixture noMovement = fixture(clips, Map.of(controller.name(), controller), functions);
        ParallelAnimationProgram.Frame frame = noMovement.sample(0, emptySelection(), true);
        assertFalse(frame.replaceEpicFightPose());
        assertIdentity(frame.wholeModelDeltas()[0]);
        assertIdentity(frame.parallelDeltas()[0]);
    }

    @Test
    void builtinDoesNotDelegateUnsupportedChildControllersOrTreatReferencesAsSlots() {
        AnimationController parent = builtinController("player.parallel0", 0);
        AnimationController.State builtin = new AnimationController.State("ysm-builtin",
                List.of(new AnimationController.AnimationReference("ignored_pose", "1")),
                List.of(), List.of(), List.of(),
                new AnimationController.BlendTransition(0, List.of()), false);
        AnimationController child = new AnimationController("controller.animation.child", "ysm-builtin",
                Map.of("ysm-builtin", builtin));
        AnimationController.State parentBuiltin = new AnimationController.State("ysm-builtin",
                List.of(new AnimationController.AnimationReference(child.name(), "1")),
                List.of(), List.of(), List.of(),
                new AnimationController.BlendTransition(0, List.of()), false);
        parent = new AnimationController(parent.name(), "ysm-builtin",
                Map.of("ysm-builtin", parentBuiltin));
        Fixture fixture = fixture(List.of(
                countedRotationClip("parallel0", 10, "v.native_calls"),
                countedRotationClip("ignored_pose", 120, "v.child_calls")),
                Map.of(parent.name(), parent, child.name(), child), Map.of());

        assertRotationZ(10, fixture.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(1.0D, fixture.environment.value("v.native_calls"));
        assertEquals(0.0D, fixture.environment.value("v.child_calls"));
    }

    @Test
    void builtinUseAndSwingSlotsKeepBothNativeHandProvidersWithoutDuplicatingThem() {
        for (Map.Entry<String, List<String>> entry : Map.of(
                "player.use", List.of("use_mainhand", "use_offhand"),
                "player.swing", List.of("swing_hand", "swing_offhand")).entrySet()) {
            AnimationController controller = builtinController(entry.getKey(), 0);
            Fixture fixture = fixture(List.of(
                    countedRotationClip(entry.getValue().get(0), 10, "v.mainhand_calls"),
                    countedRotationClip(entry.getValue().get(1), 20, "v.offhand_calls")),
                    Map.of(controller.name(), controller), Map.of());
            AutomaticAnimationSelector.Selection selected = new AutomaticAnimationSelector.Selection(
                    entry.getValue().stream().map(ScriptAnimationIntegrationTest::active).toList(),
                    null, null, Set.of());

            fixture.sample(0, selected, false);
            assertEquals(1.0D, fixture.environment.value("v.mainhand_calls"), entry.getKey());
            assertEquals(1.0D, fixture.environment.value("v.offhand_calls"), entry.getKey());
            fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 1);
            fixture.sample(1, selected, false);
            fixture.sample(1.1, selected, false);
            assertEquals(1.0D, fixture.environment.value("v.mainhand_calls"), entry.getKey());
            assertEquals(1.0D, fixture.environment.value("v.offhand_calls"), entry.getKey());
            fixture.environment.writeVariable(ExpressionEngine.slot("v.custom"), 0);
            fixture.sample(2, selected, false);
            assertEquals(2.0D, fixture.environment.value("v.mainhand_calls"), entry.getKey());
            assertEquals(2.0D, fixture.environment.value("v.offhand_calls"), entry.getKey());
        }
    }

    @Test
    void observationalUseHookKeepsCustomBowDrawingReleaseAndTheNextDrawClock() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = new GeometryDocument.Bone("RightArm");
        GeometryDocument.Bone locator = new GeometryDocument.Bone("RightHandLocator");
        GeometryDocument.Bone bow = new GeometryDocument.Bone("test_bow");
        GeometryDocument.Bone arrow = new GeometryDocument.Bone("test_arrow");
        locator.parentName(arm.name());
        bow.parentName(locator.name());
        arrow.parentName(bow.name());
        for (GeometryDocument.Bone prop : List.of(bow, arrow)) {
            prop.faces().add(new GeometryDocument.Face(new Vector3f[]{
                    new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
                    new Vector3f(1, 1, 0), new Vector3f(0, 1, 0)},
                    new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}, new Vector3f(0, 0, 1)));
        }
        for (GeometryDocument.Bone bone : List.of(arm, locator, bow, arrow)) geometry.add(bone);
        geometry.linkHierarchy();
        int armIndex = AuxiliaryBoneLayout.create(geometry).entryForBoneName(arm.name()).auxiliaryIndex();

        AnimationClip pre = scaleClip("pre_parallel0", bow.name(), 0);
        pre.boneTracks().put(arrow.name(), scaleClip("unused", arrow.name(), 0).boneTracks().get(arrow.name()));
        AnimationClip hold = scaleClip("hold_mainhand:bow", bow.name(), 1);
        AnimationClip use = scaleClip("use_mainhand:bow", bow.name(), 1);
        use.playback(AnimationClip.Playback.HOLD_LAST_FRAME);
        use.duration(2);
        use.boneTracks().put(arm.name(), rotationClip("unused", arm.name(), 30).boneTracks().get(arm.name()));
        AnimationClip.BoneTracks drawnArrow = scaleClip("unused", arrow.name(), 0).boneTracks().get(arrow.name());
        AnimationClip.VectorValue visible = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) visible.setConstant(axis, 1);
        drawnArrow.scale().keyframes().add(new AnimationClip.Keyframe(
                0.5F, AnimationClip.Interpolation.STEP, visible, null));
        use.boneTracks().put(arrow.name(), drawnArrow);
        AnimationClip swing = scaleClip("swing:bow", bow.name(), 1);
        swing.duration(0.5F);
        swing.boneTracks().put(arm.name(), rotationClip("unused", arm.name(), -20).boneTracks().get(arm.name()));
        swing.boneTracks().put(arrow.name(), scaleClip("unused", arrow.name(), 0).boneTracks().get(arrow.name()));
        Fixture fixture = fixture(geometry, List.of(pre, hold, use, swing), Map.of(),
                Map.of("observe@player_ctrl_use", """
                        v.last_use && !ctrl.use('mainhand','bow') ? {
                            v.releases+=1;
                            v.last_use=0;
                            return ctrl.state_continue;
                        };
                        v.last_use=ctrl.use('mainhand','bow');
                        """));

        fixture.environment.writeVariable(ExpressionEngine.slot("v.using"), 1);
        ParallelAnimationProgram.Frame started = fixture.sample(0, bowSelection(use.name(), 0), false);
        assertTrue(started.replaceEpicFightPose(),
                "An observational hook must not remove the automatic full-body bow owner");
        assertTrue(started.hiddenBones().contains(arrow.name()));
        assertRotationZ(30, started.wholeModelDeltas()[armIndex]);
        ParallelAnimationProgram.Frame drawing = fixture.sample(0.6, bowSelection(use.name(), 0.6), false);
        assertTrue(drawing.replaceEpicFightPose());
        assertFalse(drawing.hiddenBones().contains(arrow.name()),
                "The draw clip must reach its delayed arrow-visibility keyframe");
        ParallelAnimationProgram.Frame heldDraw = fixture.sample(1.1, bowSelection(use.name(), 1.1), false);
        assertTrue(heldDraw.replaceEpicFightPose());
        assertFalse(heldDraw.hiddenBones().contains(arrow.name()),
                "A held draw must not fade out or wrap after one second");
        assertRotationZ(30, heldDraw.wholeModelDeltas()[armIndex]);

        fixture.environment.writeVariable(ExpressionEngine.slot("v.using"), 0);
        fixture.sample(1.2, bowSelection(swing.name(), 0), false);
        assertEquals(1, fixture.environment.value("v.releases"));
        // The explicit release CONTINUE keeps its existing one-frame controller behavior.
        // The next observational callback must yield to the live native SWING provider.
        ParallelAnimationProgram.Frame released = fixture.sample(1.25, bowSelection(swing.name(), 0.05), false);
        assertTrue(released.replaceEpicFightPose());
        assertTrue(released.hiddenBones().contains(arrow.name()));
        assertRotationZ(-20, released.wholeModelDeltas()[armIndex]);
        fixture.sample(1.8, bowSelection(null, 0), false);

        fixture.environment.writeVariable(ExpressionEngine.slot("v.using"), 1);
        ParallelAnimationProgram.Frame nextDraw = fixture.sample(2, bowSelection(use.name(), 0), false);
        assertTrue(nextDraw.replaceEpicFightPose());
        assertTrue(nextDraw.hiddenBones().contains(arrow.name()),
                "A second draw must restart at its own time zero, not retain the previous draw clock");
        assertRotationZ(30, nextDraw.wholeModelDeltas()[armIndex]);
        assertFalse(fixture.sample(2.6, bowSelection(use.name(), 0.6), false)
                .hiddenBones().contains(arrow.name()));
        assertEquals(1, fixture.environment.value("v.releases"));
    }

    @Test
    void naturalLadderMirroringUsesTheMainTransitionWithoutOverwritingItsHistory() {
        Fixture fixture = fixture(List.of(rotationClip("ladder_up", "LeftArm", 40),
                rotationClip("ladder_down", "LeftArm", 80)), Map.of(),
                Map.of("main@player_ctrl_main", """
                        ctrl.set_beginning_transition_length(0.2);
                        ctrl.set_animation(v.down ? 'ladder_down' : 'ladder_up');
                        return ctrl.state_continue;
                        """));
        AutomaticAnimationSelector.Selection up = selection("ladder_up", MovementAnimationType.LADDER_UP);
        AutomaticAnimationSelector.Selection down = selection("ladder_down", MovementAnimationType.LADDER_DOWN);

        assertLadderArms(0, fixture.sample(0, up, true, true));
        assertLadderArms(20, fixture.sample(0.1, up, true, true));
        assertLadderArms(40, fixture.sample(0.2, up, true, true));

        fixture.environment.writeVariable(ExpressionEngine.slot("v.down"), 1);
        assertLadderArms(40, fixture.sample(0.3, down, true, true));
        assertLadderArms(60, fixture.sample(0.4, down, true, true));

        // Switching again midway must start from each arm's displayed effect,
        // not from the opposite arm or from the preceding clip's target.
        fixture.environment.writeVariable(ExpressionEngine.slot("v.down"), 0);
        assertLadderArms(60, fixture.sample(0.45, up, true, true));
        assertLadderArms(50, fixture.sample(0.55, up, true, true));
        assertLadderArms(40, fixture.sample(0.65, up, true, true));
    }

    @Test
    void stoppingDuringBeginningTransitionAttenuatesBothPosesAndFreezesTheDisplayedResult() {
        Fixture fixture = fixture(List.of(rotationClip("parallel0", "ear", 10),
                rotationClip("first", "ear", 40), rotationClip("second", "ear", 80)),
                Map.of(), Map.of("post@player_ctrl_parallel_1", """
                        ctrl.set_beginning_transition_length(1);
                        ctrl.set_animation(v.second ? 'second' : 'first');
                        return v.stop ? ctrl.state_stop : ctrl.state_continue;
                        """));
        fixture.sample(0, emptySelection(), false);
        assertRotationZ(50, fixture.sample(1, emptySelection(), false).parallelDeltas()[1]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.second"), 1);
        fixture.sample(2, emptySelection(), false);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.stop"), 1);
        assertRotationZ(66, fixture.sample(2.4, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(39.5, fixture.sample(2.475, emptySelection(), false).parallelDeltas()[1]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.stop"), 0);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.second"), 0);
        assertRotationZ(39.5, fixture.sample(2.5, emptySelection(), false).parallelDeltas()[1]);
    }

    @Test
    void pauseSuppressesEvaluationButPreservesTheSameSlotTransitionSourceForResume() {
        Fixture fixture = fixture(List.of(rotationClip("parallel0", "ear", 10),
                rotationClip("first", "ear", 40), rotationClip("second", "ear", 80)),
                Map.of(), Map.of("post@player_ctrl_parallel_1", """
                        ctrl.set_beginning_transition_length(1);
                        ctrl.set_animation(v.second ? 'second' : 'first');
                        return v.pause ? ctrl.state_pause : ctrl.state_continue;
                        """));
        fixture.sample(0, emptySelection(), false);
        fixture.sample(1, emptySelection(), false);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.second"), 1);
        fixture.sample(2, emptySelection(), false);
        assertRotationZ(58, fixture.sample(2.2, emptySelection(), false).parallelDeltas()[1]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.pause"), 1);
        assertRotationZ(10, fixture.sample(2.3, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(10, fixture.sample(2.4, emptySelection(), false).parallelDeltas()[1]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.pause"), 0);
        assertRotationZ(70, fixture.sample(2.5, emptySelection(), false).parallelDeltas()[1]);
    }

    @Test
    void emptyBypassHasNoOldClipTimelineOrPausedAudioScope() {
        Fixture fixture = fixture(List.of(rotationClip("gesture", "ear", 40)), Map.of(),
                Map.of("post@player_ctrl_parallel_1", "return ctrl.state_continue;"));
        String channel = "player.parallel_1";
        AnimationControllerProgram.Selection empty = new AnimationControllerProgram.Selection(List.of(), List.of());
        MolangScriptRuntime.Output playing = new MolangScriptRuntime.Output(true, "gesture", 0, 1, 1,
                new MolangScriptRuntime.Transition(1, 1, false));
        MolangScriptRuntime.Output bypass = new MolangScriptRuntime.Output(false, "", 0, 1, 1,
                new MolangScriptRuntime.Transition(2, 0, false));
        MolangScriptRuntime.Output paused = new MolangScriptRuntime.Output(true, "gesture", 0.1, 0, 1,
                new MolangScriptRuntime.Transition(1, 0.5F, false, true));
        String playingKey = fixture.program.mergeScriptControllers(empty, Map.of(channel, playing),
                ignored -> true).outputActive().get(0).instanceKey();
        AnimationControllerProgram.ActiveAnimation ending = fixture.program.mergeScriptControllers(
                empty, Map.of(channel, bypass), ignored -> true).outputActive().get(0);
        assertNotEquals(playingKey, ending.instanceKey());
        assertEquals("", ending.name());
        assertTrue(fixture.program.mergeScriptControllers(empty, Map.of(channel, paused),
                ignored -> true).outputActive().isEmpty());
    }

    @Test
    void aPreMainTransitionDoesNotDisableUnrelatedOrdinaryMovementTransitions() {
        Fixture fixture = fixture(List.of(rotationClip("walk", "head", 5),
                rotationClip("run", "head", 10), rotationClip("decoration", "ear", 20)),
                Map.of(), Map.of("before@player_ctrl_pre_main", """
                        ctrl.set_beginning_transition_length(0.2);
                        ctrl.set_animation('decoration');return ctrl.state_continue;
                        """));
        String walkingKey = fixture.sample(0, selection("walk"), true).movementPoseKey();
        String runningKey = fixture.sample(1, selection("run"), true).movementPoseKey();
        assertNotEquals(walkingKey, runningKey);
    }

    @Test
    void mainTransitionKeepsOwnershipAtZeroProgressAndBlendsClipSwitchesOnce() {
        Fixture fixture = fixture(List.of(rotationClip("walk", "head", 5),
                rotationClip("first", "head", 40), rotationClip("second", "head", -20)),
                Map.of(), Map.of("main@player_ctrl_main", """
                        ctrl.set_animation(v.second ? 'second' : 'first');
                        ctrl.set_beginning_transition_length(0.2);
                        return ctrl.state_continue;
                        """));
        ParallelAnimationProgram.Frame start = fixture.sample(0, selection("walk"), true);
        assertTrue(start.replaceEpicFightPose());
        assertRotationZ(0, start.wholeModelDeltas()[0]);
        assertRotationZ(20, fixture.sample(0.1, selection("walk"), true).wholeModelDeltas()[0]);
        assertRotationZ(40, fixture.sample(0.2, selection("walk"), true).wholeModelDeltas()[0]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.second"), 1);
        ParallelAnimationProgram.Frame switched = fixture.sample(0.3, selection("walk"), true);
        assertRotationZ(40, switched.wholeModelDeltas()[0]);
        assertEquals(start.movementPoseKey(), switched.movementPoseKey(),
                "Do not also start the fixed outer ownership blend on a script clip change");
        assertRotationZ(10, fixture.sample(0.4, selection("walk"), true).wholeModelDeltas()[0]);
        assertRotationZ(-20, fixture.sample(0.5, selection("walk"), true).wholeModelDeltas()[0]);
    }

    @Test
    void scriptEntryAndBypassBlendFromTheSameOrdinaryProviderWithoutDoubleApplyingIt() {
        Fixture fixture = fixture(List.of(rotationClip("walk", "head", 10),
                rotationClip("backward", "head", 50)), Map.of(),
                Map.of("main@player_ctrl_main", """
                        ctrl.set_beginning_transition_length(0.2);
                        v.backward ? {ctrl.set_animation('backward');return ctrl.state_continue;};
                        return ctrl.state_bypass;
                        """));
        assertRotationZ(10, fixture.sample(0, selection("walk"), true).wholeModelDeltas()[0]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.backward"), 1);
        assertRotationZ(10, fixture.sample(1, selection("walk"), true).wholeModelDeltas()[0]);
        assertRotationZ(30, fixture.sample(1.1, selection("walk"), true).wholeModelDeltas()[0]);
        assertRotationZ(50, fixture.sample(1.2, selection("walk"), true).wholeModelDeltas()[0]);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.backward"), 0);
        assertRotationZ(50, fixture.sample(2, selection("walk"), true).wholeModelDeltas()[0]);
        assertRotationZ(30, fixture.sample(2.1, selection("walk"), true).wholeModelDeltas()[0]);
        assertRotationZ(10, fixture.sample(2.2, selection("walk"), true).wholeModelDeltas()[0]);
    }

    @Test
    void parallelTransitionKeepsPrecedingLayersLiveAndDoesNotReevaluateTheOldClip() {
        AnimationClip before = rotationClip("parallel0", "ear", 10);
        AnimationClip first = scriptClip("first", "v.first_calls+=1;return 0;");
        first.boneTracks().get("ear").rotation().keyframes().get(0).value().setConstant(2, 40);
        AnimationClip second = scriptClip("second", "v.second_calls+=1;return 0;");
        second.boneTracks().get("ear").rotation().keyframes().get(0).value().setConstant(2, 80);
        Fixture fixture = fixture(List.of(before, first, second), Map.of(),
                Map.of("post@player_ctrl_parallel_1", """
                        ctrl.set_beginning_transition_length(0.2);
                        ctrl.set_animation(v.second ? 'second' : 'first');
                        return ctrl.state_continue;
                        """));
        assertRotationZ(10, fixture.sample(0, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(50, fixture.sample(0.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(2.0D, fixture.environment.value("v.first_calls"));
        fixture.environment.writeVariable(ExpressionEngine.slot("v.second"), 1);
        assertRotationZ(50, fixture.sample(1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(70, fixture.sample(1.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(90, fixture.sample(1.2, emptySelection(), false).parallelDeltas()[1]);
        assertEquals(2.0D, fixture.environment.value("v.first_calls"),
                "Old expressions must not run again just to obtain a transition source");
        assertEquals(3.0D, fixture.environment.value("v.second_calls"));
    }

    @Test
    void bypassToAnEmptySlotFadesOnlyThatLayerAndScaleVisibilityChangesImmediately() {
        AnimationClip first = rotationClip("gesture", "ear", 40);
        AnimationClip.VectorValue scale = new AnimationClip.VectorValue();
        scale.setConstant(0, 0);
        scale.setConstant(1, 0);
        scale.setConstant(2, 0);
        AnimationClip.Track scaleTrack = new AnimationClip.Track();
        scaleTrack.keyframes().add(new AnimationClip.Keyframe(
                0, AnimationClip.Interpolation.LINEAR, scale, null));
        first.boneTracks().get("ear").scale(scaleTrack);
        Fixture fixture = fixture(List.of(rotationClip("parallel0", "ear", 10), first),
                Map.of(), Map.of("post@player_ctrl_parallel_1", """
                        ctrl.set_beginning_transition_length(0.2);
                        v.bypass ? {return ctrl.state_bypass;};
                        ctrl.set_animation('gesture');return ctrl.state_continue;
                        """));
        assertTrue(fixture.sample(0, emptySelection(), false).hiddenBones().contains("ear"),
                "Beginning transitions must not fade a hidden scale up from one");
        fixture.sample(0.2, emptySelection(), false);
        fixture.environment.writeVariable(ExpressionEngine.slot("v.bypass"), 1);
        ParallelAnimationProgram.Frame ending = fixture.sample(1, emptySelection(), false);
        assertFalse(ending.hiddenBones().contains("ear"));
        assertRotationZ(50, ending.parallelDeltas()[1]);
        assertRotationZ(30, fixture.sample(1.1, emptySelection(), false).parallelDeltas()[1]);
        assertRotationZ(10, fixture.sample(1.2, emptySelection(), false).parallelDeltas()[1]);
    }

    @Test
    void rouletteStateSuppressesAndRestoresJsonControllerIdleWithoutFunctionFiles() {
        Map<String, AnimationController> controllers = BedrockAnimationControllerParser.parse(
                JsonParser.parseString("""
                        {"animation_controllers":{"player.post_main":{
                          "initial_state":"quiet",
                          "states":{
                            "quiet":{"transitions":[{"gesture":"!ctrl.playing_extra_animation"}]},
                            "gesture":{
                              "animations":["idle_gesture"],
                              "on_entry":["v.gestures+=1;"],
                              "transitions":[{"quiet":"ctrl.playing_extra_animation"}]
                            }
                          }
                        }}}
                        """).getAsJsonObject());
        Fixture fixture = fixture(List.of(rotationClip("idle_gesture", "ear", 25)),
                controllers, Map.of());
        AnimationControllerProgram.RuntimeState state = new AnimationControllerProgram.RuntimeState();
        assertTrue(fixture.scripts.isEmpty());

        fixture.scripts.playingExtraAnimation(true);
        assertIdentity(fixture.program.sampleControllersAt(0, fixture.environment, state)
                .parallelDeltas()[1]);
        assertEquals(0.0D, fixture.environment.value("v.gestures"));

        fixture.scripts.playingExtraAnimation(false);
        assertRotationZ(25, fixture.program.sampleControllersAt(1, fixture.environment, state)
                .parallelDeltas()[1]);
        assertEquals(1.0D, fixture.environment.value("v.gestures"));

        fixture.scripts.playingExtraAnimation(true);
        assertIdentity(fixture.program.sampleControllersAt(2, fixture.environment, state)
                .parallelDeltas()[1]);
        fixture.program.sampleControllersAt(3, fixture.environment, state);
        assertEquals(1.0D, fixture.environment.value("v.gestures"));

        fixture.scripts.playingExtraAnimation(false);
        assertRotationZ(25, fixture.program.sampleControllersAt(4, fixture.environment, state)
                .parallelDeltas()[1]);
        assertEquals(2.0D, fixture.environment.value("v.gestures"));
    }

    @Test
    void workerSnapshotCapturesRouletteQueryWithoutHoldingLiveRuntimeState() {
        Fixture fixture = fixture(List.of(), Map.of(), Map.of());
        ExpressionEngine.Expression expression = ExpressionEngine.compile("ctrl.playing_extra_animation");
        assertTrue(expression.isValid());
        Set<Integer> queries = expression.dependencies().querySlots();
        assertEquals(Set.of(ExpressionEngine.querySlot("ctrl.playing_extra_animation")), queries);

        fixture.scripts.playingExtraAnimation(true);
        SnapshotExpressionEnvironment playing = SnapshotExpressionEnvironment.capture(
                fixture.environment, Set.of(), queries);
        fixture.scripts.playingExtraAnimation(false);
        SnapshotExpressionEnvironment stopped = SnapshotExpressionEnvironment.capture(
                fixture.environment, Set.of(), queries);

        assertEquals(1.0D, expression.evaluate(playing));
        assertEquals(0.0D, expression.evaluate(stopped));
        fixture.scripts.reset();
        assertEquals(1.0D, expression.evaluate(playing));
    }

    @Test
    void arbitraryNamedMainClipOwnsTheWholeBodyOnlyWhenMovementIsEnabled() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        AnimationClip authored = rotationClip("authored_pose", "head", 40);
        Map<String, String> sources = Map.of("main@player_ctrl_main",
                "ctrl.set_animation('authored_pose');return ctrl.state_continue;");
        Fixture enabled = fixture(List.of(walk, authored), Map.of(), sources);
        ParallelAnimationProgram.Frame ysm = enabled.sample(selection("walk"), true);

        assertTrue(ysm.replaceEpicFightPose());
        assertRotationZ(40, ysm.wholeModelDeltas()[0]);

        Fixture disabled = fixture(List.of(walk, authored), Map.of(), sources);
        ParallelAnimationProgram.Frame epic = disabled.sample(selection("walk"), false);

        assertFalse(epic.replaceEpicFightPose());
        assertIdentity(epic.wholeModelDeltas()[0]);
        assertIdentity(epic.parallelDeltas()[0]);
    }

    @Test
    void mainHookDoesNotGainFullBodyOwnershipWithoutAnEligibleMovementState() {
        AnimationClip authored = rotationClip("authored_pose", "head", 40);
        Fixture fixture = fixture(List.of(authored), Map.of(), Map.of("main@player_ctrl_main",
                "ctrl.set_animation('authored_pose');return ctrl.state_continue;"));
        AutomaticAnimationSelector.Selection idle = new AutomaticAnimationSelector.Selection(
                List.of(), null, null, Set.of());

        ParallelAnimationProgram.Frame frame = fixture.sample(idle, true);

        assertFalse(frame.replaceEpicFightPose());
        assertIdentity(frame.wholeModelDeltas()[0]);
    }

    @Test
    void bypassKeepsTheOriginalMainSelectionAndPose() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        AnimationClip authored = rotationClip("authored_pose", "head", 40);
        Fixture fixture = fixture(List.of(walk, authored), Map.of(), Map.of("main@player_ctrl_main",
                "ctrl.set_animation('authored_pose');return ctrl.state_bypass;"));
        AutomaticAnimationSelector.Selection selected = selection("walk");
        Map<String, MolangScriptRuntime.Output> outputs = new LinkedHashMap<>();

        AutomaticAnimationSelector.Selection result = fixture.program.selectScriptControllers(
                selected, fixture.scripts, outputs, fixture.environment, 0, 0);

        assertSame(selected.main(), result.main());
        assertEquals(selected.clips(), result.clips());
        assertFalse(outputs.get("player.main").overridden());
        ParallelAnimationProgram.Frame frame = fixture.sample(selected, true);
        assertRotationZ(5, frame.wholeModelDeltas()[0]);
    }

    @Test
    void pauseSuppressesTheOriginalParallelClipWithoutSuppressingOtherSlots() {
        AnimationClip suppressed = scriptClip("parallel0", "v.suppressed+=1");
        AnimationClip retained = scriptClip("parallel1", "v.retained+=1");
        Fixture fixture = fixture(List.of(suppressed, retained), Map.of(),
                Map.of("pause@player_ctrl_parallel_0", "return ctrl.state_pause;"));

        fixture.sample(emptySelection(), false);

        assertEquals(0.0D, fixture.environment.value("v.suppressed"));
        assertEquals(1.0D, fixture.environment.value("v.retained"));
    }

    @Test
    void scriptParallelReplacementsKeepPreMainPostOrderingAndRunOnlyOnce() {
        AnimationClip originalPre = scriptClip("pre_parallel0", "v.order=v.order*10+8");
        AnimationClip originalPost = scriptClip("parallel0", "v.order=v.order*10+9");
        AnimationClip scriptedPre = scriptClip("custom_before", "v.order=v.order*10+1");
        AnimationClip walk = scriptClip("walk", "v.order=v.order*10+2");
        AnimationClip scriptedPost = scriptClip("custom_after", "v.order=v.order*10+3");
        Fixture fixture = fixture(List.of(originalPre, originalPost, scriptedPre, walk, scriptedPost),
                Map.of(), Map.of(
                        "before@player_ctrl_pre_parallel_0",
                        "ctrl.set_animation('custom_before');return ctrl.state_continue;",
                        "after@player_ctrl_parallel_0",
                        "ctrl.set_animation('custom_after');return ctrl.state_continue;"));

        fixture.sample(selection("walk"), false);

        assertEquals(123.0D, fixture.environment.value("v.order"));
    }

    @Test
    void dynamicBedrockControllersUsePhaseAndLexicalOrderForLifecycleAndPose() {
        Map<String, AnimationController> controllers = new LinkedHashMap<>();
        List<AnimationClip> clips = new java.util.ArrayList<>();
        // Deliberately reverse both phase and same-phase name order in the input.
        List<String> channels = List.of("player.parallel_z", "player.parallel_a",
                "player.post_main_z", "player.post_main_a", "player.main",
                "player.pre_main_z", "player.pre_main_a", "player.pre_parallel_start");
        for (int index = 0; index < channels.size(); index++) {
            String channel = channels.get(index);
            int digit = channels.size() - index;
            String clip = "authored_" + digit;
            clips.add(scriptClip(clip, "v.pose_order=v.pose_order*10+" + digit + ";return 0;"));
            controllers.put(channel, controller(channel, clip,
                    List.of("v.entry_order=v.entry_order*10+" + digit + ";")));
        }
        Fixture fixture = fixture(clips, controllers, Map.of());

        fixture.sample(selection("walk"), false);

        assertEquals(12345678.0D, fixture.environment.value("v.entry_order"));
        assertEquals(12345678.0D, fixture.environment.value("v.pose_order"));
    }

    @Test
    void dynamicLifecycleAndPoseUseTheSameOriginalNameCaseOrder() {
        for (String prefix : List.of("player.pre_main_", "player.pre_parallel_", "player.parallel_")) {
            Fixture fixture = fixture(List.of(
                    scriptClip("first", "v.pose_order=v.pose_order*10+1;return 0;"),
                    scriptClip("second", "v.pose_order=v.pose_order*10+2;return 0;")), Map.of(
                    prefix + "a", controller(prefix + "a", "second", List.of("v.entry_order=v.entry_order*10+2;")),
                    prefix + "Z", controller(prefix + "Z", "first", List.of("v.entry_order=v.entry_order*10+1;"))), Map.of());

            fixture.sample(emptySelection(), false);

            assertEquals(12.0D, fixture.environment.value("v.entry_order"), prefix);
            assertEquals(12.0D, fixture.environment.value("v.pose_order"), prefix);
        }
    }

    @Test
    void ordinaryParallelControllerReplacesItsNativeSlotUnderBothNumberSpellings() {
        for (String prefix : List.of("parallel", "pre_parallel")) {
            for (String suffix : List.of("0", "_0")) {
                String channel = "player." + prefix + suffix;
                AnimationClip nativeClip = countedRotationClip(prefix + "0", 10, "v.native_calls");
                AnimationClip authored = countedRotationClip("authored", 20, "v.authored_calls");
                Fixture fixture = fixture(List.of(nativeClip, authored), Map.of(
                        channel, controller(channel, authored.name(), List.of())), Map.of());

                ParallelAnimationProgram.Frame frame = fixture.sample(emptySelection(), false);

                assertEquals(0.0D, fixture.environment.value("v.native_calls"), channel);
                assertEquals(1.0D, fixture.environment.value("v.authored_calls"), channel);
                assertRotationZ(20, frame.parallelDeltas()[1]);
            }
        }
    }

    @Test
    void dynamicOrderIncludesManagedSlotsWhenNoScriptProviderNeedsEarlyPreparation() {
        AnimationController main = controller("player.main", "main_pose", List.of("v.order=v.order*10+2;"));
        Map<String, AnimationController.State> states = new LinkedHashMap<>(main.states());
        states.put("ysm-builtin", builtinController("unused", 0).states().get("ysm-builtin"));
        main = new AnimationController(main.name(), main.initialState(), states);
        Fixture fixture = fixture(List.of(rotationClip("main_pose", "head", 20)), Map.of(
                "player.main", main,
                "player.pre_main_face", controller("player.pre_main_face", "", List.of("v.order=1;")),
                "player.post_main_face", controller("player.post_main_face", "", List.of("v.order=v.order*10+3;"))),
                Map.of());

        fixture.sample(selection("walk"), true);

        assertEquals(123.0D, fixture.environment.value("v.order"));
    }

    @Test
    void dynamicMainLayersKeepTheirPositionAroundTheNativeMovementProvider() {
        AnimationClip walk = rotationClip("walk", "head", 20);
        AnimationClip before = rotationClip("before", "head", 10);
        AnimationClip afterA = rotationClip("after_a", "head", 30);
        AnimationClip afterZ = rotationClip("after_z", "head", 40);
        Map<String, AnimationController> controllers = new LinkedHashMap<>();
        controllers.put("player.post_main_z", controller("player.post_main_z", "after_z", List.of()));
        controllers.put("player.pre_main_a", controller("player.pre_main_a", "before", List.of()));
        controllers.put("player.post_main_a", controller("player.post_main_a", "after_a", List.of()));
        Fixture fixture = fixture(List.of(walk, before, afterA, afterZ), controllers, Map.of());

        ParallelAnimationProgram.Frame frame = fixture.sample(selection("walk"), true);

        assertTrue(frame.replaceEpicFightPose());
        assertRotationZ(40, frame.wholeModelDeltas()[0]);
    }

    @Test
    void dynamicParallelLayersSurroundNativeProvidersAndDoNotRunTwice() {
        AnimationClip pre = scriptClip("pre_parallel0", "v.order=v.order*10+1;return 0;");
        AnimationClip before = scriptClip("before", "v.order=v.order*10+2;return 0;");
        AnimationClip walk = scriptClip("walk", "v.order=v.order*10+3;return 0;");
        AnimationClip post = scriptClip("parallel0", "v.order=v.order*10+4;return 0;");
        AnimationClip after = scriptClip("after", "v.order=v.order*10+5;return 0;");
        Fixture fixture = fixture(List.of(pre, before, walk, post, after), Map.of(
                "player.parallel_fox", controller("player.parallel_fox", "after", List.of()),
                "player.pre_parallel_fox", controller("player.pre_parallel_fox", "before", List.of())), Map.of());

        fixture.sample(selection("walk"), false);

        assertEquals(12345.0D, fixture.environment.value("v.order"));
    }

    @Test
    void dynamicHandInsertionControllersCanAnimateAuxiliaryBonesWithoutReplacingAnItem() {
        List<String> channels = List.of("player.pre_hold_face", "player.post_hold_face",
                "player.pre_swing_face", "player.post_swing_face", "player.pre_use_face", "player.post_use_face");
        Map<String, AnimationController> controllers = new LinkedHashMap<>();
        List<AnimationClip> clips = new java.util.ArrayList<>();
        for (int index = channels.size() - 1; index >= 0; index--) {
            String name = "face_" + index;
            clips.add(scriptClip(name, "v.order=v.order*10+" + (index + 1) + ";return 0;"));
            controllers.put(channels.get(index), controller(channels.get(index), name, List.of()));
        }
        Fixture fixture = fixture(clips, controllers, Map.of());

        fixture.sample(emptySelection(), false);

        assertEquals(123456.0D, fixture.environment.value("v.order"));
    }

    @Test
    void bedrockControllerOwnsItsSlotAndTheConflictingScriptIsNotExecuted() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        AnimationClip scripted = rotationClip("scripted_pose", "head", 40);
        AnimationClip json = rotationClip("json_pose", "head", 20);
        AnimationController controller = controller("player.main", "json_pose",
                List.of("v.json_calls+=1;"));
        Fixture fixture = fixture(List.of(walk, scripted, json),
                Map.of(controller.name(), controller), Map.of("main@player_ctrl_main",
                        "v.script_calls+=1;ctrl.set_animation('scripted_pose');"
                                + "return ctrl.state_continue;"));

        ParallelAnimationProgram.Frame frame = fixture.sample(selection("walk"), true);

        assertEquals(0.0D, fixture.environment.value("v.script_calls"));
        assertEquals(1.0D, fixture.environment.value("v.json_calls"));
        assertRotationZ(20, frame.wholeModelDeltas()[0]);
    }

    @Test
    void guiAndFirstPersonOnlyHooksNeverExecuteInTheWorldControllerPass() {
        Fixture fixture = fixture(List.of(rotationClip("walk", "head", 5)), Map.of(), Map.of(
                "gui@player_ctrl_gui", "v.gui_calls+=1;return ctrl.state_continue;",
                "arm@player_ctrl_fp_arm", "v.arm_calls+=1;return ctrl.state_continue;"));

        ParallelAnimationProgram.Frame frame = fixture.sample(selection("walk"), true);

        assertEquals(0.0D, fixture.environment.value("v.gui_calls"));
        assertEquals(0.0D, fixture.environment.value("v.arm_calls"));
        assertRotationZ(5, frame.wholeModelDeltas()[0]);
    }

    @Test
    void useAndSwingAliasesTargetBothHandsAndArmorNamesTargetEquipmentSlots() {
        Map<String, String> aliases = Map.ofEntries(
                Map.entry("use_mainhand:minecraft:bow", "player.use"),
                Map.entry("use_offhand:minecraft:shield", "player.use"),
                Map.entry("swing_hand#minecraft:swords", "player.swing"),
                Map.entry("swing_offhand", "player.swing"),
                Map.entry("head:default", "player.armor_head"),
                Map.entry("chest:default", "player.armor_chest"),
                Map.entry("legs:default", "player.armor_legs"),
                Map.entry("feet:default", "player.armor_feet"));
        aliases.forEach((name, expected) -> {
            AutomaticAnimationSelector.ActiveClip active = active(name);
            assertEquals(expected, ParallelAnimationProgram.scriptChannel(active, null), name);
            assertTrue(ParallelAnimationProgram.supportsScriptController(expected), name);
        });
        assertTrue(ParallelAnimationProgram.supportsScriptController("player.parallel_0"));
        assertTrue(ParallelAnimationProgram.supportsScriptController("player.pre_parallel_7"));
        assertFalse(ParallelAnimationProgram.supportsScriptController("player.parallel8"));
        assertFalse(ParallelAnimationProgram.supportsScriptController("player.gui"));
    }

    @Test
    void usePauseRemovesBothHandVariantsButLeavesTheMainState() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        Fixture fixture = fixture(List.of(walk), Map.of(),
                Map.of("use@player_ctrl_use", "return ctrl.state_pause;"));
        AutomaticAnimationSelector.ActiveClip main = active("walk");
        AutomaticAnimationSelector.Selection selected = new AutomaticAnimationSelector.Selection(
                List.of(main, active("use_mainhand:minecraft:bow"), active("use_offhand:minecraft:shield")),
                main, MovementAnimationType.WALK, Set.of());

        AutomaticAnimationSelector.Selection result = fixture.program.selectScriptControllers(
                selected, fixture.scripts, new LinkedHashMap<>(), fixture.environment, 0, 0);

        assertEquals(List.of(main), result.clips());
        assertSame(main, result.main());
    }

    @Test
    void disabledOutputGateKeepsObservationWithoutEmittingAScriptPose() {
        Fixture fixture = fixture(List.of(rotationClip("authored_pose", "ear", 15)), Map.of(),
                Map.of("hold@player_ctrl_hold_mainhand",
                        "ctrl.set_animation('authored_pose');return ctrl.state_continue;"));
        Map<String, MolangScriptRuntime.Output> outputs = new LinkedHashMap<>();
        fixture.program.selectScriptControllers(emptySelection(), fixture.scripts, outputs,
                fixture.environment, 0, 0);

        AnimationControllerProgram.Selection result = fixture.program.mergeScriptControllers(
                new AnimationControllerProgram.Selection(List.of(), List.of()), outputs, ignored -> false);

        assertTrue(result.outputActive().isEmpty());
        assertEquals(1, result.allActive().size());
        assertEquals("authored_pose", result.allActive().get(0).name());
    }

    private static Fixture fixture(List<AnimationClip> clips,
                                   Map<String, AnimationController> controllers,
                                   Map<String, String> functions) {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone ear = new GeometryDocument.Bone("ear");
        ear.parentName("head");
        ear.pivot(0, 1, 0);
        geometry.add(head);
        geometry.add(ear);
        geometry.add(new GeometryDocument.Bone("LeftArm"));
        geometry.add(new GeometryDocument.Bone("RightArm"));
        geometry.linkHierarchy();
        return fixture(geometry, clips, controllers, functions);
    }

    private static Fixture fixture(GeometryDocument geometry, List<AnimationClip> clips,
                                   Map<String, AnimationController> controllers,
                                   Map<String, String> functions) {
        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        Map<String, MolangScriptRuntime.Clip> metadata = new LinkedHashMap<>();
        clips.forEach(clip -> {
            animations.put(clip.name(), clip);
            metadata.put(clip.name(), new MolangScriptRuntime.Clip(1, AnimationClip.Playback.REPEAT));
        });
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                "script_test", geometry, animations, controllers, functions,
                AuxiliaryBoneLayout.create(geometry), 1, 1);
        MolangScriptRuntime scripts = new MolangScriptRuntime(functions, metadata);
        return new Fixture(program, scripts, new HostEnvironment(scripts),
                new AnimationControllerProgram.RuntimeState());
    }

    private record Fixture(ParallelAnimationProgram program, MolangScriptRuntime scripts,
                           HostEnvironment environment,
                           AnimationControllerProgram.RuntimeState controllerState) {
        ParallelAnimationProgram.Frame sample(AutomaticAnimationSelector.Selection selection,
                                               boolean movementEnabled) {
            return sample(0, selection, movementEnabled);
        }

        ParallelAnimationProgram.Frame sample(double now, AutomaticAnimationSelector.Selection selection,
                                               boolean movementEnabled) {
            return program.sampleScriptControllersAt(now, selection, environment, scripts,
                    controllerState, movementEnabled);
        }

        ParallelAnimationProgram.Frame sample(double now, AutomaticAnimationSelector.Selection selection,
                                               boolean movementEnabled, boolean naturalLadderRequested) {
            return program.sampleScriptControllersAt(now, selection, environment, scripts,
                    controllerState, movementEnabled, naturalLadderRequested);
        }
    }

    private static AutomaticAnimationSelector.Selection selection(String mainName) {
        return selection(mainName, MovementAnimationType.WALK);
    }

    private static AutomaticAnimationSelector.Selection selection(
            String mainName, MovementAnimationType movement) {
        AutomaticAnimationSelector.ActiveClip main = active(mainName);
        return new AutomaticAnimationSelector.Selection(
                List.of(main), main, movement, Set.of());
    }

    private static AutomaticAnimationSelector.Selection emptySelection() {
        return new AutomaticAnimationSelector.Selection(List.of(), null, null, Set.of());
    }

    private static AutomaticAnimationSelector.Selection bowSelection(String action, double elapsed) {
        AutomaticAnimationSelector.ActiveClip hold = active("hold_mainhand:bow");
        return new AutomaticAnimationSelector.Selection(action == null ? List.of(hold)
                : List.of(hold, new AutomaticAnimationSelector.ActiveClip(action, elapsed, elapsed == 0)),
                null, null, Set.of());
    }

    private static AutomaticAnimationSelector.ActiveClip active(String name) {
        return new AutomaticAnimationSelector.ActiveClip(name, 0, false);
    }

    private static AnimationClip rotationClip(String name, String bone, double degrees) {
        AnimationClip clip = new AnimationClip(name);
        clip.playback(AnimationClip.Playback.REPEAT);
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setConstant(0, 0);
        value.setConstant(1, 0);
        value.setConstant(2, degrees);
        clip.boneTracks().put(bone, tracks(value));
        return clip;
    }

    private static AnimationClip scriptClip(String name, String expression) {
        AnimationClip clip = new AnimationClip(name);
        clip.playback(AnimationClip.Playback.REPEAT);
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setExpression(0, expression);
        value.setConstant(1, 0);
        value.setConstant(2, 0);
        clip.boneTracks().put("ear", tracks(value));
        return clip;
    }

    private static AnimationClip countedRotationClip(String name, double degrees, String counter) {
        AnimationClip clip = scriptClip(name, counter + "+=1;return 0;");
        clip.boneTracks().get("ear").rotation().keyframes().get(0).value().setConstant(2, degrees);
        return clip;
    }

    private static AnimationClip scaleClip(String name, String bone, double scale) {
        AnimationClip clip = new AnimationClip(name);
        clip.playback(AnimationClip.Playback.REPEAT);
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) value.setConstant(axis, scale);
        AnimationClip.BoneTracks boneTracks = new AnimationClip.BoneTracks();
        boneTracks.scale(tracks(value).rotation());
        clip.boneTracks().put(bone, boneTracks);
        return clip;
    }

    private static AnimationController builtinController(String channel, float blendSeconds) {
        AnimationController.BlendTransition blend = new AnimationController.BlendTransition(
                blendSeconds, List.of());
        AnimationController.State builtin = new AnimationController.State("ysm-builtin",
                List.of(new AnimationController.AnimationReference("ignored_pose",
                        "v.ignored_weight+=1;return 1;")),
                List.of(new AnimationController.Transition("custom", "v.custom")),
                List.of("v.builtin_entries+=1;"), List.of("v.builtin_exits+=1;"), blend, false);
        AnimationController.State custom = new AnimationController.State("custom",
                List.of(new AnimationController.AnimationReference("custom_pose", "1")),
                List.of(new AnimationController.Transition("ysm-builtin", "!v.custom")),
                List.of("v.custom_entries+=1;"), List.of("v.custom_exits+=1;"), blend, false);
        return new AnimationController(channel, "ysm-builtin",
                Map.of("ysm-builtin", builtin, "custom", custom));
    }

    private static AnimationClip.BoneTracks tracks(AnimationClip.VectorValue value) {
        AnimationClip.Track rotation = new AnimationClip.Track();
        rotation.keyframes().add(new AnimationClip.Keyframe(
                0, AnimationClip.Interpolation.LINEAR, value, null));
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.rotation(rotation);
        return tracks;
    }

    private static AnimationController controller(String name, String clip, List<String> onEntry) {
        AnimationController.State initial = new AnimationController.State("default",
                List.of(new AnimationController.AnimationReference(clip, "1")),
                List.of(), onEntry, List.of(),
                new AnimationController.BlendTransition(0, List.of()), false);
        return new AnimationController(name, "default", Map.of("default", initial));
    }

    private static void assertIdentity(OpenMatrix4f actual) {
        assertRotationZ(0, actual);
        assertEquals(0, actual.m30, 0.0001F);
        assertEquals(0, actual.m31, 0.0001F);
        assertEquals(0, actual.m32, 0.0001F);
    }

    private static void assertUniformScale(double scale, OpenMatrix4f actual) {
        assertNotNull(actual);
        assertEquals(scale, actual.m00, 0.0001D);
        assertEquals(scale, actual.m11, 0.0001D);
        assertEquals(scale, actual.m22, 0.0001D);
    }

    private static void assertHeldTool(double degrees, int index, ParallelAnimationProgram.Frame frame) {
        assertFalse(frame.replaceEpicFightPose());
        assertTrue(frame.replaceEpicFightAnchors()[index]);
        assertTrue(frame.suppressParallelDeltas()[index]);
        assertEquals(HumanoidRig.RIGHT_TOOL, frame.heldItemAnchorJoints()[index]);
        assertRotationZ(degrees, frame.heldItemDeltas()[index]);
        assertFalse(frame.hiddenBones().contains("test_tool"));
    }

    private static void assertLadderArms(double leftDegrees, ParallelAnimationProgram.Frame frame) {
        assertTrue(frame.replaceEpicFightPose());
        assertTrue(frame.naturalLadderPose());
        assertRotationZ(leftDegrees, frame.wholeModelDeltas()[2]);
        assertRotationZ(-leftDegrees, frame.wholeModelDeltas()[3]);
    }

    private static void assertRotationZ(double degrees, OpenMatrix4f actual) {
        assertNotNull(actual);
        double radians = Math.toRadians(degrees);
        assertEquals(Math.cos(radians), actual.m00, 0.0001D);
        assertEquals(Math.sin(radians), actual.m01, 0.0001D);
        assertEquals(-Math.sin(radians), actual.m10, 0.0001D);
        assertEquals(Math.cos(radians), actual.m11, 0.0001D);
        assertEquals(1, actual.m22, 0.0001D);
    }

    private static final class HostEnvironment implements MolangScriptRuntime.Host {
        private final Map<Integer, Object> values = new HashMap<>();
        private final MolangScriptRuntime scripts;
        private boolean inventory;

        private HostEnvironment(MolangScriptRuntime scripts) {
            this.scripts = scripts;
        }

        double value(String name) {
            return readVariable(ExpressionEngine.slot(name));
        }

        @Override public MolangScriptRuntime scripts() { return scripts; }
        @Override public boolean hasVariable(int slot) { return values.containsKey(slot); }
        @Override public Object readVariableValue(int slot) { return values.getOrDefault(slot, 0.0D); }
        @Override public double readVariable(int slot) { return ExpressionEngine.number(readVariableValue(slot)); }
        @Override public void writeVariable(int slot, double value) { writeVariableValue(slot, value); }
        @Override public void writeVariableValue(int slot, Object value) {
            values.put(slot, ExpressionEngine.boundedValue(value));
        }
        @Override public Object readQueryValue(int slot) {
            if (ExpressionEngine.slotName(slot).equals("ysm.rendering_in_inventory")) {
                return inventory ? 1.0D : 0.0D;
            }
            Object value = scripts.read(ExpressionEngine.slotName(slot), this);
            return value == MolangScriptRuntime.UNHANDLED ? 0.0D : value;
        }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(readQueryValue(slot)); }
        @Override public Object invokeValue(String name, Object[] arguments) {
            if (name.equals("ctrl.use")) return value("v.using");
            Object value = scripts.invoke(name, arguments, this);
            return value == MolangScriptRuntime.UNHANDLED ? 0.0D : value;
        }
        @Override public double invoke(String name, double[] arguments) { return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
