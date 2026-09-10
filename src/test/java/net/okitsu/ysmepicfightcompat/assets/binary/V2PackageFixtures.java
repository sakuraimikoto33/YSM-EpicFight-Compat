package net.okitsu.ysmepicfightcompat.assets.binary;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

/** Produces synthetic legacy packages; never depends on official models or runtime classes. */
public final class V2PackageFixtures {
    private V2PackageFixtures() {
    }

    public static byte[] archive(Map<String, byte[]> entries) {
        return archiveEntries(List.copyOf(entries.entrySet()), false);
    }

    static byte[] archiveEntries(List<Map.Entry<String, byte[]>> entries, boolean alreadyCompressed) {
        try {
            ByteArrayOutputStream records = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(records);
            for (Map.Entry<String, byte[]> entry : entries) {
                byte[] compressed = entry.getValue();
                if (!alreadyCompressed && compressed.length != 0) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
                        deflater.write(compressed);
                    }
                    compressed = bytes.toByteArray();
                }
                byte[] key = new byte[16];
                byte[] iv = new byte[16];
                new Random(42017).nextBytes(key);
                new Random(80332).nextBytes(iv);
                byte[] encrypted = encrypt(compressed, key, iv);
                byte[] hash = MessageDigest.getInstance("MD5").digest(encrypted);
                byte[] wrappingKey = new byte[16];
                new Random(ByteBuffer.wrap(hash, 8, 8).getLong()).nextBytes(wrappingKey);
                byte[] wrappedKey = encrypt(key, wrappingKey, iv);
                byte[] name = Base64.getEncoder().encode(entry.getKey().getBytes(StandardCharsets.UTF_8));
                output.writeInt(name.length);
                output.write(name);
                output.writeInt(encrypted.length);
                output.writeInt(wrappedKey.length);
                output.write(wrappedKey);
                output.write(iv);
                output.write(encrypted);
            }
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            DataOutputStream header = new DataOutputStream(archive);
            header.writeInt(0x59534750);
            header.writeInt(2);
            header.write(MessageDigest.getInstance("MD5").digest(records.toByteArray()));
            records.writeTo(header);
            return archive.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to construct synthetic legacy package", exception);
        }
    }

    private static byte[] encrypt(byte[] input, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }
}
