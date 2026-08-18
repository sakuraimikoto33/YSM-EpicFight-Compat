package net.okitsu.ysmepicfightcompat.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelRepositoryTest {
    @Test
    void discoversOnlyModelCatalogsAndIgnoresOfficialCacheHashes(@TempDir Path root)
            throws IOException {
        Path model = root.resolve("custom/animals/fox");
        Files.createDirectories(model);
        Files.writeString(model.resolve("ysm.json"), "{}");
        Path cache = root.resolve("cache/client/0123456789abcdef");
        Files.createDirectories(cache);
        Files.write(cache.resolve("0123456789abcdef0123456789abcdef01234567"), new byte[]{1});

        Map<String, Boolean> discovered = LocalModelRepository.discover(root);

        assertEquals(Map.of("animals/fox", false), discovered);
        assertFalse(discovered.keySet().stream().anyMatch(id -> id.contains("0123456789abcdef")));
    }

    @Test
    void discoversAndLoadsLegacyFlatDirectoryModels(@TempDir Path root) throws IOException {
        Path model = root.resolve("custom/legacy/fox");
        Files.createDirectories(model);
        Files.writeString(model.resolve("main.json"), """
                {"minecraft:geometry":[{
                  "description":{
                    "texture_width":16,"texture_height":16,
                    "ysm_width_scale":0.8,"ysm_height_scale":0.9
                  },
                  "bones":[{"name":"root","cubes":[{
                    "origin":[0,0,0],"size":[1,1,1],"uv":[0,0]
                  }]}]
                }]}
                """);
        Files.writeString(model.resolve("arm.json"), "{}");
        Files.writeString(model.resolve("main.animation.json"), """
                {"animations":{"idle":{"loop":true,"bones":{}}}}
                """);
        Files.write(model.resolve("skin.png"), new byte[]{1, 2, 3});
        Files.write(model.resolve("arrow.png"), new byte[]{4, 5, 6});

        assertEquals(Map.of("legacy/fox", false), LocalModelRepository.discover(root));

        ModelBundle loaded = LocalModelRepository.load(root, "legacy/fox");

        assertNotNull(loaded);
        assertNotNull(loaded.geometry());
        assertTrue(loaded.animations().containsKey("idle"));
        assertEquals(Set.of("skin"), loaded.textures().keySet());
        assertEquals("skin", loaded.defaultTexture());
        assertEquals(0.8F, loaded.widthScale(), 0.0001F);
        assertEquals(0.9F, loaded.heightScale(), 0.0001F);
    }

    @Test
    void keepsFirstManifestAnimationWhenLaterFilesReuseItsName(@TempDir Path root)
            throws IOException {
        Path model = root.resolve("custom/layered/outfit");
        Files.createDirectories(model.resolve("models"));
        Files.createDirectories(model.resolve("animations"));
        Files.writeString(model.resolve("ysm.json"), """
                {
                  "files":{"player":{
                    "model":{"main":"models/main.json"},
                    "animation":{
                      "main":"animations/main.animation.json",
                      "fp_arm":"animations/fp.arm.animation.json"
                    }
                  }}
                }
                """);
        Files.writeString(model.resolve("models/main.json"), """
                {"minecraft:geometry":[{
                  "description":{"texture_width":16,"texture_height":16},
                  "bones":[
                    {"name":"Jacket"},
                    {"name":"FirstPersonArm"}
                  ]
                }]}
                """);
        Files.writeString(model.resolve("animations/main.animation.json"), """
                {"animations":{"parallel0":{"bones":{
                  "Jacket":{"scale":[0,0,0]}
                }}}}
                """);
        Files.writeString(model.resolve("animations/fp.arm.animation.json"), """
                {"animations":{"parallel0":{"bones":{
                  "FirstPersonArm":{"scale":[1,1,1]}
                }}}}
                """);

        ModelBundle loaded = LocalModelRepository.load(root, "layered/outfit");

        assertNotNull(loaded);
        assertEquals(Set.of("Jacket"),
                loaded.animations().get("parallel0").boneTracks().keySet());
    }
}
