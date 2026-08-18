package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmmapping.api.mixin.YsmRuntimeMappings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;
import org.spongepowered.asm.mixin.refmap.ReferenceMapper;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class YsmReferenceMapperTest {
    @AfterEach
    void resetMappings() {
        YsmRuntimeMappings.install(UnaryOperator.identity(), UnaryOperator.identity());
    }

    @Test
    void remapsClassesAndMembersThroughTheMixin085Contract() {
        YsmRuntimeMappings.install(
                value -> value.equals("consumer/Alias") ? "runtime/Target" : value,
                value -> value.equals("render()V") ? "mappedRender()V" : value);
        var mapper = new YsmReferenceMapper(null, ReferenceMapper.DEFAULT_MAPPER);

        assertEquals("runtime/Target", mapper.remap("consumer.Mixin", "consumer/Alias"));
        assertEquals("runtime/Target", mapper.remapWithContext(
                "context", "consumer.Mixin", "consumer/Alias"));
        assertEquals("mappedRender()V", mapper.remap("consumer.Mixin", "render()V"));
        assertArrayEquals(new Class<?>[]{IReferenceMapper.class},
                YsmReferenceMapper.class.getInterfaces());
    }
}
