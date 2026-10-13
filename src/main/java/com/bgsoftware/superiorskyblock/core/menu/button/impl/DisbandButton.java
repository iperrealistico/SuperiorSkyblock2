package com.bgsoftware.superiorskyblock.core.menu.button.impl;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.menu.button.click.ButtonClickContext;
import com.bgsoftware.superiorskyblock.api.menu.button.MenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.menu.button.AbstractMenuTemplateButton;
import com.bgsoftware.superiorskyblock.core.menu.button.AbstractMenuViewButton;
import com.bgsoftware.superiorskyblock.core.menu.button.MenuTemplateButtonImpl;
import com.bgsoftware.superiorskyblock.core.menu.view.impl.IslandMenuView;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.module.BuiltinModules;

import java.math.BigDecimal;
import java.util.Collections;

public class DisbandButton extends AbstractMenuViewButton<IslandMenuView> {

    private DisbandButton(AbstractMenuTemplateButton<IslandMenuView> templateButton, IslandMenuView menuView) {
        super(templateButton, menuView);
    }

    @Override
    public Template getTemplate() {
        return (Template) super.getTemplate();
    }

    @Override
    public void onButtonClick(ButtonClickContext<IslandMenuView> context) {
        SuperiorPlayer inventoryViewer = menuView.getInventoryViewer();
        Island targetIsland = menuView.getIsland();

        if (!menuView.claimIslandAction(targetIsland, com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges.DISBAND_ISLAND)) return;

        if (getTemplate().disbandIsland && PluginEventsFactory.callIslandDisbandEvent(targetIsland, inventoryViewer)) {
            IslandUtils.disbandWithFeedback(targetIsland, context.getPlayer(), inventoryViewer.getName(), () -> {
                if (plugin.getSettings().getDisbandCount() >= 0)
                    inventoryViewer.setDisbands(inventoryViewer.getDisbands() - 1);
                Message.DISBANDED_ISLAND.send(inventoryViewer);
            });
        }

        plugin.executePlayer(menuView.getInventoryViewer(), menuView::closeView, 1L);
    }

    public static class Builder extends AbstractMenuTemplateButton.AbstractBuilder<IslandMenuView> {

        private boolean disbandIsland;

        public Builder setDisbandIsland(boolean disbandIsland) {
            this.disbandIsland = disbandIsland;
            return this;
        }

        @Override
        public MenuTemplateButton<IslandMenuView> build() {
            return new Template(this, disbandIsland);
        }

    }

    public static class Template extends MenuTemplateButtonImpl<IslandMenuView> {

        private final boolean disbandIsland;

        Template(AbstractBuilder<IslandMenuView> builder, boolean disbandIsland) {
            super(builder, DisbandButton.class, DisbandButton::new);
            this.disbandIsland = disbandIsland;
        }

    }

}
