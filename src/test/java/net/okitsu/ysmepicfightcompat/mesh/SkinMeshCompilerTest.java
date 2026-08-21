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
        assertEquals(1, result.auxiliaryBones().entries().size());
        assertEquals(HumanoidRig.EPIC_JOINT_COUNT,
                result.arrays().get("vindices")[0].intValue());
    }

    @Test
    void assignsEveryModelBoneADistinctPrivatePoseIndex() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = faceBone("head");
        GeometryDocument.Bone ear = faceBone("ear");
        ear.parentName("head");
        GeometryDocument.Bone tail = faceBone("tail");
        tail.parentName("body");
        GeometryDocument.Bone body = faceBone("body");
        geometry.add(head);
        geometry.add(ear);
        geometry.add(body);
        geometry.add(tail);
        geometry.linkHierarchy();
        ModelBundle model = ModelBundle.remote("test", geometry, null, 1.0F, 1.0F, "");

        SkinMeshCompiler.Result result = SkinMeshCompiler.compile(model);

        assertNotNull(result);
        assertEquals(4, result.auxiliaryBones().entries().size());
        Number[] influences = result.arrays().get("vindices");
        assertEquals(HumanoidRig.EPIC_JOINT_COUNT, influences[0].intValue());
        assertEquals(HumanoidRig.EPIC_JOINT_COUNT + 1, influences[4 * 2].intValue());
        assertEquals(HumanoidRig.EPIC_JOINT_COUNT + 2, influences[8 * 2].intValue());
        assertEquals(HumanoidRig.EPIC_JOINT_COUNT + 3, influences[12 * 2].intValue());
    }

    private static GeometryDocument.Bone faceBone(String name) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0)},
                new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}},
                new Vector3f(0, 0, 1)));
        return bone;
    }
}
