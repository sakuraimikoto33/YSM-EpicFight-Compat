package net.okitsu.ysmepicfightcompat.assets;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class OfficialTextureResolverTest {
    @Test
    void acceptsExactFilenameStemAndPathSelections() {
        TestTexture standard = new TestTexture();
        TestTexture alternate = new TestTexture();
        Map<String, AbstractTexture> available = new LinkedHashMap<>();
        available.put("default.png", standard);
        available.put("alternate", alternate);

        assertSame(standard, OfficialTextureResolver.selectFrom(available, "default.png"));
        assertSame(alternate,
                OfficialTextureResolver.selectFrom(available, "textures/alternate.png"));
    }

    @Test
    void unknownSelectionsUseTheFirstOfficialTexture() {
        TestTexture standard = new TestTexture();
        assertSame(standard, OfficialTextureResolver.selectFrom(
                new LinkedHashMap<>(Map.of("default.png", standard)), "missing.png"));
    }

    private static final class TestTexture extends AbstractTexture {
        @Override
        public void load(ResourceManager manager) {
        }
    }
}
