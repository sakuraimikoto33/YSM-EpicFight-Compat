package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.client.model.transformer.VanillaModelTransformer.VanillaMeshPartDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bakes model faces into the array layout consumed by Epic Fight's HumanoidMesh. */
public final class SkinMeshCompiler {
    public static final String BONE_PART_PREFIX = "ysm-bone/";
    private static final List<String> VANILLA_PARTS = List.of(
            "head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg",
            "hat", "jacket", "leftSleeve", "rightSleeve", "leftPants", "rightPants");

    public record Result(Map<String, Number[]> arrays,
                         Map<MeshPartDefinition, List<VertexBuilder>> parts,
                         AuxiliaryBoneLayout auxiliaryBones,
                         int faceCount) {
    }

    private record VertexFingerprint(int x, int y, int z, int normalX, int normalY,
                                     int normalZ, int u, int v, int joint) {
    }

    private record Visit(GeometryDocument.Bone bone, Matrix4f parentTransform) {
    }

    private SkinMeshCompiler() {
    }

    public static Result compile(ModelBundle model) {
        GeometryDocument geometry = model.geometry();
        if (geometry == null) {
            return null;
        }
        Accumulator output = new Accumulator();
        AuxiliaryBoneLayout auxiliaryBones = AuxiliaryBoneLayout.create(
                geometry, model.widthScale(), model.heightScale());
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        List<GeometryDocument.Bone> roots = geometry.roots();
        for (int index = roots.size() - 1; index >= 0; index--) {
            pending.push(new Visit(roots.get(index), new Matrix4f()));
        }
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            Matrix4f transform = bindTransform(visit.parentTransform(), visit.bone());
            output.append(visit.bone(), transform, auxiliaryBones,
                    model.widthScale(), model.heightScale());
            List<GeometryDocument.Bone> children = visit.bone().children();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(new Visit(children.get(index), transform));
            }
        }
        return output.finish(auxiliaryBones);
    }

    public static String partName(GeometryDocument.Bone bone) {
        return BONE_PART_PREFIX + bone.name();
    }

    private static Matrix4f bindTransform(Matrix4f parent, GeometryDocument.Bone bone) {
        return new Matrix4f(parent)
                .translate(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                .rotateZ(bone.rotationZ()).rotateY(bone.rotationY()).rotateX(bone.rotationX())
                .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
    }

    private static final class Accumulator {
        private final List<Float> positions = new ArrayList<>();
        private final List<Float> normals = new ArrayList<>();
        private final List<Float> textureCoordinates = new ArrayList<>();
        private final List<Integer> influenceCounts = new ArrayList<>();
        private final List<Integer> influences = new ArrayList<>();
        private final Map<VertexFingerprint, Integer> vertices = new HashMap<>();
        private final Map<String, List<Integer>> trianglesByPart = new LinkedHashMap<>();
        private int faces;

        private void append(GeometryDocument.Bone bone, Matrix4f transform,
                            AuxiliaryBoneLayout auxiliaryBones,
                            float horizontalScale, float verticalScale) {
            if (bone.faces().isEmpty()) {
                return;
            }
            int joint = auxiliaryBones.poseIndexFor(bone);
            List<Integer> triangles = trianglesByPart.computeIfAbsent(
                    partName(bone), ignored -> new ArrayList<>());
            for (GeometryDocument.Face face : bone.faces()) {
                faces++;
                int[] corner = new int[4];
                for (int index = 0; index < corner.length; index++) {
                    Vector3f position = new Vector3f(face.positions()[index]).mulPosition(transform);
                    Vector3f normal = new Vector3f(face.normal()).mulDirection(transform);
                    position.mul(horizontalScale, verticalScale, horizontalScale);
                    corner[index] = vertex(position, normal,
                            face.textureCoordinates()[index], joint);
                }
                appendTriangle(triangles, corner[0], corner[1], corner[2]);
                appendTriangle(triangles, corner[2], corner[3], corner[0]);
            }
        }

        private int vertex(Vector3f position, Vector3f normal, float[] uv, int joint) {
            VertexFingerprint fingerprint = new VertexFingerprint(
                    Math.round(position.x() * 1000.0F), Math.round(position.y() * 1000.0F),
                    Math.round(position.z() * 1000.0F), Math.round(normal.x() * 100.0F),
                    Math.round(normal.y() * 100.0F), Math.round(normal.z() * 100.0F),
                    Math.round(uv[0] * 4096.0F), Math.round(uv[1] * 4096.0F), joint);
            Integer known = vertices.get(fingerprint);
            if (known != null) {
                return known;
            }
            int index = positions.size() / 3;
            positions.add(position.x());
            positions.add(position.y());
            positions.add(position.z());
            normals.add(normal.x());
            normals.add(normal.y());
            normals.add(normal.z());
            textureCoordinates.add(uv[0]);
            textureCoordinates.add(uv[1]);
            influenceCounts.add(1);
            influences.add(joint);
            influences.add(0);
            vertices.put(fingerprint, index);
            return index;
        }

        private static void appendTriangle(List<Integer> target, int a, int b, int c) {
            for (int vertex : new int[]{a, b, c}) {
                target.add(vertex);
                target.add(vertex);
                target.add(vertex);
            }
        }

        private Result finish(AuxiliaryBoneLayout auxiliaryBones) {
            if (positions.isEmpty()) {
                return null;
            }
            Map<String, Number[]> arrays = new LinkedHashMap<>();
            arrays.put("positions", positions.toArray(Float[]::new));
            arrays.put("normals", normals.toArray(Float[]::new));
            arrays.put("uvs", textureCoordinates.toArray(Float[]::new));
            arrays.put("vcounts", influenceCounts.toArray(Integer[]::new));
            arrays.put("vindices", influences.toArray(Integer[]::new));
            arrays.put("weights", new Float[]{1.0F});

            Map<MeshPartDefinition, List<VertexBuilder>> parts = new LinkedHashMap<>();
            for (String part : VANILLA_PARTS) {
                parts.put(VanillaMeshPartDefinition.of(part), List.of());
            }
            trianglesByPart.forEach((name, indices) -> parts.put(
                    VanillaMeshPartDefinition.of(name), VertexBuilder.create(
                            indices.stream().mapToInt(Integer::intValue).toArray())));
            return new Result(Map.copyOf(arrays), Map.copyOf(parts), auxiliaryBones, faces);
        }
    }
}
