package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Render-thread Molang values used by auxiliary animations. */
final class EntityAnimationEnvironment implements ExpressionEngine.Environment {
    private static final double EPSILON = 0.0001D;

    private final LivingEntity entity;
    private final Map<Integer, Double> variables;
    private final Set<Integer> assigned;
    private final Random random;
    private final AuxiliaryPhysicsRuntime physics = new AuxiliaryPhysicsRuntime();
    private boolean firstPerson;
    private float partialTick;
    private double deltaTime;
    private double animationTime;
    private double lifeTime;
    private OfficialRoamingVariables.View roamingVariables;

    EntityAnimationEnvironment(LivingEntity entity, Map<Integer, Double> variables,
                               Set<Integer> assigned) {
        this.entity = entity;
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
    }

    void clipTime(double animationTime) {
        this.animationTime = animationTime;
    }

    void reset() {
        physics.reset();
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
        float bodyYaw = Mth.lerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float relativeHeadYaw = officialHeadYaw(headYaw, bodyYaw);
        float headPitch = officialHeadPitch(entity.getViewXRot(partialTick));
        return switch (name) {
            case "math.pi" -> Math.PI;
            case "math.e" -> Math.E;
            case "query.anim_time" -> animationTime;
            case "query.life_time" -> lifeTime;
            case "query.delta_time" -> deltaTime;
            case "query.health" -> entity.getHealth();
            case "query.max_health" -> entity.getMaxHealth();
            case "query.head_x_rotation", "ysm.head_yaw" -> relativeHeadYaw;
            case "query.head_y_rotation", "ysm.head_pitch" -> headPitch;
            case "query.body_y_rotation" -> Mth.wrapDegrees(bodyYaw);
            case "query.yaw_speed" -> yawSpeed(entity.getYRot(), entity.yRotO);
            case "query.ground_speed" -> horizontalSpeed;
            case "query.vertical_speed" -> entity.getDeltaMovement().y * 20.0D;
            case "query.hurt_time" -> entity.hurtTime;
            case "query.is_on_ground" -> flag(entity.onGround());
            case "query.is_alive" -> flag(entity.isAlive());
            case "query.is_in_water" -> flag(entity.isInWater());
            case "query.is_in_water_or_rain" -> flag(entity.isInWaterOrRain());
            case "query.is_on_fire" -> flag(entity.isOnFire());
            case "query.is_riding", "query.has_rider", "ysm.is_passenger" ->
                    flag(entity.isPassenger());
            case "query.is_sneaking", "ysm.is_sneak" -> flag(entity.isShiftKeyDown());
            case "query.is_sprinting" -> flag(entity.isSprinting());
            case "query.is_swimming" -> flag(entity.isSwimming());
            case "query.is_sleeping", "ysm.is_sleep" -> flag(entity.isSleeping());
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
            case "query.player_level" -> entity instanceof Player player
                    ? player.experienceLevel : 0.0D;
            case "query.time_stamp" -> entity.level().getGameTime() / 20.0D;
            case "query.time_of_day" ->
                    Math.floorMod(entity.level().getDayTime(), 24000L) / 24000.0D;
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
            case "ctrl.sleep" -> flag(entity.isSleeping());
            case "ctrl.swim" -> flag(entity.isSwimming());
            case "ctrl.fly" -> flag(entity instanceof Player player
                    && player.getAbilities().flying);
            case "ctrl.elytra_fly" -> flag(entity.isFallFlying());
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
            case "math.hermite", "math.hermite_blend" -> {
                double value = arg(arguments, 0);
                yield 3.0D * value * value - 2.0D * value * value * value;
            }
            case "math.min_angle" -> Math.floorMod((int) Math.round(arg(arguments, 0)) + 180,
                    360) - 180.0D;
            case "math.random" -> random(arg(arguments, 0), arg(arguments, 1), false);
            case "math.randomi", "math.random_integer" ->
                    random(arg(arguments, 0), arg(arguments, 1), true);
            case "query.position" -> coordinate(arg(arguments, 0),
                    entity.getX(), entity.getY(), entity.getZ());
            case "query.position_delta" -> coordinate(arg(arguments, 0),
                    entity.getX() - entity.xOld,
                    entity.getY() - entity.yOld,
                    entity.getZ() - entity.zOld);
            case "ysm.perlin_noise" -> AuxiliaryPhysicsRuntime.perlinNoise(arguments);
            default -> 0.0D;
        };
    }

    @Override
    public double invokeWithMixedArguments(String name, String[] textArguments,
                                           double[] numericArguments) {
        String function = name.toLowerCase(Locale.ROOT);
        String key = textArgument(textArguments, 0);
        if (key == null || numericArguments.length < 2) {
            return 0.0D;
        }
        return switch (function) {
            case "ysm.first_order" -> physics.firstOrder(key,
                    arg(numericArguments, 1), numericArguments.length > 2
                            ? arg(numericArguments, 2) : 1.0D);
            case "ysm.second_order" -> physics.secondOrder(key,
                    arg(numericArguments, 1), numericArguments.length > 2
                            ? arg(numericArguments, 2) : 1.0D,
                    numericArguments.length > 3 ? arg(numericArguments, 3) : 1.0D,
                    numericArguments.length > 4 ? arg(numericArguments, 4) : 1.0D);
            default -> invokeWithText(name, textArguments);
        };
    }

    @Override
    public double invokeWithText(String name, String[] arguments) {
        return 0.0D;
    }

    private double random(double low, double high, boolean integer) {
        double value = low + random.nextDouble() * Math.max(0.0D, high - low);
        return integer ? Math.floor(value) : value;
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
