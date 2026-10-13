package com.bgsoftware.superiorskyblock.core.menu;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.menu.MenuCommands;
import com.bgsoftware.superiorskyblock.api.menu.button.click.ButtonClickContext;
import com.bgsoftware.superiorskyblock.api.menu.layout.InventoryMenuLayout;
import com.bgsoftware.superiorskyblock.api.menu.layout.MenuLayout;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import com.bgsoftware.superiorskyblock.api.service.placeholders.PlaceholdersService;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.menu.button.click.ButtonClickContextImpl;
import com.bgsoftware.superiorskyblock.core.menu.dialog.DialogWrapper;
import com.bgsoftware.superiorskyblock.core.menu.view.AbstractMenuView;
import com.bgsoftware.superiorskyblock.core.menu.view.IPlayerMenuView;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MenuCommandsImpl implements MenuCommands {

    private static final MenuCommandsImpl INSTANCE = new MenuCommandsImpl();

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final LazyReference<PlaceholdersService> placeholdersService = new LazyReference<PlaceholdersService>() {
        @Override
        protected PlaceholdersService create() {
            return plugin.getServices().getService(PlaceholdersService.class);
        }
    };

    private static final Pattern COMMAND_PATTERN_ARGS = Pattern.compile("\\[(.+)](.+)");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\[(.+)]");

    public static MenuCommandsImpl getInstance() {
        return INSTANCE;
    }

    private MenuCommandsImpl() {

    }

    @Override
    public void runCommand(MenuView<?, ?> menuView, String command, InventoryClickEvent clickEvent) {
        try (ButtonClickContextImpl<?> ctx = ButtonClickContextImpl.obtain((MenuView) menuView, clickEvent)) {
            runCommand(menuView, command, ctx);
        }
    }

    @Override
    public void runCommand(MenuView<?, ?> menuView, String command, ButtonClickContext<?> context) {
        if (menuView instanceof AbstractMenuView && !((AbstractMenuView<?, ?>) menuView).isIslandContextCurrent())
            return;
        MenuLayout<?> menuLayout = menuView.getMenu().getLayout();
        if (menuLayout instanceof InventoryMenuLayout) {
            Player player = context.getPlayer();
            runCommandInternal(menuView, command, player, Bukkit.getConsoleSender(),
                    menuView::closeView, player::closeInventory);
        } else {
            DialogWrapper<?> dialog = ((AbstractMenuView<?, ?>) menuView).getDialog();
            SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(context.getPlayer());
            runCommandInternal(menuView, command, context.getPlayer(), Bukkit.getConsoleSender(),
                    menuView::closeView, () -> plugin.getNMSDialogs().ifPresent(nmsDialogs ->
                            nmsDialogs.closeDialog(superiorPlayer, dialog)));
        }
    }

    private void runCommandInternal(MenuView<?, ?> menuView, String command, Player player,
                                    CommandSender sender, Runnable onClose, Runnable onBack) {
        Matcher matcher = COMMAND_PATTERN_ARGS.matcher(command);
        if (matcher.matches()) {
            handleSubCommand(menuView, matcher.group(1), matcher.group(2).trim(), player, sender, onClose, onBack);
            return;
        }
        if ((matcher = COMMAND_PATTERN.matcher(command)).matches()) {
            handleSubCommand(menuView, matcher.group(1), "", player, sender, onClose, onBack);
            return;
        }
        if (command.equalsIgnoreCase("close")) {
            ((AbstractMenuView<?, ?>) menuView).setClickedCloseButton();
            onClose.run();
            return;
        }
        if (command.equalsIgnoreCase("back")) {
            ((AbstractMenuView<?, ?>) menuView).setClickedCloseButton();
            onBack.run();
            return;
        }

        SuperiorPlayer targetPlayer = menuView instanceof IPlayerMenuView ?
                ((IPlayerMenuView) menuView).getSuperiorPlayer() : null;

        if (targetPlayer != null)
            command = placeholdersService.get().parsePlaceholders(targetPlayer.asOfflinePlayer(), command);
        else if (sender instanceof Player)
            command = placeholdersService.get().parsePlaceholders((Player) sender, command);
        else
            command = placeholdersService.get().parsePlaceholders(player, command);

        CommandSender effectiveSender = sender instanceof Player || command.startsWith("PLAYER:") ? player : Bukkit.getConsoleSender();
        String expandedCommand = command.replace("PLAYER:", "").replace("%player%", player.getName());
        com.bgsoftware.superiorskyblock.api.island.Island capturedIsland = menuView instanceof AbstractMenuView ?
                ((AbstractMenuView<?, ?>) menuView).getActionTarget() : null;
        dispatchConfiguredCommand(menuView, effectiveSender, expandedCommand, capturedIsland);
    }

    /** Shared boundary for generic and built-in configured hooks, including global-list item targets. */
    public void dispatchConfiguredCommand(MenuView<?, ?> menuView, CommandSender effectiveSender,
                                          String expandedCommand, com.bgsoftware.superiorskyblock.api.island.Island capturedIsland) {
        if (menuView instanceof AbstractMenuView && !((AbstractMenuView<?, ?>) menuView).isIslandContextCurrent()) return;
        if (capturedIsland != null && (plugin.getGrid().getIslandByUUID(capturedIsland.getUniqueId()) != capturedIsland ||
                !plugin.getPvcIslandService().isAvailable(capturedIsland.getUniqueId()))) return;
        if (!(effectiveSender instanceof Player) &&
                !com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch.isOwnCommand(plugin, expandedCommand)) {
            java.util.UUID capturedOwner = capturedIsland == null || capturedIsland.getOwner() == null ? null : capturedIsland.getOwner().getUniqueId();
            long revision = capturedIsland == null ? 0L : plugin.getPvcIslandService().getRevision(capturedIsland.getUniqueId());
            long membershipRevision = capturedIsland == null ? 0L : plugin.getPvcIslandService().getMembershipRevision(capturedIsland.getUniqueId());
            com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.global(() -> {
                if (menuView instanceof AbstractMenuView && !((AbstractMenuView<?, ?>) menuView).isIslandContextCurrent()) return;
                if (capturedIsland != null && (plugin.getGrid().getIslandByUUID(capturedIsland.getUniqueId()) != capturedIsland ||
                        !plugin.getPvcIslandService().isAvailable(capturedIsland.getUniqueId()) ||
                        !java.util.Objects.equals(capturedOwner, capturedIsland.getOwner() == null ? null : capturedIsland.getOwner().getUniqueId()) ||
                        revision != plugin.getPvcIslandService().getRevision(capturedIsland.getUniqueId()) ||
                        membershipRevision != plugin.getPvcIslandService().getMembershipRevision(capturedIsland.getUniqueId()))) return;
                com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch.dispatch(plugin, effectiveSender, expandedCommand, capturedIsland);
            });
        } else {
            // Own handlers stay synchronous so following hooks observe their actual mutation.
            if (capturedIsland == null)
                com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch.dispatchCurrent(plugin, effectiveSender, expandedCommand);
            else
                com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch.dispatch(plugin, effectiveSender, expandedCommand, capturedIsland);
        }
    }

    private void handleSubCommand(MenuView<?, ?> menuView, String subCommand, String args,
                                  Player player, CommandSender sender, Runnable onClose, Runnable onBack) {
        switch (subCommand.toLowerCase(Locale.ENGLISH)) {
            case "player":
                runCommandInternal(menuView, args, player, player, onClose, onBack);
                break;
            case "admin":
                String commandLabel = plugin.getSettings().getIslandCommand().split(",")[0];
                runCommandInternal(menuView, commandLabel + " admin " + args, player, sender, onClose, onBack);
                break;
            case "close":
                ((AbstractMenuView<?, ?>) menuView).setClickedCloseButton();
                onClose.run();
                break;
            case "back":
                ((AbstractMenuView<?, ?>) menuView).setClickedCloseButton();
                onBack.run();
                break;
            default:
                dispatchConfiguredCommand(menuView, sender, plugin.getCommands().getLabel() + " " + subCommand +
                        (args.isEmpty() ? "" : " " + args),
                        menuView instanceof AbstractMenuView ? ((AbstractMenuView<?, ?>) menuView).getActionTarget() : null);
                break;
        }
    }

}
