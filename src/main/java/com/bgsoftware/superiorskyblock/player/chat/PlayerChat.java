package com.bgsoftware.superiorskyblock.player.chat;

import com.bgsoftware.superiorskyblock.core.collections.ArrayMap;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class PlayerChat {

    private static final Map<UUID, PlayerChat> playerChatListeners = new java.util.concurrent.ConcurrentHashMap<>();
    private final Function<String, Boolean> chatConsumer;
    private final UUID playerId, islandId, ownerId;
    private final long revision, created = System.currentTimeMillis();
    private final boolean explicit;
    private final com.bgsoftware.superiorskyblock.api.island.IslandPrivilege privilege;

    private PlayerChat(Player player, com.bgsoftware.superiorskyblock.api.island.IslandPrivilege privilege, Function<String, Boolean> chatConsumer) {
        this.chatConsumer = chatConsumer;
        this.playerId = player.getUniqueId(); this.privilege = privilege;
        com.bgsoftware.superiorskyblock.api.island.Island target = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
        islandId = target == null ? null : target.getUniqueId(); ownerId = target == null || target.getOwner() == null ? null : target.getOwner().getUniqueId();
        revision = target == null ? 0L : com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin.getPlugin().getPvcIslandService().getRevision(islandId);
        explicit = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget();
    }

    public static PlayerChat getChatListener(Player player) {
        return playerChatListeners.get(player.getUniqueId());
    }

    public static void listen(Player player, Function<String, Boolean> onChat) {
        listen(player, null, onChat);
    }
    public static void listen(Player player, com.bgsoftware.superiorskyblock.api.island.IslandPrivilege privilege, Function<String, Boolean> onChat) {
        playerChatListeners.put(player.getUniqueId(), new PlayerChat(player, privilege, onChat));
    }

    public static void remove(Player player) {
        playerChatListeners.remove(player.getUniqueId());
    }

    public boolean supply(String message) {
        if (playerChatListeners.get(playerId) != this) return true;
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player == null) { playerChatListeners.remove(playerId, this); return true; }
        if (!com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.owns(player)) {
            com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.entity(player, () -> supply(message), 0L,
                    () -> playerChatListeners.remove(playerId, this)); return true;
        }
        com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin plugin = com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin.getPlugin();
        com.bgsoftware.superiorskyblock.api.island.Island target = islandId == null ? null : plugin.getGrid().getIslandByUUID(islandId);
        if (System.currentTimeMillis() - created > 60000L || (islandId != null && (target == null ||
                !plugin.getPvcIslandService().isAvailable(islandId) || !java.util.Objects.equals(ownerId, target.getOwner().getUniqueId()) ||
                revision != plugin.getPvcIslandService().getRevision(islandId) || (privilege != null &&
                !target.hasPermission(plugin.getPlayers().getSuperiorPlayer(player), privilege))))) {
            playerChatListeners.remove(playerId, this);
            com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player, "This input expired or the island permissions changed. Open it again.");
            return true;
        }
        try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                     com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(target, explicit)) {
            chatConsumer.apply(message);
            return true; // Active prompt input never leaks into the public async chat pipeline.
        }
    }

}
