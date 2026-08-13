package net.okitsu.ysmepicfightcompat.assets.binary;

import org.junit.jupiter.api.Test;

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
}
