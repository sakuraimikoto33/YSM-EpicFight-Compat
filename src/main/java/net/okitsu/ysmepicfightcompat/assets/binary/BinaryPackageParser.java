package net.okitsu.ysmepicfightcompat.assets.binary;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.animation.AnimationController;
import net.okitsu.ysmepicfightcompat.animation.BedrockAnimationParser;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.assets.ModelFunctionAssets;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Boundary-checked decoder for decrypted YSM binary model payloads. */
public final class BinaryPackageParser {
    private static final int SUB_TEXTURE_NORMAL = 1;
    private static final int SUB_TEXTURE_SPECULAR = 2;
    private static final int MAX_ITEMS = 1_000_000;
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BLOB_BYTES = 128 * 1024 * 1024;
    private static final int MAX_CONTROLLERS = 4_096;
    private static final int MAX_CONTROLLER_STATES = 65_536;
    private static final int MAX_CONTROLLER_ENTRIES = 1_000_000;

    private BinaryPackageParser() {
    }

    public static ModelBundle parse(String modelId, byte[] payload) {
        Cursor input = new Cursor(payload);
        int format = input.littleEndianInt();
        ModelBundle result = new ModelBundle(modelId);
        if (format < 4) {
            readEarlyLegacy(input, format, result);
        } else if (format <= 15) {
            readLateLegacy(input, format, result);
        } else {
            readModern(input, format, result);
        }
        if (result.geometry() == null) {
            throw new IllegalStateException("YSM package has no player geometry");
        }
        return result;
    }

    private static void readEarlyLegacy(Cursor input, int format, ModelBundle result) {
        input.skip(input.varUInt("legacy header bytes"));
        repeat(input.count("legacy model"), ignored -> {
            int type = input.varUInt("legacy model id");
            require(input.varUInt("legacy model marker") == 1, "Invalid legacy model marker");
            GeometryDocument geometry = readGeometry(input, type == 1);
            if (geometry != null) {
                result.geometry(geometry);
            }
        });
        repeat(input.count("legacy animation block"), ignored -> {
            input.varUInt("legacy animation id");
            require(input.varUInt("legacy animation marker") == 1, "Invalid legacy animation marker");
            readAnimations(input, format, result);
        });
        repeat(input.count("legacy texture"), ignored -> {
            String name = input.text();
            require(input.varUInt("legacy texture marker") == 1, "Invalid legacy texture marker");
            byte[] bytes = input.blob();
            int width = input.varUInt("texture width");
            int height = input.varUInt("texture height");
            storeTexture(result, name, bytes, width, height, -1);
        });
        repeat(input.count("legacy model lookup"), ignored -> {
            input.varUInt("legacy model lookup id");
            input.text();
        });
        repeat(input.count("legacy animation lookup"), ignored -> {
            input.varUInt("legacy animation lookup id");
            input.text();
        });
        repeat(input.count("legacy texture lookup"), ignored -> {
            input.text();
            input.text();
        });
        input.text();
    }

    private static void readLateLegacy(Cursor input, int format, ModelBundle result) {
        input.skip(input.varUInt("legacy header bytes"));
        repeat(input.count("legacy model"), ignored -> {
            int type = input.varUInt("legacy model id");
            require(input.varUInt("legacy model marker") == 1, "Invalid legacy model marker");
            GeometryDocument geometry = readGeometry(input, type == 1);
            if (geometry != null) {
                result.geometry(geometry);
            }
        });
        repeat(input.count("legacy animation block"), ignored -> {
            input.varUInt("legacy animation id");
            require(input.varUInt("legacy animation marker") == 1, "Invalid legacy animation marker");
            readAnimations(input, format, result);
        });
        if (format > 9) {
            readControllers(input, format, result);
            repeat(input.count("controller lookup"), ignored -> {
                input.text();
                input.text();
            });
        }
        repeat(input.count("legacy texture"), ignored -> {
            String name = input.text();
            byte[] bytes = input.blob();
            int width = input.varUInt("texture width");
            int height = input.varUInt("texture height");
            ModelBundle.EncodedTexture normal = null;
            ModelBundle.EncodedTexture specular = null;
            int subTextures = input.count("legacy sub-texture");
            for (int child = 0; child < subTextures; child++) {
                int type = input.varUInt("sub-texture id");
                boolean retained = type == SUB_TEXTURE_NORMAL
                        || type == SUB_TEXTURE_SPECULAR;
                if (type == SUB_TEXTURE_NORMAL) {
                    require(normal == null, "Duplicate normal sub-texture");
                } else if (type == SUB_TEXTURE_SPECULAR) {
                    require(specular == null, "Duplicate specular sub-texture");
                }
                byte[] subBytes = retained ? input.blob() : null;
                if (!retained) {
                    input.skipBlob();
                }
                int subWidth = input.varUInt("sub-texture width");
                int subHeight = input.varUInt("sub-texture height");
                if (retained) {
                    ModelBundle.EncodedTexture texture = new ModelBundle.EncodedTexture(
                            subBytes, new ModelBundle.TextureInfo(subWidth, subHeight, -1));
                    if (type == SUB_TEXTURE_NORMAL) {
                        normal = texture;
                    } else {
                        specular = texture;
                    }
                }
            }
            storeTexture(result, name, bytes, width, height, -1);
            storePbrTextures(result, name, normal, specular);
        });
        if (format > 9) {
            skipSounds(input, format);
            repeat(input.count("sound lookup"), ignored -> {
                input.text();
                input.text();
            });
        }
        repeat(input.count("extra texture"), ignored -> {
            String name = input.text();
            byte[] bytes = input.blob();
            int width = input.varUInt("extra texture width");
            int height = input.varUInt("extra texture height");
            storeTexture(result, name, bytes, width, height, -1);
        });
        repeat(input.count("legacy model lookup"), ignored -> {
            input.varUInt("legacy model lookup id");
            input.text();
        });
        repeat(input.count("legacy animation lookup"), ignored -> {
            input.varUInt("legacy animation lookup id");
            input.text();
        });
        repeat(input.count("legacy texture lookup"), ignored -> {
            input.text();
            input.text();
            repeat(input.count("sub-texture lookup"), child -> {
                input.varUInt("sub-texture lookup id");
                input.text();
            });
        });
        readProperties(input, format, result);
    }

    private static void readModern(Cursor input, int format, ModelBundle result) {
        skipSounds(input, format);
        readFunctions(input, result);
        skipLanguages(input);
        if (format < 26) {
            repeat(input.count("sub-entity"), ignored -> skipSubEntity(input, format));
            input.varUInt("sub-entity separator");
        } else {
            repeat(input.count("vehicle"), ignored -> skipSubEntity(input, format));
            repeat(input.count("projectile"), ignored -> skipSubEntity(input, format));
        }
        require(input.varUInt("entity marker") == 1, "Invalid entity marker");
        repeat(input.count("animation file"), ignored -> {
            input.varUInt("animation file id");
            input.text();
            readAnimations(input, format, result);
        });
        readControllers(input, format, result);
        repeat(input.count("texture"), ignored -> readModernTexture(input, result));
        repeat(input.count("model"), ignored -> {
            int type = input.varUInt("model type");
            input.text();
            GeometryDocument geometry = readGeometry(input, type == 1);
            if (geometry != null) {
                result.geometry(geometry);
            }
        });
        readProperties(input, format, result);
    }

    private static void readModernTexture(Cursor input, ModelBundle result) {
        String name = input.text();
        input.text();
        byte[] bytes = input.blob();
        int width = input.varUInt("texture width");
        int height = input.varUInt("texture height");
        int format = input.varUInt("texture format");
        input.varUInt("texture flags");
        ModelBundle.EncodedTexture normal = null;
        ModelBundle.EncodedTexture specular = null;
        int subTextures = input.count("sub-texture");
        for (int ignored = 0; ignored < subTextures; ignored++) {
            int type = input.varUInt("sub-texture id");
            input.text();
            boolean retained = type == SUB_TEXTURE_NORMAL
                    || type == SUB_TEXTURE_SPECULAR;
            if (type == SUB_TEXTURE_NORMAL) {
                require(normal == null, "Duplicate normal sub-texture");
            } else if (type == SUB_TEXTURE_SPECULAR) {
                require(specular == null, "Duplicate specular sub-texture");
            }
            byte[] subBytes = retained ? input.blob() : null;
            if (!retained) {
                input.skipBlob();
            }
            int subWidth = input.varUInt("sub-texture width");
            int subHeight = input.varUInt("sub-texture height");
            int subFormat = input.varUInt("sub-texture format");
            input.varUInt("sub-texture flags");
            if (retained) {
                ModelBundle.EncodedTexture texture = new ModelBundle.EncodedTexture(
                        subBytes, new ModelBundle.TextureInfo(
                        subWidth, subHeight, subFormat));
                if (type == SUB_TEXTURE_NORMAL) {
                    normal = texture;
                } else {
                    specular = texture;
                }
            }
        }
        storeTexture(result, name, bytes, width, height, format);
        storePbrTextures(result, name, normal, specular);
    }

    private static GeometryDocument readGeometry(Cursor input, boolean retain) {
        GeometryDocument document = retain ? new GeometryDocument() : null;
        int bones = input.count("geometry bone");
        for (int boneIndex = 0; boneIndex < bones; boneIndex++) {
            String parent = input.text();
            GeometryDocument.Bone bone = retain ? new GeometryDocument.Bone("") : null;
            int cubes = input.count("geometry cube");
            for (int cubeIndex = 0; cubeIndex < cubes; cubeIndex++) {
                int faces = input.count("geometry face");
                for (int faceIndex = 0; faceIndex < faces; faceIndex++) {
                    if (retain) {
                        bone.faces().add(readFace(input));
                    } else {
                        input.skip(12 + 4 * 20);
                    }
                }
                input.varUInt("cube texture width");
                input.varUInt("cube texture height");
                input.varUInt("cube flags");
            }
            String name = input.text();
            for (int ignored = 0; ignored < 5; ignored++) {
                input.varUInt("bone flags");
            }
            float pivotX = input.number();
            float pivotY = input.number();
            float pivotZ = input.number();
            float rotationX = input.number();
            float rotationY = input.number();
            float rotationZ = input.number();
            if (retain) {
                GeometryDocument.Bone completed = new GeometryDocument.Bone(name);
                completed.parentName(parent);
                completed.faces().addAll(bone.faces());
                completed.pivot(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
                completed.rotation(rotationX, rotationY, rotationZ);
                document.add(completed);
            }
        }
        input.text();
        input.skip(4 * Float.BYTES);
        input.skip(input.count("visible bounds offset") * Float.BYTES);
        input.skip(2 * Float.BYTES);
        if (input.varUInt("legacy info flag") > 0) {
            skipLegacyInfo(input);
        }
        input.varUInt("geometry trailing flag");
        input.varUInt("geometry trailing flag");
        input.varUInt("geometry trailing flag");
        if (document != null) {
            document.linkHierarchy();
        }
        return document;
    }

    private static GeometryDocument.Face readFace(Cursor input) {
        Vector3f normal = new Vector3f(input.number(), input.number(), input.number());
        Vector3f[] positions = new Vector3f[4];
        float[][] uv = new float[4][2];
        for (int corner = 0; corner < 4; corner++) {
            positions[corner] = new Vector3f(input.number(), input.number(), input.number());
            uv[corner][0] = input.number();
            uv[corner][1] = input.number();
        }
        return new GeometryDocument.Face(positions, uv, normal);
    }

    private static void readAnimations(Cursor input, int format, ModelBundle result) {
        repeat(input.count("animation"), ignored -> {
            String name = input.text();
            // YSM's binary animation section stores its timeline in ticks.
            // Keep duration in the same seconds unit used by retained keyframes.
            float duration = input.number() / 20.0F;
            int playback = input.varUInt("animation playback");
            boolean retain = true;
            AnimationClip clip = retain ? new AnimationClip(name) : null;
            if (retain) {
                clip.duration(duration);
                clip.playback(AnimationClip.Playback.fromWireValue(playback));
            }
            readBlendSettings(input, format, retain ? clip.blendWeight() : null);
            repeat(input.count("animation bone"), boneIndex -> {
                String boneName = input.text();
                AnimationClip.Track rotation = readTrack(input, retain);
                AnimationClip.Track position = readTrack(input, retain);
                AnimationClip.Track scale = readTrack(input, retain);
                if (retain) {
                    AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
                    tracks.rotation(rotation);
                    tracks.position(position);
                    tracks.scale(scale);
                    if (tracks.hasAnyTrack()) {
                        clip.boneTracks().put(boneName, tracks);
                    }
                }
            });
            repeat(input.count("timeline group"), eventIndex -> {
                int statements = input.count("timeline statement");
                java.util.List<String> code = retain
                        ? new java.util.ArrayList<>(statements) : null;
                for (int i = 0; i < statements; i++) {
                    String statement = input.text();
                    if (retain) {
                        code.add(statement);
                    }
                }
                float time = input.number() / 20.0F;
                if (retain && !code.isEmpty()) {
                    clip.timeline().add(new AnimationClip.TimelineEvent(time, code));
                }
            });
            if (format > 9) {
                repeat(input.count("animation sound"), sound -> {
                    String effect = input.text();
                    float time = input.number() / 20.0F;
                    if (retain && !effect.isBlank() && Float.isFinite(time) && time >= 0.0F) {
                        clip.soundEffects().add(new AnimationClip.SoundEvent(time, effect));
                    }
                });
            }
            if (retain) {
                result.animations().put(name, clip);
            }
        });
    }

    private static AnimationClip.Track readTrack(Cursor input, boolean retain) {
        int keyframes = input.count("animation keyframe");
        AnimationClip.Track result = retain && keyframes > 0 ? new AnimationClip.Track() : null;
        for (int keyframe = 0; keyframe < keyframes; keyframe++) {
            float time = input.number() / 20.0F;
            int interpolation = input.varUInt("animation interpolation");
            AnimationClip.VectorValue value = readVector(input, retain);
            AnimationClip.VectorValue incoming = input.varUInt("incoming keyframe flag") > 0
                    ? readVector(input, retain) : null;
            if (retain) {
                result.keyframes().add(new AnimationClip.Keyframe(time,
                        AnimationClip.Interpolation.fromWireValue(interpolation), value, incoming));
            }
        }
        return result;
    }

    private static AnimationClip.VectorValue readVector(Cursor input, boolean retain) {
        AnimationClip.VectorValue result = retain ? new AnimationClip.VectorValue() : null;
        for (int axis = 0; axis < 3; axis++) {
            int type = input.unsignedByte();
            if (type == 1) {
                float value = input.number();
                if (retain) {
                    result.setConstant(axis, value);
                }
            } else if (type == 2) {
                String expression = input.text();
                if (retain) {
                    result.setExpression(axis, expression);
                }
            } else if (retain) {
                result.setConstant(axis, 0.0D);
            }
        }
        return result;
    }

    private static void readBlendSettings(Cursor input, int format,
                                          AnimationClip.ScalarValue target) {
        if (format <= 9) {
            return;
        }
        input.varUInt("animation blend flag");
        input.varUInt("animation blend flag");
        int values = input.count("blend weight");
        for (int index = 0; index < values; index++) {
            int type = input.unsignedByte();
            if (type == 1) {
                float value = input.number();
                if (target != null && values == 1) {
                    target.setConstant(value);
                }
            } else if (type == 2) {
                String expression = input.text();
                if (target != null && values == 1) {
                    target.setExpression(expression);
                }
            }
        }
        input.varUInt("animation blend trailing flag");
    }

    private static void readProperties(Cursor input, int format, ModelBundle result) {
        input.text();
        boolean richMetadata = input.varUInt("metadata version flag") != 0;
        if (richMetadata) {
            if (format <= 15) {
                input.varUInt("legacy metadata flag");
            }
            for (int ignored = 0; ignored < 4; ignored++) {
                input.text();
            }
            repeat(input.count("author"), ignored -> {
                input.text();
                input.text();
                repeat(input.count("author contact"), contact -> {
                    input.text();
                    input.text();
                });
                input.text();
            });
            repeat(input.count("model link"), ignored -> {
                input.text();
                input.text();
            });
        }
        result.scales(input.number(), input.number());
        repeat(input.count("extra animation"), ignored -> {
            input.text();
            input.text();
        });
        if (format > 9) {
            repeat(input.count("animation button"), ignored -> {
                input.text();
                input.text();
                input.varUInt("animation button flag");
                repeat(input.count("configuration form"), form -> {
                    for (int field = 0; field < 4; field++) {
                        input.text();
                    }
                    input.skip(3 * Float.BYTES);
                    repeat(input.count("configuration label"), label -> {
                        input.text();
                        input.text();
                    });
                });
            });
            repeat(input.count("animation classification"), ignored -> {
                input.text();
                repeat(input.count("classification entry"), entry -> {
                    input.text();
                    input.text();
                });
            });
        }
        result.defaultTexture(input.text());
        input.text();
        input.varUInt("property flag");
        if (format > 4) {
            input.varUInt("property flag");
        }
        if (format >= 15) {
            input.varUInt("property flag");
            input.varUInt("property flag");
        }
        if (format > 15) {
            input.varUInt("property flag");
            if (format >= 32) {
                // Its semantic identity has not been verified against a matched
                // official folder/package fixture. Do not infer multiline merging
                // from the introduction version alone; keep the bundle default.
                input.varUInt("property flag");
            }
            input.text();
            input.text();
            repeat(input.count("avatar"), ignored -> {
                input.text();
                input.skipBlob();
                for (int flag = 0; flag < 4; flag++) {
                    input.varUInt("avatar property");
                }
            });
            repeat(input.count("background image"), ignored -> {
                input.text();
                input.skipBlob();
                for (int flag = 0; flag < 4; flag++) {
                    input.varUInt("background image property");
                }
            });
        }
    }

    private static void skipSubEntity(Cursor input, int format) {
        if (format <= 26) {
            input.text();
        }
        repeat(input.count("sub-entity animation"), ignored -> {
            input.text();
            skipAnimations(input, format);
        });
        require(input.varUInt("sub-entity separator") == 0, "Invalid sub-entity separator");
        input.text();
        input.skipBlob();
        for (int ignored = 0; ignored < 4; ignored++) {
            input.varUInt("sub-entity texture property");
        }
        repeat(input.count("sub-entity texture"), ignored -> {
            input.varUInt("sub-entity texture id");
            input.text();
            input.skipBlob();
            for (int property = 0; property < 4; property++) {
                input.varUInt("sub-entity texture property");
            }
        });
        input.text();
        readGeometry(input, false);
        if (format > 26) {
            input.varUInt("sub-entity type");
            input.text();
        }
    }

    private static void skipAnimations(Cursor input, int format) {
        ModelBundle discard = new ModelBundle("discard");
        readAnimations(input, format, discard);
    }

    private static void skipControllers(Cursor input, int format) {
        repeat(input.count("animation controller"), ignored -> {
            if (format <= 15) {
                input.varUInt("controller id");
            } else {
                input.text();
                input.text();
            }
            repeat(input.count("controller animation"), animation -> {
                input.text();
                input.text();
                repeat(input.count("controller state"), state -> {
                    input.text();
                    repeat(input.count("state animation"), item -> {
                        input.text();
                        input.text();
                    });
                    repeat(input.count("state transition"), item -> {
                        input.text();
                        input.text();
                    });
                    repeat(input.count("state entry action"), item -> input.text());
                    repeat(input.count("state exit action"), item -> input.text());
                    if (input.varUInt("blend transition mode") != 0) {
                        input.number();
                    } else {
                        repeat(input.count("blend transition"), item -> {
                            input.number();
                            input.number();
                        });
                    }
                    input.varUInt("controller state flag");
                    if (format > 26) {
                        repeat(input.count("controller sound"), item -> input.text());
                    }
                });
            });
        });
    }

    private static void readControllers(Cursor input, int format, ModelBundle result) {
        int controllerFiles = input.count("animation controller");
        require(controllerFiles <= MAX_CONTROLLERS, "Too many animation controller files");
        long controllerTotal = 0;
        long stateTotal = 0;
        long entryTotal = 0;
        for (int fileIndex = 0; fileIndex < controllerFiles; fileIndex++) {
            if (format <= 15) {
                input.varUInt("controller id");
            } else {
                input.text();
                input.text();
            }
            int controllerCount = input.count("controller animation");
            controllerTotal += controllerCount;
            require(controllerTotal <= MAX_CONTROLLERS, "Too many animation controllers");
            for (int controllerIndex = 0; controllerIndex < controllerCount; controllerIndex++) {
                String name = input.text();
                String initialState = input.text();
                Map<String, AnimationController.State> states = new LinkedHashMap<>();
                int stateCount = input.count("controller state");
                stateTotal += stateCount;
                require(stateTotal <= MAX_CONTROLLER_STATES,
                        "Too many animation controller states");
                for (int stateIndex = 0; stateIndex < stateCount; stateIndex++) {
                    String stateName = input.text();
                    int animationCount = input.count("state animation");
                    entryTotal += animationCount;
                    require(entryTotal <= MAX_CONTROLLER_ENTRIES,
                            "Too many animation controller entries");
                    List<AnimationController.AnimationReference> animations =
                            new ArrayList<>(animationCount);
                    for (int item = 0; item < animationCount; item++) {
                        animations.add(new AnimationController.AnimationReference(
                                input.text(), input.text()));
                    }
                    int transitionCount = input.count("state transition");
                    entryTotal += transitionCount;
                    require(entryTotal <= MAX_CONTROLLER_ENTRIES,
                            "Too many animation controller entries");
                    List<AnimationController.Transition> transitions =
                            new ArrayList<>(transitionCount);
                    for (int item = 0; item < transitionCount; item++) {
                        transitions.add(new AnimationController.Transition(
                                input.text(), input.text()));
                    }
                    int onEntryCount = input.count("state entry action");
                    entryTotal += onEntryCount;
                    require(entryTotal <= MAX_CONTROLLER_ENTRIES,
                            "Too many animation controller entries");
                    List<String> onEntry = new ArrayList<>(onEntryCount);
                    for (int item = 0; item < onEntryCount; item++) {
                        onEntry.add(input.text());
                    }
                    int onExitCount = input.count("state exit action");
                    entryTotal += onExitCount;
                    require(entryTotal <= MAX_CONTROLLER_ENTRIES,
                            "Too many animation controller entries");
                    List<String> onExit = new ArrayList<>(onExitCount);
                    for (int item = 0; item < onExitCount; item++) {
                        onExit.add(input.text());
                    }
                    float duration = 0.0F;
                    List<AnimationController.BlendPoint> curve = new ArrayList<>();
                    if (input.varUInt("blend transition mode") != 0) {
                        duration = input.number();
                    } else {
                        int pointCount = input.count("blend transition");
                        entryTotal += pointCount;
                        require(entryTotal <= MAX_CONTROLLER_ENTRIES,
                                "Too many animation controller entries");
                        for (int item = 0; item < pointCount; item++) {
                            float time = input.number();
                            float value = input.number();
                            if (Float.isFinite(time) && time >= 0.0F
                                    && Float.isFinite(value)) {
                                curve.add(new AnimationController.BlendPoint(time, value));
                            }
                        }
                        curve.sort(Comparator.comparing(AnimationController.BlendPoint::time));
                    }
                    boolean shortestPath = input.varUInt("controller state flag") != 0;
                    List<String> soundEffects = new ArrayList<>();
                    if (format > 26) {
                        int sounds = input.count("controller sound");
                        entryTotal += sounds;
                        require(entryTotal <= MAX_CONTROLLER_ENTRIES,
                                "Too many animation controller entries");
                        for (int item = 0; item < sounds; item++) {
                            String effect = input.text();
                            if (!effect.isBlank()) {
                                soundEffects.add(effect);
                            }
                        }
                    }
                    states.putIfAbsent(stateName, new AnimationController.State(
                            stateName, animations, transitions, onEntry, onExit, soundEffects,
                            new AnimationController.BlendTransition(duration, curve),
                            shortestPath));
                }
                result.animationControllers().putIfAbsent(name,
                        new AnimationController(name, initialState, states));
            }
        }
    }

    private static void skipSounds(Cursor input, int format) {
        repeat(input.count("sound file"), ignored -> {
            input.text();
            if (format > 15) {
                input.text();
            }
            input.skipBlob();
        });
    }

    private static void readFunctions(Cursor input, ModelBundle result) {
        int count = input.count("function file");
        require(count <= ModelFunctionAssets.MAX_FUNCTIONS, "Too many Molang functions");
        long totalBytes = 0;
        for (int index = 0; index < count; index++) {
            String name = input.text(ModelFunctionAssets.MAX_NAME_BYTES, "function name");
            // Real modern packages retain the complete basename, including any
            // @subscription, followed by opaque hexadecimal metadata (not a path).
            // The source identity comes only from the first field.
            input.text(16 * 1_024, "function metadata");
            String key = ModelFunctionAssets.canonicalName(name);
            byte[] bytes = input.blob(ModelFunctionAssets.MAX_SOURCE_BYTES, "Molang source");
            totalBytes += bytes.length;
            require(totalBytes <= ModelFunctionAssets.MAX_TOTAL_SOURCE_BYTES,
                    "Molang sources exceed their total size limit");
            String source = ModelFunctionAssets.decodeSource(bytes);
            require(result.functions().putIfAbsent(key, source) == null,
                    "Duplicate Molang function name");
        }
    }

    private static void skipLanguages(Cursor input) {
        repeat(input.count("language file"), ignored -> {
            input.text();
            input.text();
            repeat(input.count("language entry"), entry -> {
                input.text();
                input.text();
            });
        });
    }

    private static void skipLegacyInfo(Cursor input) {
        input.text();
        input.text();
        repeat(input.count("legacy extra animation"), ignored -> input.text());
        repeat(input.count("legacy author"), ignored -> input.text());
        input.text();
        input.varUInt("legacy info flag");
    }

    private static void storeTexture(ModelBundle result, String name, byte[] bytes,
                                     int width, int height, int format) {
        // A later texture record with the same name replaces the entire material.
        result.pbrTextures().remove(name);
        result.textures().put(name, bytes);
        result.textureInfo().put(name, new ModelBundle.TextureInfo(width, height, format));
    }

    private static void storePbrTextures(
            ModelBundle result, String name, ModelBundle.EncodedTexture normal,
            ModelBundle.EncodedTexture specular) {
        ModelBundle.PbrTextures textures = new ModelBundle.PbrTextures(normal, specular);
        if (!textures.isEmpty()) {
            result.pbrTextures().put(name, textures);
        }
    }

    private static void repeat(int count, java.util.function.IntConsumer action) {
        for (int index = 0; index < count; index++) {
            action.accept(index);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class Cursor {
        private final byte[] source;
        private int offset;

        private Cursor(byte[] source) {
            if (source == null) {
                throw new IllegalArgumentException("Binary model payload is null");
            }
            this.source = source;
        }

        private int littleEndianInt() {
            requireAvailable(Integer.BYTES);
            int value = (source[offset] & 0xFF)
                    | (source[offset + 1] & 0xFF) << 8
                    | (source[offset + 2] & 0xFF) << 16
                    | (source[offset + 3] & 0xFF) << 24;
            offset += Integer.BYTES;
            return value;
        }

        private float number() {
            return Float.intBitsToFloat(littleEndianInt());
        }

        private int unsignedByte() {
            requireAvailable(1);
            return source[offset++] & 0xFF;
        }

        private int varUInt(String label) {
            long value = 0L;
            for (int group = 0; group < 5; group++) {
                int current = unsignedByte();
                value |= (long) (current & 0x7F) << (group * 7);
                if ((current & 0x80) == 0) {
                    if (value > Integer.MAX_VALUE) {
                        throw new IllegalStateException(label + " exceeds the supported range");
                    }
                    return (int) value;
                }
            }
            throw new IllegalStateException(label + " uses an overlong variable integer");
        }

        private int count(String label) {
            int value = varUInt(label + " count");
            if (value > MAX_ITEMS) {
                throw new IllegalStateException(label + " count exceeds " + MAX_ITEMS);
            }
            return value;
        }

        private String text() {
            return text(MAX_TEXT_BYTES, "text");
        }

        private String text(int maximum, String label) {
            int length = boundedLength(maximum, label);
            String value = new String(source, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        private byte[] blob() {
            return blob(MAX_BLOB_BYTES, "blob");
        }

        private byte[] blob(int maximum, String label) {
            int length = boundedLength(maximum, label);
            byte[] value = java.util.Arrays.copyOfRange(source, offset, offset + length);
            offset += length;
            return value;
        }

        private void skipBlob() {
            skip(boundedLength(MAX_BLOB_BYTES, "blob"));
        }

        private int boundedLength(int maximum, String label) {
            int length = varUInt(label + " length");
            if (length > maximum || length > source.length - offset) {
                throw new IllegalStateException(label + " exceeds package bounds");
            }
            return length;
        }

        private void skip(int bytes) {
            if (bytes < 0) {
                throw new IllegalStateException("Negative binary skip");
            }
            requireAvailable(bytes);
            offset += bytes;
        }

        private void requireAvailable(int bytes) {
            if (bytes < 0 || bytes > source.length - offset) {
                throw new IllegalStateException("Binary model section exceeds package bounds");
            }
        }
    }
}
