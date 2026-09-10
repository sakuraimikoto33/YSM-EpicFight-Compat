package net.okitsu.ysmepicfightcompat.integration.oculus;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OculusPbrRecoveryTest {
    @Test
    void detectsACompanionLosingItsTextureWithoutLoadingOrMutatingIt() throws Exception {
        Texture defaults = new Texture(10);
        Texture specular = new Texture(11);
        Manager manager = new Manager(new Holder(defaults, defaults),
                Map.of(20, new Holder(defaults, specular)));
        Set<Integer> liveTextures = new HashSet<>(Set.of(10, 11));
        var access = access(manager);

        assertFalse(access.hasInvalidCompanion(20, true, true, liveTextures::contains));
        liveTextures.remove(11); // Iris closed the companion during reload.
        assertTrue(access.hasInvalidCompanion(20, true, true, liveTextures::contains));
        assertEquals(List.of(-1, 20, -1, 20), manager.lookups);
        assertEquals(0, defaults.idReads, "Global defaults must never be probed");
        assertEquals(2, specular.idReads);
    }

    @Test
    void missingDefaultAndUnknownCompanionsAreInconclusive() throws Exception {
        Texture defaults = new Texture(10);
        Manager manager = new Manager(new Holder(defaults, defaults),
                Map.of(20, new Holder(null, new Object()),
                        21, new Holder(defaults, defaults)));
        var access = access(manager);
        for (int id : List.of(20, 21, 99)) {
            assertFalse(access.hasInvalidCompanion(id, true, true, ignored -> {
                throw new AssertionError("No usable non-default companion was supplied");
            }));
        }
    }

    @Test
    void onlyChecksChannelsPresentInTheSelectedFallback() throws Exception {
        Texture defaults = new Texture(10);
        Texture normal = new Texture(11);
        Texture specular = new Texture(12);
        Manager manager = new Manager(new Holder(defaults, defaults),
                Map.of(20, new Holder(normal, specular)));
        var access = access(manager);
        List<Integer> checked = new ArrayList<>();
        assertFalse(access.hasInvalidCompanion(20, false, true, id -> {
            checked.add(id);
            return id == 12;
        }));
        assertEquals(List.of(12), checked);
        assertTrue(access.hasInvalidCompanion(20, true, false, id -> id == 12));
    }

    private static OculusPbrBridge.HolderAccess access(Manager manager) throws Exception {
        return new OculusPbrBridge.HolderAccess(manager,
                Manager.class.getMethod("getHolder", int.class),
                Holder.class.getMethod("normalTexture"), Holder.class.getMethod("specularTexture"));
    }

    public record Holder(Object normalTexture, Object specularTexture) { }

    public static final class Manager {
        final Holder defaults;
        final Map<Integer, Holder> holders;
        final List<Integer> lookups = new ArrayList<>();

        Manager(Holder defaults, Map<Integer, Holder> holders) {
            this.defaults = defaults;
            this.holders = holders;
        }

        public Holder getHolder(int id) {
            lookups.add(id);
            return holders.getOrDefault(id, defaults);
        }
    }

    private static final class Texture extends AbstractTexture {
        private final int textureId;
        int idReads;

        Texture(int textureId) {
            this.textureId = textureId;
        }

        @Override public int getId() { idReads++; return textureId; }
        @Override public void load(ResourceManager resources) { throw new AssertionError("load"); }
        @Override public void bind() { throw new AssertionError("bind"); }
        @Override public void close() { throw new AssertionError("close"); }
        @Override public void releaseId() { throw new AssertionError("releaseId"); }
    }
}
