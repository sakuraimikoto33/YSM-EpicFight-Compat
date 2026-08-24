package net.okitsu.ysmepicfightcompat.animation;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Plays model-local tracks through official YSM's in-memory audio decoder/cache. */
final class ClientSoundOutput {
    private static final int MAX_ACTIVE_PER_ENTITY = 64;
    private static final int MAX_TEXT_LENGTH = 16 * 1024;
    private static final String GLOBAL_SCOPE = "global";
    private static final ResourceLocation CUSTOM_SOUND = ResourceLocation.fromNamespaceAndPath(
            "yes_steve_model", "custom");
    private static final Set<YsmSymbolKey<?>> REQUIRED_SYMBOLS = Set.of(
            YsmSymbols.CLIENT_MODEL_LOOKUP,
            YsmSymbols.CLIENT_MODEL_RESOURCES_GETTER,
            YsmSymbols.CLIENT_MODEL_SOUNDS_GETTER,
            YsmSymbols.CLIENT_AUDIO_STREAM_CACHE_ACQUIRE,
            YsmSymbols.CLIENT_AUDIO_STREAM_OPEN);

    record PlayRequest(String id, String sound, boolean forceReplace, boolean global,
                       boolean looping, float volume, float pitch) {
    }

    private record Handles(MethodHandle findModel, MethodHandle resources,
                           MethodHandle sounds, MethodHandle acquireProvider,
                           MethodHandle openStream) {
    }

    private record StreamSource(MethodHandle openStream, Object provider, Object track) {
        AudioStream open() throws Throwable {
            Object result = openStream.invoke(provider, track);
            if (result instanceof AudioStream stream) {
                return stream;
            }
            throw new IOException("Official YSM returned an incompatible audio stream");
        }
    }

    private record ActiveSound(String modelId, String scope, String id, EntitySound instance) {
    }

    private static final Map<LivingEntity, List<ActiveSound>> ACTIVE = new WeakHashMap<>();
    private static volatile Handles handles;
    private static volatile boolean resolved;

    private ClientSoundOutput() {
    }

    static PlayRequest request(String[] textArguments, double[] numericArguments) {
        int size = Math.max(length(textArguments), length(numericArguments));
        if (size < 2 || size > 5) {
            return null;
        }
        for (int index = 2; index < length(textArguments); index++) {
            if (textArguments[index] != null) {
                return null;
            }
        }
        String id = identifier(textArguments, numericArguments, 0);
        String sound = text(textArguments, 1);
        if (!validIdentifierArgument(textArguments, numericArguments, 0)
                || sound == null || sound.isBlank()
                || sound.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        int flags = size >= 3 ? integer(numericArguments, 2) : 0;
        if (flags < 0 || flags > 7) {
            return null;
        }
        float volume = size >= 4 ? bounded(numericArguments, 3) : 1.0F;
        float pitch = size >= 5 ? bounded(numericArguments, 4) : 1.0F;
        return new PlayRequest(id, sound, (flags & 1) != 0, (flags & 2) != 0,
                (flags & 4) != 0, volume, pitch);
    }

    static boolean playEffect(LivingEntity entity, String modelId, String scope,
                              String effect) {
        return play(entity, modelId, scope,
                new PlayRequest(null, effect, false, false, false, 1.0F, 1.0F));
    }

    static synchronized boolean play(LivingEntity entity, String modelId, String scope,
                                     PlayRequest request) {
        if (entity == null || request == null || !RenderSystem.isOnRenderThreadOrInit()) {
            return false;
        }
        String targetScope = request.global() ? GLOBAL_SCOPE : normalizedScope(scope);
        List<ActiveSound> sounds = ACTIVE.computeIfAbsent(entity, ignored -> new ArrayList<>());
        prune(sounds);
        if (sounds.size() >= MAX_ACTIVE_PER_ENTITY) {
            return false;
        }
        if (request.id() != null) {
            ActiveSound existing = find(sounds, targetScope, request.id());
            if (existing != null) {
                if (!request.forceReplace()) {
                    return false;
                }
                release(existing.instance());
                sounds.remove(existing);
            }
        }
        EntitySound instance = create(entity, modelId, request);
        if (instance == null) {
            if (sounds.isEmpty()) {
                ACTIVE.remove(entity);
            }
            return false;
        }
        sounds.add(new ActiveSound(modelId == null ? "" : modelId,
                targetScope, request.id(), instance));
        Minecraft.getInstance().getSoundManager().play(instance);
        instance.markStarted();
        return true;
    }

    static synchronized boolean stop(LivingEntity entity, String scope, String id,
                                     boolean global) {
        List<ActiveSound> sounds = ACTIVE.get(entity);
        if (sounds == null || id == null) {
            return false;
        }
        ActiveSound existing = find(sounds, global ? GLOBAL_SCOPE : normalizedScope(scope), id);
        if (existing == null) {
            return false;
        }
        release(existing.instance());
        sounds.remove(existing);
        if (sounds.isEmpty()) {
            ACTIVE.remove(entity);
        }
        return true;
    }

    static synchronized void stopAll(LivingEntity entity, String scope, boolean global) {
        stopMatching(entity, active -> active.scope().equals(
                global ? GLOBAL_SCOPE : normalizedScope(scope)));
    }

    static synchronized void stopScope(LivingEntity entity, String scope) {
        stopMatching(entity, active -> active.scope().equals(normalizedScope(scope)));
    }

    static synchronized void stopAll(LivingEntity entity) {
        stopMatching(entity, ignored -> true);
    }

    static synchronized void stopModel(String modelId) {
        if (modelId == null) {
            return;
        }
        for (LivingEntity entity : List.copyOf(ACTIVE.keySet())) {
            stopMatching(entity, active -> active.modelId().equals(modelId));
        }
    }

    static synchronized void clear() {
        ACTIVE.values().forEach(sounds -> sounds.forEach(
                active -> release(active.instance())));
        ACTIVE.clear();
        handles = null;
        resolved = false;
    }

    static String identifier(String[] textArguments, double[] numericArguments, int index) {
        String text = text(textArguments, index);
        if (text != null) {
            return text.isBlank() || text.length() > MAX_TEXT_LENGTH ? null : "text:" + text;
        }
        if (numericArguments == null || index >= numericArguments.length) {
            return null;
        }
        double value = numericArguments[index];
        if (!Double.isFinite(value) || value < 0.0D) {
            return null;
        }
        int numericId = (int) value;
        return numericId == 0 ? null : "number:" + numericId;
    }

    private static boolean validIdentifierArgument(String[] textArguments,
                                                   double[] numericArguments, int index) {
        String text = text(textArguments, index);
        if (text != null) {
            return !text.isBlank() && text.length() <= MAX_TEXT_LENGTH;
        }
        return numericArguments != null && index < numericArguments.length
                && Double.isFinite(numericArguments[index])
                && numericArguments[index] >= 0.0D;
    }

    private static EntitySound create(LivingEntity entity, String modelId, PlayRequest request) {
        if (request.sound() == null || request.sound().isBlank()) {
            return null;
        }
        boolean namespaced = request.sound().contains(":");
        ResourceLocation registered = namespaced
                ? ResourceLocation.tryParse(request.sound()) : null;
        if (namespaced && registered == null) {
            return null;
        }
        if (registered != null) {
            return new EntitySound(SoundEvent.createVariableRangeEvent(registered), entity,
                    request.volume(), request.pitch(), request.looping());
        }
        StreamSource source = streamSource(modelId, request.sound());
        return source == null ? null : new ModelSound(
                SoundEvent.createVariableRangeEvent(CUSTOM_SOUND), entity,
                request.volume(), request.pitch(), request.looping(), source);
    }

    private static StreamSource streamSource(String modelId, String sound) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        Handles access = handles();
        if (access == null) {
            return null;
        }
        try {
            Object lookup = access.findModel().invoke(modelId);
            if (!(lookup instanceof Optional<?> model) || model.isEmpty()) {
                return null;
            }
            Object resources = access.resources().invoke(model.get());
            Object available = access.sounds().invoke(resources);
            if (!(available instanceof Map<?, ?> soundMap)) {
                return null;
            }
            Object track = soundMap.get(sound);
            if (track == null) {
                return null;
            }
            Object provider = access.acquireProvider().invoke(model.get());
            return provider == null ? null : new StreamSource(access.openStream(), provider, track);
        } catch (Throwable exception) {
            CompatMod.LOG.warn("YSM-EF Compat: official sound lookup failed for '{}'", modelId,
                    exception);
            return null;
        }
    }

    private static Handles handles() {
        if (resolved) {
            return handles;
        }
        synchronized (ClientSoundOutput.class) {
            if (resolved) {
                return handles;
            }
            resolved = true;
            try {
                MappingSnapshot mapping = YsmMappingApi.resolve(CompatMod.MOD_ID,
                        REQUIRED_SYMBOLS);
                handles = new Handles(
                        handle(mapping.require(YsmSymbols.CLIENT_MODEL_LOOKUP)),
                        handle(mapping.require(YsmSymbols.CLIENT_MODEL_RESOURCES_GETTER)),
                        handle(mapping.require(YsmSymbols.CLIENT_MODEL_SOUNDS_GETTER)),
                        handle(mapping.require(YsmSymbols.CLIENT_AUDIO_STREAM_CACHE_ACQUIRE)),
                        handle(mapping.require(YsmSymbols.CLIENT_AUDIO_STREAM_OPEN)));
                CompatMod.LOG.info("YSM-EF Compat: official YSM sound cache access is ready");
            } catch (Exception exception) {
                CompatMod.LOG.warn("YSM-EF Compat: official YSM sound cache is unavailable",
                        exception);
            }
            return handles;
        }
    }

    private static MethodHandle handle(YsmMethodSymbol symbol)
            throws ReflectiveOperationException, IllegalAccessException {
        ClassLoader loader = ClientSoundOutput.class.getClassLoader();
        Class<?> owner = Class.forName(symbol.owner().replace('/', '.'), false, loader);
        MethodType type = MethodType.fromMethodDescriptorString(symbol.descriptor(), loader);
        Method method = owner.getDeclaredMethod(symbol.name(), type.parameterArray());
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }

    private static ActiveSound find(List<ActiveSound> sounds, String scope, String id) {
        return sounds.stream().filter(active -> active.scope().equals(scope)
                && id.equals(active.id())).findFirst().orElse(null);
    }

    private static void prune(List<ActiveSound> sounds) {
        sounds.removeIf(active -> active.instance().isFinished());
    }

    private static void stopMatching(LivingEntity entity,
                                     java.util.function.Predicate<ActiveSound> predicate) {
        List<ActiveSound> sounds = ACTIVE.get(entity);
        if (sounds == null) {
            return;
        }
        Iterator<ActiveSound> iterator = sounds.iterator();
        while (iterator.hasNext()) {
            ActiveSound active = iterator.next();
            if (predicate.test(active)) {
                release(active.instance());
                iterator.remove();
            }
        }
        if (sounds.isEmpty()) {
            ACTIVE.remove(entity);
        }
    }

    private static void release(EntitySound sound) {
        sound.release();
        Minecraft.getInstance().getSoundManager().stop(sound);
    }

    private static String normalizedScope(String scope) {
        return scope == null || scope.isBlank() ? "model" : scope;
    }

    private static int length(String[] values) {
        return values == null ? 0 : values.length;
    }

    private static int length(double[] values) {
        return values == null ? 0 : values.length;
    }

    private static String text(String[] values, int index) {
        return values != null && index < values.length ? values[index] : null;
    }

    private static int integer(double[] values, int index) {
        double value = values != null && index < values.length ? values[index] : 0.0D;
        return Double.isFinite(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? (int) value : Integer.MIN_VALUE;
    }

    private static float bounded(double[] values, int index) {
        double value = values != null && index < values.length ? values[index] : 1.0D;
        if (!Double.isFinite(value)) {
            value = 1.0D;
        }
        return (float) Math.max(0.001D, Math.min(1000.0D, value));
    }

    private static class EntitySound extends AbstractTickableSoundInstance {
        private final WeakReference<LivingEntity> entity;
        private boolean started;

        private EntitySound(SoundEvent event, LivingEntity entity, float volume,
                            float pitch, boolean looping) {
            super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.entity = new WeakReference<>(entity);
            this.volume = volume;
            this.pitch = pitch;
            this.looping = looping;
            tick();
        }

        @Override
        public void tick() {
            LivingEntity target = entity.get();
            if (target == null || target.isRemoved()) {
                stop();
                return;
            }
            x = target.getX();
            y = target.getY();
            z = target.getZ();
        }

        final void release() {
            stop();
        }

        private void markStarted() {
            started = true;
        }

        private boolean isFinished() {
            return isStopped() || started
                    && !Minecraft.getInstance().getSoundManager().isActive(this);
        }
    }

    private static final class ModelSound extends EntitySound {
        private final StreamSource source;

        private ModelSound(SoundEvent event, LivingEntity entity, float volume,
                           float pitch, boolean looping, StreamSource source) {
            super(event, entity, volume, pitch, looping);
            this.source = source;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound,
                                                        boolean looping) {
            CompletableFuture<AudioStream> result = new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
                try {
                    AudioStream stream = looping
                            ? new ReopeningAudioStream(source) : source.open();
                    // SoundBufferLibrary closes a decoded stream as soon as its samples
                    // have been uploaded to OpenAL. That is not the end of audible
                    // playback, so do not translate stream exhaustion/close into a
                    // TickableSoundInstance stop. SoundManager owns playback lifetime.
                    result.complete(stream);
                } catch (Throwable exception) {
                    result.completeExceptionally(exception);
                }
            });
            return result;
        }
    }

    private static final class ReopeningAudioStream implements AudioStream {
        private final StreamSource source;
        private AudioStream current;

        private ReopeningAudioStream(StreamSource source) throws Throwable {
            this.source = source;
            current = source.open();
        }

        @Override
        public AudioFormat getFormat() {
            return current.getFormat();
        }

        @Override
        public ByteBuffer read(int bytes) throws IOException {
            ByteBuffer result = current.read(bytes);
            if (result.hasRemaining()) {
                return result;
            }
            current.close();
            try {
                current = source.open();
            } catch (Throwable exception) {
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Could not reopen official YSM audio stream", exception);
            }
            return current.read(bytes);
        }

        @Override
        public void close() throws IOException {
            current.close();
        }
    }
}
