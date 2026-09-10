package net.okitsu.ysmepicfightcompat.assets;

import net.okitsu.ysmepicfightcompat.assets.binary.V2PackageFixtures;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.geometry.GeometryTransferCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2PackageRepositoryTest {
    private static final String MODEL_ID = "legacy/authored.ysm";
    private static final String GEOMETRY = """
            {"minecraft:geometry":[{
              "description":{"texture_width":16,"texture_height":32,
                "ysm_width_scale":0.8,"ysm_height_scale":0.9},
              "bones":[{"name":"Root","cubes":[{
                "origin":[0,0,0],"size":[1,2,3],"uv":[0,0]
              }]}]
            }]}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"builtin", "built", "custom", "auth"})
    void discoversLoadsAndFingerprintsEncryptedLegacyPackages(
            String catalog, @TempDir Path root) throws Exception {
        writeArchive(root, catalog, MODEL_ID, assets());

        assertEquals(Map.of(MODEL_ID, true), LocalModelRepository.discover(root));
        assertLegacyModel(LocalModelRepository.load(root, MODEL_ID), false);
        byte[] digest = LocalModelRepository.contentDigest(root, MODEL_ID);
        assertNotNull(digest);
        assertEquals(ModelDiskCache.DIGEST_BYTES, digest.length);
    }

    @Test
    void matchesFlatDirectorySelectionScalesAndAnimationPrecedence(@TempDir Path root)
            throws Exception {
        Map<String, byte[]> entries = assets();
        writeArchive(root, "custom", MODEL_ID, entries);
        Path directory = Files.createDirectories(root.resolve("custom/legacy/folder"));
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Files.write(directory.resolve(entry.getKey()), entry.getValue());
        }
        writePrimaryAnimations(root, 2);

        ModelBundle archive = LocalModelRepository.load(root, MODEL_ID);
        ModelBundle folder = LocalModelRepository.load(root, "legacy/folder");
        assertLegacyModel(archive, true);
        assertLegacyModel(folder, true);
        assertArrayEquals(GeometryTransferCodec.encode(folder), GeometryTransferCodec.encode(archive),
                "equivalent legacy assets must produce the same transferable model");
    }

    @Test
    void fingerprintsDecodedAssetsIndependentlyOfPackageEntryOrder(@TempDir Path root)
            throws Exception {
        Map<String, byte[]> entries = assets();
        Path archive = writeArchive(root, "custom", MODEL_ID, entries);
        byte[] firstEnvelope = Files.readAllBytes(archive);
        byte[] firstDigest = LocalModelRepository.contentDigest(root, MODEL_ID);
        byte[] firstModel = GeometryTransferCodec.encode(LocalModelRepository.load(root, MODEL_ID));
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.reverse(names);
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        names.forEach(name -> reversed.put(name, entries.get(name)));
        Files.write(archive, V2PackageFixtures.archive(reversed));

        assertFalse(Arrays.equals(firstEnvelope, Files.readAllBytes(archive)));
        assertNotNull(firstDigest);
        assertArrayEquals(firstDigest, LocalModelRepository.contentDigest(root, MODEL_ID));
        assertArrayEquals(firstModel,
                GeometryTransferCodec.encode(LocalModelRepository.load(root, MODEL_ID)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"main.json", "ash.png", "a.animation.json"})
    void invalidatesSourceDigestWhenAnAssetChanges(String changedAsset, @TempDir Path root)
            throws Exception {
        Map<String, byte[]> entries = assets();
        Path archive = writeArchive(root, "custom", MODEL_ID, entries);
        byte[] before = LocalModelRepository.contentDigest(root, MODEL_ID);
        byte[] changed = switch (changedAsset) {
            case "main.json" -> utf8(GEOMETRY.replace("[1,2,3]", "[2,2,3]"));
            case "ash.png" -> new byte[]{9, 8, 7};
            default -> utf8("""
                    {"animations":{"idle":{"animation_length":8,"bones":{}}}}
                    """);
        };
        entries.put(changedAsset, changed);
        Files.write(archive, V2PackageFixtures.archive(entries));

        assertNotNull(before);
        byte[] after = LocalModelRepository.contentDigest(root, MODEL_ID);
        assertNotNull(after);
        assertFalse(Arrays.equals(before, after));
        assertNotNull(LocalModelRepository.load(root, MODEL_ID));
    }

    @Test
    void inheritsMissingPrimaryClipsAndInvalidatesDigestWhenTheyChange(@TempDir Path root)
            throws Exception {
        writeArchive(root, "custom", MODEL_ID, assets());
        writePrimaryAnimations(root, 2);
        byte[] before = LocalModelRepository.contentDigest(root, MODEL_ID);
        assertLegacyModel(LocalModelRepository.load(root, MODEL_ID), true);

        writePrimaryAnimations(root, 3);

        byte[] after = LocalModelRepository.contentDigest(root, MODEL_ID);
        assertNotNull(before);
        assertNotNull(after);
        assertFalse(Arrays.equals(before, after));
        ModelBundle updated = LocalModelRepository.load(root, MODEL_ID);
        assertNotNull(updated);
        assertEquals(3.0F, updated.animations().get("walk").duration());
        assertEquals(7.0F, updated.animations().get("idle").duration(),
                "model clips retain priority over inherited primary clips");
    }

    @Test
    void rejectsCorruptPackageWithoutHidingOtherCatalogModels(@TempDir Path root)
            throws Exception {
        Path broken = writeArchive(root, "custom", "broken.ysm", assets());
        writeArchive(root, "custom", MODEL_ID, assets());
        byte[] envelope = Files.readAllBytes(broken);
        Files.write(broken, Arrays.copyOf(envelope, envelope.length / 2));

        assertNull(LocalModelRepository.load(root, "broken.ysm"));
        assertNull(LocalModelRepository.contentDigest(root, "broken.ysm"));
        assertEquals(Set.of("broken.ysm", MODEL_ID), LocalModelRepository.discover(root).keySet());
        assertLegacyModel(LocalModelRepository.load(root, MODEL_ID), false);
    }

    @Test
    void retainsSubpixelBoxDetailsThroughPackageLoadingAndTransfer(@TempDir Path root)
            throws Exception {
        Map<String, byte[]> entries = assets();
        entries.put("main.json", utf8(GEOMETRY.replace("[1,2,3]", "[0.2,7,0.2]")));
        writeArchive(root, "custom", MODEL_ID, entries);

        ModelBundle loaded = LocalModelRepository.load(root, MODEL_ID);
        assertNotNull(loaded);
        assertEquals(6, loaded.geometry().bones().get("Root").faces().size(),
                "nonzero box geometry must survive a zero-width automatic UV rectangle");
        ModelBundle transferred = GeometryTransferCodec.decode(MODEL_ID,
                GeometryTransferCodec.encode(loaded));
        assertEquals(6, transferred.geometry().bones().get("Root").faces().size());
    }

    @Test
    void retainsLegacyModelThroughVersionOneTransferAndDiskCache(@TempDir Path root)
            throws Exception {
        writeArchive(root, "custom", MODEL_ID, assets());
        writePrimaryAnimations(root, 2);
        ModelBundle loaded = LocalModelRepository.load(root, MODEL_ID);
        assertLegacyModel(loaded, true);
        byte[] payload = GeometryTransferCodec.encode(loaded);
        try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            input.readInt();
            assertEquals(1, input.readInt(), "the existing transfer format version stays fixed");
        }
        assertLegacyModel(GeometryTransferCodec.decode(MODEL_ID, payload), true);

        byte[] sourceDigest = LocalModelRepository.contentDigest(root, MODEL_ID);
        byte[] payloadDigest = ModelDiskCache.sha256(payload);
        Path cache = root.resolve("cache");
        assertTrue(ModelDiskCache.write(cache, MODEL_ID,
                new ModelDiskCache.Entry(sourceDigest, payloadDigest, payload), 1024 * 1024));
        ModelDiskCache.Entry cached = ModelDiskCache.read(cache, MODEL_ID, 1024 * 1024).orElseThrow();
        assertArrayEquals(sourceDigest, cached.validationDigest());
        assertArrayEquals(payloadDigest, cached.payloadDigest());
        assertArrayEquals(payload, cached.payload());
        assertLegacyModel(GeometryTransferCodec.decode(MODEL_ID, cached.payload()), true);
        try (var files = Files.list(cache)) {
            List<Path> cacheFiles = files.toList();
            assertEquals(1, cacheFiles.size());
            try (var input = new DataInputStream(Files.newInputStream(cacheFiles.get(0)))) {
                input.readInt();
                assertEquals(1, input.readInt(), "the existing cache format version stays fixed");
            }
        }
    }

    private static void assertLegacyModel(ModelBundle bundle, boolean inherited) {
        assertNotNull(bundle);
        assertNotNull(bundle.geometry());
        assertEquals(Set.of("Root"), bundle.geometry().bones().keySet());
        assertEquals(6, bundle.geometry().bones().get("Root").faces().size());
        assertEquals(16, bundle.geometry().textureWidth());
        assertEquals(32, bundle.geometry().textureHeight());
        assertEquals(0.8F, bundle.widthScale());
        assertEquals(0.9F, bundle.heightScale());
        assertEquals("ash", bundle.defaultTexture());
        assertEquals(Set.of("ash", "snow"), bundle.textures().keySet());
        assertArrayEquals(new byte[]{1, 2, 3}, bundle.textures().get("ash"));
        assertArrayEquals(new byte[]{4, 5, 6}, bundle.textures().get("snow"));
        assertEquals(inherited ? Set.of("idle", "extra", "walk") : Set.of("idle", "extra"),
                bundle.animations().keySet());
        assertEquals(7.0F, bundle.animations().get("idle").duration());
        assertEquals(Set.of("Root"), bundle.animations().get("idle").boneTracks().keySet());
        assertEquals(4.0F, bundle.animations().get("extra").duration());
        if (inherited) {
            assertEquals(2.0F, bundle.animations().get("walk").duration());
        }
        assertFalse(bundle.allCutout());
        assertFalse(bundle.renderLayersFirst());
        assertFalse(bundle.mergeMultilineExpressions());
    }

    private static Map<String, byte[]> assets() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("main.json", utf8(GEOMETRY));
        entries.put("arm.json", utf8(GEOMETRY.replace("Root", "ArmOnly")));
        entries.put("z.animation.json", utf8("""
                {"animations":{
                  "idle":{"animation_length":99,"bones":{"ArmOnly":{"rotation":[0,0,9]}}},
                  "extra":{"animation_length":4,"bones":{}}
                }}
                """));
        entries.put("snow.png", new byte[]{4, 5, 6});
        entries.put("a.animation.json", utf8("""
                {"animations":{"idle":{
                  "animation_length":7,"loop":true,"bones":{"Root":{"rotation":[0,0,3]}}
                }}}
                """));
        entries.put("ash.png", new byte[]{1, 2, 3});
        entries.put("arrow.png", new byte[]{7, 8, 9});
        entries.put("invalid.animation.json", utf8("{invalid"));
        return entries;
    }

    private static Path writeArchive(Path root, String catalog, String id,
                                     Map<String, byte[]> entries) throws Exception {
        Path archive = root.resolve(catalog).resolve(id);
        Files.createDirectories(archive.getParent());
        Files.write(archive, V2PackageFixtures.archive(entries));
        return archive;
    }

    private static void writePrimaryAnimations(Path root, int walkDuration) throws Exception {
        Path primary = Files.createDirectories(root.resolve("builtin/default"));
        Files.writeString(primary.resolve("ysm.json"), """
                {"files":{"player":{"animation":{"main":"main.animation.json"}}}}
                """);
        Files.writeString(primary.resolve("main.animation.json"), """
                {"animations":{
                  "idle":{"animation_length":1,"bones":{}},
                  "walk":{"animation_length":%d,"bones":{}}
                }}
                """.formatted(walkDuration));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
