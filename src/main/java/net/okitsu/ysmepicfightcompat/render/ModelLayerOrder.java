package net.okitsu.ysmepicfightcompat.render;

import javax.annotation.Nullable;

/** One body pass: finish its pose, optionally draw layers, then emit body geometry. */
public final class ModelLayerOrder {
    @Nullable
    private Runnable earlyLayers;
    private boolean bodyActive = true;
    private boolean layersStarted;
    private boolean layersRendered;

    public ModelLayerOrder(@Nullable Runnable earlyLayers) {
        this.earlyLayers = earlyLayers;
    }

    /** Called only after the mesh has published the final attachment pose. */
    public void beforeBodyGeometry() {
        if (!hasPendingLayers()) {
            return;
        }
        // Layer renderers can recursively render entities or meshes.
        layersStarted = true;
        earlyLayers.run();
        layersRendered = true;
    }

    public boolean hasPendingLayers() {
        return bodyActive && !layersStarted && earlyLayers != null;
    }

    /** Always called in finally, including a skipped/failed mesh draw. */
    public void finishBody() {
        bodyActive = false;
        earlyLayers = null;
    }

    public boolean bodyActive() {
        return bodyActive;
    }

    public boolean layersRendered() {
        return layersRendered;
    }
}
