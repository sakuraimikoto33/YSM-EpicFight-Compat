package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmmapping.api.mixin.YsmRuntimeMappings;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;

import java.util.Objects;

/** Consumer-local entry point required by Mixin's package-relative refmap wrapper loading. */
public final class YsmReferenceMapper implements IReferenceMapper {
    private final IReferenceMapper delegate;

    public YsmReferenceMapper(MixinEnvironment environment, IReferenceMapper delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean isDefault() {
        return false;
    }

    @Override
    public String getResourceName() {
        return "YSM Mapping API runtime mappings";
    }

    @Override
    public String getStatus() {
        return "Using YSM Mapping API runtime mappings";
    }

    @Override
    public String getContext() {
        return delegate.getContext();
    }

    @Override
    public void setContext(String context) {
        delegate.setContext(context);
    }

    @Override
    public String remap(String className, String reference) {
        String mapped = YsmRuntimeMappings.mapReference(reference);
        if (!mapped.equals(reference)) {
            return mapped;
        }
        mapped = YsmRuntimeMappings.mapClass(reference);
        return mapped.equals(reference) ? delegate.remap(className, reference) : mapped;
    }

    @Override
    public String remapWithContext(String context, String className, String reference) {
        String mapped = YsmRuntimeMappings.mapReference(reference);
        if (!mapped.equals(reference)) {
            return mapped;
        }
        mapped = YsmRuntimeMappings.mapClass(reference);
        return mapped.equals(reference)
                ? delegate.remapWithContext(context, className, reference) : mapped;
    }
}
