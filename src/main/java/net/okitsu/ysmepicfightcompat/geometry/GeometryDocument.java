package net.okitsu.ysmepicfightcompat.geometry;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory geometry shared by local package loading, network transfer and mesh baking. */
public final class GeometryDocument {
    public static final class Bone {
        private final String name;
        private String parentName = "";
        private Bone parent;
        private final List<Bone> children = new ArrayList<>();
        private final List<Face> faces = new ArrayList<>();
        private float pivotX;
        private float pivotY;
        private float pivotZ;
        private float rotationX;
        private float rotationY;
        private float rotationZ;

        public Bone(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public String parentName() {
            return parentName;
        }

        public void parentName(String value) {
            parentName = value == null ? "" : value;
        }

        public Bone parent() {
            return parent;
        }

        void parent(Bone value) {
            parent = value;
        }

        public List<Bone> children() {
            return children;
        }

        public List<Face> faces() {
            return faces;
        }

        public float pivotX() {
            return pivotX;
        }

        public float pivotY() {
            return pivotY;
        }

        public float pivotZ() {
            return pivotZ;
        }

        public void pivot(float x, float y, float z) {
            pivotX = x;
            pivotY = y;
            pivotZ = z;
        }

        public float rotationX() {
            return rotationX;
        }

        public float rotationY() {
            return rotationY;
        }

        public float rotationZ() {
            return rotationZ;
        }

        public void rotation(float x, float y, float z) {
            rotationX = x;
            rotationY = y;
            rotationZ = z;
        }
    }

    public static final class Face {
        private final Vector3f[] positions;
        private final float[][] textureCoordinates;
        private final Vector3f normal;

        public Face(Vector3f[] positions, float[][] textureCoordinates, Vector3f normal) {
            if (positions.length != 4 || textureCoordinates.length != 4) {
                throw new IllegalArgumentException("A model face must contain four corners");
            }
            this.positions = positions;
            this.textureCoordinates = textureCoordinates;
            this.normal = normal;
        }

        public Vector3f[] positions() {
            return positions;
        }

        public float[][] textureCoordinates() {
            return textureCoordinates;
        }

        public Vector3f normal() {
            return normal;
        }
    }

    private int textureWidth = 64;
    private int textureHeight = 64;
    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private final List<Bone> roots = new ArrayList<>();

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    public void textureSize(int width, int height) {
        textureWidth = width > 0 ? width : 64;
        textureHeight = height > 0 ? height : 64;
    }

    public Map<String, Bone> bones() {
        return bones;
    }

    public List<Bone> roots() {
        return roots;
    }

    public void add(Bone bone) {
        if (bones.putIfAbsent(bone.name(), bone) != null) {
            throw new IllegalArgumentException("Duplicate model bone: " + bone.name());
        }
    }

    public void linkHierarchy() {
        roots.clear();
        for (Bone bone : bones.values()) {
            bone.children().clear();
            Bone parent = bone.parentName().isEmpty() ? null : bones.get(bone.parentName());
            bone.parent(parent);
            if (parent == null) {
                roots.add(bone);
            } else {
                parent.children().add(bone);
            }
        }
    }
}
