package net.okitsu.ysmepicfightcompat.assets;

import net.okitsu.ysmepicfightcompat.animation.AnimationClip;
import net.okitsu.ysmepicfightcompat.animation.AnimationController;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;

import java.util.LinkedHashMap;
import java.util.Map;

/** Complete conversion input for one official YSM model. */
public final class ModelBundle {
    public record TextureInfo(int width, int height, int format) {
    }

    private final String modelId;
    private GeometryDocument geometry;
    private final Map<String, byte[]> textures = new LinkedHashMap<>();
    private final Map<String, TextureInfo> textureInfo = new LinkedHashMap<>();
    private final Map<String, AnimationClip> animations = new LinkedHashMap<>();
    private final Map<String, AnimationController> animationControllers = new LinkedHashMap<>();
    private float widthScale = 0.7F;
    private float heightScale = 0.7F;
    private String defaultTexture = "";

    public ModelBundle(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Model id must not be empty");
        }
        this.modelId = modelId;
    }

    public static ModelBundle remote(String modelId, GeometryDocument geometry,
                                     Map<String, AnimationClip> animations,
                                     float widthScale, float heightScale,
                                     String defaultTexture) {
        return remote(modelId, geometry, animations, Map.of(), widthScale, heightScale,
                defaultTexture);
    }

    public static ModelBundle remote(String modelId, GeometryDocument geometry,
                                     Map<String, AnimationClip> animations,
                                     Map<String, AnimationController> animationControllers,
                                     float widthScale, float heightScale,
                                     String defaultTexture) {
        ModelBundle bundle = new ModelBundle(modelId);
        bundle.geometry(geometry);
        if (animations != null) {
            bundle.animations().putAll(animations);
        }
        if (animationControllers != null) {
            bundle.animationControllers().putAll(animationControllers);
        }
        bundle.scales(widthScale, heightScale);
        bundle.defaultTexture(defaultTexture);
        return bundle;
    }

    public String modelId() {
        return modelId;
    }

    public GeometryDocument geometry() {
        return geometry;
    }

    public void geometry(GeometryDocument value) {
        if (value == null) {
            throw new IllegalArgumentException("Model geometry must not be null");
        }
        geometry = value;
    }

    public Map<String, byte[]> textures() {
        return textures;
    }

    public Map<String, TextureInfo> textureInfo() {
        return textureInfo;
    }

    public Map<String, AnimationClip> animations() {
        return animations;
    }

    public Map<String, AnimationController> animationControllers() {
        return animationControllers;
    }

    public float widthScale() {
        return widthScale;
    }

    public float heightScale() {
        return heightScale;
    }

    public void scales(float width, float height) {
        widthScale = width;
        heightScale = height;
    }

    public String defaultTexture() {
        return defaultTexture;
    }

    public void defaultTexture(String value) {
        defaultTexture = value == null ? "" : value;
    }
}
