package net.okitsu.ysmepicfightcompat.mesh;

import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Protects a prepared body while earlier equipment layers perform nested renders. */
final class MeshDrawSnapshot {
    private record Part(MeshPart part, boolean hidden,
                        OpenMatrix4f transform, OpenMatrix4f savedTransform) {
        void restore() {
            part.setHidden(hidden);
            if (transform != null) {
                transform.load(savedTransform);
            }
        }
    }

    private final OpenMatrix4f[] poses;
    private final List<Part> parts = new ArrayList<>();

    MeshDrawSnapshot(OpenMatrix4f[] source, Set<Map.Entry<String, MeshPart>> meshParts) {
        poses = source == null ? null : new OpenMatrix4f[source.length];
        if (source != null) {
            for (int index = 0; index < source.length; index++) {
                poses[index] = source[index] == null ? null : new OpenMatrix4f(source[index]);
            }
        }
        for (Map.Entry<String, MeshPart> entry : meshParts) {
            MeshPart part = entry.getValue();
            OpenMatrix4f transform = part.getVanillaPartTransform();
            parts.add(new Part(part, part.isHidden(), transform,
                    transform == null ? null : new OpenMatrix4f(transform)));
        }
    }

    OpenMatrix4f[] poses() {
        return poses;
    }

    void restoreParts() {
        parts.forEach(Part::restore);
    }
}
