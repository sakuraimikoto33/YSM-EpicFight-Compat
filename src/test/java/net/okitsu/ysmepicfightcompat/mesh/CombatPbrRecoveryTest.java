package net.okitsu.ysmepicfightcompat.mesh;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.okitsu.ysmepicfightcompat.integration.oculus.CompatPbrTexture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("CombatMeshCache.lifecycle")
class CombatPbrRecoveryTest {
    private static final String MODEL = "pbr_recovery_test:model";
    private static final ResourceLocation STALE = ResourceLocation.parse("pbr_recovery_test:stale");
    private static final ResourceLocation HEALTHY = ResourceLocation.parse("pbr_recovery_test:healthy");

    @Test
    void replacementMustBeUploadedRegisteredAsPbrAndStillOpen() throws Exception {
        assertFalse(RenderSystem.isOnRenderThread(), "This fixture does not create a GL context");
        Set<String> uploaded = field("UPLOADED");
        CompatPbrTexture.ImageData companion;
        try (NativeImage image = image()) {
            companion = CompatPbrTexture.ImageData.copyOf(image);
        }
        // The image constructor queues its upload off-thread; no render calls are replayed here.
        try (CompatPbrTexture texture = new CompatPbrTexture(image(), null, companion)) {
            assertFalse(CombatMeshCache.isUploadedPbrTexture(STALE, texture));
            uploaded.add(STALE.toString());
            assertFalse(CombatMeshCache.isUploadedPbrTexture(STALE, null));
            assertFalse(CombatMeshCache.isUploadedPbrTexture(STALE, new PlainTexture()));
            assertTrue(CombatMeshCache.isUploadedPbrTexture(STALE, texture));
            texture.close();
            assertFalse(CombatMeshCache.isUploadedPbrTexture(STALE, texture));
        } finally {
            uploaded.remove(STALE.toString());
        }
    }

    @Test
    void healthyVariantCannotCancelAnotherVariantsPendingRecovery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.recovering.add(STALE.toString());
            var claim = fixture.uploads.claim(STALE.toString());
            assertNotNull(claim);
            fixture.uploaded.add(HEALTHY.toString());

            invoke("releaseUploadedFallbacks");

            assertTrue(fixture.uploads.isCurrent(claim));
            assertTrue(fixture.recovering.contains(STALE.toString()));
            assertFalse(fixture.uploaded.contains(HEALTHY.toString()));
            assertTrue(fixture.retiring.containsKey(HEALTHY.toString()));

            fixture.recovering.remove(STALE.toString()); // The selected official companion recovered.
            invoke("releaseUploadedFallbacks");
            assertFalse(fixture.uploads.isCurrent(claim));
        }
    }

    @Test
    void evictingAModelRemovesItsRecoveryMarkersAndPendingUpload() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.recovering.add(STALE.toString());
            var claim = fixture.uploads.claim(STALE.toString());
            assertNotNull(claim);

            invoke("discardFallbacks");

            assertFalse(fixture.recovering.contains(STALE.toString()));
            assertFalse(fixture.locations.containsKey(MODEL + "#stale"));
            assertFalse(fixture.uploads.isCurrent(claim));
        }
    }

    private static NativeImage image() {
        return new NativeImage(NativeImage.Format.RGBA, 1, 1, true);
    }

    private static void invoke(String name) throws Exception {
        Method method = CombatMeshCache.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        method.invoke(null, MODEL);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(String name) throws Exception {
        Field field = CombatMeshCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static final class Fixture implements AutoCloseable {
        final Set<String> uploaded = field("UPLOADED");
        final Set<String> recovering = field("PBR_RECOVERY_LOCATIONS");
        final Map<String, ResourceLocation> locations = field("FALLBACK_LOCATIONS");
        final Map<String, Integer> retiring = field("RELEASE_AFTER_TICKS");
        final FrameUploadQueue<String, Object> uploads = field("TEXTURE_UPLOADS");

        Fixture() throws Exception {
            locations.put(MODEL + "#stale", STALE);
            locations.put(MODEL + "#healthy", HEALTHY);
        }

        @Override
        public void close() {
            for (ResourceLocation location : new ResourceLocation[]{STALE, HEALTHY}) {
                String key = location.toString();
                uploads.cancel(key);
                uploaded.remove(key);
                recovering.remove(key);
                retiring.remove(key);
            }
            locations.remove(MODEL + "#stale");
            locations.remove(MODEL + "#healthy");
        }
    }

    private static final class PlainTexture extends AbstractTexture {
        @Override public void load(ResourceManager resources) { }
    }
}
