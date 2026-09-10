package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleCommandParserTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void retainsLegacyDustValuesAndScaleClamping() throws Exception {
        DustParticleOptions dust = assertInstanceOf(DustParticleOptions.class,
                parse("minecraft:dust 0.25 0.5 0.75 2"));
        assertEquals(new Vector3f(0.25F, 0.5F, 0.75F), dust.getColor());
        assertEquals(2.0F, dust.getScale());
        assertEquals(4.0F, assertInstanceOf(DustParticleOptions.class,
                parse("dust 1 0 0 100")).getScale());
        assertEquals(0.01F, assertInstanceOf(DustParticleOptions.class,
                parse("dust 1 0 0 -1")).getScale());
    }

    @Test
    void retainsLegacyTransitionArgumentOrder() throws Exception {
        DustColorTransitionOptions dust = assertInstanceOf(DustColorTransitionOptions.class,
                parse("dust_color_transition 1 0 0 2 0 0.5 1"));
        assertEquals(new Vector3f(1, 0, 0), dust.getFromColor());
        assertEquals(new Vector3f(0, 0.5F, 1), dust.getToColor());
        assertEquals(2.0F, dust.getScale());
    }

    @Test
    void retainsAllThreeLegacyBlockTypesAndProperties() throws Exception {
        String[] ids = {"block", "block_marker", "falling_dust"};
        Object[] types = {ParticleTypes.BLOCK, ParticleTypes.BLOCK_MARKER, ParticleTypes.FALLING_DUST};
        for (int i = 0; i < ids.length; i++) {
            BlockParticleOption block = assertInstanceOf(BlockParticleOption.class,
                    parse(ids[i] + " minecraft:oak_log[axis=x]"));
            assertSame(types[i], block.getType());
            assertTrue(block.getState().is(Blocks.OAK_LOG));
            assertEquals("x", block.getState().getValue(BlockStateProperties.AXIS).getName());
        }
    }

    @Test
    void retainsLegacyItemAndMigratesVisualTags() throws Exception {
        ItemParticleOption plain = assertInstanceOf(ItemParticleOption.class,
                parse("item minecraft:diamond"));
        assertTrue(plain.getItem().is(Items.DIAMOND));
        assertEquals(1, plain.getItem().getCount());

        ItemParticleOption tagged = assertInstanceOf(ItemParticleOption.class,
                parse("item minecraft:diamond_sword{CustomModelData:7,Damage:2,ysm_probe:1}"));
        assertTrue(tagged.getItem().is(Items.DIAMOND_SWORD));
        assertEquals(7, tagged.getItem().get(DataComponents.CUSTOM_MODEL_DATA).value());
        assertEquals(2, tagged.getItem().getDamageValue());
        assertEquals(1, tagged.getItem().get(DataComponents.CUSTOM_DATA).copyTag().getInt("ysm_probe"));
    }

    @Test
    void retainsLegacySculkShriekAndVibrationValues() throws Exception {
        assertEquals(0.75F, assertInstanceOf(SculkChargeParticleOptions.class,
                parse("sculk_charge 0.75")).roll());
        assertEquals(12, assertInstanceOf(ShriekParticleOption.class, parse("shriek 12")).getDelay());
        VibrationParticleOption vibration = assertInstanceOf(VibrationParticleOption.class,
                parse("vibration 1.75 2.25 -1.75 20"));
        assertEquals(new Vec3(1.5, 2.5, -1.5), vibration.getDestination().getPosition(null).orElseThrow());
        assertEquals(20, vibration.getArrivalInTicks());
    }

    @Test
    void retainsLegacyEffectIdsForVelocityColorsAndAmbientOpacity() throws Exception {
        ParticleCommandParser.LegacyEntityEffect effect = assertInstanceOf(
                ParticleCommandParser.LegacyEntityEffect.class, parse("entity_effect"));
        ParticleCommandParser.LegacyEntityEffect ambient = assertInstanceOf(
                ParticleCommandParser.LegacyEntityEffect.class, parse("minecraft:ambient_entity_effect"));
        assertFalse(effect.ambient());
        assertTrue(ambient.ambient());
        assertSame(ParticleTypes.ENTITY_EFFECT, effect.renderOptions().getType());
        assertSame(ParticleTypes.ENTITY_EFFECT, ambient.renderOptions().getType());
        assertEquals(1.0F, effect.renderOptions().getAlpha());
        assertEquals(38.0F / 255.0F, ambient.renderOptions().getAlpha());
    }

    @Test
    void keepsCurrentSnbtParticleOptions() throws Exception {
        DustParticleOptions dust = assertInstanceOf(DustParticleOptions.class,
                parse("minecraft:dust{color:[0.25,0.5,0.75],scale:2.0}"));
        assertEquals(new Vector3f(0.25F, 0.5F, 0.75F), dust.getColor());
        assertEquals(2.0F, dust.getScale());
        BlockParticleOption block = assertInstanceOf(BlockParticleOption.class,
                parse("minecraft:block{block_state:\"minecraft:stone\"}"));
        assertTrue(block.getState().is(Blocks.STONE));
        ColorParticleOption effect = assertInstanceOf(ColorParticleOption.class,
                parse("minecraft:entity_effect{color:[1.0,0.0,0.0,0.5]}"));
        assertEquals(1.0F, effect.getRed());
        assertEquals(0.0F, effect.getGreen());
        assertEquals(127.0F / 255.0F, effect.getAlpha());
    }

    @Test
    void keepsCurrentItemComponents() throws Exception {
        ItemParticleOption component = assertInstanceOf(ItemParticleOption.class,
                parse("item minecraft:diamond[minecraft:custom_model_data=9]"));
        assertEquals(9, component.getItem().get(DataComponents.CUSTOM_MODEL_DATA).value());
        ItemParticleOption snbt = assertInstanceOf(ItemParticleOption.class,
                parse("item{item:{id:\"minecraft:diamond\",count:1,components:{\"minecraft:custom_model_data\":11}}}"));
        assertEquals(11, snbt.getItem().get(DataComponents.CUSTOM_MODEL_DATA).value());
    }

    @Test
    void keepsSimpleParticlesAndRejectsInvalidOrOversizedArguments() throws Exception {
        assertSame(ParticleTypes.FLAME, parse("minecraft:flame"));
        for (String invalid : new String[]{"dust", "dust 1 0 0", "dust NaN 0 0 1",
                "block minecraft:oak_log[axis=invalid]", "item minecraft:missing_item",
                "vibration 1 2 3", "minecraft:missing_particle", "dust{color:[1,0,0],scale:100}"}) {
            assertThrows(Exception.class, () -> parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> parse("flame" + " ".repeat(ParticleCommandParser.MAX_COMMAND_LENGTH)));
    }

    private static ParticleOptions parse(String source) throws Exception {
        return ParticleCommandParser.parse(source, registries);
    }
}
