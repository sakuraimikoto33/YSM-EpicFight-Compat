package net.okitsu.ysmepicfightcompat.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.ModList;
import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.animation.BedrockAnimationParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Supplies the primary model clips that official YSM inherits into every non-primary
 * model when a clip with the same name is not declared by that model.
 */
final class OfficialDefaultAnimationLibrary {
    private static final String YSM_MOD_ID = "yes_steve_model";
    private static final String RESOURCE_ROOT =
            "assets/yes_steve_model/builtin/default/";
    private static final String MANIFEST = "ysm.json";
    private static final byte[] CACHE_REVISION =
            "official-primary-animation-inheritance-v2".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ANIMATION_BYTES = 64 * 1024 * 1024;

    private record Source(Map<String, byte[]> animations, byte[] digest) {
        private Source {
            animations = Collections.unmodifiableMap(new LinkedHashMap<>(animations));
            digest = digest.clone();
        }
    }

    private record Snapshot(byte[] digest, Map<String, AnimationClip> animations) {
        private Snapshot {
            digest = digest.clone();
            animations = Collections.unmodifiableMap(new LinkedHashMap<>(animations));
        }
    }

    private static volatile Snapshot cached;

    private OfficialDefaultAnimationLibrary() {
    }

    static void inherit(Path ysmRoot, ModelBundle target) {
        if (target == null || "default".equals(target.modelId())) {
            return;
        }
        Snapshot snapshot = snapshot(ysmRoot);
        snapshot.animations().forEach(target.animations()::putIfAbsent);
    }

    /** Makes parsed-model disk caches depend on the inherited official assets and policy. */
    static void contributeDigest(Path ysmRoot, MessageDigest target) {
        target.update(CACHE_REVISION);
        target.update(snapshot(ysmRoot).digest());
    }

    private static Snapshot snapshot(Path ysmRoot) {
        // Official YSM loads its primary model directly from its own mod asset. Resolve
        // that exact mod file first so another classpath resource with the same name
        // cannot become the inheritance authority.
        Source source = modSource();
        if (source == null) {
            source = classpathSource();
        }
        if (source == null) {
            source = diskSource(ysmRoot);
        }
        if (source == null) {
            return new Snapshot(emptyDigest(), Map.of());
        }
        Snapshot known = cached;
        if (known != null && MessageDigest.isEqual(known.digest(), source.digest())) {
            return known;
        }
        Snapshot parsed = new Snapshot(source.digest(), parseAnimations(source.animations()));
        cached = parsed;
        return parsed;
    }

    private static Source modSource() {
        try {
            var fileInfo = ModList.get().getModFileById(YSM_MOD_ID);
            if (fileInfo == null || fileInfo.getFile() == null) {
                return null;
            }
            Path root = fileInfo.getFile().findResource(
                    "assets", YSM_MOD_ID, "builtin", "default");
            return pathSource(root);
        } catch (RuntimeException | LinkageError ignored) {
            // Plain unit tests do not bootstrap ModList. The classpath/disk fallbacks
            // below keep the parser independently testable without changing runtime
            // authority inside an actual Forge instance.
            return null;
        }
    }

    private static Source diskSource(Path ysmRoot) {
        if (ysmRoot == null) {
            return null;
        }
        Path root = ysmRoot.resolve("builtin").resolve("default").normalize();
        return pathSource(root);
    }

    private static Source pathSource(Path root) {
        if (root == null) {
            return null;
        }
        Path manifest = root.resolve(MANIFEST);
        try {
            if (!regularFile(manifest)) {
                return null;
            }
            byte[] manifestBytes = readBounded(manifest, MAX_MANIFEST_BYTES);
            Map<String, String> declarations = declarations(manifestBytes);
            Map<String, byte[]> animations = new LinkedHashMap<>();
            for (Map.Entry<String, String> declaration : declarations.entrySet()) {
                String group = declaration.getKey();
                String relative = declaration.getValue();
                Path file = confine(root, relative);
                if (!regularFile(file)) {
                    return null;
                }
                animations.put(group, readBounded(file, MAX_ANIMATION_BYTES));
            }
            return source(manifestBytes, animations);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Source classpathSource() {
        ClassLoader loader = OfficialDefaultAnimationLibrary.class.getClassLoader();
        try {
            byte[] manifest = readBounded(loader, RESOURCE_ROOT + MANIFEST,
                    MAX_MANIFEST_BYTES);
            if (manifest == null) {
                return null;
            }
            Map<String, String> declarations = declarations(manifest);
            Map<String, byte[]> animations = new LinkedHashMap<>();
            for (Map.Entry<String, String> declaration : declarations.entrySet()) {
                String group = declaration.getKey();
                String relative = declaration.getValue();
                byte[] bytes = readBounded(loader, RESOURCE_ROOT + relative,
                        MAX_ANIMATION_BYTES);
                if (bytes == null) {
                    return null;
                }
                animations.put(group, bytes);
            }
            return source(manifest, animations);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Source source(byte[] manifest, Map<String, byte[]> animations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CACHE_REVISION);
            digest.update(manifest);
            for (Map.Entry<String, byte[]> entry : animations.entrySet()) {
                String group = entry.getKey();
                byte[] bytes = entry.getValue();
                digest.update((byte) group.length());
                digest.update(group.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return new Source(animations, digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<String, AnimationClip> parseAnimations(
            Map<String, byte[]> sources) {
        Map<String, AnimationClip> result = new LinkedHashMap<>();
        for (byte[] bytes : sources.values()) {
            try {
                JsonObject root = JsonParser.parseString(
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonObject animations = object(root, "animations");
                if (animations == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        result.putIfAbsent(entry.getKey(), BedrockAnimationParser.parse(
                                entry.getKey(), entry.getValue().getAsJsonObject()));
                    }
                }
            } catch (RuntimeException ignored) {
                // A malformed primary file must not make an otherwise valid model unusable.
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> declarations(byte[] manifest) {
        JsonObject root = JsonParser.parseString(
                new String(manifest, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject files = object(root, "files");
        JsonObject player = object(files, "player");
        JsonObject animation = object(player, "animation");
        Map<String, String> result = new LinkedHashMap<>();
        if (animation != null) {
            for (Map.Entry<String, JsonElement> entry : animation.entrySet()) {
                String group = entry.getKey();
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()
                        && safeRelative(value.getAsString())) {
                    result.put(group, value.getAsString().replace('\\', '/'));
                }
            }
        }
        return result;
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static Path confine(Path root, String relative) throws IOException {
        if (!safeRelative(relative)) {
            throw new IOException("Unsafe primary animation path");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar))
                .normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Primary animation path escaped its model");
        }
        return target;
    }

    private static boolean safeRelative(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        return !normalized.startsWith("/") && !normalized.contains(":")
                && Arrays.stream(normalized.split("/"))
                .noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."));
    }

    private static boolean regularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static byte[] readBounded(Path path, int maximum) throws IOException {
        long size = Files.size(path);
        if (size < 0 || size > maximum) {
            throw new IOException("Official primary asset exceeds its limit");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maximum) {
            throw new IOException("Official primary asset exceeds its limit");
        }
        return bytes;
    }

    private static byte[] readBounded(ClassLoader loader, String resource, int maximum)
            throws IOException {
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IOException("Official primary resource exceeds its limit");
            }
            return bytes;
        }
    }

    private static byte[] emptyDigest() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(CACHE_REVISION);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
