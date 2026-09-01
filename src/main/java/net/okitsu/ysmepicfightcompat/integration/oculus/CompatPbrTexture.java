package net.okitsu.ysmepicfightcompat.integration.oculus;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

import javax.annotation.Nullable;

/**
 * Dynamic base texture whose optional LabPBR companions can be recreated after
 * an Oculus/Iris texture reload.
 */
public final class CompatPbrTexture extends DynamicTexture {
    @Nullable
    private final ImageData normal;
    @Nullable
    private final ImageData specular;

    public CompatPbrTexture(NativeImage base, @Nullable ImageData normal,
                            @Nullable ImageData specular) {
        super(base);
        if (normal == null && specular == null) {
            throw new IllegalArgumentException("At least one PBR companion is required");
        }
        this.normal = normal;
        this.specular = specular;
    }

    @Nullable
    DynamicTexture createNormalTexture() {
        return normal == null ? null : normal.createTexture();
    }

    @Nullable
    DynamicTexture createSpecularTexture() {
        return specular == null ? null : specular.createTexture();
    }

    /** Immutable RGBA snapshot created off the render thread after image decoding. */
    public static final class ImageData {
        private final int width;
        private final int height;
        private final int[] pixels;

        private ImageData(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        public static ImageData copyOf(NativeImage image) {
            if (image == null) {
                throw new IllegalArgumentException("Image must not be null");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[Math.multiplyExact(width, height)];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = image.getPixelRGBA(x, y);
                }
            }
            return new ImageData(width, height, pixels);
        }

        NativeImage createImage() {
            NativeImage image = new NativeImage(
                    NativeImage.Format.RGBA, width, height, true);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setPixelRGBA(x, y, pixels[y * width + x]);
                }
            }
            return image;
        }

        private DynamicTexture createTexture() {
            return new DynamicTexture(createImage());
        }
    }
}
