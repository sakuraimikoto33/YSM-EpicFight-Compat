package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Render-thread Molang values used by auxiliary animations. */
final class EntityAnimationEnvironment implements ExpressionEngine.Environment {
    private static final double EPSILON = 0.0001D;
    private static final int MAX_DIE_ROLLS = 1024;
    private static final int MAX_RELATIVE_BLOCK_OFFSET = 8;

    private final LivingEntity entity;
    private final String modelId;
    private final Map<Integer, Double> variables;
    private final Set<Integer> assigned;
    private final Random random;
    private final AuxiliaryPhysicsRuntime physics = new AuxiliaryPhysicsRuntime();
    private boolean firstPerson;
    private float partialTick;
    @Nullable
    private Float customBowRelativeHeadYaw;
    @Nullable
    private Float fullBodyModelYaw;
    private double deltaTime;
    private double animationTime;
    private double lifeTime;
    private OfficialRoamingVariables.View roamingVariables;
    private int cachedActorCount = -1;
    private boolean cameraPositionResolved;
    private Vec3 cachedCameraPosition;
    private String soundScope = "model";
    private boolean soundOutputEnabled = true;
    private Set<InteractionHand> attackReplacementHands = Set.of();
    @Nullable
    private InteractionHand attackSoundHand;

    EntityAnimationEnvironment(LivingEntity entity, Map<Integer, Double> variables,
                               Set<Integer> assigned) {
        this(entity, variables, assigned, "");
    }

    EntityAnimationEnvironment(LivingEntity entity, Map<Integer, Double> variables,
                               Set<Integer> assigned, String modelId) {
        this.entity = entity;
        this.modelId = modelId == null ? "" : modelId;
        this.variables = variables;
        this.assigned = assigned;
        random = new Random(entity.getUUID().getMostSignificantBits()
                ^ entity.getUUID().getLeastSignificantBits());
        roamingVariables = OfficialRoamingVariables.view(entity);
    }

    void update(float partialTick, boolean firstPerson, double deltaTime) {
        physics.update(deltaTime);
        this.partialTick = partialTick;
        this.firstPerson = firstPerson;
        this.deltaTime = deltaTime;
        lifeTime = (entity.tickCount + partialTick) / 20.0D;
        roamingVariables = OfficialRoamingVariables.view(entity);
        cachedActorCount = -1;
        cameraPositionResolved = false;
        cachedCameraPosition = null;
        ClientParticleOutput.update(entity);
    }

    /**
     * Makes a complete model-authored bow pose aim from the actual player view while
     * preserving Epic Fight's outer model rotation. Null values restore official YSM's
     * ordinary head/body-relative queries.
     *
     * <p>The supplied value is the interpolated view yaw selected by the caller. The
     * local player deliberately uses its current entity yaw instead, because that is
     * the yaw used by {@code BowItem} when the projectile is launched. The result is
     * made relative to Epic Fight's actual interpolated model yaw, not vanilla
     * {@code yBodyRot}; mixing those two bases rotates the authored upper body twice.</p>
     */
    void customBowAim(@Nullable Float visualFacingYaw,
                      @Nullable Float epicModelYaw) {
        if (visualFacingYaw == null || !Float.isFinite(visualFacingYaw)
                || epicModelYaw == null || !Float.isFinite(epicModelYaw)) {
            customBowRelativeHeadYaw = null;
            return;
        }
        customBowRelativeHeadYaw = customBowRelativeHeadYaw(
                entity.getYRot(), visualFacingYaw,
                entity instanceof LocalPlayer, epicModelYaw);
    }

    /**
     * Aligns model-authored full-body locomotion queries with Epic Fight's actual
     * outer model rotation. Without this reference, head and body tracks use
     * vanilla's independently interpolated body yaw and can split at turn seams.
     */
    void fullBodyReferenceYaw(@Nullable Float epicModelYaw) {
        fullBodyModelYaw = epicModelYaw != null && Float.isFinite(epicModelYaw)
                ? epicModelYaw : null;
    }

    void clipTime(double animationTime) {
        this.animationTime = animationTime;
    }

    void soundScope(String soundScope) {
        this.soundScope = soundScope == null || soundScope.isBlank() ? "model" : soundScope;
        attackSoundHand = attackHandForScope(this.soundScope, attackReplacementHands);
    }

    void attackReplacementHands(Set<InteractionHand> hands) {
        attackReplacementHands = hands == null ? Set.of() : Set.copyOf(hands);
        attackSoundHand = attackHandForScope(soundScope, attackReplacementHands);
    }

    boolean soundOutputEnabled() {
        return soundOutputEnabled;
    }

    void soundOutputEnabled(boolean soundOutputEnabled) {
        this.soundOutputEnabled = soundOutputEnabled;
    }

    boolean playSoundEffect(String effect) {
        if (soundOutputEnabled && effect != null && !effect.isBlank()) {
            boolean played = ClientSoundOutput.playEffect(entity, modelId, soundScope, effect);
            claimAttackSound(played);
            return played;
        }
        return false;
    }

    void stopSoundScope(String scope) {
        ClientSoundOutput.stopScope(entity, modelId, scope);
    }

    void playParticleEffect(DeclarativeParticleEffect effect, boolean scoped) {
        if (effect == null || effect.effect().isBlank()) {
            return;
        }
        if (!effect.preEffectScript().isBlank()) {
            ExpressionEngine.compile(effect.preEffectScript()).evaluate(this);
        }
        ClientParticleOutput.emitEffect(entity, modelId, soundScope, effect, scoped);
    }

    void stopParticleScope(String scope) {
        ClientParticleOutput.stopScope(entity, modelId, scope);
    }

    void reset() {
        physics.reset();
        ClientSoundOutput.stopModel(entity, modelId);
        ClientParticleOutput.stopModel(entity, modelId);
        attackReplacementHands = Set.of();
        attackSoundHand = null;
    }

    @Override
    public double readVariable(int slot) {
        String name = ExpressionEngine.slotName(slot);
        RoamingVariableLookup.Lookup official = roamingVariables.lookup(name);
        if (RoamingVariableLookup.isRoaming(name) && official.present()) {
            return official.value();
        }
        ConfigurationVariableOverrides.Lookup configuration =
                OfficialConfigurationVariables.lookup(entity, slot);
        if (configuration.present()) {
            return configuration.value();
        }
        if (assigned.contains(slot)) {
            return variables.getOrDefault(slot, 0.0D);
        }
        if (official.present()) {
            return official.value();
        }
        return variables.getOrDefault(slot, 0.0D);
    }

    @Override
    public boolean hasVariable(int slot) {
        String name = ExpressionEngine.slotName(slot);
        RoamingVariableLookup.Lookup official = roamingVariables.lookup(name);
        return official.present()
                || OfficialConfigurationVariables.lookup(entity, slot).present()
                || assigned.contains(slot);
    }

    @Override
    public void writeVariable(int slot, double value) {
        String name = ExpressionEngine.slotName(slot);
        if (RoamingVariableLookup.isRoaming(name)
                && roamingVariables.writeRoaming(name, value)) {
            return;
        }
        variables.put(slot, Double.isFinite(value) ? value : 0.0D);
        assigned.add(slot);
    }

    @Override
    public double readQuery(int slot) {
        String name = ExpressionEngine.slotName(slot);
        double horizontalSpeed = Math.sqrt(entity.getDeltaMovement().x * entity.getDeltaMovement().x
                + entity.getDeltaMovement().z * entity.getDeltaMovement().z) * 20.0D;
        float headYaw = Mth.lerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float modelBodyYaw = fullBodyModelYaw == null ? bodyYaw : fullBodyModelYaw;
        float relativeHeadYaw = customBowRelativeHeadYaw == null
                ? officialHeadYaw(headYaw, modelBodyYaw)
                : customBowRelativeHeadYaw;
        float headPitch = officialHeadPitch(entity.getViewXRot(partialTick));
        return switch (name) {
            case "math.pi" -> Math.PI;
            case "math.e" -> Math.E;
            case "query.actor_count" -> actorCount();
            case "query.anim_time" -> animationTime;
            case "query.life_time" -> lifeTime;
            case "query.delta_time" -> deltaTime;
            case "query.health" -> entity.getHealth();
            case "query.max_health" -> entity.getMaxHealth();
            case "query.head_x_rotation", "ysm.head_yaw" -> relativeHeadYaw;
            case "query.head_y_rotation", "ysm.head_pitch" -> headPitch;
            case "query.eye_target_x_rotation" -> entity.getViewXRot(partialTick);
            case "query.eye_target_y_rotation" ->
                    Mth.wrapDegrees(entity.getViewYRot(partialTick));
            case "query.body_x_rotation" ->
                    Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
            case "query.body_y_rotation" -> Mth.wrapDegrees(modelBodyYaw);
            case "query.yaw_speed" -> yawSpeed(entity.getYRot(), entity.yRotO);
            case "query.ground_speed" -> horizontalSpeed;
            case "query.vertical_speed" -> entity.getDeltaMovement().y * 20.0D;
            case "query.walk_distance" -> entity.moveDist;
            case "query.modified_distance_moved" -> entity.walkDist;
            case "query.hurt_time" -> entity.hurtTime;
            case "query.is_on_ground" -> flag(entity.onGround());
            case "query.is_alive" -> flag(entity.isAlive());
            case "query.is_in_water" -> flag(entity.isInWater());
            case "query.is_in_water_or_rain" -> flag(entity.isInWaterOrRain());
            case "query.is_on_fire" -> flag(entity.isOnFire());
            case "query.is_riding", "ysm.is_passenger" ->
                    flag(entity.isPassenger());
            case "query.has_rider" -> flag(entity.isVehicle());
            case "query.is_sneaking", "ysm.is_sneak" -> flag(entity.isShiftKeyDown());
            case "query.is_sprinting" -> flag(entity.isSprinting());
            case "query.is_swimming" -> flag(entity.isSwimming());
            case "query.is_sleeping", "ysm.is_sleep" -> flag(entity.isSleeping());
            case "query.is_playing_dead" -> flag(entity.isDeadOrDying());
            case "query.is_using_item" -> flag(entity.isUsingItem());
            case "query.is_jumping" -> flag(!entity.onGround()
                    && entity.getDeltaMovement().y > 0.0D);
            case "query.is_spectator" -> flag(entity instanceof Player player
                    && player.isSpectator());
            case "query.is_first_person" -> flag(firstPerson);
            case "query.item_in_use_duration" -> entity.isUsingItem()
                    ? (entity.getUseItem().getUseDuration() - entity.getUseItemRemainingTicks()) / 20.0D
                    : 0.0D;
            case "query.item_max_use_duration" -> entity.isUsingItem()
                    ? entity.getUseItem().getUseDuration() / 20.0D : 0.0D;
            case "query.item_remaining_use_duration" ->
                    entity.getUseItemRemainingTicks() / 20.0D;
            case "query.equipment_count" -> equipmentCount(entity);
            case "query.has_cape" -> flag(hasCape(entity));
            case "query.cape_flap_amount" -> capeFlapAmount(entity, partialTick);
            case "query.player_level" -> entity instanceof Player player
                    ? player.experienceLevel : 0.0D;
            case "query.time_stamp" -> entity.level().getGameTime() / 20.0D;
            case "query.time_of_day" -> timeOfDay(entity.level().getDayTime());
            case "query.moon_phase" -> entity.level().getMoonPhase();
            case "query.cardinal_facing_2d" -> cardinalFacing2d(entity.getDirection());
            case "query.distance_from_camera" -> distanceFromCamera();
            case "query.is_eating" -> flag(entity.isUsingItem()
                    && entity.getUseItem().getUseAnimation() == UseAnim.EAT);
            case "query.armor_value", "ysm.armor_value" -> entity.getArmorValue();
            case "ysm.food_level" -> entity instanceof Player player
                    ? player.getFoodData().getFoodLevel() : 0.0D;
            case "ysm.input_vertical" -> entity.zza;
            case "ysm.input_horizontal" -> entity.xxa;
            case "ysm.is_close_eyes" -> flag(blinking(entity));
            case "ysm.has_helmet" -> flag(!entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
            case "ysm.has_chest_plate" ->
                    flag(!entity.getItemBySlot(EquipmentSlot.CHEST).isEmpty());
            case "ysm.has_leggings" -> flag(!entity.getItemBySlot(EquipmentSlot.LEGS).isEmpty());
            case "ysm.has_boots" -> flag(!entity.getItemBySlot(EquipmentSlot.FEET).isEmpty());
            case "ysm.has_mainhand" -> flag(!entity.getMainHandItem().isEmpty());
            case "ysm.has_offhand" -> flag(!entity.getOffhandItem().isEmpty());
            case "ysm.has_elytra" -> flag(entity.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
            case "ysm.air_supply" -> entity.getAirSupply();
            case "ysm.frozen_ticks" -> entity.getTicksFrozen();
            case "ctrl.death" -> flag(entity.isDeadOrDying());
            case "ctrl.riptide" -> flag(entity.isAutoSpinAttack());
            case "ctrl.sleep" -> flag(entity.isSleeping());
            case "ctrl.swim" -> flag(entity.isSwimming());
            case "ctrl.climb" -> flag(isCrawling() && horizontalSpeed > 0.01D);
            case "ctrl.climbing" -> flag(isCrawling() && horizontalSpeed <= 0.01D);
            case "ctrl.ladder_up" -> flag(entity.onClimbable()
                    && entity.getDeltaMovement().y > 0.01D);
            case "ctrl.ladder_stillness" -> flag(entity.onClimbable()
                    && Math.abs(entity.getDeltaMovement().y) <= 0.01D);
            case "ctrl.ladder_down" -> flag(entity.onClimbable()
                    && entity.getDeltaMovement().y < -0.01D);
            case "ctrl.fly" -> flag(entity instanceof Player player
                    && player.getAbilities().flying);
            case "ctrl.elytra_fly" -> flag(entity.isFallFlying());
            case "ctrl.swim_stand" -> flag(entity.isInWaterOrBubble()
                    && !entity.isSwimming());
            case "ctrl.attacked" -> flag(entity.hurtTime > 0);
            case "ctrl.jump" -> flag(!entity.onGround() && entity.getDeltaMovement().y > 0.0D);
            case "ctrl.sneak" -> flag(entity.isShiftKeyDown() && horizontalSpeed > 0.01D);
            case "ctrl.sneaking" -> flag(entity.isShiftKeyDown() && horizontalSpeed <= 0.01D);
            case "ctrl.run" -> flag(entity.isSprinting());
            case "ctrl.walk" -> flag(!entity.isSprinting() && horizontalSpeed > 0.01D);
            case "ctrl.idle" -> flag(horizontalSpeed <= 0.01D);
            default -> 0.0D;
        };
    }

    @Override
    public double invoke(String name, double[] arguments) {
        String function = name.toLowerCase(Locale.ROOT);
        return switch (function) {
            case "math.floor" -> Math.floor(arg(arguments, 0));
            case "math.round" -> Math.round(arg(arguments, 0));
            case "math.ceil" -> Math.ceil(arg(arguments, 0));
            case "math.trunc" -> arg(arguments, 0) < 0.0D
                    ? Math.ceil(arg(arguments, 0)) : Math.floor(arg(arguments, 0));
            case "math.abs" -> Math.abs(arg(arguments, 0));
            case "math.exp" -> Math.exp(arg(arguments, 0));
            case "math.ln" -> Math.log(Math.max(EPSILON, arg(arguments, 0)));
            case "math.sqrt" -> Math.sqrt(Math.max(0.0D, arg(arguments, 0)));
            case "math.pow" -> Math.pow(arg(arguments, 0), arg(arguments, 1));
            case "math.mod" -> arg(arguments, 1) == 0.0D ? 0.0D
                    : arg(arguments, 0) % arg(arguments, 1);
            case "math.sin" -> Math.sin(Math.toRadians(arg(arguments, 0)));
            case "math.cos" -> Math.cos(Math.toRadians(arg(arguments, 0)));
            case "math.asin" -> Math.asin(clamp(arg(arguments, 0), -1.0D, 1.0D));
            case "math.acos" -> Math.acos(clamp(arg(arguments, 0), -1.0D, 1.0D));
            case "math.atan" -> Math.atan(arg(arguments, 0));
            case "math.atan2" -> Math.atan2(arg(arguments, 0), arg(arguments, 1));
            case "math.min" -> Math.min(arg(arguments, 0), arg(arguments, 1));
            case "math.max" -> Math.max(arg(arguments, 0), arg(arguments, 1));
            case "math.clamp" -> clamp(arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case "math.lerp" -> arg(arguments, 0)
                    + (arg(arguments, 1) - arg(arguments, 0)) * arg(arguments, 2);
            case "math.lerprotate", "math.lerprotatee" -> lerpRotate(
                    arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case "math.hermite", "math.hermite_blend" -> {
                double value = arg(arguments, 0);
                yield 3.0D * value * value - 2.0D * value * value * value;
            }
            case "math.min_angle" -> minimumAngle(arg(arguments, 0));
            case "math.random" -> random(arg(arguments, 0), arg(arguments, 1), false);
            case "math.randomi", "math.random_integer" ->
                    random(arg(arguments, 0), arg(arguments, 1), true);
            case "math.die_roll", "math.roll" -> dieRoll(random, arguments, false);
            case "math.die_roll_integer", "math.rolli" ->
                    dieRoll(random, arguments, true);
            case "query.position" -> coordinate(arg(arguments, 0),
                    entity.getX(), entity.getY(), entity.getZ());
            case "query.position_delta" -> coordinate(arg(arguments, 0),
                    entity.getX() - entity.xOld,
                    entity.getY() - entity.yOld,
                    entity.getZ() - entity.zOld);
            case "query.rotation_to_camera" -> rotationToCamera(arg(arguments, 0));
            case "ysm.perlin_noise" -> AuxiliaryPhysicsRuntime.perlinNoise(arguments);
            case "ysm.stop_sound" -> stopSound(null, arguments);
            case "ysm.stop_all_sounds" -> stopAllSounds(null, arguments);
            default -> 0.0D;
        };
    }

    @Override
    public double invokeWithMixedArguments(String name, String[] textArguments,
                                           double[] numericArguments) {
        String function = name.toLowerCase(Locale.ROOT);
        return switch (function) {
            case "ysm.first_order" -> firstOrder(textArguments, numericArguments);
            case "ysm.second_order" -> secondOrder(textArguments, numericArguments);
            case "ysm.particle" -> particle(textArguments, numericArguments, false);
            case "ysm.abs_particle" -> particle(textArguments, numericArguments, true);
            case "ysm.play_sound" -> playSound(textArguments, numericArguments);
            case "ysm.stop_sound" -> stopSound(textArguments, numericArguments);
            case "ysm.stop_all_sounds" -> stopAllSounds(textArguments, numericArguments);
            case "query.biome_has_all_tags" -> biomeHasTags(textArguments, true);
            case "query.biome_has_any_tag", "query.biome_has_any_tags" ->
                    biomeHasTags(textArguments, false);
            case "query.relative_block_has_all_tags" -> relativeBlockHasTags(
                    textArguments, numericArguments, true);
            case "query.relative_block_has_any_tag", "query.relative_block_has_any_tags" ->
                    relativeBlockHasTags(textArguments, numericArguments, false);
            case "query.is_item_name_any" -> itemNameAny(textArguments);
            case "query.equipped_item_all_tags" -> equippedItemHasTags(textArguments, true);
            case "query.equipped_item_any_tag", "query.equipped_item_any_tags" ->
                    equippedItemHasTags(textArguments, false);
            case "query.max_durability" -> durability(textArguments, false);
            case "query.remaining_durability" -> durability(textArguments, true);
            case "ctrl.hold" -> flag(AnimationConditionMatcher.hold(entity,
                    textArgument(textArguments, 0), textArgument(textArguments, 1)));
            case "ctrl.swing" -> flag(AnimationConditionMatcher.swing(entity,
                    textArgument(textArguments, 0), textArgument(textArguments, 1)));
            case "ctrl.use" -> flag(AnimationConditionMatcher.use(entity,
                    textArgument(textArguments, 0), textArgument(textArguments, 1)));
            case "ctrl.armor" -> flag(AnimationConditionMatcher.armor(entity,
                    textArgument(textArguments, 0), textArgument(textArguments, 1)));
            case "ctrl.ride" -> flag(AnimationConditionMatcher.ride(entity,
                    textArgument(textArguments, 0), textArgument(textArguments, 1)));
            default -> 0.0D;
        };
    }

    @Override
    public double invokeWithText(String name, String[] arguments) {
        return invokeWithMixedArguments(name, arguments, new double[arguments.length]);
    }

    private double random(double low, double high, boolean integer) {
        return random(random, low, high, integer);
    }

    private double playSound(String[] textArguments, double[] numericArguments) {
        if (!soundOutputEnabled) {
            return 0.0D;
        }
        ClientSoundOutput.PlayRequest request = ClientSoundOutput.request(
                textArguments, numericArguments);
        boolean played = ClientSoundOutput.play(entity, modelId, soundScope, request);
        claimAttackSound(played);
        return flag(played);
    }

    private void claimAttackSound(boolean played) {
        if (played && attackSoundHand != null) {
            AttackSoundOwnership.claim(entity, attackSoundHand, modelId);
        }
    }

    @Nullable
    static InteractionHand attackHandForScope(String scope,
                                               Set<InteractionHand> availableHands) {
        if (scope == null || availableHands == null || availableHands.isEmpty()) {
            return null;
        }
        String normalized = scope.toLowerCase(Locale.ROOT);
        if (!normalized.contains("swing")) {
            return null;
        }
        if (normalized.contains("swing_offhand")) {
            return availableHands.contains(InteractionHand.OFF_HAND)
                    ? InteractionHand.OFF_HAND : null;
        }
        return availableHands.contains(InteractionHand.MAIN_HAND)
                ? InteractionHand.MAIN_HAND : null;
    }

    private double stopSound(String[] textArguments, double[] numericArguments) {
        if (!soundOutputEnabled) {
            return 0.0D;
        }
        int size = Math.max(textArguments == null ? 0 : textArguments.length,
                numericArguments == null ? 0 : numericArguments.length);
        if (size < 1 || size > 2) {
            return 0.0D;
        }
        if (size > 1 && textArgument(textArguments, 1) != null) {
            return 0.0D;
        }
        String id = ClientSoundOutput.identifier(textArguments, numericArguments, 0);
        boolean global = size > 1 && arg(numericArguments, 1) != 0.0D;
        return flag(ClientSoundOutput.stop(
                entity, modelId, soundScope, id, global));
    }

    private double stopAllSounds(String[] textArguments, double[] numericArguments) {
        if (!soundOutputEnabled) {
            return 0.0D;
        }
        if (numericArguments != null && numericArguments.length > 1) {
            return 0.0D;
        }
        if (textArguments != null && java.util.Arrays.stream(textArguments)
                .anyMatch(java.util.Objects::nonNull)) {
            return 0.0D;
        }
        boolean global = numericArguments != null && numericArguments.length == 1
                && arg(numericArguments, 0) != 0.0D;
        ClientSoundOutput.stopAll(entity, modelId, soundScope, global);
        return 1.0D;
    }

    private double firstOrder(String[] textArguments, double[] numericArguments) {
        String key = textArgument(textArguments, 0);
        return key == null || numericArguments.length < 2 ? 0.0D
                : physics.firstOrder(key, arg(numericArguments, 1),
                numericArguments.length > 2 ? arg(numericArguments, 2) : 1.0D);
    }

    private double secondOrder(String[] textArguments, double[] numericArguments) {
        String key = textArgument(textArguments, 0);
        return key == null || numericArguments.length < 2 ? 0.0D
                : physics.secondOrder(key, arg(numericArguments, 1),
                numericArguments.length > 2 ? arg(numericArguments, 2) : 1.0D,
                numericArguments.length > 3 ? arg(numericArguments, 3) : 1.0D,
                numericArguments.length > 4 ? arg(numericArguments, 4) : 1.0D);
    }

    private double particle(String[] textArguments, double[] numericArguments,
                            boolean absolute) {
        return flag(ClientParticleOutput.emit(entity, random, textArguments,
                numericArguments, absolute));
    }

    private double biomeHasTags(String[] arguments, boolean requireAll) {
        int tagCount = textCount(arguments, 0);
        if (tagCount == 0) {
            return 0.0D;
        }
        var biome = entity.level().getBiome(entity.blockPosition());
        boolean matched = requireAll;
        for (String argument : arguments) {
            if (argument == null) {
                continue;
            }
            ResourceLocation id = resourceLocation(argument, '#');
            boolean present = id != null
                    && biome.is(TagKey.create(Registries.BIOME, id));
            if (requireAll && !present) {
                return 0.0D;
            }
            if (!requireAll && present) {
                return 1.0D;
            }
            matched = present;
        }
        return flag(matched);
    }

    private double relativeBlockHasTags(String[] textArguments, double[] numericArguments,
                                        boolean requireAll) {
        Integer x = relativeOffset(arg(numericArguments, 0));
        Integer y = relativeOffset(arg(numericArguments, 1));
        Integer z = relativeOffset(arg(numericArguments, 2));
        if (x == null || y == null || z == null || textCount(textArguments, 3) == 0) {
            return 0.0D;
        }
        BlockPos position = entity.blockPosition().offset(x, y, z);
        BlockState state = entity.level().getBlockState(position);
        return flag(blockHasTags(state, textArguments, 3, requireAll));
    }

    private double itemNameAny(String[] arguments) {
        ItemStack stack = itemBySlot(textArgument(arguments, 0));
        if (stack.isEmpty()) {
            return 0.0D;
        }
        ResourceLocation actual = BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (int index = 1; index < arguments.length; index++) {
            ResourceLocation expected = resourceLocation(arguments[index], '$');
            if (expected != null && expected.equals(actual)) {
                return 1.0D;
            }
        }
        return 0.0D;
    }

    private double equippedItemHasTags(String[] arguments, boolean requireAll) {
        ItemStack stack = itemBySlot(textArgument(arguments, 0));
        if (stack.isEmpty() || textCount(arguments, 1) == 0) {
            return 0.0D;
        }
        boolean matched = requireAll;
        for (int index = 1; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument == null) {
                continue;
            }
            ResourceLocation id = resourceLocation(argument, '#');
            boolean present = id != null && stack.is(TagKey.create(Registries.ITEM, id));
            if (requireAll && !present) {
                return 0.0D;
            }
            if (!requireAll && present) {
                return 1.0D;
            }
            matched = present;
        }
        return flag(matched);
    }

    private double durability(String[] arguments, boolean remaining) {
        ItemStack stack = itemBySlot(textArgument(arguments, 0));
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return 0.0D;
        }
        return remaining ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue())
                : stack.getMaxDamage();
    }

    private ItemStack itemBySlot(String name) {
        if (name == null) {
            return ItemStack.EMPTY;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "head" -> entity.getItemBySlot(EquipmentSlot.HEAD);
            case "chest" -> entity.getItemBySlot(EquipmentSlot.CHEST);
            case "legs" -> entity.getItemBySlot(EquipmentSlot.LEGS);
            case "feet" -> entity.getItemBySlot(EquipmentSlot.FEET);
            case "mainhand", "main_hand" -> entity.getMainHandItem();
            case "offhand", "off_hand" -> entity.getOffhandItem();
            default -> ItemStack.EMPTY;
        };
    }

    private boolean isCrawling() {
        return entity.getPose() == Pose.SWIMMING && !entity.isInWaterOrBubble()
                && !entity.isFallFlying();
    }

    private double actorCount() {
        if (cachedActorCount >= 0) {
            return cachedActorCount;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level != entity.level()) {
            return 0.0D;
        }
        int count = 0;
        for (Entity ignored : level.entitiesForRendering()) {
            count++;
        }
        cachedActorCount = count;
        return cachedActorCount;
    }

    private double distanceFromCamera() {
        Vec3 camera = cameraPosition();
        return camera == null ? 0.0D : interpolatedPosition().distanceTo(camera);
    }

    private double rotationToCamera(double axis) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level != entity.level()) {
            return 0.0D;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 origin = interpolatedPosition().add(0.0D, entity.getEyeHeight(), 0.0D);
        return rotationToCamera(origin, camera.getPosition(), camera.getXRot(),
                camera.getYRot(), (int) axis);
    }

    private Vec3 cameraPosition() {
        if (cameraPositionResolved) {
            return cachedCameraPosition;
        }
        cameraPositionResolved = true;
        Minecraft minecraft = Minecraft.getInstance();
        cachedCameraPosition = minecraft.level == null || minecraft.level != entity.level()
                ? null : minecraft.gameRenderer.getMainCamera().getPosition();
        return cachedCameraPosition;
    }

    private Vec3 interpolatedPosition() {
        return new Vec3(Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()));
    }

    private static boolean blockHasTags(BlockState state, String[] arguments,
                                        int firstTag, boolean requireAll) {
        boolean matched = requireAll;
        for (int index = firstTag; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument == null) {
                continue;
            }
            ResourceLocation id = resourceLocation(argument, '#');
            boolean present = id != null && state.is(TagKey.create(Registries.BLOCK, id));
            if (requireAll && !present) {
                return false;
            }
            if (!requireAll && present) {
                return true;
            }
            matched = present;
        }
        return matched;
    }

    private static ResourceLocation resourceLocation(String value, char optionalPrefix) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.charAt(0) == optionalPrefix ? value.substring(1) : value;
        return ResourceLocation.tryParse(normalized);
    }

    private static int textCount(String[] arguments, int first) {
        if (arguments == null) {
            return 0;
        }
        int count = 0;
        for (int index = first; index < arguments.length; index++) {
            if (arguments[index] != null) {
                count++;
            }
        }
        return count;
    }

    static Integer relativeOffset(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_RELATIVE_BLOCK_OFFSET
                ? (int) value : null;
    }

    static double minimumAngle(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        double wrapped = value % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        } else if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped == -0.0D ? 0.0D : wrapped;
    }

    static double lerpRotate(double start, double end, double amount) {
        return start + minimumAngle(end - start) * amount;
    }

    static double dieRoll(Random random, double[] arguments, boolean integer) {
        int count = (int) clamp(Math.floor(arg(arguments, 0)), 0.0D, MAX_DIE_ROLLS);
        double total = 0.0D;
        for (int roll = 0; roll < count; roll++) {
            total += random(random, arg(arguments, 1), arg(arguments, 2), integer);
        }
        return Double.isFinite(total) ? total : 0.0D;
    }

    private static double random(Random random, double low, double high, boolean integer) {
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return 0.0D;
        }
        double minimum = Math.min(low, high);
        double maximum = Math.max(low, high);
        if (!integer) {
            return minimum + random.nextDouble() * (maximum - minimum);
        }
        minimum = Math.ceil(minimum);
        maximum = Math.floor(maximum);
        if (maximum < minimum) {
            return 0.0D;
        }
        double span = maximum - minimum + 1.0D;
        return minimum + Math.floor(random.nextDouble() * span);
    }

    static double timeOfDay(long dayTime) {
        return Math.floorMod(dayTime + 6000L, 24000L) / 24000.0D;
    }

    static double cardinalFacing2d(Direction direction) {
        return direction.get3DDataValue();
    }

    static double rotationToCamera(Vec3 origin, Vec3 camera, float fallbackPitch,
                                   float fallbackYaw, int axis) {
        Vec3 delta = camera.subtract(origin);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (delta.lengthSqr() <= 1.0E-12D) {
            return axis == 1 ? Mth.wrapDegrees(fallbackYaw) : fallbackPitch;
        }
        return axis == 1
                ? Mth.wrapDegrees(Math.toDegrees(Math.atan2(-delta.x, delta.z)))
                : -Math.toDegrees(Math.atan2(delta.y, horizontal));
    }

    private static boolean hasCape(LivingEntity entity) {
        return entity instanceof AbstractClientPlayer player
                && player.isModelPartShown(PlayerModelPart.CAPE)
                && player.getCloakTextureLocation() != null;
    }

    private static double capeFlapAmount(LivingEntity entity, float partialTick) {
        if (!(entity instanceof AbstractClientPlayer player)) {
            return 0.0D;
        }
        double dx = Mth.lerp(partialTick, player.xCloakO, player.xCloak)
                - Mth.lerp(partialTick, player.xOld, player.getX());
        double dy = Mth.lerp(partialTick, player.yCloakO, player.yCloak)
                - Mth.lerp(partialTick, player.yOld, player.getY());
        double dz = Mth.lerp(partialTick, player.zCloakO, player.zCloak)
                - Mth.lerp(partialTick, player.zOld, player.getZ());
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        double sine = Math.sin(Math.toRadians(bodyYaw));
        double negativeCosine = -Math.cos(Math.toRadians(bodyYaw));
        double vertical = clamp(dy * 10.0D, -6.0D, 32.0D);
        double forward = clamp((dx * sine + dz * negativeCosine) * 100.0D,
                0.0D, 150.0D);
        double crouchLift = player.isCrouching() ? 25.0D : 0.0D;
        return clamp((forward * 0.5D + vertical + crouchLift) / 107.0D,
                0.0D, 1.0D);
    }

    private static int equipmentCount(LivingEntity entity) {
        int count = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!entity.getItemBySlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean blinking(LivingEntity entity) {
        long phase = Math.floorMod(entity.tickCount
                + entity.getUUID().getLeastSignificantBits(), 80L);
        return entity.isSleeping() || phase < 3L;
    }

    private static double coordinate(double axis, double x, double y, double z) {
        return switch ((int) axis) {
            case 1 -> y;
            case 2 -> z;
            default -> x;
        };
    }

    static float officialHeadYaw(float headYaw, float bodyYaw) {
        return -Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -85.0F, 85.0F);
    }

    static float customBowAimYaw(float projectileYaw, float interpolatedViewYaw,
                                 boolean localPlayer) {
        return localPlayer ? projectileYaw : interpolatedViewYaw;
    }

    static float customBowRelativeHeadYaw(float projectileYaw,
                                          float interpolatedViewYaw,
                                          boolean localPlayer,
                                          float epicModelYaw) {
        return officialHeadYaw(customBowAimYaw(projectileYaw,
                interpolatedViewYaw, localPlayer), epicModelYaw);
    }

    static float officialHeadPitch(float headPitch) {
        return -headPitch;
    }

    static float yawSpeed(float currentYaw, float previousYaw) {
        return Mth.wrapDegrees(currentYaw - previousYaw) * 20.0F;
    }

    private static double arg(double[] values, int index) {
        return index < values.length ? values[index] : 0.0D;
    }

    private static String textArgument(String[] values, int index) {
        return values != null && index < values.length ? values[index] : null;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double flag(boolean value) {
        return value ? 1.0D : 0.0D;
    }
}
