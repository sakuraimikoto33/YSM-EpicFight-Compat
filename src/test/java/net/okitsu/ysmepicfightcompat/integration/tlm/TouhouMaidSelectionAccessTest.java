package net.okitsu.ysmepicfightcompat.integration.tlm;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouhouMaidSelectionAccessTest {
    @Test
    void readsTheSynchronizedOfficialYsmSelectionWithoutLinkingTlm() {
        DuckTyped maid = new DuckTyped(true,
                "wine_fox/01_taisho_maid", "texture2");

        TouhouMaidSelectionAccess.Selection selection =
                TouhouMaidSelectionAccess.readSelection(maid);

        assertEquals("wine_fox/01_taisho_maid", selection.modelId());
        assertEquals("texture2", selection.textureName());
    }

    @Test
    void leavesGeoAndInvalidSelectionsToEftlm() {
        assertNull(TouhouMaidSelectionAccess.readSelection(
                new DuckTyped(false, "wine_fox/default", "texture")));
        assertNull(TouhouMaidSelectionAccess.readSelection(
                new DuckTyped(true, "  ", "texture")));

        TouhouMaidSelectionAccess.Selection defaultTexture =
                TouhouMaidSelectionAccess.readSelection(
                        new DuckTyped(true, "wine_fox/default", null));
        assertEquals("", defaultTexture.textureName());
    }

    @Test
    void missingWrongOrThrowingApisFailClosed() {
        assertNull(TouhouMaidSelectionAccess.readSelection(new Object()));
        assertNull(TouhouMaidSelectionAccess.readSelection(new WrongFlagType()));
        assertNull(TouhouMaidSelectionAccess.readSelection(new ThrowingSelection()));
        assertNull(TouhouMaidSelectionAccess.readSelection(new MissingDependencySelection()));
    }

    @Test
    void disabledSelectionsDoNotReadTheRemainingAccessors() {
        DisabledSelection selection = new DisabledSelection();

        assertNull(TouhouMaidSelectionAccess.readSelection(selection));
        assertEquals(0, selection.modelReads);
        assertEquals(0, selection.textureReads);
    }

    @Test
    void aTransientInvocationFailureDoesNotPoisonTheClassAccessor() {
        TransientSelection selection = new TransientSelection();

        assertNull(TouhouMaidSelectionAccess.readSelection(selection));
        assertEquals("model", TouhouMaidSelectionAccess
                .readSelection(selection).modelId());
    }

    @Test
    void fatalVmStyleErrorsAreNotHidden() {
        assertThrows(AssertionError.class, () ->
                TouhouMaidSelectionAccess.readSelection(new FatalSelection()));
    }

    @Test
    void acceptsOnlyTheRegisteredTouhouMaidEntityType() {
        assertTrue(TouhouMaidSelectionAccess.isSupportedEntityType(
                ResourceLocation.fromNamespaceAndPath(
                        "touhou_little_maid", "maid")));
        assertFalse(TouhouMaidSelectionAccess.isSupportedEntityType(
                ResourceLocation.fromNamespaceAndPath("minecraft", "player")));
        assertFalse(TouhouMaidSelectionAccess.isSupportedEntityType(null));
    }

    @Test
    void scaleCompensationIsTheInverseOfEftlmsMaidScale() {
        assertEquals(1.0F,
                0.8F * TouhouMaidRenderBridge.EFTLM_SCALE_COMPENSATION,
                0.00001F);
    }

    public static final class DuckTyped {
        private final boolean enabled;
        private final String modelId;
        private final String texture;

        DuckTyped(boolean enabled, String modelId, String texture) {
            this.enabled = enabled;
            this.modelId = modelId;
            this.texture = texture;
        }

        public boolean isYsmModel() {
            return enabled;
        }

        public String getYsmModelId() {
            return modelId;
        }

        public String getYsmModelTexture() {
            return texture;
        }
    }

    public static final class WrongFlagType {
        public Boolean isYsmModel() {
            return Boolean.TRUE;
        }

        public String getYsmModelId() {
            return "model";
        }

        public String getYsmModelTexture() {
            return "texture";
        }
    }

    public static final class ThrowingSelection {
        public boolean isYsmModel() {
            return true;
        }

        public String getYsmModelId() {
            throw new IllegalStateException("broken synchronized state");
        }

        public String getYsmModelTexture() {
            return "texture";
        }
    }

    public static final class DisabledSelection {
        int modelReads;
        int textureReads;

        public boolean isYsmModel() {
            return false;
        }

        public String getYsmModelId() {
            modelReads++;
            return "model";
        }

        public String getYsmModelTexture() {
            textureReads++;
            return "texture";
        }
    }

    public static final class TransientSelection {
        private boolean first = true;

        public boolean isYsmModel() {
            return true;
        }

        public String getYsmModelId() {
            if (first) {
                first = false;
                throw new IllegalStateException("not synchronized yet");
            }
            return "model";
        }

        public String getYsmModelTexture() {
            return "texture";
        }
    }

    public static final class MissingDependencySelection {
        public boolean isYsmModel() {
            return true;
        }

        public String getYsmModelId() {
            throw new NoClassDefFoundError("optional dependency disappeared");
        }

        public String getYsmModelTexture() {
            return "texture";
        }
    }

    public static final class FatalSelection {
        public boolean isYsmModel() {
            throw new AssertionError("fatal");
        }

        public String getYsmModelId() {
            return "model";
        }

        public String getYsmModelTexture() {
            return "texture";
        }
    }
}
