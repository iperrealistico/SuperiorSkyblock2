package com.bgsoftware.superiorskyblock.commands;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** Own command chains pass their subject explicitly rather than relying on server dispatcher locals. */
public final class CapturedCommandDispatch {
    private CapturedCommandDispatch() { }

    public static boolean isOwnCommand(SuperiorSkyblockPlugin plugin, String command) {
        return plugin.getCommands() instanceof CommandsManagerImpl &&
                ((CommandsManagerImpl) plugin.getCommands()).ownsCapturedCommand(command);
    }

    public static boolean dispatch(SuperiorSkyblockPlugin plugin, CommandSender sender, String command, Island target) {
        return plugin.getCommands() instanceof CommandsManagerImpl ?
                ((CommandsManagerImpl) plugin.getCommands()).dispatchCapturedCommand(sender, command, target) :
                Bukkit.dispatchCommand(sender, command);
    }

    public static boolean dispatchCurrent(SuperiorSkyblockPlugin plugin, CommandSender sender, String command) {
        return plugin.getCommands() instanceof CommandsManagerImpl ?
                ((CommandsManagerImpl) plugin.getCommands()).dispatchCurrentCommand(sender, command) :
                Bukkit.dispatchCommand(sender, command);
    }
}
