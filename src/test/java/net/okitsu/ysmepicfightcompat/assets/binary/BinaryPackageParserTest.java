package net.okitsu.ysmepicfightcompat.assets.binary;

import net.okitsu.ysmepicfightcompat.animation.AnimationController;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryPackageParserTest {
    @Test
    void rejectsCountsBeyondTheSafetyLimit() {
        byte[] payload = {16, 0, 0, 0, (byte) 0xC1, (byte) 0x84, 0x3D};
        assertThrows(IllegalStateException.class,
                () -> BinaryPackageParser.parse("oversized", payload));
    }

    @Test
    void rejectsTextThatRunsPastThePayload() {
        byte[] payload = {16, 0, 0, 0, 1, 16};
        assertThrows(IllegalStateException.class,
                () -> BinaryPackageParser.parse("truncated", payload));
    }

    @Test
    void rejectsOverlongVariableIntegers() {
        byte[] payload = {16, 0, 0, 0,
                (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, 0};
        assertThrows(IllegalStateException.class,
                () -> BinaryPackageParser.parse("varint", payload));
    }

    @Test
    void retainsAnimationControllersFromLegacyEncryptedPackages() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt(output, 10);
        writeVarUInt(output, 0); // Header bytes.
        writeVarUInt(output, 1); // Models.
        writeVarUInt(output, 1); // Player model.
        writeVarUInt(output, 1); // Model marker.
        writeEmptyGeometry(output);
        writeVarUInt(output, 0); // Animation blocks.

        writeVarUInt(output, 1); // Controller files.
        writeVarUInt(output, 0); // Legacy controller id.
        writeVarUInt(output, 1); // Controllers in the file.
        writeText(output, "player.parallel_4");
        writeText(output, "idle");
        writeVarUInt(output, 2); // States.

        writeText(output, "idle");
        writeVarUInt(output, 1);
        writeText(output, "custom.pose");
        writeText(output, "v.enabled");
        writeVarUInt(output, 1);
        writeText(output, "done");
        writeText(output, "q.all_animations_finished");
        writeVarUInt(output, 1);
        writeText(output, "v.entered=1;");
        writeVarUInt(output, 1);
        writeText(output, "v.exited=1;");
        writeVarUInt(output, 1); // Scalar blend transition.
        writeFloat(output, 0.2F);
        writeVarUInt(output, 1); // Shortest-path blend.

        writeText(output, "done");
        writeVarUInt(output, 0); // Animations.
        writeVarUInt(output, 0); // Transitions.
        writeVarUInt(output, 0); // Entry actions.
        writeVarUInt(output, 0); // Exit actions.
        writeVarUInt(output, 0); // Curve blend transition.
        writeVarUInt(output, 2);
        writeFloat(output, 0.0F);
        writeFloat(output, 1.0F);
        writeFloat(output, 0.2F);
        writeFloat(output, 0.0F);
        writeVarUInt(output, 0); // Shortest-path blend.

        writeVarUInt(output, 0); // Controller lookup.
        writeVarUInt(output, 0); // Textures.
        writeVarUInt(output, 0); // Sounds.
        writeVarUInt(output, 0); // Sound lookup.
        writeVarUInt(output, 0); // Extra textures.
        writeVarUInt(output, 0); // Model lookup.
        writeVarUInt(output, 0); // Animation lookup.
        writeVarUInt(output, 0); // Texture lookup.
        writeEmptyProperties(output);

        ModelBundle model = BinaryPackageParser.parse("controller", output.toByteArray());
        AnimationController controller = model.animationControllers().get("player.parallel_4");
        AnimationController.State idle = controller.states().get("idle");

        assertEquals("idle", controller.initialState());
        assertEquals("custom.pose", idle.animations().get(0).name());
        assertEquals("v.enabled", idle.animations().get(0).weightExpression());
        assertEquals("done", idle.transitions().get(0).targetState());
        assertEquals("q.all_animations_finished",
                idle.transitions().get(0).conditionExpression());
        assertEquals(0.2F, idle.blendTransition().fixedDuration(), 0.0001F);
        assertTrue(idle.blendViaShortestPath());
        assertEquals(0.5F, controller.states().get("done")
                .blendTransition().progress(0.1D), 0.0001F);
    }

    @Test
    void retainsTypedPbrSubTexturesFromLegacyEncryptedPackages() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt(output, 10);
        writeVarUInt(output, 0); // Header bytes.
        writeVarUInt(output, 1); // Models.
        writeVarUInt(output, 1); // Player model.
        writeVarUInt(output, 1); // Model marker.
        writeEmptyGeometry(output);
        writeVarUInt(output, 0); // Animation blocks.
        writeVarUInt(output, 0); // Controller files.
        writeVarUInt(output, 0); // Controller lookup.

        writeVarUInt(output, 1); // Textures.
        writeText(output, "skin");
        writeBlob(output, new byte[]{1, 2, 3, 4});
        writeVarUInt(output, 1);
        writeVarUInt(output, 1);
        writeVarUInt(output, 2); // Sub-textures.
        writeVarUInt(output, 1); // Normal.
        writeBlob(output, new byte[]{5, 6, 7, 8});
        writeVarUInt(output, 1);
        writeVarUInt(output, 1);
        writeVarUInt(output, 2); // Specular.
        writeBlob(output, new byte[]{9, 10, 11, 12});
        writeVarUInt(output, 1);
        writeVarUInt(output, 1);

        writeVarUInt(output, 0); // Sounds.
        writeVarUInt(output, 0); // Sound lookup.
        writeVarUInt(output, 0); // Extra textures.
        writeVarUInt(output, 0); // Model lookup.
        writeVarUInt(output, 0); // Animation lookup.
        writeVarUInt(output, 0); // Texture lookup.
        writeEmptyProperties(output);

        ModelBundle model = BinaryPackageParser.parse("pbr", output.toByteArray());
        ModelBundle.PbrTextures pbr = model.pbrTextures().get("skin");

        assertArrayEquals(new byte[]{1, 2, 3, 4}, model.textures().get("skin"));
        assertArrayEquals(new byte[]{5, 6, 7, 8}, pbr.normal().bytes());
        assertEquals(new ModelBundle.TextureInfo(1, 1, -1), pbr.normal().info());
        assertArrayEquals(new byte[]{9, 10, 11, 12}, pbr.specular().bytes());
        assertEquals(new ModelBundle.TextureInfo(1, 1, -1), pbr.specular().info());
    }

    @Test
    void retainsTypedPbrSubTexturesFromModernEncryptedPackages() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt(output, 32);
        writeVarUInt(output, 0); // Sounds.
        writeVarUInt(output, 0); // Functions.
        writeVarUInt(output, 0); // Languages.
        writeVarUInt(output, 0); // Vehicles.
        writeVarUInt(output, 0); // Projectiles.
        writeVarUInt(output, 1); // Entity marker.
        writeVarUInt(output, 0); // Animation files.
        writeVarUInt(output, 0); // Controller files.

        writeVarUInt(output, 1); // Textures.
        writeText(output, "skin");
        writeText(output, "textures/skin.png");
        writeBlob(output, new byte[]{1, 2, 3});
        writeVarUInt(output, 2);
        writeVarUInt(output, 2);
        writeVarUInt(output, 7);
        writeVarUInt(output, 0);
        writeVarUInt(output, 3); // Sub-textures, including one unknown type.
        writeModernSubTexture(output, 1, "normal", new byte[]{4, 5}, 2, 2, 8);
        writeModernSubTexture(output, 2, "specular", new byte[]{6, 7}, 2, 2, 9);
        writeModernSubTexture(output, 99, "future", new byte[]{8}, 1, 1, 10);

        writeVarUInt(output, 1); // Models.
        writeVarUInt(output, 1); // Player model type.
        writeText(output, "models/main.json");
        writeEmptyGeometry(output);
        writeModernEmptyProperties(output);

        ModelBundle model = BinaryPackageParser.parse("modern-pbr", output.toByteArray());
        ModelBundle.PbrTextures pbr = model.pbrTextures().get("skin");

        assertArrayEquals(new byte[]{4, 5}, pbr.normal().bytes());
        assertEquals(new ModelBundle.TextureInfo(2, 2, 8), pbr.normal().info());
        assertArrayEquals(new byte[]{6, 7}, pbr.specular().bytes());
        assertEquals(new ModelBundle.TextureInfo(2, 2, 9), pbr.specular().info());
    }

    private static void writeEmptyGeometry(ByteArrayOutputStream output) {
        writeVarUInt(output, 0); // Bones.
        writeText(output, ""); // Geometry identifier.
        for (int index = 0; index < 4; index++) {
            writeFloat(output, 0.0F);
        }
        writeVarUInt(output, 0); // Visible bounds offsets.
        writeFloat(output, 0.0F);
        writeFloat(output, 0.0F);
        writeVarUInt(output, 0); // Legacy metadata.
        writeVarUInt(output, 0);
        writeVarUInt(output, 0);
        writeVarUInt(output, 0);
    }

    private static void writeEmptyProperties(ByteArrayOutputStream output) {
        writeText(output, "");
        writeVarUInt(output, 0); // Rich metadata.
        writeFloat(output, 1.0F);
        writeFloat(output, 1.0F);
        writeVarUInt(output, 0); // Extra animations.
        writeVarUInt(output, 0); // Animation buttons.
        writeVarUInt(output, 0); // Animation classifications.
        writeText(output, ""); // Default texture.
        writeText(output, "");
        writeVarUInt(output, 0);
        writeVarUInt(output, 0);
    }

    private static void writeModernEmptyProperties(ByteArrayOutputStream output) {
        writeEmptyProperties(output);
        writeVarUInt(output, 0); // Two additional >=15 property flags.
        writeVarUInt(output, 0);
        writeVarUInt(output, 0); // >15 property flag.
        writeVarUInt(output, 0); // >=32 property flag.
        writeText(output, "");
        writeText(output, "");
        writeVarUInt(output, 0); // Avatars.
        writeVarUInt(output, 0); // Backgrounds.
    }

    private static void writeModernSubTexture(
            ByteArrayOutputStream output, int type, String name, byte[] bytes,
            int width, int height, int format) {
        writeVarUInt(output, type);
        writeText(output, name);
        writeBlob(output, bytes);
        writeVarUInt(output, width);
        writeVarUInt(output, height);
        writeVarUInt(output, format);
        writeVarUInt(output, 0);
    }

    private static void writeText(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeBlob(ByteArrayOutputStream output, byte[] bytes) {
        writeVarUInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarUInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            output.write(next | (remaining == 0 ? 0 : 0x80));
        } while (remaining != 0);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeFloat(ByteArrayOutputStream output, float value) {
        writeInt(output, Float.floatToIntBits(value));
    }
}
