package com.bgsoftware.superiorskyblock.pvc;

import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/** Branding for plugin-generated service replies; player-authored payloads stay untouched. */
public final class PvcMessages {
    public static final String PREFIX = "&8[&6Skyblock&8] &7";

    private PvcMessages() { }

    public static String service(String text) {
        return PREFIX + text;
    }

    public static void send(CommandSender sender, String text) {
        Message.CUSTOM.send(sender, service(text), true);
    }

    public static void send(SuperiorPlayer player, String text) {
        Message.CUSTOM.send(player, service(text), true);
    }

    /** Preserve literal ampersands when echoing operator-authored configuration values. */
    public static void sendRaw(CommandSender sender, String text) {
        Message.CUSTOM.send(sender, ChatColor.translateAlternateColorCodes('&', PREFIX) + text, false);
    }
}
