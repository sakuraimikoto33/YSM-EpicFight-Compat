package net.okitsu.ysmepicfightcompat.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Converts Bedrock geometry 1.12 JSON into Minecraft-space faces. */
public final class BedrockGeometryParser {
    private static final double EMPTY_UV_EPSILON = 1.0E-8D;
    private static final float EMPTY_FACE_EPSILON = 1.0E-8F;

    private enum Side {
        WEST(new int[]{3, 2, 0, 1}, -1, 0, 0),
        EAST(new int[]{6, 7, 5, 4}, 1, 0, 0),
        NORTH(new int[]{2, 6, 4, 0}, 0, 0, -1),
        SOUTH(new int[]{7, 3, 1, 5}, 0, 0, 1),
        UP(new int[]{3, 7, 6, 2}, 0, 1, 0),
        DOWN(new int[]{0, 4, 5, 1}, 0, -1, 0);

        private final int[] corners;
        private final Vector3f normal;

        Side(int[] corners, float x, float y, float z) {
            this.corners = corners;
            normal = new Vector3f(x, y, z);
        }
    }

    private BedrockGeometryParser() {
    }

    public static GeometryDocument parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty() || !geometries.get(0).isJsonObject()) {
            return null;
        }
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonArray boneArray = geometry.getAsJsonArray("bones");
        if (boneArray == null) {
            return null;
        }

        GeometryDocument result = new GeometryDocument();
        JsonObject description = geometry.getAsJsonObject("description");
        if (description != null) {
            result.textureSize(number(description, "texture_width", 64),
                    number(description, "texture_height", 64));
        }
        for (JsonElement element : boneArray) {
            if (element.isJsonObject()) {
                result.add(readBone(result, element.getAsJsonObject()));
            }
        }
        result.linkHierarchy();
        return result;
    }

    private static GeometryDocument.Bone readBone(GeometryDocument document, JsonObject source) {
        String name = source.get("name").getAsString();
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        if (source.has("parent")) {
            bone.parentName(source.get("parent").getAsString());
        }
        float[] pivot = vector(source, "pivot");
        bone.pivot(-pivot[0] / 16.0F, pivot[1] / 16.0F, pivot[2] / 16.0F);
        float[] rotation = vector(source, "rotation");
        bone.rotation(radians(-rotation[0]), radians(-rotation[1]), radians(rotation[2]));

        boolean inheritedMirror = flag(source, "mirror");
        float inheritedInflate = scalar(source, "inflate", 0.0F) / 16.0F;
        JsonArray cubes = source.getAsJsonArray("cubes");
        if (cubes != null) {
            for (JsonElement element : cubes) {
                if (element.isJsonObject()) {
                    bone.faces().addAll(readCube(document, element.getAsJsonObject(),
                            inheritedMirror, inheritedInflate));
                }
            }
        }
        return bone;
    }

    private static List<GeometryDocument.Face> readCube(
            GeometryDocument document, JsonObject source,
            boolean boneMirror, float boneInflate) {
        float[] origin = vector(source, "origin");
        float[] size = vector(source, "size");
        boolean cubeMirror = flag(source, "mirror");
        boolean swapGeometry = boneMirror || cubeMirror;
        float inflate = source.has("inflate")
                ? source.get("inflate").getAsFloat() / 16.0F : boneInflate;

        float minX = -(origin[0] + size[0]) / 16.0F - inflate;
        float minY = origin[1] / 16.0F - inflate;
        float minZ = origin[2] / 16.0F - inflate;
        float maxX = minX + size[0] / 16.0F + inflate * 2.0F;
        float maxY = minY + size[1] / 16.0F + inflate * 2.0F;
        float maxZ = minZ + size[2] / 16.0F + inflate * 2.0F;
        Vector3f[] corners = {
                new Vector3f(minX, minY, minZ), new Vector3f(minX, minY, maxZ),
                new Vector3f(minX, maxY, minZ), new Vector3f(minX, maxY, maxZ),
                new Vector3f(maxX, minY, minZ), new Vector3f(maxX, minY, maxZ),
                new Vector3f(maxX, maxY, minZ), new Vector3f(maxX, maxY, maxZ)
        };

        JsonElement uvElement = source.get("uv");
        JsonObject perFace = uvElement != null && uvElement.isJsonObject()
                ? uvElement.getAsJsonObject() : null;
        double[] boxOrigin = uvElement != null && uvElement.isJsonArray()
                ? pair(uvElement.getAsJsonArray()) : new double[]{0.0D, 0.0D};
        List<GeometryDocument.Face> faces = new ArrayList<>(6);
        float extentX = Math.abs(maxX - minX);
        float extentY = Math.abs(maxY - minY);
        float extentZ = Math.abs(maxZ - minZ);
        boolean[] collapsedPairEmitted = new boolean[3];
        for (Side side : Side.values()) {
            if (!hasGeometricArea(side, extentX, extentY, extentZ)) {
                continue;
            }
            double[] rectangle = perFace == null
                    ? boxRectangle(side, boxOrigin, size)
                    : faceRectangle(perFace, side.name().toLowerCase());
            if (rectangle == null) {
                continue;
            }
            int collapsedAxis = collapsedNormalAxis(side, extentX, extentY, extentZ);
            if (collapsedAxis >= 0 && collapsedPairEmitted[collapsedAxis]) {
                // Both UV entries of a zero-thickness cube can be populated even though
                // they describe the same plane. Rendering both produces the dense line
                // artifacts seen on official YSM glow circles through depth fighting.
                continue;
            }
            if (collapsedAxis >= 0) {
                collapsedPairEmitted[collapsedAxis] = true;
            }
            Side geometrySide = mirroredSide(side, swapGeometry, perFace != null);
            faces.add(face(corners, geometrySide.corners, rectangle,
                    document.textureWidth(), document.textureHeight(), cubeMirror, side.normal));
        }

        float[] cubeRotation = vector(source, "rotation");
        if (cubeRotation[0] != 0.0F || cubeRotation[1] != 0.0F || cubeRotation[2] != 0.0F) {
            float[] pivot = vector(source, "pivot");
            float px = -pivot[0] / 16.0F;
            float py = pivot[1] / 16.0F;
            float pz = pivot[2] / 16.0F;
            Matrix4f transform = new Matrix4f().translate(px, py, pz)
                    .rotateZ(radians(cubeRotation[2]))
                    .rotateY(radians(-cubeRotation[1]))
                    .rotateX(radians(-cubeRotation[0]))
                    .translate(-px, -py, -pz);
            for (GeometryDocument.Face face : faces) {
                for (Vector3f position : face.positions()) {
                    position.mulPosition(transform);
                }
                face.normal().mulDirection(transform);
            }
        }
        return faces;
    }

    private static boolean hasGeometricArea(Side side, float x, float y, float z) {
        return switch (side) {
            case WEST, EAST -> y > EMPTY_FACE_EPSILON && z > EMPTY_FACE_EPSILON;
            case NORTH, SOUTH -> x > EMPTY_FACE_EPSILON && y > EMPTY_FACE_EPSILON;
            case UP, DOWN -> x > EMPTY_FACE_EPSILON && z > EMPTY_FACE_EPSILON;
        };
    }

    private static int collapsedNormalAxis(Side side, float x, float y, float z) {
        return switch (side) {
            case WEST, EAST -> x <= EMPTY_FACE_EPSILON ? 0 : -1;
            case UP, DOWN -> y <= EMPTY_FACE_EPSILON ? 1 : -1;
            case NORTH, SOUTH -> z <= EMPTY_FACE_EPSILON ? 2 : -1;
        };
    }

    private static Side mirroredSide(Side side, boolean mirrored, boolean perFaceUv) {
        if (!mirrored) {
            return side;
        }
        return switch (side) {
            case WEST -> Side.EAST;
            case EAST -> Side.WEST;
            case UP -> perFaceUv ? Side.DOWN : Side.UP;
            case DOWN -> perFaceUv ? Side.UP : Side.DOWN;
            default -> side;
        };
    }

    private static GeometryDocument.Face face(
            Vector3f[] cubeCorners, int[] order, double[] rectangle,
            int textureWidth, int textureHeight, boolean reverseU, Vector3f sourceNormal) {
        Vector3f[] positions = new Vector3f[4];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = new Vector3f(cubeCorners[order[i]]);
        }
        float left = (float) (rectangle[0] / textureWidth);
        float top = (float) (rectangle[1] / textureHeight);
        float right = (float) ((rectangle[0] + rectangle[2]) / textureWidth);
        float bottom = (float) ((rectangle[1] + rectangle[3]) / textureHeight);
        float[][] uv = reverseU
                ? new float[][]{{left, top}, {right, top}, {right, bottom}, {left, bottom}}
                : new float[][]{{right, top}, {left, top}, {left, bottom}, {right, bottom}};
        Vector3f normal = new Vector3f(sourceNormal);
        if (reverseU) {
            normal.mul(-1.0F, 1.0F, 1.0F);
        }
        return new GeometryDocument.Face(positions, uv, normal);
    }

    private static double[] boxRectangle(Side side, double[] uv, float[] size) {
        double x = Math.floor(size[0]);
        double y = Math.floor(size[1]);
        double z = Math.floor(size[2]);
        double u = uv[0];
        double v = uv[1];
        return switch (side) {
            case WEST -> rectangle(u + z + x, v + z, z, y);
            case EAST -> rectangle(u, v + z, z, y);
            case NORTH -> rectangle(u + z, v + z, x, y);
            case SOUTH -> rectangle(u + z + x + z, v + z, x, y);
            case UP -> rectangle(u + z, v, x, z);
            case DOWN -> rectangle(u + z + x, v + z, x, -z);
        };
    }

    private static double[] faceRectangle(JsonObject faces, String side) {
        JsonObject face = faces.getAsJsonObject(side);
        if (face == null || !face.has("uv") || !face.has("uv_size")) {
            return null;
        }
        double[] start = pair(face.getAsJsonArray("uv"));
        double[] size = pair(face.getAsJsonArray("uv_size"));
        return rectangle(start[0], start[1], size[0], size[1]);
    }

    private static double[] pair(JsonArray array) {
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble()};
    }

    private static double[] rectangle(double u, double v, double width, double height) {
        // Blockbench uses an explicitly zero-sized per-face UV to disable the opposite
        // side of a zero-thickness plane. Keeping that face creates two coplanar quads;
        // the disabled face then z-fights with the textured face (most visibly on YSM
        // glow planes such as 05_magical's bow magic circle).
        if (Math.abs(width) <= EMPTY_UV_EPSILON
                || Math.abs(height) <= EMPTY_UV_EPSILON) {
            return null;
        }
        return new double[]{u, v, width, height};
    }

    private static float[] vector(JsonObject source, String key) {
        JsonArray value = source.getAsJsonArray(key);
        if (value == null || value.size() < 3) {
            return new float[3];
        }
        return new float[]{value.get(0).getAsFloat(), value.get(1).getAsFloat(),
                value.get(2).getAsFloat()};
    }

    private static int number(JsonObject source, String key, int fallback) {
        return source.has(key) ? source.get(key).getAsInt() : fallback;
    }

    private static float scalar(JsonObject source, String key, float fallback) {
        return source.has(key) ? source.get(key).getAsFloat() : fallback;
    }

    private static boolean flag(JsonObject source, String key) {
        return source.has(key) && source.get(key).getAsBoolean();
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
