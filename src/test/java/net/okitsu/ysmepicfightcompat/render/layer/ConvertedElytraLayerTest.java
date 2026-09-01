package net.okitsu.ysmepicfightcompat.render.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvertedElytraLayerTest {
    @Test
    void fallsBackOnlyWhenNoConvertedMeshOwnsTheRender() {
        assertEquals(ConvertedElytraLayer.RenderPath.FALLBACK,
                ConvertedElytraLayer.renderPath(false, false));
        assertEquals(ConvertedElytraLayer.RenderPath.HIDE,
                ConvertedElytraLayer.renderPath(true, false));
        assertEquals(ConvertedElytraLayer.RenderPath.LOCATOR,
                ConvertedElytraLayer.renderPath(true, true));
    }

    @Test
    void keepsTheElytraRootAtTheLocatorAndCancelsTheVanillaOffset() {
        PoseStack matrices = new PoseStack();
        OpenMatrix4f locator = new OpenMatrix4f()
                .translate(2.0F, 3.0F, 4.0F)
                .rotateDeg(25.0F, Vec3f.Y_AXIS)
                .scale(0.7F, 0.8F, 0.7F);

        ConvertedElytraLayer.applyLocatorTransform(matrices, locator);
        matrices.translate(0.0F, 0.0F, 0.125F);

        Matrix4f expected = OpenMatrix4f.exportToMojangMatrix(locator)
                .rotateZ((float) Math.PI);
        assertTrue(matrices.last().pose().equals(expected, 0.0001F),
                "the elytra root must stay on the animated ElytraLocator origin");
    }
}
