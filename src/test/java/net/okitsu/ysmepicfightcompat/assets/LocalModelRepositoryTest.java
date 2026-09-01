package net.okitsu.ysmepicfightcompat.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelRepositoryTest {
    @Test
    void associatesManifestPbrTexturesWithoutExposingThemAsSkins(@TempDir Path root)
            throws IOException {
        Path model = root.resolve("custom/pbr/saint");
        Files.createDirectories(model.resolve("models"));
        Files.createDirectories(model.resolve("textures/pbr"));
        Files.writeString(model.resolve("ysm.json"), """
                {"files":{"player":{
                  "model":{"main":"models/main.json"},
                  "texture":[{
                    "uv":"textures/skin.png",
                    "normal":"textures/pbr/skin_height.png",
                    "specular":"textures/pbr/skin_mer.png"
                  }]
                }}}
                """);
        Files.writeString(model.resolve("models/main.json"), """
                {"minecraft:geometry":[{
                  "description":{"texture_width":16,"texture_height":16},
                  "bones":[{"name":"root"}]
                }]}
                """);
        Files.write(model.resolve("textures/skin.png"), new byte[]{1, 2, 3});
        Files.write(model.resolve("textures/pbr/skin_height.png"), new byte[]{4, 5});
        Files.write(model.resolve("textures/pbr/skin_mer.png"), new byte[]{6, 7});

        byte[] digestBefore = LocalModelRepository.contentDigest(root, "pbr/saint");
        ModelBundle loaded = LocalModelRepository.load(root, "pbr/saint");

        assertNotNull(loaded);
        assertEquals(Set.of("skin"), loaded.textures().keySet());
        ModelBundle.PbrTextures pbr = loaded.pbrTextures().get("skin");
        assertNotNull(pbr);
        assertArrayEquals(new byte[]{4, 5}, pbr.normal().bytes());
        assertArrayEquals(new byte[]{6, 7}, pbr.specular().bytes());

        Files.write(model.resolve("textures/pbr/skin_mer.png"), new byte[]{8, 9});
        assertFalse(Arrays.equals(digestBefore,
                LocalModelRepository.contentDigest(root, "pbr/saint")));
    }

    @Test
    void replacingASelectableTextureAlsoReplacesItsPbrAssociation(@TempDir Path root)
            throws IOException {
        Path model = root.resolve("custom/pbr/replaced");
        Files.createDirectories(model.resolve("models"));
        Files.createDirectories(model.resolve("textures/first"));
        Files.createDirectories(model.resolve("textures/second"));
        Files.writeString(model.resolve("ysm.json"), """
                {"files":{"player":{
                  "model":{"main":"models/main.json"},
                  "texture":[
                    {"uv":"textures/first/skin.png",
                     "normal":"textures/first/normal.png"},
                    "textures/second/skin.png"
                  ]
                }}}
                """);
        Files.writeString(model.resolve("models/main.json"), """
                {"minecraft:geometry":[{
                  "description":{"texture_width":16,"texture_height":16},
                  "bones":[{"name":"root"}]
                }]}
                """);
        Files.write(model.resolve("textures/first/skin.png"), new byte[]{1});
        Files.write(model.resolve("textures/first/normal.png"), new byte[]{2});
        Files.write(model.resolve("textures/second/skin.png"), new byte[]{3});

        ModelBundle loaded = LocalModelRepository.load(root, "pbr/replaced");

        assertNotNull(loaded);
        assertArrayEquals(new byte[]{3}, loaded.textures().get("skin"));
        assertFalse(loaded.pbrTextures().containsKey("skin"));
    }

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
        Files.createDirectories(model.resolve("controller"));
        Files.writeString(model.resolve("ysm.json"), """
                {
                  "files":{"player":{
                    "model":{"main":"models/main.json"},
                    "animation":{
                      "main":"animations/main.animation.json",
                      "fp_arm":"animations/fp.arm.animation.json"
                    },
                    "animation_controllers":["controller/main.animation_controllers.json"]
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
                {"animations":{
                  "parallel0":{"bones":{"Jacket":{"scale":[0,0,0]}}},
                  "extra0":{"animation_length":1.0,"bones":{"Jacket":{"rotation":[0,0,20]}}}
                }}
                """);
        Files.writeString(model.resolve("animations/fp.arm.animation.json"), """
                {"animations":{"parallel0":{"bones":{
                  "FirstPersonArm":{"scale":[1,1,1]}
                }}}}
                """);
        Files.writeString(model.resolve("controller/main.animation_controllers.json"), """
                {"format_version":"1.19.0","animation_controllers":{
                  "player.parallel_4":{"initial_state":"default","states":{
                    "default":{"animations":["extra0"],"transitions":[
                      {"hidden":"v.roaming.jacket==0"}
                    ]},
                    "hidden":{"on_entry":["v.hidden=1;"]}
                  }}
                }}
                """);

        ModelBundle loaded = LocalModelRepository.load(root, "layered/outfit");

        assertNotNull(loaded);
        assertEquals(Set.of("Jacket"),
                loaded.animations().get("parallel0").boneTracks().keySet());
        assertEquals(Set.of("Jacket"),
                loaded.animations().get("extra0").boneTracks().keySet());
        assertEquals("default", loaded.animationControllers().get("player.parallel_4")
                .initialState());
        assertEquals("hidden", loaded.animationControllers().get("player.parallel_4")
                .states().get("default").transitions().get(0).targetState());
    }

    @Test
    void inheritsEveryMissingPrimaryAnimationGroupAndInvalidatesTheSourceDigest(
            @TempDir Path root) throws IOException {
        Path primary = root.resolve("builtin/default");
        Files.createDirectories(primary.resolve("animations"));
        Files.writeString(primary.resolve("ysm.json"), """
                {"files":{"player":{
                  "animation":{
                    "main":"animations/main.animation.json",
                    "arm":"animations/arm.animation.json",
                    "extra":"animations/extra.animation.json"
                  },
                  "animation_controllers":["controller/primary.controller.json"]
                }}}
                """);
        Files.createDirectories(primary.resolve("controller"));
        Files.writeString(primary.resolve("controller/primary.controller.json"), """
                {"animation_controllers":{"controller.animation.primary":{
                  "initial_state":"default","states":{"default":{}}
                }}}
                """);
        Files.writeString(primary.resolve("animations/main.animation.json"), """
                {"animations":{
                  "idle":{"animation_length":1.0,"bones":{}},
                  "walk":{"animation_length":2.0,"bones":{}}
                }}
                """);
        Files.writeString(primary.resolve("animations/arm.animation.json"), """
                {"animations":{"hold_mainhand:sword":{
                  "animation_length":0.5,
                  "bones":{"RightArm":{"rotation":{"0":[0,0,0],"0.5":[0,0,20]}}}
                }}}
                """);
        Files.writeString(primary.resolve("animations/extra.animation.json"), """
                {"animations":{"extra0":{"animation_length":9.0,"bones":{}}}}
                """);

        Path model = root.resolve("custom/maid");
        Files.createDirectories(model.resolve("models"));
        Files.createDirectories(model.resolve("animations"));
        Files.writeString(model.resolve("ysm.json"), """
                {"files":{"player":{
                  "model":{"main":"models/main.json"},
                  "animation":{"main":"animations/main.animation.json"}
                }}}
                """);
        Files.writeString(model.resolve("models/main.json"), """
                {"minecraft:geometry":[{"description":{
                  "texture_width":16,"texture_height":16
                },"bones":[{"name":"Root"},{"name":"RightArm","parent":"Root"}]}]}
                """);
        Files.writeString(model.resolve("animations/main.animation.json"), """
                {"animations":{"idle":{"animation_length":7.0,"bones":{}}}}
                """);

        byte[] before = LocalModelRepository.contentDigest(root, "maid");
        ModelBundle loaded = LocalModelRepository.load(root, "maid");

        assertNotNull(loaded);
        assertEquals(7.0F, loaded.animations().get("idle").duration(), 0.0001F,
                "the model-specific clip must win over the primary clip");
        assertTrue(loaded.animations().containsKey("walk"));
        assertTrue(loaded.animations().containsKey("hold_mainhand:sword"));
        assertTrue(loaded.animations().containsKey("extra0"),
                "official YSM inherits every primary player animation group");
        assertTrue(loaded.animationControllers().isEmpty(),
                "official YSM does not inherit primary animation controllers");

        Files.writeString(primary.resolve("animations/arm.animation.json"), """
                {"animations":{"hold_mainhand:sword":{
                  "animation_length":0.75,
                  "bones":{"RightArm":{"rotation":{"0":[0,0,0],"0.75":[0,0,30]}}}
                }}}
                """);
        byte[] after = LocalModelRepository.contentDigest(root, "maid");
        assertFalse(Arrays.equals(before, after),
                "a primary HOLD update must invalidate parsed client/server model caches");
    }
}
