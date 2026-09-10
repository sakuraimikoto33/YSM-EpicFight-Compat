package net.okitsu.ysmepicfightcompat.animation;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import org.joml.Vector3f;

/** Retains 1.20.1 vanilla particle arguments alongside the current command syntax. */
final class ParticleCommandParser {
    static final int MAX_COMMAND_LENGTH = 16_384;
    private static final int MINECRAFT_1_20_1_DATA_VERSION = 3465;

    private ParticleCommandParser() {
    }

    static ParticleOptions parse(String source, HolderLookup.Provider registries)
            throws CommandSyntaxException {
        if (source == null || source.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("Particle command is absent or too long");
        }
        StringReader reader = new StringReader(source);
        ResourceLocation id = ResourceLocation.read(reader);
        // Explicit SNBT, including modded options, belongs to Minecraft's current codec.
        if (!id.getNamespace().equals("minecraft") || reader.canRead() && reader.peek() == '{') {
            return ParticleArgument.readParticle(new StringReader(source), registries);
        }
        return switch (id.getPath()) {
            case "dust" -> new DustParticleOptions(vector(reader), number(reader));
            case "dust_color_transition" -> {
                Vector3f from = vector(reader);
                float scale = number(reader);
                yield new DustColorTransitionOptions(from, vector(reader), scale);
            }
            case "block" -> block(reader, registries, ParticleTypes.BLOCK);
            case "block_marker" -> block(reader, registries, ParticleTypes.BLOCK_MARKER);
            case "falling_dust" -> block(reader, registries, ParticleTypes.FALLING_DUST);
            case "item" -> item(reader, registries);
            case "sculk_charge" -> new SculkChargeParticleOptions(number(reader));
            case "shriek" -> new ShriekParticleOption(integer(reader));
            case "vibration" -> new VibrationParticleOption(
                    new BlockPositionSource(BlockPos.containing(
                            coordinate(reader), coordinate(reader), coordinate(reader))), integer(reader));
            // These used velocity arguments as RGB; ambient was removed in 1.20.5.
            case "entity_effect" -> new LegacyEntityEffect(false);
            case "ambient_entity_effect" -> new LegacyEntityEffect(true);
            default -> ParticleArgument.readParticle(new StringReader(source), registries);
        };
    }

    private static BlockParticleOption block(StringReader reader, HolderLookup.Provider registries,
                                              ParticleType<BlockParticleOption> type)
            throws CommandSyntaxException {
        reader.expect(' ');
        return new BlockParticleOption(type, BlockStateParser.parseForBlock(
                registries.lookupOrThrow(Registries.BLOCK), reader, false).blockState());
    }

    private static ItemParticleOption item(StringReader reader, HolderLookup.Provider registries)
            throws CommandSyntaxException {
        reader.expect(' ');
        ItemParser.ItemResult parsed = new ItemParser(registries).parse(reader);
        ItemStack stack;
        if (reader.canRead() && reader.peek() == '{') {
            if (!parsed.components().isEmpty()) {
                throw new IllegalArgumentException("Mixed item tag and component syntax");
            }
            CompoundTag legacy = new CompoundTag();
            legacy.putString("id", parsed.item().unwrapKey().orElseThrow().location().toString());
            legacy.putByte("Count", (byte) 1);
            legacy.put("tag", new TagParser(reader).readStruct());
            // Let the vanilla item migration preserve visual tags such as CustomModelData.
            Dynamic<Tag> updated = DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<Tag>(NbtOps.INSTANCE, legacy), MINECRAFT_1_20_1_DATA_VERSION,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            stack = ItemStack.parse(registries, updated.getValue()).orElseThrow(
                    () -> new IllegalArgumentException("Invalid legacy particle item"));
        } else {
            stack = new ItemInput(parsed.item(), parsed.components()).createItemStack(1, false);
        }
        return new ItemParticleOption(ParticleTypes.ITEM, stack);
    }

    private static Vector3f vector(StringReader reader) throws CommandSyntaxException {
        return new Vector3f(number(reader), number(reader), number(reader));
    }

    private static float number(StringReader reader) throws CommandSyntaxException {
        reader.expect(' ');
        float value = reader.readFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite particle argument");
        return value;
    }

    private static double coordinate(StringReader reader) throws CommandSyntaxException {
        reader.expect(' ');
        // The previous command parser rounded coordinates to float before flooring.
        float value = (float) reader.readDouble();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite particle coordinate");
        return value;
    }

    private static int integer(StringReader reader) throws CommandSyntaxException {
        reader.expect(' ');
        return reader.readInt();
    }

    record LegacyEntityEffect(boolean ambient) implements ParticleOptions {
        @Override
        public ParticleType<?> getType() {
            return ParticleTypes.ENTITY_EFFECT;
        }

        ColorParticleOption renderOptions() {
            // Current color options store alpha in a byte (the nearest value to old 0.15).
            return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
                    ambient ? 0x26FFFFFF : 0xFFFFFFFF);
        }
    }
}
