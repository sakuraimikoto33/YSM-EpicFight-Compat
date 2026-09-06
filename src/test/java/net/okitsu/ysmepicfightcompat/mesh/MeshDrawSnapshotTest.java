package net.okitsu.ysmepicfightcompat.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MeshDrawSnapshotTest {
    @Test
    void protectsPoseAndVisibilityFromNestedDrawsWithoutResampling() {
        OpenMatrix4f transform = new OpenMatrix4f().translate(2, 3, 4);
        MeshPart part = new MeshPart(List.of(), null, () -> transform) {
            @Override
            public void draw(PoseStack matrices, VertexConsumer buffer,
                             Mesh.DrawingFunction draw, int light,
                             float red, float green, float blue, float alpha, int overlay) {
            }
        };
        part.setHidden(true);
        OpenMatrix4f[] original = {new OpenMatrix4f().translate(1, 2, 3), null};
        MeshDrawSnapshot snapshot = new MeshDrawSnapshot(original,
                Set.of(Map.entry("part", part)));
        original[0].m30 = 99;
        part.setHidden(false);
        transform.m31 = 99;
        snapshot.restoreParts();
        assertTrue(part.isHidden());
        assertEquals(3, transform.m31);
        assertEquals(1, snapshot.poses()[0].m30);
        assertNull(snapshot.poses()[1]);
        assertNotSame(original, snapshot.poses());
    }
}
