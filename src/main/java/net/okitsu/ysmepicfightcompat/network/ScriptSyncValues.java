package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** Wire bounds and identity checks for numeric Molang events, never executable code. */
public final class ScriptSyncValues {
    public static final int MAX_MODEL_ID = 4096;
    public static final int MAX_ARGUMENTS = 16;
    public static final int MAX_PENDING = 32;
    public static final long PENDING_TICKS = 100L;

    private ScriptSyncValues() {
    }

    public static String modelId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_MODEL_ID) {
            throw new IllegalArgumentException("Invalid script sync model ID");
        }
        return value;
    }

    public static double[] arguments(double[] values) {
        if (values == null || values.length > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("Too many script sync arguments");
        }
        double[] copy = values.clone();
        for (double value : copy) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite script sync argument");
            }
        }
        return copy;
    }

    public static void write(FriendlyByteBuf output, double[] arguments) {
        double[] values = arguments(arguments);
        output.writeVarInt(values.length);
        for (double value : values) {
            output.writeDouble(value);
        }
    }

    public static double[] read(FriendlyByteBuf input) {
        int count = input.readVarInt();
        if (count < 0 || count > MAX_ARGUMENTS || input.readableBytes() < count * Double.BYTES) {
            throw new IllegalArgumentException("Invalid script sync argument count");
        }
        double[] values = new double[count];
        for (int index = 0; index < count; index++) {
            values[index] = input.readDouble();
        }
        return arguments(values);
    }

    public static boolean selectedModel(String requestedModel, String selectedModel) {
        return requestedModel != null && !requestedModel.isBlank()
                && requestedModel.length() <= MAX_MODEL_ID && requestedModel.equals(selectedModel);
    }

    public static boolean accepts(int incomingEntityId, UUID incomingUuid, String incomingModel,
                                  long sequence, int actualEntityId, UUID actualUuid,
                                  String selectedModel, long previousSequence) {
        return incomingEntityId >= 0 && incomingEntityId == actualEntityId
                && incomingUuid != null && incomingUuid.equals(actualUuid)
                && selectedModel(incomingModel, selectedModel)
                && sequence > 0L && sequence > previousSequence;
    }

    public static boolean pendingAlive(long receivedAt, long now) {
        long age = now - receivedAt;
        return now >= receivedAt && age >= 0L && age < PENDING_TICKS;
    }
}
