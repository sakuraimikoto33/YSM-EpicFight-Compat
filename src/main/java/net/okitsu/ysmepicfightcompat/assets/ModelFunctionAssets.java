package net.okitsu.ysmepicfightcompat.assets;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared bounds and canonical names for untrusted model-local Molang sources. */
public final class ModelFunctionAssets {
    public static final int MAX_FUNCTIONS = 4_096;
    public static final int MAX_NAME_BYTES = 1_024;
    public static final int MAX_SOURCE_BYTES = 1_024 * 1_024;
    public static final int MAX_TOTAL_SOURCE_BYTES = 16 * 1_024 * 1_024;

    private ModelFunctionAssets() {
    }

    /** Keeps the event/controller suffix: it is part of the source file identity. */
    public static String canonicalName(String fileName) {
        if (fileName == null || fileName.isEmpty()
                || fileName.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Invalid Molang function name size");
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".molang")) {
            name = name.substring(0, name.length() - ".molang".length());
        }
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Molang function name");
        }
        int event = name.indexOf('@');
        if (event < 0) {
            if (!identifier(name)) {
                throw new IllegalArgumentException("Invalid Molang callable name");
            }
        } else {
            // Official built-ins use Unicode labels before @; only the subscription
            // suffix is an identifier, and an anonymous controller has no label.
            if (!identifier(name.substring(event + 1)) || name.indexOf('@', event + 1) >= 0) {
                throw new IllegalArgumentException("Invalid Molang subscription name");
            }
        }
        return name;
    }

    public static String decodeSource(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Molang source exceeds its size limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Molang source is not valid UTF-8", exception);
        }
    }

    public static byte[] encodeSource(String source) {
        if (source == null || source.length() > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Molang source exceeds its size limit");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(source));
            if (encoded.remaining() > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("Molang source exceeds its size limit");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Molang source is not valid Unicode", exception);
        }
    }

    private static boolean identifier(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '_' && (character < 'a' || character > 'z')
                    && (character < '0' || character > '9')) {
                return false;
            }
        }
        return true;
    }
}
