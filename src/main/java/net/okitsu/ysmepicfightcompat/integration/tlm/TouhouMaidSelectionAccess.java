package net.okitsu.ysmepicfightcompat.integration.tlm;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Optional boundary for Touhou Little Maid's synchronized official-YSM selection.
 *
 * <p>The integration deliberately keeps TLM classes out of this mod's constant pool.
 * TLM exposes these three methods publicly on {@code EntityMaid}; resolving them at
 * runtime lets this mod remain usable when TLM and EFTLM are not
 * installed.</p>
 */
public final class TouhouMaidSelectionAccess {
    private static final ResourceLocation MAID_ENTITY_TYPE =
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "maid");
    public record Selection(String modelId, String textureName) {
    }

    private interface Accessor {
        @Nullable
        Selection read(Object source);
    }

    private static final Accessor UNSUPPORTED = ignored -> null;
    private static final ClassValue<Accessor> ACCESSORS = new ClassValue<>() {
        @Override
        protected Accessor computeValue(Class<?> type) {
            return discover(type);
        }
    };

    private TouhouMaidSelectionAccess() {
    }

    public static boolean integrationLoaded() {
        return ModList.get().isLoaded("touhou_little_maid")
                && ModList.get().isLoaded("ef_tlm");
    }

    /** Returns a usable selection only for TLM's registered maid entity type. */
    @Nullable
    public static Selection resolve(@Nullable Object source) {
        if (!isSupportedMaid(source)) {
            return null;
        }
        return readSelection(source);
    }

    /** Public optional-integration gate without linking TLM implementation classes. */
    public static boolean isSupportedMaid(@Nullable Object source) {
        return source instanceof Entity entity
                && isSupportedEntityType(
                ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()));
    }

    /** Returns TLM's synchronized vanilla owner UUID, including across dimensions. */
    @Nullable
    public static UUID ownerUuid(@Nullable Object source) {
        return isSupportedMaid(source) && source instanceof OwnableEntity ownable
                ? ownable.getOwnerUUID() : null;
    }

    static boolean isSupportedEntityType(@Nullable ResourceLocation entityType) {
        return MAID_ENTITY_TYPE.equals(entityType);
    }

    /** Package-visible method-only seam used after the entity-type gate and by tests. */
    @Nullable
    static Selection readSelection(@Nullable Object source) {
        return source == null ? null : ACCESSORS.get(source.getClass()).read(source);
    }

    private static Accessor discover(Class<?> type) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle enabled = lookup.findVirtual(type, "isYsmModel",
                    MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            MethodHandle modelId = lookup.findVirtual(type, "getYsmModelId",
                    MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
            MethodHandle texture = lookup.findVirtual(type, "getYsmModelTexture",
                    MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
            return source -> invoke(enabled, modelId, texture, source);
        } catch (NoSuchMethodException | IllegalAccessException
                 | SecurityException | LinkageError ignored) {
            return UNSUPPORTED;
        }
    }

    @Nullable
    private static Selection invoke(MethodHandle enabled, MethodHandle modelId,
                                    MethodHandle texture,
                                    Object source) {
        try {
            if (!(boolean) enabled.invokeExact(source)) {
                return null;
            }
            String selectedModel = (String) modelId.invokeExact(source);
            if (selectedModel == null || selectedModel.isBlank()) {
                return null;
            }
            String selectedTexture = (String) texture.invokeExact(source);
            return new Selection(selectedModel,
                    selectedTexture == null ? "" : selectedTexture);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
