package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Texture-free, size-limited transfer format used for server-only model geometry. */
public final class GeometryTransferCodec {
    private static final int MAGIC = 0x59454632;
    private static final int VERSION = 1;
    public static final int MAX_COMPRESSED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 256 * 1024 * 1024;
    private static final int MAX_BONES = 65_536;
    private static final int MAX_FACES = 2_000_000;
    private static final int MAX_ANIMATIONS = 65_536;
    private static final int MAX_KEYFRAMES = 2_000_000;
    private static final int MAX_TIMELINE_STATEMENTS = 1_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024;

    private GeometryTransferCodec() {
    }

    public static byte[] encode(ModelBundle model) throws IOException {
        if (model == null || model.geometry() == null) {
            throw new IOException("Missing model geometry");
        }
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(
                new OutputLimit(target, MAX_COMPRESSED_BYTES));
             DataOutputStream output = new DataOutputStream(gzip)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            finite(output, model.widthScale());
            finite(output, model.heightScale());
            string(output, model.defaultTexture());
            output.writeInt(model.geometry().textureWidth());
            output.writeInt(model.geometry().textureHeight());
            writeGeometry(output, model.geometry());
            writeAnimations(output, model.animations());
        }
        byte[] payload = target.toByteArray();
        if (payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed model transfer is too large");
        }
        return payload;
    }

    public static ModelBundle decode(String modelId, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Invalid compressed model transfer size");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload));
             InputLimit limited = new InputLimit(gzip, MAX_EXPANDED_BYTES);
             DataInputStream input = new DataInputStream(limited)) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported model transfer format");
            }
            float widthScale = finite(input);
            float heightScale = finite(input);
            String defaultTexture = string(input);
            int textureWidth = positive(input.readInt(), 65_536, "texture width");
            int textureHeight = positive(input.readInt(), 65_536, "texture height");
            GeometryDocument geometry = readGeometry(input, textureWidth, textureHeight);
            Map<String, AnimationClip> animations = readAnimations(input);
            if (input.read() != -1) {
                throw new IOException("Trailing bytes in model transfer");
            }
            return ModelBundle.remote(modelId, geometry, animations,
                    widthScale, heightScale, defaultTexture);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid model transfer", exception);
        }
    }

    private static void writeGeometry(DataOutputStream output, GeometryDocument geometry)
            throws IOException {
        bounded(geometry.bones().size(), MAX_BONES, "bone");
        output.writeInt(geometry.bones().size());
        long faceTotal = 0;
        for (GeometryDocument.Bone bone : geometry.bones().values()) {
            string(output, bone.name());
            string(output, bone.parent() == null ? "" : bone.parent().name());
            finite(output, bone.pivotX());
            finite(output, bone.pivotY());
            finite(output, bone.pivotZ());
            finite(output, bone.rotationX());
            finite(output, bone.rotationY());
            finite(output, bone.rotationZ());
            faceTotal += bone.faces().size();
            if (faceTotal > MAX_FACES) {
                throw new IOException("Model has too many faces");
            }
            output.writeInt(bone.faces().size());
            for (GeometryDocument.Face face : bone.faces()) {
                finite(output, face.normal().x());
                finite(output, face.normal().y());
                finite(output, face.normal().z());
                for (int corner = 0; corner < 4; corner++) {
                    finite(output, face.positions()[corner].x());
                    finite(output, face.positions()[corner].y());
                    finite(output, face.positions()[corner].z());
                    finite(output, face.textureCoordinates()[corner][0]);
                    finite(output, face.textureCoordinates()[corner][1]);
                }
            }
        }
    }

    private static GeometryDocument readGeometry(DataInputStream input, int textureWidth,
                                                  int textureHeight) throws IOException {
        int boneCount = bounded(input.readInt(), MAX_BONES, "bone");
        GeometryDocument geometry = new GeometryDocument();
        geometry.textureSize(textureWidth, textureHeight);
        long faceTotal = 0;
        for (int boneIndex = 0; boneIndex < boneCount; boneIndex++) {
            GeometryDocument.Bone bone = new GeometryDocument.Bone(string(input));
            if (bone.name().isBlank()) {
                throw new IOException("Empty model bone name");
            }
            bone.parentName(string(input));
            bone.pivot(finite(input), finite(input), finite(input));
            bone.rotation(finite(input), finite(input), finite(input));
            int faces = bounded(input.readInt(), MAX_FACES, "face");
            faceTotal += faces;
            if (faceTotal > MAX_FACES) {
                throw new IOException("Model has too many faces");
            }
            for (int faceIndex = 0; faceIndex < faces; faceIndex++) {
                Vector3f normal = new Vector3f(finite(input), finite(input), finite(input));
                Vector3f[] positions = new Vector3f[4];
                float[][] uv = new float[4][2];
                for (int corner = 0; corner < 4; corner++) {
                    positions[corner] = new Vector3f(finite(input), finite(input), finite(input));
                    uv[corner][0] = finite(input);
                    uv[corner][1] = finite(input);
                }
                bone.faces().add(new GeometryDocument.Face(positions, uv, normal));
            }
            try {
                geometry.add(bone);
            } catch (IllegalArgumentException exception) {
                throw new IOException(exception.getMessage(), exception);
            }
        }
        geometry.linkHierarchy();
        validateHierarchy(geometry);
        return geometry;
    }

    private static void writeAnimations(DataOutputStream output,
                                        Map<String, AnimationClip> animations) throws IOException {
        bounded(animations.size(), MAX_ANIMATIONS, "animation");
        output.writeInt(animations.size());
        long keyframes = 0;
        long statements = 0;
        for (AnimationClip clip : animations.values()) {
            string(output, clip.name());
            output.writeByte(clip.playback().wireValue());
            // Official YSM packages use +Infinity for animations without a finite end.
            // Duration is not consulted by the transferred default-form program, so keep
            // the wire format finite without rejecting an otherwise valid package.
            finite(output, Float.isFinite(clip.duration()) ? clip.duration() : 0.0F);
            writeScalar(output, clip.blendWeight());
            bounded(clip.boneTracks().size(), MAX_BONES, "animated bone");
            output.writeInt(clip.boneTracks().size());
            for (Map.Entry<String, AnimationClip.BoneTracks> entry : clip.boneTracks().entrySet()) {
                string(output, entry.getKey());
                keyframes += writeTrack(output, entry.getValue().rotation());
                keyframes += writeTrack(output, entry.getValue().position());
                keyframes += writeTrack(output, entry.getValue().scale());
                if (keyframes > MAX_KEYFRAMES) {
                    throw new IOException("Model has too many animation keyframes");
                }
            }
            output.writeInt(clip.timeline().size());
            for (AnimationClip.TimelineEvent event : clip.timeline()) {
                finite(output, event.time());
                output.writeInt(event.statements().size());
                statements += event.statements().size();
                if (statements > MAX_TIMELINE_STATEMENTS) {
                    throw new IOException("Model has too many animation statements");
                }
                for (String statement : event.statements()) {
                    string(output, statement);
                }
            }
        }
    }

    private static int writeTrack(DataOutputStream output, AnimationClip.Track track)
            throws IOException {
        output.writeBoolean(track != null);
        if (track == null) {
            return 0;
        }
        output.writeInt(track.keyframes().size());
        for (AnimationClip.Keyframe keyframe : track.keyframes()) {
            finite(output, keyframe.time());
            output.writeByte(keyframe.interpolation().wireValue());
            writeVector(output, keyframe.value());
            output.writeBoolean(keyframe.incomingValue() != null);
            if (keyframe.incomingValue() != null) {
                writeVector(output, keyframe.incomingValue());
            }
        }
        return track.keyframes().size();
    }

    private static void writeVector(DataOutputStream output, AnimationClip.VectorValue value)
            throws IOException {
        for (int axis = 0; axis < 3; axis++) {
            String expression = value.expression(axis);
            output.writeBoolean(expression != null);
            if (expression == null) {
                double constant = value.constant(axis);
                if (!Double.isFinite(constant)) {
                    throw new IOException("Non-finite animation value");
                }
                output.writeDouble(constant);
            } else {
                string(output, expression);
            }
        }
    }

    private static void writeScalar(DataOutputStream output, AnimationClip.ScalarValue value)
            throws IOException {
        String expression = value.expression();
        output.writeBoolean(expression != null);
        if (expression == null) {
            double constant = value.constant();
            if (!Double.isFinite(constant)) {
                throw new IOException("Non-finite animation blend weight");
            }
            output.writeDouble(constant);
        } else {
            string(output, expression);
        }
    }

    private static Map<String, AnimationClip> readAnimations(DataInputStream input)
            throws IOException {
        int animationCount = bounded(input.readInt(), MAX_ANIMATIONS, "animation");
        Map<String, AnimationClip> result = new LinkedHashMap<>();
        long keyframes = 0;
        long statements = 0;
        for (int animationIndex = 0; animationIndex < animationCount; animationIndex++) {
            String name = string(input);
            AnimationClip clip = new AnimationClip(name);
            clip.playback(AnimationClip.Playback.fromWireValue(input.readUnsignedByte()));
            clip.duration(finite(input));
            readScalar(input, clip.blendWeight());
            int boneCount = bounded(input.readInt(), MAX_BONES, "animated bone");
            for (int boneIndex = 0; boneIndex < boneCount; boneIndex++) {
                String bone = string(input);
                TrackResult rotation = readTrack(input);
                TrackResult position = readTrack(input);
                TrackResult scale = readTrack(input);
                keyframes += rotation.keyframes() + position.keyframes() + scale.keyframes();
                if (keyframes > MAX_KEYFRAMES) {
                    throw new IOException("Model has too many animation keyframes");
                }
                AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
                tracks.rotation(rotation.track());
                tracks.position(position.track());
                tracks.scale(scale.track());
                if (tracks.hasAnyTrack()) {
                    clip.boneTracks().put(bone, tracks);
                }
            }
            int timelineCount = bounded(input.readInt(), MAX_TIMELINE_STATEMENTS, "timeline event");
            for (int eventIndex = 0; eventIndex < timelineCount; eventIndex++) {
                float time = finite(input);
                int statementCount = bounded(input.readInt(), MAX_TIMELINE_STATEMENTS,
                        "timeline statement");
                statements += statementCount;
                if (statements > MAX_TIMELINE_STATEMENTS) {
                    throw new IOException("Model has too many animation statements");
                }
                java.util.List<String> code = new java.util.ArrayList<>(statementCount);
                for (int statement = 0; statement < statementCount; statement++) {
                    code.add(string(input));
                }
                clip.timeline().add(new AnimationClip.TimelineEvent(time, code));
            }
            if (result.putIfAbsent(name, clip) != null) {
                throw new IOException("Duplicate animation name");
            }
        }
        return result;
    }

    private static void readScalar(DataInputStream input, AnimationClip.ScalarValue target)
            throws IOException {
        if (input.readBoolean()) {
            target.setExpression(string(input));
        } else {
            double constant = input.readDouble();
            if (!Double.isFinite(constant)) {
                throw new IOException("Non-finite animation blend weight");
            }
            target.setConstant(constant);
        }
    }

    private static TrackResult readTrack(DataInputStream input) throws IOException {
        if (!input.readBoolean()) {
            return new TrackResult(null, 0);
        }
        int count = bounded(input.readInt(), MAX_KEYFRAMES, "keyframe");
        AnimationClip.Track track = new AnimationClip.Track();
        for (int index = 0; index < count; index++) {
            float time = finite(input);
            AnimationClip.Interpolation interpolation = AnimationClip.Interpolation.fromWireValue(
                    input.readUnsignedByte());
            AnimationClip.VectorValue value = readVector(input);
            AnimationClip.VectorValue incoming = input.readBoolean() ? readVector(input) : null;
            track.keyframes().add(new AnimationClip.Keyframe(time, interpolation, value, incoming));
        }
        return new TrackResult(track, count);
    }

    private static AnimationClip.VectorValue readVector(DataInputStream input) throws IOException {
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) {
            if (input.readBoolean()) {
                value.setExpression(axis, string(input));
            } else {
                double constant = input.readDouble();
                if (!Double.isFinite(constant)) {
                    throw new IOException("Non-finite animation value");
                }
                value.setConstant(axis, constant);
            }
        }
        return value;
    }

    private static void validateHierarchy(GeometryDocument geometry) throws IOException {
        IdentityHashMap<GeometryDocument.Bone, Integer> state = new IdentityHashMap<>();
        record Node(GeometryDocument.Bone bone, boolean exit, int depth) {
        }
        for (GeometryDocument.Bone root : geometry.roots()) {
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.push(new Node(root, false, 0));
            while (!stack.isEmpty()) {
                Node node = stack.pop();
                if (node.depth() > 1024) {
                    throw new IOException("Model hierarchy is too deep");
                }
                if (node.exit()) {
                    state.put(node.bone(), 2);
                    continue;
                }
                Integer known = state.get(node.bone());
                if (known != null) {
                    if (known == 1) {
                        throw new IOException("Cyclic model hierarchy");
                    }
                    continue;
                }
                state.put(node.bone(), 1);
                stack.push(new Node(node.bone(), true, node.depth()));
                for (GeometryDocument.Bone child : node.bone().children()) {
                    stack.push(new Node(child, false, node.depth() + 1));
                }
            }
        }
        if (state.size() != geometry.bones().size()) {
            throw new IOException("Disconnected or cyclic model hierarchy");
        }
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Model transfer string is too long");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String string(DataInputStream input) throws IOException {
        int length = bounded(input.readInt(), MAX_STRING_BYTES, "string byte");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated model transfer string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void finite(DataOutputStream output, float value) throws IOException {
        if (!Float.isFinite(value)) {
            throw new IOException("Non-finite model value");
        }
        output.writeFloat(value);
    }

    private static float finite(DataInputStream input) throws IOException {
        float value = input.readFloat();
        if (!Float.isFinite(value)) {
            throw new IOException("Non-finite model value");
        }
        return value;
    }

    private static int bounded(int value, int maximum, String label) throws IOException {
        if (value < 0 || value > maximum) {
            throw new IOException("Invalid " + label + " count");
        }
        return value;
    }

    private static int positive(int value, int maximum, String label) throws IOException {
        if (value <= 0 || value > maximum) {
            throw new IOException("Invalid " + label);
        }
        return value;
    }

    private record TrackResult(AnimationClip.Track track, int keyframes) {
    }

    private static final class InputLimit extends FilterInputStream {
        private long remaining;

        private InputLimit(InputStream source, long maximum) {
            super(source);
            remaining = maximum;
        }

        @Override
        public int read() throws IOException {
            ensureRemaining(1);
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            ensureRemaining(1);
            int count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        private void ensureRemaining(int requested) throws IOException {
            if (remaining < requested) {
                throw new IOException("Expanded model transfer exceeds its limit");
            }
        }
    }

    private static final class OutputLimit extends FilterOutputStream {
        private long remaining;

        private OutputLimit(OutputStream target, long maximum) {
            super(target);
            remaining = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            reserve(length);
            out.write(buffer, offset, length);
        }

        private void reserve(int bytes) throws IOException {
            if (bytes > remaining) {
                throw new IOException("Compressed model transfer exceeds its limit");
            }
            remaining -= bytes;
        }
    }
}
