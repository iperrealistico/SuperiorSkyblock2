package com.bgsoftware.superiorskyblock.listener;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.platform.event.GameEvent;
import com.bgsoftware.superiorskyblock.platform.event.GameEventPriority;
import com.bgsoftware.superiorskyblock.platform.event.GameEventType;
import com.bgsoftware.superiorskyblock.platform.event.args.GameEventArgs;
import org.bukkit.entity.Player;

import java.util.UUID;

public class AdminPlayersListener extends AbstractGameEventListener {

    private static final UUID DEVELOPER_UUID = UUID.fromString("45713654-41bf-45a1-aa6f-00fe6598703b");

    private final String buildName;

    public AdminPlayersListener(SuperiorSkyblockPlugin plugin) {
        super(plugin);
        String fileName = plugin.getFileName().split("\\.")[0];
        String buildName = fileName.contains("-") ? fileName.substring(fileName.indexOf('-') + 1) : "";
        this.buildName = buildName.isEmpty() ? "" : " (Build: " + buildName + ")";

        registerCallback(GameEventType.PLAYER_JOIN_EVENT, GameEventPriority.MONITOR, this::onPlayerJoin);
    }

    private void onPlayerJoin(GameEvent<GameEventArgs.PlayerJoinEvent> e) {
        Player player = e.getArgs().player;

        // Notifies me when a server uses one of my plugins.
        if (player.getUniqueId().equals(DEVELOPER_UUID)) {
            com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.entity(player, () ->
                    com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player,
                            "This server is using SuperiorSkyblock2 &ev" +
                                    plugin.getDescription().getVersion() + "&7" + buildName), 5L);
        }

        // Notifies operators about new updates
        if (player.isOp() && plugin.getUpdater().isOutdated()) {
            com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.entity(player, () ->
                    com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player,
                            "A new version is available &8(&ev" + plugin.getUpdater().getLatestVersion() + "&8)&7!"), 20L);
        }
    }

}
