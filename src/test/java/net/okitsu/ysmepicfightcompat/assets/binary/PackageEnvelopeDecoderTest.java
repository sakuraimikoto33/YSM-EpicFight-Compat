package net.okitsu.ysmepicfightcompat.assets.binary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PackageEnvelopeDecoderTest {
    @Test
    void rejectsTruncatedAndUnsupportedEnvelopesBeforeDecrypting() {
        assertThrows(IllegalArgumentException.class,
                () -> PackageEnvelopeDecoder.open(new byte[12]));
        byte[] unsupported = new byte[80];
        unsupported[0] = 'm';
        unsupported[1] = 0;
        unsupported[2] = 2;
        assertThrows(IllegalArgumentException.class,
                () -> PackageEnvelopeDecoder.open(unsupported));
    }
}
