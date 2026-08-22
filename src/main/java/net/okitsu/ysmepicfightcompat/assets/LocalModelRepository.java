package net.okitsu.ysmepicfightcompat.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.animation.AnimationController;
import net.okitsu.ysmepicfightcompat.animation.BedrockAnimationControllerParser;
import net.okitsu.ysmepicfightcompat.animation.BedrockAnimationParser;
import net.okitsu.ysmepicfightcompat.assets.binary.BinaryPackageParser;
import net.okitsu.ysmepicfightcompat.assets.binary.PackageEnvelopeDecoder;
import net.okitsu.ysmepicfightcompat.geometry.BedrockGeometryParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads official YSM model sources while leaving all generated state in YSM's own folders. */
public final class LocalModelRepository {
    private static final Path DEFAULT_ROOT = Path.of("config", "yes_steve_model");
    private static final List<String> CATALOGS = List.of("builtin", "built", "custom", "auth");
    private static final long MAX_MANIFEST = 4L * 1024 * 1024;
    private static final long MAX_GEOMETRY = 64L * 1024 * 1024;
    private static final long MAX_ANIMATION = 64L * 1024 * 1024;
    private static final long MAX_TEXTURE = 128L * 1024 * 1024;
    private static final long MAX_ARCHIVE = 256L * 1024 * 1024;
    private static final int MAX_LEGACY_FILES = 4_096;

    private LocalModelRepository() {
    }

    public static ModelBundle load(String modelId) {
        return load(DEFAULT_ROOT, modelId);
    }

    static ModelBundle load(Path ysmRoot, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        try {
            Optional<LocatedModel> located = locate(ysmRoot, modelId);
            if (located.isEmpty()) {
                return null;
            }
            LocatedModel source = located.get();
            return switch (source.format()) {
                case ARCHIVE -> readArchive(modelId, source.path());
                case MANIFEST_DIRECTORY -> readDirectory(modelId, source.path());
                case LEGACY_DIRECTORY -> readLegacyDirectory(modelId, source.path());
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean exists(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        try {
            return locate(DEFAULT_ROOT, modelId).isPresent();
        } catch (IOException ignored) {
            return false;
        }
    }

    public static Map<String, Boolean> discover() {
        return discover(DEFAULT_ROOT);
    }

    static Map<String, Boolean> discover(Path ysmRoot) {
        Map<String, Boolean> models = new LinkedHashMap<>();
        for (String catalog : CATALOGS) {
            Path catalogRoot = ysmRoot.resolve(catalog);
            if (!Files.isDirectory(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(catalogRoot)) {
                paths.filter(path -> !Files.isSymbolicLink(path)).forEach(path -> {
                    String leaf = path.getFileName().toString();
                    if (leaf.equals("ysm.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        String id = slashPath(catalogRoot.relativize(path.getParent()));
                        if (!id.isEmpty()) {
                            models.put(id, false);
                        }
                    } else if (leaf.equals("main.json")
                            && isLegacyDirectory(path.getParent())) {
                        String id = slashPath(catalogRoot.relativize(path.getParent()));
                        if (!id.isEmpty()) {
                            models.put(id, false);
                        }
                    } else if (leaf.endsWith(".ysm")
                            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        models.put(slashPath(catalogRoot.relativize(path)), true);
                    }
                });
            } catch (IOException ignored) {
            }
        }
        return models;
    }

    public static long metadataStamp(String modelId) {
        try {
            Optional<LocatedModel> source = locate(DEFAULT_ROOT, modelId);
            if (source.isEmpty()) {
                return -1L;
            }
            LocatedModel located = source.get();
            if (located.archive()) {
                return avalanche(Files.size(located.path())
                        ^ Long.rotateLeft(Files.getLastModifiedTime(located.path()).toMillis(), 19)
                        ^ modelId.hashCode());
            }
            List<Path> files = regularFiles(located.path());
            long stamp = 0x6A09E667F3BCC909L;
            for (Path file : files) {
                long entry = slashPath(located.path().relativize(file)).hashCode();
                entry ^= Long.rotateLeft(Files.size(file), 13);
                entry ^= Long.rotateLeft(Files.getLastModifiedTime(file).toMillis(), 37);
                stamp = avalanche(stamp ^ entry);
            }
            return stamp;
        } catch (IOException exception) {
            return -1L;
        }
    }

    public static long contentStamp(String modelId) {
        try {
            Optional<LocatedModel> source = locate(DEFAULT_ROOT, modelId);
            if (source.isEmpty()) {
                return -1L;
            }
            LocatedModel located = source.get();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(modelId.getBytes(StandardCharsets.UTF_8));
            if (located.archive()) {
                byte[] decrypted = PackageEnvelopeDecoder.open(readBounded(located.path(), MAX_ARCHIVE));
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(decrypted.length).array());
                digest.update(decrypted);
            } else {
                for (Path file : regularFiles(located.path())) {
                    byte[] bytes = readBounded(file, MAX_TEXTURE);
                    digest.update(slashPath(located.path().relativize(file)).getBytes(StandardCharsets.UTF_8));
                    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            return -1L;
        }
    }

    private static ModelBundle readDirectory(String modelId, Path directory) throws IOException {
        JsonObject manifest = JsonParser.parseString(textBounded(
                directory.resolve("ysm.json"), MAX_MANIFEST)).getAsJsonObject();
        ModelBundle bundle = new ModelBundle(modelId);
        JsonObject properties = object(manifest, "properties");
        if (properties != null) {
            bundle.scales(decimal(properties, "width_scale", 0.7F),
                    decimal(properties, "height_scale", 0.7F));
            bundle.defaultTexture(string(properties, "default_texture", ""));
        }
        JsonObject files = object(manifest, "files");
        JsonObject player = object(files, "player");
        if (player == null) {
            return null;
        }
        JsonObject models = object(player, "model");
        if (models != null && models.has("main")) {
            Path geometry = confine(directory, models.get("main").getAsString());
            if (Files.isRegularFile(geometry, LinkOption.NOFOLLOW_LINKS)) {
                bundle.geometry(BedrockGeometryParser.parse(textBounded(geometry, MAX_GEOMETRY)));
            }
        }
        JsonObject animations = object(player, "animation");
        if (animations != null) {
            for (JsonElement location : animations.asMap().values()) {
                Path file = confine(directory, location.getAsString());
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    readAnimationFile(file, bundle.animations());
                }
            }
        }
        readControllerDeclarations(directory, player.get("animation_controllers"), bundle);
        readTextures(directory, player.get("texture"), bundle);
        return bundle.geometry() == null ? null : bundle;
    }

    private static ModelBundle readArchive(String modelId, Path archive) throws IOException {
        byte[] envelope = readBounded(archive, MAX_ARCHIVE);
        return BinaryPackageParser.parse(modelId, PackageEnvelopeDecoder.open(envelope));
    }

    private static ModelBundle readLegacyDirectory(String modelId, Path directory)
            throws IOException {
        String geometryJson = textBounded(directory.resolve("main.json"), MAX_GEOMETRY);
        ModelBundle bundle = new ModelBundle(modelId);
        bundle.geometry(BedrockGeometryParser.parse(geometryJson));
        readLegacyScales(geometryJson, bundle);

        List<Path> files = directRegularFiles(directory);
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(".animation.json")) {
                readAnimationFile(file, bundle.animations());
            } else if (isLegacyPlayerTexture(file)) {
                bundle.textures().put(stem(name), readBounded(file, MAX_TEXTURE));
            }
        }
        if (!bundle.textures().isEmpty()) {
            bundle.defaultTexture(bundle.textures().keySet().iterator().next());
        }
        return bundle;
    }

    private static void readLegacyScales(String geometryJson, ModelBundle bundle) {
        JsonObject root = JsonParser.parseString(geometryJson).getAsJsonObject();
        JsonElement geometries = root.get("minecraft:geometry");
        if (geometries == null || !geometries.isJsonArray()
                || geometries.getAsJsonArray().isEmpty()
                || !geometries.getAsJsonArray().get(0).isJsonObject()) {
            return;
        }
        JsonObject description = object(
                geometries.getAsJsonArray().get(0).getAsJsonObject(), "description");
        if (description != null) {
            bundle.scales(decimal(description, "ysm_width_scale", bundle.widthScale()),
                    decimal(description, "ysm_height_scale", bundle.heightScale()));
        }
    }

    private static void readAnimationFile(Path file, Map<String, AnimationClip> target) {
        try {
            JsonObject root = JsonParser.parseString(textBounded(file, MAX_ANIMATION)).getAsJsonObject();
            JsonObject animations = object(root, "animations");
            if (animations == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    // A model can reuse an animation name in a later, specialized file
                    // (for example fp_arm). The manifest order is the precedence order:
                    // keep the main definition instead of replacing it with a partial one.
                    target.putIfAbsent(entry.getKey(), BedrockAnimationParser.parse(
                            entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void readControllerDeclarations(Path directory, JsonElement declaration,
                                                   ModelBundle target) throws IOException {
        if (declaration == null || declaration.isJsonNull()) {
            return;
        }
        Iterable<JsonElement> entries = declaration.isJsonArray()
                ? declaration.getAsJsonArray() : List.of(declaration);
        for (JsonElement entry : entries) {
            if (!entry.isJsonPrimitive()) {
                continue;
            }
            Path file = confine(directory, entry.getAsString());
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                readControllerFile(file, target.animationControllers());
            }
        }
    }

    private static void readControllerFile(Path file,
                                           Map<String, AnimationController> target) {
        try {
            JsonObject root = JsonParser.parseString(
                    textBounded(file, MAX_ANIMATION)).getAsJsonObject();
            BedrockAnimationControllerParser.parse(root).forEach(target::putIfAbsent);
        } catch (Exception ignored) {
        }
    }

    private static void readTextures(Path directory, JsonElement declaration,
                                     ModelBundle target) throws IOException {
        if (declaration == null || declaration.isJsonNull()) {
            return;
        }
        Iterable<JsonElement> entries = declaration.isJsonArray()
                ? declaration.getAsJsonArray() : List.of(declaration);
        for (JsonElement entry : entries) {
            String relative = null;
            if (entry.isJsonPrimitive()) {
                relative = entry.getAsString();
            } else if (entry.isJsonObject() && entry.getAsJsonObject().has("uv")) {
                relative = entry.getAsJsonObject().get("uv").getAsString();
            }
            if (relative == null) {
                continue;
            }
            Path file = confine(directory, relative);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                target.textures().put(stem(relative), readBounded(file, MAX_TEXTURE));
            }
        }
    }

    private static Optional<LocatedModel> locate(Path root, String modelId) throws IOException {
        boolean archive = modelId.endsWith(".ysm");
        for (String catalog : CATALOGS) {
            Path candidate = confine(root.resolve(catalog), modelId);
            if (archive && regularFile(candidate)) {
                return Optional.of(new LocatedModel(candidate, ModelFormat.ARCHIVE));
            }
            if (!archive && regularFile(candidate.resolve("ysm.json"))) {
                return Optional.of(new LocatedModel(candidate, ModelFormat.MANIFEST_DIRECTORY));
            }
            if (!archive && isLegacyDirectory(candidate)) {
                return Optional.of(new LocatedModel(candidate, ModelFormat.LEGACY_DIRECTORY));
            }
        }
        return Optional.empty();
    }

    private static boolean isLegacyDirectory(Path directory) {
        if (regularFile(directory.resolve("ysm.json"))
                || !regularFile(directory.resolve("main.json"))
                || !regularFile(directory.resolve("arm.json"))) {
            return false;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.anyMatch(LocalModelRepository::isLegacyPlayerTexture);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isLegacyPlayerTexture(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".png") && !name.equals("arrow.png") && regularFile(path);
    }

    private static boolean regularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    static Path confine(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("Empty model path");
        }
        Path parsed;
        try {
            parsed = Path.of(relative.replace('/', java.io.File.separatorChar));
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid model path", exception);
        }
        if (parsed.isAbsolute()) {
            throw new IOException("Absolute model paths are not accepted");
        }
        Path boundary = root.toAbsolutePath().normalize();
        Path resolved = boundary.resolve(parsed).normalize();
        if (!resolved.startsWith(boundary)) {
            throw new IOException("Model path escapes its catalog");
        }
        return resolved;
    }

    private static List<Path> regularFiles(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path)).forEach(result::add);
        }
        result.sort(Comparator.comparing(path -> slashPath(directory.relativize(path))));
        return result;
    }

    private static List<Path> directRegularFiles(Path directory) throws IOException {
        List<Path> result;
        try (Stream<Path> paths = Files.list(directory)) {
            result = paths.filter(LocalModelRepository::regularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_LEGACY_FILES + 1L).toList();
        }
        if (result.size() > MAX_LEGACY_FILES) {
            throw new IOException("Legacy model contains too many files");
        }
        return result;
    }

    private static byte[] readBounded(Path file, long limit) throws IOException {
        long size = Files.size(file);
        if (size < 0 || size > limit) {
            throw new IOException("Model file exceeds " + limit + " bytes: " + file.getFileName());
        }
        return Files.readAllBytes(file);
    }

    private static String textBounded(Path file, long limit) throws IOException {
        return new String(readBounded(file, limit), StandardCharsets.UTF_8);
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : null;
    }

    private static float decimal(JsonObject source, String name, float fallback) {
        return source.has(name) ? source.get(name).getAsFloat() : fallback;
    }

    private static String string(JsonObject source, String name, String fallback) {
        return source.has(name) ? source.get(name).getAsString() : fallback;
    }

    private static String stem(String relative) {
        String name = relative.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int extension = name.lastIndexOf('.');
        return extension < 0 ? name : name.substring(0, extension);
    }

    private static String slashPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static long avalanche(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private enum ModelFormat {
        ARCHIVE,
        MANIFEST_DIRECTORY,
        LEGACY_DIRECTORY
    }

    private record LocatedModel(Path path, ModelFormat format) {
        private boolean archive() {
            return format == ModelFormat.ARCHIVE;
        }
    }
}
