package net.okitsu.ysmepicfightcompat.animation;

import com.mojang.brigadier.StringReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.WeakHashMap;

/** Bounded client-side implementation of official YSM's particle Molang helpers. */
final class ClientParticleOutput {
    static final int MAX_COUNT = 256;
    static final int MAX_LIFETIME = 1_200;
    static final double MAX_OFFSET = 64.0D;
    static final double MAX_DELTA = 64.0D;
    static final double MAX_SPEED = 16.0D;

    private static final int MAX_CACHE_ENTRIES = 128;
    private static final Map<String, Optional<ParticleOptions>> PARTICLES =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, Optional<ParticleOptions>> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private static final Map<LivingEntity, Map<Scope, List<BoundParticle>>> SCOPES =
            new WeakHashMap<>();

    private record Scope(String modelId, String name) {
    }

    private record BoundParticle(Particle particle, String locator, boolean bound) {
    }

    record Request(String particle, double offsetX, double offsetY, double offsetZ,
                   double deltaX, double deltaY, double deltaZ, double speed,
                   int count, int lifetime, boolean absolute) {
    }

    private ClientParticleOutput() {
    }

    static boolean emit(LivingEntity entity, Random random, String[] textArguments,
                        double[] numericArguments, boolean absolute) {
        Request request = request(textArguments, numericArguments, absolute);
        if (entity == null || entity.isRemoved() || request == null) {
            return false;
        }
        ParticleOptions particle = particle(request.particle());
        Minecraft minecraft = Minecraft.getInstance();
        if (particle == null || minecraft.level == null || minecraft.particleEngine == null) {
            return false;
        }

        Runnable emission = () -> emit(minecraft.particleEngine, particle, entity,
                random, request);
        if (minecraft.isSameThread()) {
            emission.run();
        } else {
            minecraft.execute(emission);
        }
        return true;
    }

    static boolean emitEffect(LivingEntity entity, String modelId, String scope,
                              DeclarativeParticleEffect effect, boolean scoped) {
        if (entity == null || entity.isRemoved() || effect == null
                || effect.effect().isBlank()) {
            return false;
        }
        ParticleOptions particle = particle(effect.effect());
        Minecraft minecraft = Minecraft.getInstance();
        if (particle == null || minecraft.level == null || minecraft.particleEngine == null
                || !minecraft.isSameThread()) {
            return false;
        }
        Vec3 offset = locatorOffset(entity, effect.locator());
        Particle created = create(minecraft.particleEngine, particle,
                entity.getX() + offset.x, entity.getY() + offset.y,
                entity.getZ() + offset.z, 0.0D, 0.0D, 0.0D,
                scoped ? MAX_LIFETIME : 20);
        if (created == null || (!scoped && !effect.bindToActor())) {
            return created != null;
        }
        Scope normalizedScope = scope(modelId, scope);
        SCOPES.computeIfAbsent(entity, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(normalizedScope, ignored -> new ArrayList<>())
                .add(new BoundParticle(created, effect.locator(), effect.bindToActor()));
        return true;
    }

    static void update(LivingEntity entity) {
        Map<Scope, List<BoundParticle>> scopes = SCOPES.get(entity);
        if (scopes == null) {
            return;
        }
        scopes.values().forEach(particles -> particles.removeIf(bound -> {
            Particle particle = bound.particle();
            if (!particle.isAlive()) {
                return true;
            }
            if (bound.bound()) {
                Vec3 offset = locatorOffset(entity, bound.locator());
                particle.setPos(entity.getX() + offset.x,
                        entity.getY() + offset.y, entity.getZ() + offset.z);
            }
            return false;
        }));
        scopes.values().removeIf(List::isEmpty);
        if (scopes.isEmpty()) {
            SCOPES.remove(entity);
        }
    }

    static void stopScope(LivingEntity entity, String modelId, String scope) {
        Map<Scope, List<BoundParticle>> scopes = SCOPES.get(entity);
        if (scopes == null) {
            return;
        }
        List<BoundParticle> particles = scopes.remove(scope(modelId, scope));
        if (particles != null) {
            particles.forEach(bound -> bound.particle().remove());
        }
        if (scopes.isEmpty()) {
            SCOPES.remove(entity);
        }
    }

    static void stopAll(LivingEntity entity) {
        Map<Scope, List<BoundParticle>> scopes = SCOPES.remove(entity);
        if (scopes != null) {
            scopes.values().forEach(particles ->
                    particles.forEach(bound -> bound.particle().remove()));
        }
    }

    static void stopModel(LivingEntity entity, String modelId) {
        Map<Scope, List<BoundParticle>> scopes = SCOPES.get(entity);
        if (scopes == null || modelId == null) {
            return;
        }
        List<Scope> owned = scopes.keySet().stream()
                .filter(scope -> scope.modelId().equals(modelId)).toList();
        for (Scope scope : owned) {
            List<BoundParticle> particles = scopes.remove(scope);
            if (particles != null) {
                particles.forEach(bound -> bound.particle().remove());
            }
        }
        if (scopes.isEmpty()) {
            SCOPES.remove(entity);
        }
    }

    static void clear() {
        SCOPES.values().forEach(scopes -> scopes.values().forEach(particles ->
                particles.forEach(bound -> bound.particle().remove())));
        SCOPES.clear();
    }

    private static Scope scope(String modelId, String name) {
        return new Scope(modelId == null ? "" : modelId,
                name == null || name.isBlank() ? "controller" : name);
    }

    static Request request(String[] textArguments, double[] numericArguments,
                           boolean absolute) {
        String particle = text(textArguments, 0);
        if (particle == null || particle.isBlank()) {
            return null;
        }
        return new Request(particle,
                bounded(number(numericArguments, 1), MAX_OFFSET),
                bounded(number(numericArguments, 2), MAX_OFFSET),
                bounded(number(numericArguments, 3), MAX_OFFSET),
                bounded(number(numericArguments, 4), MAX_DELTA),
                bounded(number(numericArguments, 5), MAX_DELTA),
                bounded(number(numericArguments, 6), MAX_DELTA),
                bounded(number(numericArguments, 7), MAX_SPEED),
                boundedInteger(number(numericArguments, 8), 0, MAX_COUNT, 0),
                boundedInteger(number(numericArguments, 9, 20.0D),
                        1, MAX_LIFETIME, 20),
                absolute);
    }

    static Vec3 rotateOffset(double x, double y, double z, float yaw, boolean absolute) {
        Vec3 result = new Vec3(x, y, z);
        return absolute ? result : result.yRot((float) Math.toRadians(-yaw));
    }

    private static void emit(ParticleEngine engine, ParticleOptions particle,
                             LivingEntity entity, Random random, Request request) {
        if (entity.isRemoved()) {
            return;
        }
        if (request.count() == 0) {
            float yaw = entity instanceof Player player ? player.yBodyRot : entity.getYRot();
            Vec3 offset = rotateOffset(request.offsetX(), request.offsetY(),
                    request.offsetZ(), yaw, request.absolute());
            create(engine, particle,
                    entity.getX() + offset.x,
                    entity.getY() + offset.y,
                    entity.getZ() + offset.z,
                    request.speed() * request.deltaX(),
                    request.speed() * request.deltaY(),
                    request.speed() * request.deltaZ(),
                    request.lifetime());
            return;
        }

        for (int index = 0; index < request.count(); index++) {
            Vec3 offset = rotateOffset(
                    request.offsetX() + random.nextGaussian() * request.deltaX(),
                    request.offsetY() + random.nextGaussian() * request.deltaY(),
                    request.offsetZ() + random.nextGaussian() * request.deltaZ(),
                    entity.getYRot(), request.absolute());
            create(engine, particle,
                    entity.getX() + offset.x,
                    entity.getY() + offset.y,
                    entity.getZ() + offset.z,
                    random.nextGaussian() * request.speed(),
                    random.nextGaussian() * request.speed(),
                    random.nextGaussian() * request.speed(),
                    request.lifetime());
        }
    }

    private static Particle create(ParticleEngine engine, ParticleOptions particle,
                               double x, double y, double z,
                               double velocityX, double velocityY, double velocityZ,
                               int lifetime) {
        Particle created = engine.createParticle(
                particle, x, y, z, velocityX, velocityY, velocityZ);
        if (created != null) {
            created.setLifetime(lifetime);
        }
        return created;
    }

    private static Vec3 locatorOffset(LivingEntity entity, String locator) {
        if (locator == null || locator.isBlank()) {
            return new Vec3(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
        }
        String name = locator.toLowerCase(java.util.Locale.ROOT);
        double height = entity.getBbHeight();
        double width = entity.getBbWidth();
        Vec3 local = switch (name) {
            case "head", "face", "eyes" -> new Vec3(0.0D, entity.getEyeHeight(), 0.0D);
            case "body", "chest", "torso" -> new Vec3(0.0D, height * 0.62D, 0.0D);
            case "leftarm", "left_arm", "lefthand", "left_hand" ->
                    new Vec3(width * 0.6D, height * 0.58D, 0.0D);
            case "rightarm", "right_arm", "righthand", "right_hand" ->
                    new Vec3(-width * 0.6D, height * 0.58D, 0.0D);
            case "leftleg", "left_leg", "leftfoot", "left_foot" ->
                    new Vec3(width * 0.2D, height * 0.12D, 0.0D);
            case "rightleg", "right_leg", "rightfoot", "right_foot" ->
                    new Vec3(-width * 0.2D, height * 0.12D, 0.0D);
            default -> new Vec3(0.0D, height * 0.5D, 0.0D);
        };
        return rotateOffset(local.x, local.y, local.z, entity.getYRot(), false);
    }

    private static ParticleOptions particle(String source) {
        Optional<ParticleOptions> cached;
        synchronized (PARTICLES) {
            cached = PARTICLES.get(source);
            if (cached == null) {
                cached = parseParticle(source);
                PARTICLES.put(source, cached);
            }
        }
        return cached.orElse(null);
    }

    private static Optional<ParticleOptions> parseParticle(String source) {
        try {
            return Optional.of(ParticleArgument.readParticle(
                    new StringReader(source), BuiltInRegistries.PARTICLE_TYPE.asLookup()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String text(String[] arguments, int index) {
        return arguments != null && index >= 0 && index < arguments.length
                ? arguments[index] : null;
    }

    private static double number(double[] arguments, int index) {
        return number(arguments, index, 0.0D);
    }

    private static double number(double[] arguments, int index, double fallback) {
        double value = arguments != null && index >= 0 && index < arguments.length
                ? arguments[index] : fallback;
        return Double.isFinite(value) ? value : fallback;
    }

    private static double bounded(double value, double maximum) {
        return Math.max(-maximum, Math.min(maximum, value));
    }

    private static int boundedInteger(double value, int minimum, int maximum, int fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        int integer = value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
        return Math.max(minimum, Math.min(maximum, integer));
    }
}
