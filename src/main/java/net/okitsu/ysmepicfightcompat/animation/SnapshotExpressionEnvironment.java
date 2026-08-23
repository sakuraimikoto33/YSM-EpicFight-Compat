package net.okitsu.ysmepicfightcompat.animation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Immutable render-thread capture used by worker-side, side-effect-free Molang evaluation. */
final class SnapshotExpressionEnvironment implements ExpressionEngine.Environment {
    private static final double EPSILON = 0.0001D;
    private static final int ANIMATION_TIME = ExpressionEngine.querySlot("query.anim_time");

    private final Map<Integer, Double> variables;
    private final Set<Integer> assigned;
    private final Map<Integer, Double> queries;
    private double animationTime;

    private SnapshotExpressionEnvironment(Map<Integer, Double> variables,
                                          Set<Integer> assigned,
                                          Map<Integer, Double> queries) {
        this.variables = new HashMap<>(variables);
        this.assigned = Set.copyOf(assigned);
        this.queries = Map.copyOf(queries);
    }

    static SnapshotExpressionEnvironment capture(ExpressionEngine.Environment source,
                                                 Set<Integer> variableSlots,
                                                 Set<Integer> querySlots) {
        Map<Integer, Double> variables = new HashMap<>();
        java.util.HashSet<Integer> assigned = new java.util.HashSet<>();
        for (int slot : variableSlots) {
            variables.put(slot, finite(source.readVariable(slot)));
            if (source.hasVariable(slot)) {
                assigned.add(slot);
            }
        }
        Map<Integer, Double> queries = new HashMap<>();
        for (int slot : querySlots) {
            if (slot != ANIMATION_TIME) {
                queries.put(slot, finite(source.readQuery(slot)));
            }
        }
        return new SnapshotExpressionEnvironment(variables, assigned, queries);
    }

    SnapshotExpressionEnvironment withVariables(Map<Integer, Double> overlay) {
        Map<Integer, Double> combined = new HashMap<>(variables);
        combined.putAll(overlay);
        java.util.HashSet<Integer> present = new java.util.HashSet<>(assigned);
        present.addAll(overlay.keySet());
        return new SnapshotExpressionEnvironment(combined, present, queries);
    }

    void clipTime(double value) {
        animationTime = finite(value);
    }

    @Override
    public double readVariable(int slot) {
        return variables.getOrDefault(slot, 0.0D);
    }

    @Override
    public boolean hasVariable(int slot) {
        return assigned.contains(slot);
    }

    @Override
    public void writeVariable(int slot, double value) {
        // Worker evaluation is only enabled for expressions without assignments.
        variables.put(slot, finite(value));
    }

    @Override
    public double readQuery(int slot) {
        return slot == ANIMATION_TIME ? animationTime : queries.getOrDefault(slot, 0.0D);
    }

    @Override
    public double invoke(String name, double[] arguments) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
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
            case "math.lerprotate", "math.lerprotatee" ->
                    EntityAnimationEnvironment.lerpRotate(
                            arg(arguments, 0), arg(arguments, 1), arg(arguments, 2));
            case "math.hermite", "math.hermite_blend" -> {
                double value = arg(arguments, 0);
                yield 3.0D * value * value - 2.0D * value * value * value;
            }
            case "math.min_angle" ->
                    EntityAnimationEnvironment.minimumAngle(arg(arguments, 0));
            case "ysm.perlin_noise" -> AuxiliaryPhysicsRuntime.perlinNoise(arguments);
            default -> 0.0D;
        };
    }

    @Override
    public double invokeWithText(String name, String[] arguments) {
        return 0.0D;
    }

    @Override
    public double invokeWithMixedArguments(String name, String[] textArguments,
                                           double[] numericArguments) {
        return 0.0D;
    }

    private static double arg(double[] values, int index) {
        return values != null && index >= 0 && index < values.length ? values[index] : 0.0D;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }
}
