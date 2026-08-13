package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkinMeshCompilerTest {
    @Test
    void scalesPositionsWithoutChangingNormalsOrUvs() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        head.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(1, 2, 3), new Vector3f(4, 5, 6),
                new Vector3f(7, 8, 9), new Vector3f(10, 11, 12)},
                new float[][]{{0.1F, 0.2F}, {0.3F, 0.2F},
                        {0.3F, 0.4F}, {0.1F, 0.4F}},
                new Vector3f(0.25F, 0.5F, 0.75F)));
        geometry.add(head);
        geometry.linkHierarchy();
        ModelBundle model = ModelBundle.remote("test", geometry, null, 2.0F, 3.0F, "");

        SkinMeshCompiler.Result result = SkinMeshCompiler.compile(model);

        assertNotNull(result);
        Number[] positions = result.arrays().get("positions");
        Number[] normals = result.arrays().get("normals");
        Number[] uvs = result.arrays().get("uvs");
        assertEquals(2.0F, positions[0].floatValue());
        assertEquals(6.0F, positions[1].floatValue());
        assertEquals(6.0F, positions[2].floatValue());
        assertEquals(0.25F, normals[0].floatValue());
        assertEquals(0.5F, normals[1].floatValue());
        assertEquals(0.75F, normals[2].floatValue());
        assertEquals(0.1F, uvs[0].floatValue());
        assertEquals(0.2F, uvs[1].floatValue());
        assertEquals(1, result.faceCount());
    }
}
