package net.okitsu.ysmepicfightcompat.mesh;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ClientAttackSoundRouter;
import net.okitsu.ysmepicfightcompat.animation.DefaultPoseProgram;
import net.okitsu.ysmepicfightcompat.animation.ParallelAnimationProgram;
import net.okitsu.ysmepicfightcompat.assets.LocalModelRepository;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.assets.OfficialTextureResolver;
import net.okitsu.ysmepicfightcompat.cache.ClientLocalModelCache;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.oculus.CompatPbrTexture;
import net.okitsu.ysmepicfightcompat.integration.oculus.OculusPbrBridge;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidAnimationStateAccess;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.render.EpicFightPoseOwnership;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Session cache for asynchronously converted combat meshes and temporary texture fallbacks. */
public final class CombatMeshCache {
    private static final String NAMESPACE = CompatMod.MOD_ID;
    private static final Map<String, MeshHandle> MESHES = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_MODELS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED_STAMPS = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, Boolean> RECENCY =
            new LinkedHashMap<>(64, 0.75F, true);

    private static final Map<String, FallbackTexture> FALLBACK_TEXTURES =
            new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> FALLBACK_LOCATIONS = new ConcurrentHashMap<>();
    private static final Set<String> UPLOADED = ConcurrentHashMap.newKeySet();
    private static final Set<String> DECODING = ConcurrentHashMap.newKeySet();
    private static final Queue<TextureUpload> READY_UPLOADS = new ConcurrentLinkedQueue<>();
    private static final Map<String, Integer> RELEASE_AFTER_TICKS = new ConcurrentHashMap<>();
    private static final Map<LivingEntity, String> ENTITY_MODELS = new WeakHashMap<>();

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), task -> {
                Thread worker = new Thread(task, "ysm-ef-model-converter");
                worker.setDaemon(true);
                return worker;
            });
    private static final Semaphore CONVERSION_PERMITS = new Semaphore(2);
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final int RELEASE_DELAY = 5;
    private static final long UPLOAD_TIME_BUDGET = 10_000_000L;

    private record MeshHandle(ResourceLocation name,
                              CompatHumanoidMesh mesh) implements AssetAccessor<CompatHumanoidMesh> {
        @Override
        public ResourceLocation registryName() {
            return name;
        }

        @Override
        public CompatHumanoidMesh get() {
            return mesh;
        }

        @Override
        public boolean inRegistry() {
            return false;
        }
    }

    private record TextureSource(String name, ResourceLocation location,
                                 byte[] bytes, ModelBundle.TextureInfo info,
                                 ModelBundle.PbrTextures pbr) {
    }

    private record Conversion(String modelId, CompatHumanoidMesh mesh,
                              int faces, List<TextureSource> textures) {
    }

    private record FallbackTexture(byte[] bytes, ModelBundle.TextureInfo info,
                                   ModelBundle.PbrTextures pbr) {
    }

    private record TextureUpload(ResourceLocation location, FallbackTexture source,
                                 NativeImage image,
                                 CompatPbrTexture.ImageData normal,
                                 CompatPbrTexture.ImageData specular) {
    }

    private CombatMeshCache() {
    }

    public static synchronized boolean ensure(String modelId) {
        return ensure(modelId, null);
    }

    public static synchronized boolean ensure(
            String modelId, LivingEntity sourceEntity) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (MESHES.containsKey(modelId)) {
            markUsed(modelId);
            trim();
            return true;
        }
        if (PENDING_MODELS.contains(modelId)) {
            return false;
        }
        boolean local = LocalModelRepository.exists(modelId);
        ModelBundle remote = local || sourceEntity == null
                ? null : ClientModelTransfers.findOrRequest(modelId, sourceEntity);
        if (!local && remote == null) {
            return false;
        }
        Long failedAt = FAILED_STAMPS.get(modelId);
        if (failedAt != null) {
            long current = local ? LocalModelRepository.metadataStamp(modelId) : 0L;
            if (failedAt == current) {
                return false;
            }
            FAILED_STAMPS.remove(modelId);
        }
        PENDING_MODELS.add(modelId);
        int generation = GENERATION.get();
        WORKERS.execute(() -> convert(modelId, remote, local, generation));
        return false;
    }

    public static AssetAccessor<CompatHumanoidMesh> find(String modelId) {
        return find(modelId, null);
    }

    public static AssetAccessor<CompatHumanoidMesh> find(
            String modelId, LivingEntity sourceEntity) {
        ensure(modelId, sourceEntity);
        MeshHandle handle = MESHES.get(modelId);
        if (handle != null) {
            markUsed(modelId);
            trim();
        }
        return handle;
    }

    /** Returns a converted mesh without starting conversion or changing its LRU state. */
    public static CompatHumanoidMesh readyMesh(String modelId) {
        MeshHandle handle = modelId == null ? null : MESHES.get(modelId);
        return handle == null ? null : handle.mesh();
    }

    /** Keeps model-authored outputs alive for selected players outside the render frustum. */
    public static void advanceAnimationOutputs() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        for (net.minecraft.client.player.AbstractClientPlayer player
                : minecraft.level.players()) {
            PlayerSelectionResolver.Selection selection = PlayerSelectionResolver.current(player);
            observeEntitySelection(player,
                    selection == null ? null : selection.modelId());
            CompatHumanoidMesh mesh = selection == null
                    ? null : readyMesh(selection.modelId());
            if (mesh == null) {
                continue;
            }
            boolean firstPerson = player == minecraft.player
                    && minecraft.options.getCameraType().isFirstPerson();
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                    player, LivingEntityPatch.class);
            mesh.advanceAnimationOutputs(player, firstPerson,
                    EpicFightPoseOwnership.actionOwnsPose(player, patch));
        }
        if (!TouhouMaidSelectionAccess.integrationLoaded()) {
            return;
        }
        for (Entity candidate : minecraft.level.entitiesForRendering()) {
            if (!(candidate instanceof LivingEntity entity)
                    || entity instanceof Player) {
                continue;
            }
            TouhouMaidSelectionAccess.Selection selection =
                    TouhouMaidSelectionAccess.resolve(entity);
            observeEntitySelection(entity,
                    selection == null ? null : selection.modelId());
            CompatHumanoidMesh mesh = selection == null
                    ? null : readyMesh(selection.modelId());
            if (mesh == null) {
                continue;
            }
            LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                    entity, LivingEntityPatch.class);
            if (TouhouMaidRenderBridge.supportsPatch(patch)) {
                mesh.advanceAnimationOutputs(entity, false,
                        EpicFightPoseOwnership.actionOwnsPose(entity, patch));
            } else {
                mesh.releaseAnimationState(entity);
                ClientAttackSoundRouter.release(entity);
            }
        }
    }

    public static boolean isReady(String modelId) {
        return modelId != null && MESHES.containsKey(modelId);
    }

    /** Releases animation outputs for a tracked entity without evicting shared meshes. */
    public static void releaseEntity(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        TouhouMaidAnimationStateAccess.release(entity);
        ClientAttackSoundRouter.release(entity);
        synchronized (ENTITY_MODELS) {
            ENTITY_MODELS.remove(entity);
        }
        MESHES.values().forEach(handle ->
                handle.mesh().releaseAnimationState(entity));
    }

    /** Releases the previous converted model immediately after any entity model switch. */
    public static void observeEntitySelection(
            LivingEntity entity, String selectedModelId) {
        if (entity == null) {
            return;
        }
        String previous;
        synchronized (ENTITY_MODELS) {
            previous = selectedModelId == null
                    ? ENTITY_MODELS.remove(entity)
                    : ENTITY_MODELS.put(entity, selectedModelId);
        }
        if (previous != null && !previous.equals(selectedModelId)) {
            TouhouMaidAnimationStateAccess.release(entity);
            ClientAttackSoundRouter.release(entity);
            CompatHumanoidMesh mesh = readyMesh(previous);
            if (mesh != null) {
                mesh.releaseAnimationState(entity);
            }
        }
    }

    public static ResourceLocation texture(String modelId, String textureName) {
        ensure(modelId);
        ResourceLocation official = OfficialTextureResolver.resolve(modelId, textureName);
        if (official != null) {
            releaseUploadedFallbacks(modelId);
            return official;
        }
        ResourceLocation exact = FALLBACK_LOCATIONS.get(modelId + '#'
                + (textureName == null ? "" : textureName));
        if (exact != null) {
            return exact;
        }
        String prefix = modelId + '#';
        return FALLBACK_LOCATIONS.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    public static void requestTextureUpload(ResourceLocation location) {
        if (location == null) {
            return;
        }
        String key = location.toString();
        FallbackTexture source = FALLBACK_TEXTURES.get(key);
        if (source == null || UPLOADED.contains(key) || !DECODING.add(key)) {
            return;
        }
        RELEASE_AFTER_TICKS.remove(key);
        WORKERS.execute(() -> {
            NativeImage image = null;
            boolean queued = false;
            try {
                image = decodeTexture(source.bytes(), source.info());
                ModelBundle.PbrTextures pbr = source.pbr();
                CompatPbrTexture.ImageData normal = decodePbrTexture(
                        location, "normal", pbr == null ? null : pbr.normal());
                CompatPbrTexture.ImageData specular = decodePbrTexture(
                        location, "specular", pbr == null ? null : pbr.specular());
                READY_UPLOADS.add(new TextureUpload(
                        location, source, image, normal, specular));
                queued = true;
                Minecraft.getInstance().execute(CombatMeshCache::uploadReadyTextures);
            } catch (Throwable exception) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: failed to decode temporary texture {}", location, exception);
            } finally {
                if (!queued && image != null) {
                    image.close();
                }
                DECODING.remove(key);
            }
        });
    }

    public static void uploadReadyTextures() {
        long deadline = System.nanoTime() + UPLOAD_TIME_BUDGET;
        TextureUpload upload;
        while ((upload = READY_UPLOADS.poll()) != null) {
            String key = upload.location().toString();
            if (FALLBACK_TEXTURES.get(key) != upload.source()) {
                upload.image().close();
                continue;
            }
            RELEASE_AFTER_TICKS.remove(key);
            AbstractTexture texture = createUploadedTexture(upload);
            try {
                Minecraft.getInstance().getTextureManager().register(
                        upload.location(), texture);
                UPLOADED.add(key);
            } catch (Throwable exception) {
                texture.close();
                CompatMod.LOG.warn(
                        "YSM-EF Compat: failed to register temporary texture {}",
                        upload.location(), exception);
            }
            if (System.nanoTime() >= deadline) {
                Minecraft.getInstance().execute(CombatMeshCache::uploadReadyTextures);
                return;
            }
        }
    }

    public static void releaseExpiredTextures() {
        RELEASE_AFTER_TICKS.replaceAll((key, ticks) -> ticks - 1);
        List<String> expired = RELEASE_AFTER_TICKS.entrySet().stream()
                .filter(entry -> entry.getValue() <= 0).map(Map.Entry::getKey).toList();
        for (String key : expired) {
            if (RELEASE_AFTER_TICKS.remove(key) != null) {
                try {
                    Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(key));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    public static void retryChangedFailures() {
        for (Map.Entry<String, Long> failure : List.copyOf(FAILED_STAMPS.entrySet())) {
            long current = LocalModelRepository.metadataStamp(failure.getKey());
            if (current >= 0 && current != failure.getValue()) {
                FAILED_STAMPS.remove(failure.getKey(), failure.getValue());
            }
        }
    }

    public static void remoteArrived(String modelId) {
        FAILED_STAMPS.remove(modelId);
    }

    public static void markUsed(String modelId) {
        synchronized (RECENCY) {
            RECENCY.put(modelId, true);
        }
    }

    public static Set<String> knownModelIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>(LocalModelRepository.discover().keySet());
        result.addAll(MESHES.keySet());
        result.addAll(PENDING_MODELS);
        return Set.copyOf(result);
    }

    public static int size() {
        return MESHES.size();
    }

    public static synchronized void clear() {
        GENERATION.incrementAndGet();
        MESHES.values().forEach(handle -> {
            handle.mesh().releaseAllAnimationStates();
            handle.mesh().destroy();
        });
        UPLOADED.forEach(key -> RELEASE_AFTER_TICKS.put(key, RELEASE_DELAY));
        MESHES.clear();
        PENDING_MODELS.clear();
        FAILED_STAMPS.clear();
        FALLBACK_TEXTURES.clear();
        FALLBACK_LOCATIONS.clear();
        UPLOADED.clear();
        DECODING.clear();
        TextureUpload queued;
        while ((queued = READY_UPLOADS.poll()) != null) {
            queued.image().close();
        }
        synchronized (RECENCY) {
            RECENCY.clear();
        }
        synchronized (ENTITY_MODELS) {
            ENTITY_MODELS.clear();
        }
        OfficialTextureResolver.clear();
        ParallelAnimationProgram.clearSoundOutput();
    }

    private static void convert(String modelId, ModelBundle remote, boolean local,
                                int expectedGeneration) {
        boolean acquired = false;
        try {
            CONVERSION_PERMITS.acquire();
            acquired = true;
            ModelBundle source = remote != null ? remote : ClientLocalModelCache.load(modelId);
            Conversion conversion = bake(source);
            Minecraft.getInstance().execute(() -> completeConversion(
                    modelId, local, expectedGeneration, conversion));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            PENDING_MODELS.remove(modelId);
        } catch (Throwable exception) {
            PENDING_MODELS.remove(modelId);
            FAILED_STAMPS.put(modelId,
                    local ? LocalModelRepository.metadataStamp(modelId) : 0L);
            CompatMod.LOG.warn(
                    "YSM-EF Compat: model conversion failed for '{}'", modelId, exception);
        } finally {
            if (acquired) {
                CONVERSION_PERMITS.release();
            }
        }
    }

    /** Publishes and replaces render resources only on Minecraft's render thread. */
    private static synchronized void completeConversion(
            String modelId, boolean local, int expectedGeneration,
            Conversion conversion) {
        PENDING_MODELS.remove(modelId);
        if (expectedGeneration != GENERATION.get()) {
            if (conversion != null) {
                conversion.mesh().releaseAllAnimationStates();
                conversion.mesh().destroy();
            }
            return;
        }
        if (conversion == null) {
            FAILED_STAMPS.put(modelId,
                    local ? LocalModelRepository.metadataStamp(modelId) : 0L);
            return;
        }
        register(conversion);
    }

    private static Conversion bake(ModelBundle source) {
        if (source == null) {
            return null;
        }
        SkinMeshCompiler.Result baked = SkinMeshCompiler.compile(source);
        if (baked == null) {
            return null;
        }
        Mesh.RenderProperties properties = Mesh.RenderProperties.Builder.create()
                .transparency(false).build();
        DefaultPoseProgram pose = new DefaultPoseProgram(source.geometry(), source.animations());
        ParallelAnimationProgram parallel = new ParallelAnimationProgram(
                source.modelId(), source.geometry(), source.animations(),
                source.animationControllers(),
                baked.auxiliaryBones(),
                source.widthScale(), source.heightScale());
        CompatHumanoidMesh mesh = new CompatHumanoidMesh(source.modelId(), pose, parallel,
                baked.auxiliaryBones(),
                baked.arrays(), baked.parts(), baked.glowParts(), null, properties);
        List<TextureSource> textures = new ArrayList<>();
        source.textures().forEach((name, bytes) -> textures.add(new TextureSource(
                name, fallbackLocation(source.modelId(), name), bytes,
                source.textureInfo().get(name), source.pbrTextures().get(name))));
        return new Conversion(source.modelId(), mesh, baked.faceCount(), List.copyOf(textures));
    }

    private static void register(Conversion conversion) {
        FAILED_STAMPS.remove(conversion.modelId());
        for (TextureSource texture : conversion.textures()) {
            String resourceKey = texture.location().toString();
            FALLBACK_LOCATIONS.put(conversion.modelId() + '#' + texture.name(), texture.location());
            FALLBACK_TEXTURES.put(resourceKey, new FallbackTexture(
                    texture.bytes(), texture.info(), texture.pbr()));
        }
        MeshHandle handle = new MeshHandle(ResourceLocation.fromNamespaceAndPath(
                NAMESPACE, "memory/entity/" + safePath(conversion.modelId())), conversion.mesh());
        MeshHandle previous = MESHES.put(conversion.modelId(), handle);
        if (previous != null) {
            previous.mesh().releaseAllAnimationStates();
            previous.mesh().destroy();
        }
        markUsed(conversion.modelId());
        CompatMod.LOG.info(
                "YSM-EF Compat: converted '{}' in memory ({} faces)",
                conversion.modelId(), conversion.faces());
    }

    private static void trim() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        List<String> victims = new ArrayList<>();
        synchronized (RECENCY) {
            while (RECENCY.size() > ClientPreferences.CLIENT_MODEL_MEMORY_CACHE_SIZE.get()) {
                String victim = RECENCY.keySet().iterator().next();
                RECENCY.remove(victim);
                victims.add(victim);
            }
        }
        victims.forEach(CombatMeshCache::evict);
    }

    private static synchronized void evict(String modelId) {
        MeshHandle handle = MESHES.remove(modelId);
        if (handle != null) {
            handle.mesh().releaseAllAnimationStates();
            handle.mesh().destroy();
        }
        discardFallbacks(modelId);
        OfficialTextureResolver.release(modelId);
        ParallelAnimationProgram.releaseSoundOutput(modelId);
    }

    private static void discardFallbacks(String modelId) {
        String prefix = modelId + '#';
        List<ResourceLocation> locations = new ArrayList<>();
        FALLBACK_LOCATIONS.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) {
                return false;
            }
            locations.add(entry.getValue());
            return true;
        });
        for (ResourceLocation location : locations) {
            String key = location.toString();
            FALLBACK_TEXTURES.remove(key);
            if (UPLOADED.remove(key)) {
                RELEASE_AFTER_TICKS.put(key, RELEASE_DELAY);
            }
        }
    }

    private static void releaseUploadedFallbacks(String modelId) {
        String prefix = modelId + '#';
        FALLBACK_LOCATIONS.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .forEach(location -> {
                    String key = location.toString();
                    if (UPLOADED.remove(key)) {
                        RELEASE_AFTER_TICKS.put(key, RELEASE_DELAY);
                    }
                });
    }

    private static AbstractTexture createUploadedTexture(TextureUpload upload) {
        boolean hasPbr = upload.normal() != null || upload.specular() != null;
        if (hasPbr && OculusPbrBridge.ensureRegistered()) {
            return new CompatPbrTexture(
                    upload.image(), upload.normal(), upload.specular());
        }
        return new DynamicTexture(upload.image());
    }

    private static CompatPbrTexture.ImageData decodePbrTexture(
            ResourceLocation location, String kind,
            ModelBundle.EncodedTexture encoded) {
        if (encoded == null) {
            return null;
        }
        NativeImage image = null;
        try {
            image = decodeTexture(encoded.bytes(), encoded.info());
            return CompatPbrTexture.ImageData.copyOf(image);
        } catch (IOException | RuntimeException exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: failed to decode fallback {} texture for {}; ignoring that PBR companion",
                    kind, location, exception);
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static NativeImage decodeTexture(byte[] bytes, ModelBundle.TextureInfo info)
            throws IOException {
        if (info != null && info.format() == -1) {
            return rawTexture(bytes, info.width(), info.height());
        }
        try {
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (IOException encodedFailure) {
            int pixels = bytes.length / 4;
            int side = (int) Math.round(Math.sqrt(pixels));
            if (bytes.length % 4 == 0 && side * side == pixels) {
                return rawTexture(bytes, side, side);
            }
            throw encodedFailure;
        }
    }

    private static NativeImage rawTexture(byte[] bytes, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height * 4 > bytes.length) {
            throw new IOException("Invalid raw texture dimensions");
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int rgba = (bytes[offset + 3] & 0xFF) << 24
                        | (bytes[offset + 2] & 0xFF) << 16
                        | (bytes[offset + 1] & 0xFF) << 8
                        | bytes[offset] & 0xFF;
                image.setPixelRGBA(x, y, rgba);
            }
        }
        return image;
    }

    private static ResourceLocation fallbackLocation(String modelId, String textureName) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE,
                "memory/textures/" + safePath(modelId) + '/' + safePath(textureName));
    }

    private static String safePath(String source) {
        StringBuilder result = new StringBuilder();
        boolean changed = false;
        for (char character : source.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '.' || character == '/'
                    || character == '-';
            result.append(valid ? character : '_');
            changed |= !valid;
        }
        if (changed) {
            result.append('_').append(Integer.toHexString(source.hashCode()));
        }
        return result.toString();
    }
}
