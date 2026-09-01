package net.okitsu.ysmepicfightcompat.integration.oculus;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompatPbrTextureTest {
    @Test
    void companionSnapshotCanBeRecreatedAfterAResourceReload() {
        CompatPbrTexture.ImageData snapshot;
        try (NativeImage source = new NativeImage(
                NativeImage.Format.RGBA, 2, 1, true)) {
            source.setPixelRGBA(0, 0, 0x10203040);
            source.setPixelRGBA(1, 0, 0x50607080);
            snapshot = CompatPbrTexture.ImageData.copyOf(source);
        }

        assertPixels(snapshot);
        assertPixels(snapshot);
    }

    private static void assertPixels(CompatPbrTexture.ImageData snapshot) {
        try (NativeImage restored = snapshot.createImage()) {
            assertEquals(2, restored.getWidth());
            assertEquals(1, restored.getHeight());
            assertEquals(0x10203040, restored.getPixelRGBA(0, 0));
            assertEquals(0x50607080, restored.getPixelRGBA(1, 0));
        }
    }
}
