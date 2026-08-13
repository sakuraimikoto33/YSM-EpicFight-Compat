package net.okitsu.ysmepicfightcompat.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalModelRepositoryTest {
    @Test
    void discoversOnlyModelCatalogsAndIgnoresOfficialCacheHashes(@TempDir Path root)
            throws IOException {
        Path model = root.resolve("custom/animals/fox");
        Files.createDirectories(model);
        Files.writeString(model.resolve("ysm.json"), "{}");
        Path cache = root.resolve("cache/client/0123456789abcdef");
        Files.createDirectories(cache);
        Files.write(cache.resolve("0123456789abcdef0123456789abcdef01234567"), new byte[]{1});

        Map<String, Boolean> discovered = LocalModelRepository.discover(root);

        assertEquals(Map.of("animals/fox", false), discovered);
        assertFalse(discovered.keySet().stream().anyMatch(id -> id.contains("0123456789abcdef")));
    }
}
