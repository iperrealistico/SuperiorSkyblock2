package com.bgsoftware.superiorskyblock.core.menu.button.impl;

import com.bgsoftware.common.config.CommentedConfiguration;
import com.bgsoftware.superiorskyblock.api.menu.button.MenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.menu.button.PagedMenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.itemstack.ItemBuilder;
import com.bgsoftware.superiorskyblock.core.menu.Menus;
import com.bgsoftware.superiorskyblock.core.menu.button.AbstractPagedMenuButton;
import com.bgsoftware.superiorskyblock.core.menu.button.PagedMenuTemplateButtonImpl;
import com.bgsoftware.superiorskyblock.core.menu.impl.internal.MenuConfigEditor;
import com.bgsoftware.superiorskyblock.player.chat.PlayerChat;
import org.bukkit.ChatColor;
import com.bgsoftware.superiorskyblock.api.menu.button.click.ButtonClickContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ConfigEditorPagedObjectButton extends AbstractPagedMenuButton<MenuConfigEditor.View, ItemStack> {

    private ConfigEditorPagedObjectButton(MenuTemplateButton<MenuConfigEditor.View> templateButton, MenuConfigEditor.View menuView) {
        super(templateButton, menuView);
    }

    @Override
    public void onButtonClick(ButtonClickContext<MenuConfigEditor.View> context) {
        MenuConfigEditor.View currentView = getView();
        SuperiorPlayer inventoryViewer = currentView.getInventoryViewer();
        Player player = inventoryViewer.asPlayer();

        if (player == null)
            return;

        try {
            String sectionPath = currentView.getPathSlots().get((currentView.getCurrentPage() - 1) * 36 +
                    context.getClickedSlot());

            if (sectionPath == null)
                return;

            String viewPath = currentView.getPath();
            String fullPath = viewPath.isEmpty() ? sectionPath : viewPath + "." + sectionPath;

            CommentedConfiguration config = Menus.MENU_CONFIG_EDITOR.getConfig();

            if (config.isConfigurationSection(fullPath)) {
                currentView.setPreviousMove(false);
                currentView.closeView();
                currentView.getMenu().createView(inventoryViewer, new MenuConfigEditor.Args(fullPath), currentView);
            } else if (config.isBoolean(fullPath)) {
                Menus.MENU_CONFIG_EDITOR.updateConfig(player, fullPath, !config.getBoolean(fullPath));
                currentView.refreshView();
            } else {
                PlayerChat.listen(player, message -> onPlayerChat(currentView, config, player, message, fullPath));

                currentView.closeView();

                com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player, "Please enter a new value &8(&6-cancel &7to cancel&8):");

                if (config.isList(fullPath)) {
                    com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player, "If you enter a value that is already in the list, it will be removed.");
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public ItemStack modifyViewItem(ItemBuilder itemBuilder) {
        return pagedObject;
    }

    private boolean onPlayerChat(MenuView<?, ?> lastView, CommentedConfiguration config, Player player, Object message, String path) {
        if (!message.toString().equalsIgnoreCase("-cancel")) {
            if (config.isList(path)) {
                List<String> list = config.getStringList(path);

                if (list.contains(message.toString())) {
                    list.remove(message.toString());
                    com.bgsoftware.superiorskyblock.pvc.PvcMessages.sendRaw(player,
                            "Removed the value " + ChatColor.YELLOW + message + ChatColor.GRAY + " from " + ChatColor.YELLOW + path + ChatColor.GRAY + ".");
                } else {
                    list.add(message.toString());
                    com.bgsoftware.superiorskyblock.pvc.PvcMessages.sendRaw(player,
                            "Added the value " + ChatColor.YELLOW + message + ChatColor.GRAY + " to " + ChatColor.YELLOW + path + ChatColor.GRAY + ".");
                }

                config.set(path, list);
            } else {
                boolean valid = true;
                if (config.isInt(path)) {
                    try {
                        message = Integer.valueOf(message.toString());
                    } catch (IllegalArgumentException ex) {
                        com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player, "Please specify a valid number.");
                        valid = false;
                    }
                } else if (config.isDouble(path)) {
                    try {
                        message = Double.valueOf(message.toString());
                    } catch (IllegalArgumentException ex) {
                        com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player, "Please specify a valid number.");
                        valid = false;
                    }
                }

                if (valid) {
                    config.set(path, message);
                    Menus.MENU_CONFIG_EDITOR.updateConfig(player, path, message);
                }
            }
        }

        PlayerChat.remove(player);

        lastView.refreshView();

        return true;
    }

    public static class Builder extends PagedMenuTemplateButtonImpl.AbstractBuilder<MenuConfigEditor.View, ItemStack> {

        @Override
        public PagedMenuTemplateButton<MenuConfigEditor.View, ItemStack> build() {
            return new PagedMenuTemplateButtonImpl<>(this, ConfigEditorPagedObjectButton.class,
                    ConfigEditorPagedObjectButton::new);
        }

    }

}
