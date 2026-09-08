package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceQueryMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the query-owned leases without starting Minecraft, conversion, or a render context. */
class ClientSubEntityModelCachePinsTest {
    private static final String PREFERENCES =
            "net/okitsu/ysmepicfightcompat/network/ClientSubEntityModelPreferences";
    private static final String QUERY =
            "net/okitsu/ysmepicfightcompat/network/message/SubEntityPreferenceQueryMessage";
    private static final UUID OWNER = new UUID(11L, 12L);
    private static final UUID EPOCH = new UUID(13L, 14L);
    private static final ResourceLocation ARROW = ResourceLocation.fromNamespaceAndPath("minecraft", "arrow");
    private static final ResourceLocation BOW = ResourceLocation.fromNamespaceAndPath("minecraft", "bow");

    private Map<UUID, Object> pending;
    private Map<UUID, Object> previousPending;
    private Map<UUID, Object> remote;
    private Map<UUID, Object> previousRemote;
    private Field tickField;
    private Field invalidRulesField;
    private long previousTick;
    private boolean previousInvalidRules;
    private Method remember;

    @BeforeEach
    void saveAndIsolateClientQueryState() throws ReflectiveOperationException {
        pending = mapField(ClientSubEntityModelPreferences.class, "PENDING");
        previousPending = new LinkedHashMap<>(pending);
        remote = mapField(RemoteSubEntityModelPreferences.class, "CURRENT");
        previousRemote = new LinkedHashMap<>(remote);
        tickField = field(ClientSubEntityModelPreferences.class, "clientTick");
        invalidRulesField = field(ClientSubEntityModelPreferences.class, "invalidRulesLogged");
        previousTick = tickField.getLong(null);
        previousInvalidRules = invalidRulesField.getBoolean(null);
        remember = ClientSubEntityModelPreferences.class.getDeclaredMethod(
                "remember", SubEntityPreferenceQueryMessage.class);
        remember.setAccessible(true);
        pending.clear();
        tickField.setLong(null, 40L);
    }

    @AfterEach
    void restoreClientQueryState() throws IllegalAccessException {
        if (previousPending != null) {
            pending.clear();
            pending.putAll(previousPending);
        }
        if (previousRemote != null) {
            remote.clear();
            remote.putAll(previousRemote);
        }
        if (tickField != null) {
            tickField.setLong(null, previousTick);
        }
        if (invalidRulesField != null) {
            invalidRulesField.setBoolean(null, previousInvalidRules);
        }
    }

    @Test
    void aSharedModelRemainsPinnedUntilItsLastPendingQueryIsRemoved()
            throws ReflectiveOperationException {
        SubEntityPreferenceQueryMessage first = query(1, "historic/launch");
        SubEntityPreferenceQueryMessage second = query(2, "historic/launch");
        remember(first);
        remember(second);

        assertEquals(Set.of("historic/launch"), ClientSubEntityModelPreferences.pendingModelIds());
        pending.remove(first.queryId());
        assertEquals(Set.of("historic/launch"), ClientSubEntityModelPreferences.pendingModelIds());
        pending.remove(second.queryId());
        assertTrue(ClientSubEntityModelPreferences.pendingModelIds().isEmpty());
    }

    @Test
    void launchSnapshotModelsAreNotReplacedByASingleCurrentOwnerSelection()
            throws ReflectiveOperationException {
        // Both requests have the same owner but different immutable launch models.
        // No live owner or Minecraft singleton exists in this test.
        SubEntityPreferenceQueryMessage first = query(1, "historic/bow");
        SubEntityPreferenceQueryMessage second = query(2, "historic/trident");
        assertEquals(first.ownerUuid(), second.ownerUuid());
        remember(first);
        remember(second);

        assertEquals(Set.of(first.modelId(), second.modelId()),
                ClientSubEntityModelPreferences.pendingModelIds());
    }

    @Test
    void snapshotsAreDetachedImmutableAndDoNotChangeQueueOrderOrTime()
            throws ReflectiveOperationException {
        SubEntityPreferenceQueryMessage first = query(1, "historic/first");
        SubEntityPreferenceQueryMessage second = query(2, "historic/second");
        remember(first);
        remember(second);
        List<UUID> order = new ArrayList<>(pending.keySet());
        Map<UUID, Object> entries = new LinkedHashMap<>(pending);

        Set<String> snapshot = ClientSubEntityModelPreferences.pendingModelIds();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("not/a/query"));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(first.modelId()));
        assertEquals(snapshot, ClientSubEntityModelPreferences.pendingModelIds());
        assertEquals(order, new ArrayList<>(pending.keySet()));
        assertEquals(entries, pending);
        assertEquals(40L, tickField.getLong(null));

        remember(query(3, "historic/third"));
        assertEquals(Set.of(first.modelId(), second.modelId()), snapshot);
        assertEquals(Set.of(first.modelId(), second.modelId(), "historic/third"),
                ClientSubEntityModelPreferences.pendingModelIds());
    }

    @Test
    void boundedRememberReleasesEvictedPinsAndDuplicateQueriesDoNotExtendTheirLifetime()
            throws ReflectiveOperationException {
        SubEntityPreferenceQueryMessage oldest = query(1, "historic/oldest");
        remember(oldest);
        Object originalPending = pending.get(oldest.queryId());
        for (int index = 2; index <= 256; index++) {
            remember(query(index, "historic/model_" + index));
        }
        assertEquals(256, pending.size());
        assertEquals(256, ClientSubEntityModelPreferences.pendingModelIds().size());
        tickField.setLong(null, 99L);
        remember(query(1, "not/a/replacement"));
        assertSame(originalPending, pending.get(oldest.queryId()));
        assertEquals(oldest.queryId(), pending.keySet().iterator().next());
        assertFalse(ClientSubEntityModelPreferences.pendingModelIds().contains("not/a/replacement"));

        remember(query(257, "historic/newest"));
        assertEquals(256, pending.size());
        assertFalse(pending.containsKey(oldest.queryId()));
        Set<String> pins = ClientSubEntityModelPreferences.pendingModelIds();
        assertEquals(256, pins.size());
        assertFalse(pins.contains(oldest.modelId()));
        assertTrue(pins.contains("historic/newest"));
    }

    @Test
    void beginningAConnectionClearsPinsWithTheExistingQueueLifecycle()
            throws ReflectiveOperationException {
        remember(query(1, "historic/first"));
        remember(query(2, "historic/second"));
        invalidRulesField.setBoolean(null, true);

        ClientSubEntityModelPreferences.beginConnection();

        assertTrue(pending.isEmpty());
        assertTrue(ClientSubEntityModelPreferences.pendingModelIds().isEmpty());
        assertEquals(0L, tickField.getLong(null));
        assertFalse(invalidRulesField.getBoolean(null));
    }

    @Test
    void pinSnapshotOnlyReadsPendingModelIdsAndCannotStartOrRetainMeshes() throws Exception {
        List<String> fieldsRead = new ArrayList<>();
        List<String> fieldsWritten = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(PREFERENCES + ".class")) {
            assertNotNull(source);
            new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    if (!name.equals("pendingModelIds") && !name.startsWith("lambda$pendingModelIds$")) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            (opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD
                                    ? fieldsRead : fieldsWritten).add(owner + "#" + name);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            calls.add(owner + "#" + name);
                            assertTrue(owner.startsWith("java/util/")
                                            || owner.equals(PREFERENCES + "$Pending") && name.equals("query")
                                            || owner.equals(QUERY) && name.equals("modelId"),
                                    "A pending pin snapshot must not resolve owners, schedule conversion, or touch LRU: "
                                            + owner + "#" + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of(PREFERENCES + "#PENDING"), fieldsRead);
        assertTrue(fieldsWritten.isEmpty());
        assertTrue(calls.contains(QUERY + "#modelId"));
    }

    private void remember(SubEntityPreferenceQueryMessage query) throws ReflectiveOperationException {
        remember.invoke(null, query);
    }

    private static SubEntityPreferenceQueryMessage query(int index, String modelId) {
        return new SubEntityPreferenceQueryMessage(new UUID(20L, index), index,
                new UUID(21L, index), OWNER, EPOCH, 1L,
                SubEntityModelKind.PROJECTILE, modelId, ARROW, BOW);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> mapField(Class<?> type, String name)
            throws ReflectiveOperationException {
        return (Map<UUID, Object>) field(type, name).get(null);
    }
}
