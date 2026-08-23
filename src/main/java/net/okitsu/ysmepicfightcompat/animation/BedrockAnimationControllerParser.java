package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Boundary-checked parser for the supported Bedrock animation controller data used by YSM. */
public final class BedrockAnimationControllerParser {
    private static final int MAX_CONTROLLERS = 4_096;
    private static final int MAX_STATES = 65_536;
    private static final int MAX_STATE_ENTRIES = 1_000_000;
    private static final int MAX_EXPRESSION_CHARS = 16 * 1024;

    private BedrockAnimationControllerParser() {
    }

    public static Map<String, AnimationController> parse(JsonObject root) {
        JsonObject source = object(root, "animation_controllers");
        if (source == null) {
            return Map.of();
        }
        require(source.size() <= MAX_CONTROLLERS, "Too many animation controllers");
        Map<String, AnimationController> result = new LinkedHashMap<>();
        long states = 0;
        long entries = 0;
        for (Map.Entry<String, JsonElement> controllerEntry : source.entrySet()) {
            if (!controllerEntry.getValue().isJsonObject()) {
                continue;
            }
            String name = text(controllerEntry.getKey());
            JsonObject controllerSource = controllerEntry.getValue().getAsJsonObject();
            String initial = string(controllerSource.get("initial_state"), "default");
            JsonObject stateSources = object(controllerSource, "states");
            Map<String, AnimationController.State> controllerStates = new LinkedHashMap<>();
            if (stateSources != null) {
                states += stateSources.size();
                require(states <= MAX_STATES, "Too many animation controller states");
                for (Map.Entry<String, JsonElement> stateEntry : stateSources.entrySet()) {
                    if (!stateEntry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject state = stateEntry.getValue().getAsJsonObject();
                    List<AnimationController.AnimationReference> animations =
                            animations(state.get("animations"));
                    List<AnimationController.Transition> transitions =
                            transitions(state.get("transitions"));
                    List<String> onEntry = actions(state.get("on_entry"));
                    List<String> onExit = actions(state.get("on_exit"));
                    List<String> soundEffects = soundEffects(state.get("sound_effects"));
                    List<AnimationController.StateVariable> variables =
                            variables(state.get("variables"));
                    List<DeclarativeParticleEffect> particleEffects =
                            particleEffects(state.get("particle_effects"));
                    entries += animations.size() + transitions.size()
                            + onEntry.size() + onExit.size() + soundEffects.size()
                            + variables.size() + particleEffects.size()
                            + variables.stream().mapToLong(
                            variable -> variable.remapCurve().size()).sum();
                    require(entries <= MAX_STATE_ENTRIES,
                            "Too many animation controller entries");
                    String stateName = text(stateEntry.getKey());
                    controllerStates.put(stateName, new AnimationController.State(
                            stateName, animations, transitions, onEntry, onExit, soundEffects,
                            variables, particleEffects,
                            blend(state.get("blend_transition")),
                            bool(state.get("blend_via_shortest_path"))));
                }
            }
            result.putIfAbsent(name, new AnimationController(name, initial, controllerStates));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static List<AnimationController.AnimationReference> animations(JsonElement source) {
        if (source == null || !source.isJsonArray()) {
            return List.of();
        }
        List<AnimationController.AnimationReference> result = new ArrayList<>();
        for (JsonElement entry : source.getAsJsonArray()) {
            if (entry.isJsonPrimitive()) {
                result.add(new AnimationController.AnimationReference(
                        text(entry.getAsString()), "1"));
            } else if (entry.isJsonObject()) {
                for (Map.Entry<String, JsonElement> weighted : entry.getAsJsonObject().entrySet()) {
                    result.add(new AnimationController.AnimationReference(
                            text(weighted.getKey()), string(weighted.getValue(), "0")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<AnimationController.Transition> transitions(JsonElement source) {
        if (source == null || !source.isJsonArray()) {
            return List.of();
        }
        List<AnimationController.Transition> result = new ArrayList<>();
        for (JsonElement entry : source.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> transition : entry.getAsJsonObject().entrySet()) {
                result.add(new AnimationController.Transition(
                        text(transition.getKey()), string(transition.getValue(), "0")));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> actions(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (source.isJsonArray()) {
            source.getAsJsonArray().forEach(value -> result.add(string(value, "")));
        } else if (source.isJsonPrimitive()) {
            result.add(string(source, ""));
        }
        return result.stream().filter(value -> !value.isBlank()).toList();
    }

    private static List<String> soundEffects(JsonElement source) {
        if (source == null || !source.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement entry : source.getAsJsonArray()) {
            if (entry.isJsonObject()) {
                JsonElement effect = entry.getAsJsonObject().get("effect");
                if (effect != null && effect.isJsonPrimitive()) {
                    result.add(text(effect.getAsString()));
                }
            } else if (entry.isJsonPrimitive()) {
                result.add(text(entry.getAsString()));
            }
        }
        return result.stream().filter(value -> !value.isBlank()).toList();
    }

    private static List<AnimationController.StateVariable> variables(JsonElement source) {
        if (source == null || !source.isJsonObject()) {
            return List.of();
        }
        List<AnimationController.StateVariable> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
            String name = text(entry.getKey());
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                result.add(new AnimationController.StateVariable(
                        name, string(value, "0"), List.of()));
                continue;
            }
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            String input = string(object.get("input"), "0");
            List<AnimationController.RemapPoint> curve = new ArrayList<>();
            JsonObject curveSource = object(object, "remap_curve");
            if (curveSource != null) {
                for (Map.Entry<String, JsonElement> point : curveSource.entrySet()) {
                    try {
                        float coordinate = Float.parseFloat(point.getKey());
                        float output = point.getValue().getAsFloat();
                        if (Float.isFinite(coordinate) && Float.isFinite(output)) {
                            curve.add(new AnimationController.RemapPoint(coordinate, output));
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
                curve.sort(Comparator.comparing(AnimationController.RemapPoint::input));
            }
            result.add(new AnimationController.StateVariable(name, input, curve));
        }
        return List.copyOf(result);
    }

    private static List<DeclarativeParticleEffect> particleEffects(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return List.of();
        }
        List<DeclarativeParticleEffect> result = new ArrayList<>();
        if (source.isJsonArray()) {
            source.getAsJsonArray().forEach(value -> addParticle(result, value));
        } else {
            addParticle(result, source);
        }
        return List.copyOf(result);
    }

    private static void addParticle(List<DeclarativeParticleEffect> result,
                                    JsonElement source) {
        DeclarativeParticleEffect particle = BedrockAnimationParser.particle(source);
        if (particle != null && !particle.effect().isBlank()) {
            text(particle.effect());
            text(particle.locator());
            text(particle.preEffectScript());
            result.add(particle);
        }
    }

    private static AnimationController.BlendTransition blend(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return new AnimationController.BlendTransition(0.0F, List.of());
        }
        if (source.isJsonPrimitive() && source.getAsJsonPrimitive().isNumber()) {
            return new AnimationController.BlendTransition(source.getAsFloat(), List.of());
        }
        if (!source.isJsonObject()) {
            return new AnimationController.BlendTransition(0.0F, List.of());
        }
        List<AnimationController.BlendPoint> points = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
            try {
                float time = Float.parseFloat(entry.getKey());
                float value = entry.getValue().getAsFloat();
                if (Float.isFinite(time) && time >= 0.0F && Float.isFinite(value)) {
                    points.add(new AnimationController.BlendPoint(time, value));
                }
            } catch (RuntimeException ignored) {
            }
        }
        points.sort(Comparator.comparing(AnimationController.BlendPoint::time));
        return new AnimationController.BlendTransition(0.0F, points);
    }

    private static JsonObject object(JsonObject source, String name) {
        JsonElement value = source == null ? null : source.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonElement value, String fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        return text(value.getAsString());
    }

    private static String text(String value) {
        String result = value == null ? "" : value;
        require(result.length() <= MAX_EXPRESSION_CHARS,
                "Animation controller text is too long");
        return result;
    }

    private static boolean bool(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
