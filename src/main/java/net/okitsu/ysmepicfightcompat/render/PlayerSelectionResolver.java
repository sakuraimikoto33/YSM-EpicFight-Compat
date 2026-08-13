package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.okitsu.ysmepicfightcompat.network.PlayerSelectionNbt;
import net.okitsu.ysmepicfightcompat.network.RemoteSelectionState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves a player selection from integrated-server state or dedicated-server sync. */
@OnlyIn(Dist.CLIENT)
public final class PlayerSelectionResolver {
    public record Selection(String modelId, String textureName) {
    }

    private record Cached(Level level, long readAt, Selection selection) {
    }

    private static final long CACHE_TICKS = 20;
    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private PlayerSelectionResolver() {
    }

    public static Selection current(Player player) {
        if (player == null || player.level() == null) {
            return null;
        }
        Level level = player.level();
        long now = level.getGameTime();
        Cached cached = CACHE.get(player.getUUID());
        if (cached != null && cached.level() == level
                && now >= cached.readAt() && now - cached.readAt() < CACHE_TICKS) {
            return cached.selection();
        }
        Selection selected = resolve(player);
        CACHE.put(player.getUUID(), new Cached(level, now, selected));
        return selected;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static Selection resolve(Player clientPlayer) {
        PlayerSelectionNbt.Selection integrated = integratedServerSelection(clientPlayer);
        if (integrated != null) {
            return convert(integrated);
        }
        RemoteSelectionState.Entry synchronizedSelection =
                RemoteSelectionState.find(clientPlayer.getUUID());
        if (synchronizedSelection != null) {
            return new Selection(synchronizedSelection.modelId(),
                    synchronizedSelection.textureName());
        }
        return convert(PlayerSelectionNbt.read(clientPlayer));
    }

    private static PlayerSelectionNbt.Selection integratedServerSelection(Player player) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return null;
            }
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
            return serverPlayer == null ? null : PlayerSelectionNbt.read(serverPlayer);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Selection convert(PlayerSelectionNbt.Selection selection) {
        return selection == null ? null
                : new Selection(selection.modelId(), selection.textureName());
    }
}
