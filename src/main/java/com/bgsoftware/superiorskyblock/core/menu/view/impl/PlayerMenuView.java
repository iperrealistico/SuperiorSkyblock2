package com.bgsoftware.superiorskyblock.core.menu.view.impl;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.api.menu.Menu;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.menu.view.AbstractMenuView;
import com.bgsoftware.superiorskyblock.core.menu.view.IPlayerMenuView;
import com.bgsoftware.superiorskyblock.core.menu.view.args.PlayerViewArgs;

public class PlayerMenuView extends AbstractMenuView<PlayerMenuView, PlayerViewArgs> implements IPlayerMenuView, com.bgsoftware.superiorskyblock.core.menu.view.IIslandMenuView {

    private final SuperiorPlayer superiorPlayer;
    private final com.bgsoftware.superiorskyblock.api.island.Island island;

    public PlayerMenuView(SuperiorPlayer inventoryViewer, @Nullable MenuView<?, ?> previousMenuView,
                          Menu<PlayerMenuView, PlayerViewArgs> menu, PlayerViewArgs args) {
        super(inventoryViewer, previousMenuView, menu);
        this.superiorPlayer = args.getSuperiorPlayer();
        this.island = previousMenuView instanceof com.bgsoftware.superiorskyblock.core.menu.view.IIslandMenuView ?
                ((com.bgsoftware.superiorskyblock.core.menu.view.IIslandMenuView) previousMenuView).getIsland() : inventoryViewer.getIsland();
        bindActionTarget(this.island);
        this.cachedTitleArgs = new Object[]{this.superiorPlayer.getName()};
    }

    @Override
    public SuperiorPlayer getSuperiorPlayer() {
        return this.superiorPlayer;
    }

    @Override public com.bgsoftware.superiorskyblock.api.island.Island getIsland() { return island; }

}
