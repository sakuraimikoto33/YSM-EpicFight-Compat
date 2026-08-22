package net.okitsu.ysmepicfightcompat.animation;

import org.lwjgl.stb.STBPerlin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session-local YSM physics values used only while sampling auxiliary animation tracks. */
final class AuxiliaryPhysicsRuntime {
    private static final double EPSILON = 1.0E-6D;
    private static final double MAX_DELTA_SECONDS = 0.25D;
    private static final int MAX_FILTERS = 256;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_SUBSTEPS = 1_024;

    private interface Filter {
        void update(double timeStep);

        void setArguments(double first, double second, double third, double fourth);

        double value();
    }

    private final Map<String, Filter> filters = new LinkedHashMap<>();

    void update(double timeStep) {
        if (!Double.isFinite(timeStep) || timeStep <= 0.0D) {
            return;
        }
        double boundedStep = Math.min(timeStep, MAX_DELTA_SECONDS);
        filters.values().forEach(filter -> filter.update(boundedStep));
    }

    double firstOrder(String key, double input, double response) {
        if (!validKey(key)) {
            return 0.0D;
        }
        double finiteInput = finite(input, 0.0D);
        double finiteResponse = finite(response, 1.0D);
        Filter filter = filters.get(key);
        if (filter == null) {
            if (!canCreate(key)) {
                return finiteInput;
            }
            filters.put(key, new FirstOrderFilter(finiteInput, finiteResponse));
            return finiteInput;
        }
        filter.setArguments(finiteInput, finiteResponse, 0.0D, 0.0D);
        return finite(filter.value(), finiteInput);
    }

    double secondOrder(String key, double input, double frequency,
                       double coefficient, double response) {
        if (!validKey(key)) {
            return 0.0D;
        }
        double finiteInput = finite(input, 0.0D);
        double finiteFrequency = finite(frequency, 1.0D);
        double finiteCoefficient = finite(coefficient, 1.0D);
        double finiteResponse = finite(response, 1.0D);
        Filter filter = filters.get(key);
        if (filter == null) {
            if (!canCreate(key)) {
                return finiteInput;
            }
            filters.put(key, new SecondOrderFilter(finiteInput, finiteFrequency,
                    finiteCoefficient, finiteResponse));
            return finiteInput;
        }
        filter.setArguments(finiteInput, finiteFrequency,
                finiteCoefficient, finiteResponse);
        return finite(filter.value(), finiteInput);
    }

    void reset() {
        filters.clear();
    }

    static double perlinNoise(double[] arguments) {
        if (arguments == null || arguments.length < 2) {
            return 0.0D;
        }
        int seed = (int) finite(arguments[0], 0.0D);
        float x = finiteFloat(arguments[1]);
        float y = arguments.length > 2 ? finiteFloat(arguments[2]) : 0.0F;
        float z = arguments.length > 3 ? finiteFloat(arguments[3]) : 0.0F;
        return finite(STBPerlin.stb_perlin_noise3_seed(
                x, y, z, 0, 0, 0, seed), 0.0D);
    }

    private boolean canCreate(String key) {
        return validKey(key) && filters.size() < MAX_FILTERS;
    }

    private static boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_LENGTH;
    }

    private static final class FirstOrderFilter implements Filter {
        private double input;
        private double response;
        private double lastSimulation;

        private FirstOrderFilter(double input, double response) {
            this.input = input;
            this.response = response;
        }

        @Override
        public void update(double timeStep) {
            if (Math.abs(response) <= EPSILON) {
                lastSimulation = input;
                return;
            }
            double weight = timeStep / response;
            lastSimulation = finite(
                    (1.0D - weight) * lastSimulation + weight * input, input);
        }

        @Override
        public void setArguments(double first, double second,
                                 double third, double fourth) {
            input = first;
            response = second;
        }

        @Override
        public double value() {
            return lastSimulation;
        }
    }

    private static final class SecondOrderFilter implements Filter {
        private double inputFunction;
        private double lastSimulation;
        private double lastSimulationDerivative;
        private double input;
        private double frequency;
        private double coefficient;
        private double response;

        private SecondOrderFilter(double input, double frequency,
                                  double coefficient, double response) {
            setArguments(input, frequency, coefficient, response);
        }

        @Override
        public void update(double timeStep) {
            double boundedFrequency = clamp(frequency, 0.0D, 5.0D);
            double boundedCoefficient = clamp(coefficient, 0.0D, 1.0D);
            if (boundedFrequency <= EPSILON) {
                inputFunction = input;
                lastSimulation = input;
                lastSimulationDerivative = 0.0D;
                return;
            }

            double k1 = boundedCoefficient / Math.PI / boundedFrequency;
            double angularTerm = 2.0D * Math.PI * boundedFrequency;
            double k2 = 1.0D / angularTerm / angularTerm;
            double k3 = response * boundedCoefficient / angularTerm;
            double inputDerivative = (input - inputFunction) / timeStep;
            inputFunction = input;

            double maximumStep = Math.sqrt(4.0D * k2 + k1 * k1) - k1;
            int cycles = !Double.isFinite(maximumStep) || maximumStep <= EPSILON
                    ? 1 : (int) Math.ceil(timeStep / maximumStep);
            cycles = Math.max(1, Math.min(MAX_SUBSTEPS, cycles));
            double step = timeStep / cycles;

            double nextValue = lastSimulation;
            double nextDerivative = lastSimulationDerivative;
            for (int cycle = 0; cycle < cycles; cycle++) {
                nextValue += step * nextDerivative;
                nextDerivative += step * (k3 * inputDerivative + input
                        - nextValue - k1 * nextDerivative) / k2;
                if (!Double.isFinite(nextValue) || !Double.isFinite(nextDerivative)) {
                    lastSimulation = input;
                    lastSimulationDerivative = 0.0D;
                    return;
                }
            }
            lastSimulation = nextValue;
            lastSimulationDerivative = nextDerivative;
        }

        @Override
        public void setArguments(double first, double second,
                                 double third, double fourth) {
            input = first;
            frequency = second;
            coefficient = third;
            response = fourth;
        }

        @Override
        public double value() {
            return lastSimulation;
        }
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static float finiteFloat(double value) {
        float result = (float) value;
        return Float.isFinite(result) ? result : 0.0F;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
