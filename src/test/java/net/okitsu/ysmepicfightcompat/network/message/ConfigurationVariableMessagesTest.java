package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationVariableMessagesTest {
    private static final String MODEL = "test:configuration_model";
    private static final UUID PLAYER = new UUID(1L, 2L);
    private static final UUID CONTEXT = new UUID(3L, 4L);
    private static final UUID SCOPE = new UUID(5L, 6L);
    private static final UUID NO_CONTEXT = new UUID(0L, 0L);
    private static final int ACKNOWLEDGED_FORMAT_MARKER = -1;

    @Test
    void updatesAndSnapshotsRoundTripAcknowledgementsAndCanonicalExplicitZeroValues() {
        Map<String, Double> input = new LinkedHashMap<>();
        input.put("variable.eye", 0.0D);
        input.put("v.hat", -2.5D);
        Map<String, Double> expected = Map.of("v.eye", 0.0D, "v.hat", -2.5D);
        ConfigurationVariableUpdateMessage update = new ConfigurationVariableUpdateMessage(
                MODEL, CONTEXT, SCOPE, 129L, 130L, input);
        ConfigurationVariableSnapshotMessage snapshot = new ConfigurationVariableSnapshotMessage(
                PLAYER, MODEL, SCOPE, CONTEXT, 17L, 130L, input);
        input.put("variable.eye", 10.0D);

        assertEquals(expected, update.changes());
        assertEquals(expected, snapshot.values());
        assertThrows(UnsupportedOperationException.class,
                () -> update.changes().put("v.eye", 3.0D));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.values().put("v.eye", 3.0D));
        roundTrip(update, ConfigurationVariableUpdateMessage::write,
                ConfigurationVariableUpdateMessage::read);
        roundTrip(snapshot, ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read);
    }

    @Test
    void scopeRequestsAndBothReplyKindsRoundTripWithoutVariableValues() {
        roundTrip(new ConfigurationVariableScopeRequestMessage(MODEL, CONTEXT, 128L),
                ConfigurationVariableScopeRequestMessage::write,
                ConfigurationVariableScopeRequestMessage::read);
        for (boolean granted : new boolean[]{true, false}) {
            roundTrip(new ConfigurationVariableScopeReplyMessage(
                            MODEL, CONTEXT, 128L, SCOPE, granted),
                    ConfigurationVariableScopeReplyMessage::write,
                    ConfigurationVariableScopeReplyMessage::read);
        }
    }

    @Test
    void allMessagesSupportMaximumModelLengthAndPositiveLongCounters() {
        String longestModel = "あ".repeat(ConfigurationVariableScopeRequestMessage.MAX_MODEL_ID);
        roundTrip(new ConfigurationVariableUpdateMessage(
                        longestModel, CONTEXT, SCOPE, Long.MAX_VALUE, Long.MAX_VALUE, Map.of()),
                ConfigurationVariableUpdateMessage::write,
                ConfigurationVariableUpdateMessage::read);
        roundTrip(new ConfigurationVariableSnapshotMessage(
                        PLAYER, longestModel, SCOPE, CONTEXT, Long.MAX_VALUE, Long.MAX_VALUE, Map.of()),
                ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read);
        roundTrip(new ConfigurationVariableScopeRequestMessage(longestModel, CONTEXT, Long.MAX_VALUE),
                ConfigurationVariableScopeRequestMessage::write,
                ConfigurationVariableScopeRequestMessage::read);
        roundTrip(new ConfigurationVariableScopeReplyMessage(
                        longestModel, CONTEXT, Long.MAX_VALUE, SCOPE, true),
                ConfigurationVariableScopeReplyMessage::write,
                ConfigurationVariableScopeReplyMessage::read);
    }

    @Test
    void unselectedSnapshotsCanCarryNoClientContextAndZeroAcknowledgements() {
        roundTrip(new ConfigurationVariableSnapshotMessage(
                        PLAYER, "", SCOPE, NO_CONTEXT, 0L, 0L, Map.of()),
                ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read);
    }

    @Test
    void maximumVariableCountRoundTripsAndOneMoreIsRejectedBeforeWriting() {
        Map<String, Double> maximum = variables(ConfigurationVariableValues.MAX_VARIABLES);
        roundTrip(new ConfigurationVariableUpdateMessage(MODEL, CONTEXT, SCOPE, 1L, 1L, maximum),
                ConfigurationVariableUpdateMessage::write,
                ConfigurationVariableUpdateMessage::read);
        roundTrip(new ConfigurationVariableSnapshotMessage(
                        PLAYER, MODEL, SCOPE, CONTEXT, 1L, 1L, maximum),
                ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read);

        Map<String, Double> oversized = variables(ConfigurationVariableValues.MAX_VARIABLES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigurationVariableUpdateMessage(MODEL, CONTEXT, SCOPE, 1L, 1L, oversized));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, MODEL, SCOPE, CONTEXT, 1L, 1L, oversized));
    }

    @Test
    void updateAndSnapshotMarkersOccupyTheLegacyCountPositionAndRejectOldDecoders() {
        for (boolean snapshot : new boolean[]{false, true}) {
            FriendlyByteBuf buffer = buffer();
            try {
                if (snapshot) {
                    ConfigurationVariableSnapshotMessage.write(new ConfigurationVariableSnapshotMessage(
                            PLAYER, MODEL, SCOPE, CONTEXT, 1L, 1L, Map.of("v.eye", 0.0D)), buffer);
                    assertEquals(PLAYER, buffer.readUUID());
                    assertEquals(MODEL, buffer.readUtf(4096));
                } else {
                    ConfigurationVariableUpdateMessage.write(new ConfigurationVariableUpdateMessage(
                            MODEL, CONTEXT, SCOPE, 1L, 1L, Map.of("v.eye", 0.0D)), buffer);
                }
                int countPosition = buffer.readerIndex();
                int legacyCount = buffer.readVarInt();
                assertEquals(ACKNOWLEDGED_FORMAT_MARKER, legacyCount);
                assertFalse(legacyCount >= 0 && legacyCount <= ConfigurationVariableValues.MAX_VARIABLES);
                buffer.readerIndex(countPosition);
                assertThrows(IllegalArgumentException.class, () -> ConfigurationVariableValues.read(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void everyLegalLegacyCountIsRejectedForCompleteUnversionedUpdateAndSnapshotPayloads() {
        for (boolean snapshot : new boolean[]{false, true}) {
            for (int count = 0; count <= ConfigurationVariableValues.MAX_VARIABLES; count++) {
                FriendlyByteBuf buffer = buffer();
                try {
                    if (snapshot) {
                        buffer.writeUUID(PLAYER);
                        buffer.writeUtf(MODEL);
                    }
                    int countPosition = buffer.writerIndex();
                    ConfigurationVariableValues.write(buffer, variables(count));
                    assertThrows(IllegalArgumentException.class, () -> readValuesMessage(snapshot, buffer));
                    // Reject the format itself; never start treating old entries as new identity fields.
                    assertEquals(countPosition + FriendlyByteBuf.getVarIntSize(count), buffer.readerIndex());
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void unexpectedFormatMarkersAreRejectedBeforeNewMetadataIsRead() {
        for (int marker : new int[]{-2, Integer.MIN_VALUE, 257, Integer.MAX_VALUE}) {
            for (boolean snapshot : new boolean[]{false, true}) {
                FriendlyByteBuf buffer = buffer();
                try {
                    if (snapshot) {
                        buffer.writeUUID(PLAYER);
                        buffer.writeUtf(MODEL);
                    }
                    buffer.writeVarInt(marker);
                    assertThrows(IllegalArgumentException.class, () -> readValuesMessage(snapshot, buffer));
                    assertEquals(0, buffer.readableBytes());
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void malformedVariableCountsAreRejectedInsideTheAcknowledgedFormat() {
        for (int count : new int[]{-1, ConfigurationVariableValues.MAX_VARIABLES + 1, Integer.MAX_VALUE}) {
            assertInvalidValuesWire(buffer -> buffer.writeVarInt(count), IllegalArgumentException.class);
        }
    }

    @Test
    void ordinaryAliasesAreCanonicalizedWhenReadingRawWireValues() {
        for (boolean snapshot : new boolean[]{false, true}) {
            FriendlyByteBuf buffer = buffer();
            try {
                writeValuesPrefix(snapshot, buffer);
                buffer.writeVarInt(1);
                buffer.writeUtf("variable.eye");
                buffer.writeDouble(0.0D);
                Object decoded = readValuesMessage(snapshot, buffer);
                Map<String, Double> values = snapshot
                        ? ((ConfigurationVariableSnapshotMessage) decoded).values()
                        : ((ConfigurationVariableUpdateMessage) decoded).changes();
                assertEquals(Map.of("v.eye", 0.0D), values);
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void wireRejectsDuplicateNamesAliasesRoamingNonFiniteAndOversizedNames() {
        for (String secondName : new String[]{"v.eye", "variable.eye"}) {
            assertInvalidValuesWire(buffer -> {
                buffer.writeVarInt(2);
                buffer.writeUtf("v.eye");
                buffer.writeDouble(1.0D);
                buffer.writeUtf(secondName);
                buffer.writeDouble(0.0D);
            }, IllegalArgumentException.class);
        }
        for (String name : new String[]{"v.roaming.hat", "variable.roaming.hat", "v.", "v.eye\n"}) {
            assertInvalidValuesWire(buffer -> {
                buffer.writeVarInt(1);
                buffer.writeUtf(name);
                buffer.writeDouble(1.0D);
            }, IllegalArgumentException.class);
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertInvalidValuesWire(buffer -> {
                buffer.writeVarInt(1);
                buffer.writeUtf("v.eye");
                buffer.writeDouble(value);
            }, IllegalArgumentException.class);
        }
        assertInvalidValuesWire(buffer -> {
            buffer.writeVarInt(1);
            buffer.writeUtf("v." + "x".repeat(255));
            buffer.writeDouble(1.0D);
        }, RuntimeException.class);
    }

    @Test
    void valueNamesAtTheWireLengthBoundaryRoundTrip() {
        Map<String, Double> values = Map.of("v." + "x".repeat(254), 0.0D);
        roundTrip(new ConfigurationVariableUpdateMessage(MODEL, CONTEXT, SCOPE, 1L, 1L, values),
                ConfigurationVariableUpdateMessage::write,
                ConfigurationVariableUpdateMessage::read);
        roundTrip(new ConfigurationVariableSnapshotMessage(
                        PLAYER, MODEL, SCOPE, CONTEXT, 1L, 1L, values),
                ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read);
    }

    @Test
    void constructorsRejectMissingIdentitiesAndOutOfRangeAcknowledgements() {
        for (String invalidModel : new String[]{null, "", " ", "x".repeat(4097)}) {
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeRequestMessage(
                    invalidModel, CONTEXT, 1L));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeReplyMessage(
                    invalidModel, CONTEXT, 1L, SCOPE, true));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableUpdateMessage(
                    invalidModel, CONTEXT, SCOPE, 1L, 1L, Map.of()));
        }
        for (UUID invalidContext : new UUID[]{null, NO_CONTEXT}) {
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeRequestMessage(
                    MODEL, invalidContext, 1L));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeReplyMessage(
                    MODEL, invalidContext, 1L, SCOPE, true));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableUpdateMessage(
                    MODEL, invalidContext, SCOPE, 1L, 1L, Map.of()));
        }
        for (long invalid : new long[]{0L, -1L, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeRequestMessage(
                    MODEL, CONTEXT, invalid));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeReplyMessage(
                    MODEL, CONTEXT, invalid, SCOPE, false));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableUpdateMessage(
                    MODEL, CONTEXT, SCOPE, invalid, 1L, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableUpdateMessage(
                    MODEL, CONTEXT, SCOPE, 1L, invalid, Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableScopeReplyMessage(
                MODEL, CONTEXT, 1L, null, true));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableUpdateMessage(
                MODEL, CONTEXT, null, 1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                null, MODEL, SCOPE, CONTEXT, 1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, null, SCOPE, CONTEXT, 1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, "x".repeat(4097), SCOPE, CONTEXT, 1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, MODEL, null, CONTEXT, 1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, MODEL, SCOPE, null, 1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, MODEL, SCOPE, CONTEXT, -1L, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationVariableSnapshotMessage(
                PLAYER, MODEL, SCOPE, CONTEXT, 1L, -1L, Map.of()));
    }

    @Test
    void readersAlsoRejectInvalidAcknowledgementNumbersAndZeroClientContexts() {
        for (boolean invalidOrder : new boolean[]{false, true}) {
            FriendlyByteBuf buffer = buffer();
            try {
                buffer.writeVarInt(ACKNOWLEDGED_FORMAT_MARKER);
                buffer.writeUtf(MODEL);
                buffer.writeUUID(CONTEXT);
                buffer.writeUUID(SCOPE);
                buffer.writeVarLong(invalidOrder ? 0L : 1L);
                buffer.writeVarLong(invalidOrder ? 1L : 0L);
                buffer.writeVarInt(0);
                assertThrows(IllegalArgumentException.class, () -> ConfigurationVariableUpdateMessage.read(buffer));
                buffer.clear();
                buffer.writeUUID(PLAYER);
                buffer.writeUtf(MODEL);
                buffer.writeVarInt(ACKNOWLEDGED_FORMAT_MARKER);
                buffer.writeUUID(SCOPE);
                buffer.writeUUID(CONTEXT);
                buffer.writeVarLong(invalidOrder ? -1L : 0L);
                buffer.writeVarLong(invalidOrder ? 0L : -1L);
                buffer.writeVarInt(0);
                assertThrows(IllegalArgumentException.class, () -> ConfigurationVariableSnapshotMessage.read(buffer));
                buffer.clear();
                buffer.writeUtf(MODEL);
                buffer.writeUUID(invalidOrder ? CONTEXT : NO_CONTEXT);
                buffer.writeVarLong(invalidOrder ? 0L : 1L);
                assertThrows(IllegalArgumentException.class,
                        () -> ConfigurationVariableScopeRequestMessage.read(buffer));
                buffer.readerIndex(0);
                buffer.writeUUID(SCOPE);
                buffer.writeBoolean(false);
                assertThrows(IllegalArgumentException.class,
                        () -> ConfigurationVariableScopeReplyMessage.read(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void readersBoundModelLengthIndependentlyOfConstructors() {
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(ACKNOWLEDGED_FORMAT_MARKER);
            buffer.writeUtf("x".repeat(4097));
            assertThrows(RuntimeException.class, () -> ConfigurationVariableUpdateMessage.read(buffer));
            buffer.clear();
            buffer.writeUUID(PLAYER);
            buffer.writeUtf("x".repeat(4097));
            assertThrows(RuntimeException.class, () -> ConfigurationVariableSnapshotMessage.read(buffer));
            buffer.clear();
            buffer.writeUtf("x".repeat(4097));
            assertThrows(RuntimeException.class, () -> ConfigurationVariableScopeRequestMessage.read(buffer));
            buffer.readerIndex(0);
            assertThrows(RuntimeException.class, () -> ConfigurationVariableScopeReplyMessage.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void everyTruncatedPrefixOfEveryMessageIsRejected() {
        assertAllTruncationsRejected(new ConfigurationVariableUpdateMessage(
                        MODEL, CONTEXT, SCOPE, 128L, 129L, Map.of("v.eye", 0.0D)),
                ConfigurationVariableUpdateMessage::write,
                ConfigurationVariableUpdateMessage::read);
        assertAllTruncationsRejected(new ConfigurationVariableSnapshotMessage(
                        PLAYER, MODEL, SCOPE, CONTEXT, 128L, 129L, Map.of("v.eye", 0.0D)),
                ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read);
        assertAllTruncationsRejected(new ConfigurationVariableScopeRequestMessage(MODEL, CONTEXT, 128L),
                ConfigurationVariableScopeRequestMessage::write,
                ConfigurationVariableScopeRequestMessage::read);
        assertAllTruncationsRejected(new ConfigurationVariableScopeReplyMessage(
                        MODEL, CONTEXT, 128L, SCOPE, false),
                ConfigurationVariableScopeReplyMessage::write,
                ConfigurationVariableScopeReplyMessage::read);
    }

    private static <T> void roundTrip(T expected, BiConsumer<T, FriendlyByteBuf> writer,
                                      Function<FriendlyByteBuf, T> reader) {
        FriendlyByteBuf buffer = buffer();
        try {
            writer.accept(expected, buffer);
            assertEquals(expected, reader.apply(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static <T> void assertAllTruncationsRejected(T message, BiConsumer<T, FriendlyByteBuf> writer,
                                                         Function<FriendlyByteBuf, T> reader) {
        FriendlyByteBuf full = buffer();
        try {
            writer.accept(message, full);
            for (int length = 0; length < full.writerIndex(); length++) {
                FriendlyByteBuf truncated = buffer();
                try {
                    truncated.writeBytes(full, 0, length);
                    assertThrows(RuntimeException.class, () -> reader.apply(truncated),
                            "Accepted truncated message with " + length + " bytes");
                } finally {
                    truncated.release();
                }
            }
        } finally {
            full.release();
        }
    }

    private static void assertInvalidValuesWire(Consumer<FriendlyByteBuf> writeValues,
                                                 Class<? extends Throwable> exceptionType) {
        for (boolean snapshot : new boolean[]{false, true}) {
            FriendlyByteBuf buffer = buffer();
            try {
                writeValuesPrefix(snapshot, buffer);
                writeValues.accept(buffer);
                assertThrows(exceptionType, () -> readValuesMessage(snapshot, buffer));
            } finally {
                buffer.release();
            }
        }
    }

    private static void writeValuesPrefix(boolean snapshot, FriendlyByteBuf buffer) {
        if (snapshot) {
            buffer.writeUUID(PLAYER);
            buffer.writeUtf(MODEL);
            buffer.writeVarInt(ACKNOWLEDGED_FORMAT_MARKER);
            buffer.writeUUID(SCOPE);
            buffer.writeUUID(CONTEXT);
        } else {
            buffer.writeVarInt(ACKNOWLEDGED_FORMAT_MARKER);
            buffer.writeUtf(MODEL);
            buffer.writeUUID(CONTEXT);
            buffer.writeUUID(SCOPE);
        }
        buffer.writeVarLong(1L);
        buffer.writeVarLong(1L);
    }

    private static Object readValuesMessage(boolean snapshot, FriendlyByteBuf buffer) {
        return snapshot ? ConfigurationVariableSnapshotMessage.read(buffer)
                : ConfigurationVariableUpdateMessage.read(buffer);
    }

    private static Map<String, Double> variables(int count) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            values.put("v.value_" + index, (double) index);
        }
        return values;
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
