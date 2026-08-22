package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Decodes Bedrock/YSM animation JSON used by automatic, parallel, and roulette playback. */
public final class BedrockAnimationParser {
    private static final Set<String> STATES = Set.of(
            "empty", "idle", "new_idle_empty", "walk", "run", "sneak", "sneaking",
            "swim", "swim_stand", "fly", "elytra_fly", "climb", "climbing",
            "ladder_up", "ladder_down", "ladder_stillness", "sit", "ride",
            "ride_pig", "boat", "sleep", "death", "use_mainhand", "use_offhand",
            "swing_hand", "swing_offhand", "attacked", "jump", "riptide");
    private static final Set<String> CONDITIONAL_ROOTS = Set.of(
            "hold_mainhand", "hold_offhand", "use_mainhand", "use_offhand",
            "swing", "swing_offhand", "head", "chest", "legs", "feet",
            "vehicle", "passenger");
    private static final Set<String> HAND_ITEM_STATES = Set.of(
            "hold_mainhand", "hold_offhand", "use_mainhand", "use_offhand",
            "swing", "swing_hand", "swing_offhand");
    private static final Set<String> HAND_ITEM_CONDITIONAL_ROOTS = Set.of(
            "hold_mainhand", "hold_offhand", "use_mainhand", "use_offhand",
            "swing", "swing_offhand");

    private BedrockAnimationParser() {
    }

    public static boolean isAutomatic(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("pre_parallel") || normalized.startsWith("parallel")
                || STATES.contains(normalized)) {
            return true;
        }
        int separator = firstConditionSeparator(normalized);
        return separator > 0 && CONDITIONAL_ROOTS.contains(normalized.substring(0, separator));
    }

    /**
     * Returns whether a clip is driven by a held item. These names stay reserved as automatic
     * clips so they cannot become roulette animations, but the Epic Fight renderer does not
     * compile or play them because Epic Fight owns held-item poses and rendering.
     */
    static boolean isHandItemAnimation(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (HAND_ITEM_STATES.contains(normalized)) {
            return true;
        }
        int separator = firstConditionSeparator(normalized);
        return separator > 0
                && HAND_ITEM_CONDITIONAL_ROOTS.contains(normalized.substring(0, separator));
    }

    private static int firstConditionSeparator(String name) {
        int result = -1;
        for (char separator : new char[]{':', '$', '#'}) {
            int candidate = name.indexOf(separator);
            if (candidate >= 0 && (result < 0 || candidate < result)) {
                result = candidate;
            }
        }
        return result;
    }

    public static AnimationClip parse(String name, JsonObject source) {
        AnimationClip clip = new AnimationClip(name);
        JsonElement loop = source.get("loop");
        if (loop != null && loop.isJsonPrimitive()) {
            JsonPrimitive primitive = loop.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                clip.playback(primitive.getAsBoolean()
                        ? AnimationClip.Playback.REPEAT : AnimationClip.Playback.ONCE);
            } else if ("hold_on_last_frame".equals(primitive.getAsString())) {
                clip.playback(AnimationClip.Playback.HOLD_LAST_FRAME);
            }
        }
        if (source.has("animation_length")) {
            clip.duration(source.get("animation_length").getAsFloat());
        }
        readScalar(source.get("blend_weight"), clip.blendWeight());
        readBones(source.getAsJsonObject("bones"), clip);
        readTimeline(source.getAsJsonObject("timeline"), clip);
        readSoundEffects(source.getAsJsonObject("sound_effects"), clip);
        return clip;
    }

    private static void readScalar(JsonElement source, AnimationClip.ScalarValue target) {
        if (source == null || !source.isJsonPrimitive()) {
            return;
        }
        JsonPrimitive primitive = source.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            target.setConstant(primitive.getAsDouble());
        } else if (primitive.isString()) {
            target.setExpression(primitive.getAsString());
        }
    }

    private static void readBones(JsonObject bones, AnimationClip clip) {
        if (bones == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject source = entry.getValue().getAsJsonObject();
            AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
            tracks.rotation(readTrack(source.get("rotation")));
            tracks.position(readTrack(source.get("position")));
            tracks.scale(readTrack(source.get("scale")));
            if (tracks.hasAnyTrack()) {
                clip.boneTracks().put(entry.getKey(), tracks);
            }
        }
    }

    private static AnimationClip.Track readTrack(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return null;
        }
        AnimationClip.Track track = new AnimationClip.Track();
        if (source.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                Float time = parseTime(entry.getKey());
                if (time == null) {
                    continue;
                }
                JsonElement valueSource = entry.getValue();
                AnimationClip.VectorValue value;
                AnimationClip.VectorValue incoming = null;
                AnimationClip.Interpolation interpolation = AnimationClip.Interpolation.LINEAR;
                if (valueSource.isJsonObject()) {
                    JsonObject key = valueSource.getAsJsonObject();
                    value = readVector(key.get("post"));
                    incoming = readVector(key.get("pre"));
                    String mode = key.has("lerp_mode") ? key.get("lerp_mode").getAsString() : "";
                    if (mode.equals("step")) {
                        interpolation = AnimationClip.Interpolation.STEP;
                    } else if (mode.equals("catmullrom")) {
                        interpolation = AnimationClip.Interpolation.CATMULL_ROM;
                    }
                } else {
                    value = readVector(valueSource);
                }
                if (value != null) {
                    track.keyframes().add(new AnimationClip.Keyframe(
                            time, interpolation, value, incoming));
                }
            }
        } else {
            AnimationClip.VectorValue value = readVector(source);
            if (value != null) {
                track.keyframes().add(new AnimationClip.Keyframe(0.0F,
                        AnimationClip.Interpolation.LINEAR, value, null));
            }
        }
        track.keyframes().sort(Comparator.comparing(AnimationClip.Keyframe::time));
        return track.keyframes().isEmpty() ? null : track;
    }

    private static AnimationClip.VectorValue readVector(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return null;
        }
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        if (source.isJsonPrimitive()) {
            JsonPrimitive primitive = source.getAsJsonPrimitive();
            for (int axis = 0; axis < 3; axis++) {
                if (primitive.isNumber()) {
                    value.setConstant(axis, primitive.getAsDouble());
                } else {
                    value.setExpression(axis, primitive.getAsString());
                }
            }
            return value;
        }
        if (!source.isJsonArray()) {
            return null;
        }
        JsonArray axes = source.getAsJsonArray();
        for (int axis = 0; axis < 3; axis++) {
            JsonElement element = axis < axes.size() ? axes.get(axis) : null;
            if (element != null && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isNumber()) {
                value.setConstant(axis, element.getAsDouble());
            } else if (element != null && !element.isJsonNull()) {
                value.setExpression(axis, element.getAsString());
            } else {
                value.setConstant(axis, 0.0D);
            }
        }
        return value;
    }

    private static void readTimeline(JsonObject source, AnimationClip clip) {
        if (source == null) {
            return;
        }
        List<AnimationClip.TimelineEvent> events = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            Float time = parseTime(entry.getKey());
            if (time == null) {
                continue;
            }
            List<String> statements = new ArrayList<>();
            if (entry.getValue().isJsonArray()) {
                entry.getValue().getAsJsonArray().forEach(value -> statements.add(value.getAsString()));
            } else {
                statements.add(entry.getValue().getAsString());
            }
            events.add(new AnimationClip.TimelineEvent(time, statements));
        }
        events.sort(Comparator.comparing(AnimationClip.TimelineEvent::time));
        clip.timeline().addAll(events);
    }

    private static void readSoundEffects(JsonObject source, AnimationClip clip) {
        if (source == null) {
            return;
        }
        List<AnimationClip.SoundEvent> events = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            Float time = parseTime(entry.getKey());
            if (time == null || !Float.isFinite(time) || time < 0.0F) {
                continue;
            }
            JsonElement value = entry.getValue();
            String effect = "";
            if (value.isJsonObject()) {
                JsonElement named = value.getAsJsonObject().get("effect");
                if (named != null && named.isJsonPrimitive()) {
                    effect = named.getAsString();
                }
            } else if (value.isJsonPrimitive()) {
                effect = value.getAsString();
            }
            if (!effect.isBlank()) {
                events.add(new AnimationClip.SoundEvent(time, effect));
            }
        }
        events.sort(Comparator.comparing(AnimationClip.SoundEvent::time));
        clip.soundEffects().addAll(events);
    }

    private static Float parseTime(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
