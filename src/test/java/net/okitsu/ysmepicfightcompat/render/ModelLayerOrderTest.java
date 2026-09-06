package net.okitsu.ysmepicfightcompat.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelLayerOrderTest {
    @Test
    void rendersAndFlushesLayersAfterPoseButBeforeGeometryOnlyOnce() {
        List<String> calls = new ArrayList<>();
        ModelLayerOrder order = new ModelLayerOrder(() -> {
            calls.add("layers");
            calls.add("flush");
        });
        calls.add("pose");
        order.beforeBodyGeometry();
        calls.add("body");
        order.beforeBodyGeometry();
        calls.add("glow");
        order.finishBody();
        order.beforeBodyGeometry();
        assertEquals(List.of("pose", "layers", "flush", "body", "glow"), calls);
        assertTrue(order.layersRendered());
        assertFalse(order.bodyActive());
    }

    @Test
    void defaultOrSkippedBodyPreservesTheOriginalLayerCall() {
        ModelLayerOrder defaultOrder = new ModelLayerOrder(null);
        defaultOrder.beforeBodyGeometry();
        defaultOrder.finishBody();
        assertFalse(defaultOrder.layersRendered());

        AtomicInteger count = new AtomicInteger();
        ModelLayerOrder skipped = new ModelLayerOrder(count::incrementAndGet);
        skipped.finishBody();
        skipped.beforeBodyGeometry();
        assertFalse(skipped.layersRendered());
        assertEquals(0, count.get());
    }

    @Test
    void rejectsReentrantLayersAndDoesNotReportFailedLayersAsRendered() {
        AtomicReference<ModelLayerOrder> self = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        ModelLayerOrder order = new ModelLayerOrder(() -> {
            count.incrementAndGet();
            self.get().beforeBodyGeometry();
        });
        self.set(order);
        order.beforeBodyGeometry();
        assertEquals(1, count.get());
        assertTrue(order.layersRendered());

        ModelLayerOrder failed = new ModelLayerOrder(() -> {
            throw new IllegalStateException("layer failed");
        });
        try {
            assertThrows(IllegalStateException.class, failed::beforeBodyGeometry);
        } finally {
            failed.finishBody();
        }
        assertFalse(failed.layersRendered());
        assertFalse(failed.bodyActive());
    }
}
