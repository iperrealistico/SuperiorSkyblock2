package com.bgsoftware.superiorskyblock.platform.folia.nms.v1_21_11;

import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.pvc.runtime.PvcLatestDelivery;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * Fight state is owned by its region; recipient delivery is owned by each player.
 * The native viewer set stays empty. One pending callback per viewer coalesces
 * updates, including hide/remove, without touching a foreign player's connection.
 */
public final class RegionalDragonBossEvent extends ServerBossEvent {
    private final Object stateLock = new Object();
    private final ConcurrentMap<ServerPlayer, Viewer> viewers = new ConcurrentHashMap<>();
    private boolean regionalVisible = true;
    private PacketSnapshot snapshot;

    public RegionalDragonBossEvent(Component name, BossBarColor color, BossBarOverlay overlay) {
        super(name, color, overlay);
        this.snapshot = snapshotState();
    }

    @Override public void setProgress(float value) {
        synchronized (this.stateLock) { super.setProgress(value); }
    }
    @Override public void setColor(BossBarColor value) {
        synchronized (this.stateLock) { super.setColor(value); }
    }
    @Override public void setOverlay(BossBarOverlay value) {
        synchronized (this.stateLock) { super.setOverlay(value); }
    }
    @Override public void setName(Component value) {
        synchronized (this.stateLock) { super.setName(value); }
    }
    @Override public BossEvent setDarkenScreen(boolean value) {
        synchronized (this.stateLock) { super.setDarkenScreen(value); }
        return this;
    }
    @Override public BossEvent setPlayBossMusic(boolean value) {
        synchronized (this.stateLock) { super.setPlayBossMusic(value); }
        return this;
    }
    @Override public BossEvent setCreateWorldFog(boolean value) {
        synchronized (this.stateLock) { super.setCreateWorldFog(value); }
        return this;
    }

    @Override public void broadcast(Function<BossEvent, ClientboundBossEventPacket> ignoredFactory) {
        synchronized (this.stateLock) {
            this.snapshot = snapshotState();
            for (Viewer viewer : this.viewers.values())
                if (viewer.member) publish(viewer, true);
        }
    }

    @Override public void setVisible(boolean visible) {
        synchronized (this.stateLock) {
            if (visible == this.regionalVisible) return;
            // Native viewers are empty, so this preserves native dirty-state
            // semantics without sending any packets from the fight region.
            super.setVisible(visible);
            this.regionalVisible = visible;
            this.snapshot = snapshotState();
            for (Viewer viewer : this.viewers.values())
                if (viewer.member) publish(viewer, true);
        }
    }

    @Override public boolean isVisible() {
        synchronized (this.stateLock) { return this.regionalVisible; }
    }

    @Override public void addPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        synchronized (this.stateLock) {
            Viewer viewer = this.viewers.computeIfAbsent(player, Viewer::new);
            if (viewer.member) return;
            viewer.member = true;
            publish(viewer, true);
        }
    }

    @Override public void removePlayer(ServerPlayer player) {
        synchronized (this.stateLock) {
            Viewer viewer = this.viewers.get(player);
            if (viewer == null || !viewer.member) return;
            viewer.member = false;
            publish(viewer, false);
        }
    }

    @Override public void removeAllPlayers() {
        synchronized (this.stateLock) {
            for (Viewer viewer : this.viewers.values()) {
                if (!viewer.member) continue;
                viewer.member = false;
                publish(viewer, false);
            }
        }
    }

    @Override public Collection<ServerPlayer> getPlayers() {
        synchronized (this.stateLock) {
            ArrayList<ServerPlayer> players = new ArrayList<>();
            for (Viewer viewer : this.viewers.values())
                if (viewer.member) players.add(viewer.player);
            return Collections.unmodifiableList(players);
        }
    }

    private void publish(Viewer viewer, boolean member) {
        try {
            viewer.delivery.publish(new Desired(member, member && this.regionalVisible ? this.snapshot : null));
        } catch (RejectedExecutionException stopping) {
            // Entity retirement or plugin shutdown has no live recipient.
            viewer.delivery.retire();
        }
    }

    private PacketSnapshot snapshotState() {
        // Component.copy() detaches the packet name from the mutable native
        // component. The frozen event is private and never mutated afterward.
        FrozenBossEvent frozen = new FrozenBossEvent(getId(), super.getName().copy(),
                super.getProgress(), super.getColor(), super.getOverlay(),
                super.shouldDarkenScreen(), super.shouldPlayBossMusic(), super.shouldCreateWorldFog());
        return new PacketSnapshot(frozen);
    }

    private final class Viewer {
        final ServerPlayer player;
        final PvcLatestDelivery<Desired> delivery;
        boolean member;
        PacketSnapshot displayed; // Accessed only by this player's entity scheduler.

        Viewer(ServerPlayer player) {
            this.player = player;
            this.delivery = new PvcLatestDelivery<>(
                    (task, retired) -> BukkitExecutor.entity(player.getBukkitEntity(), task, 1L, retired),
                    this::deliver,
                    this::completed,
                    () -> {
                        synchronized (stateLock) {
                            this.member = false;
                            viewers.remove(this.player, this);
                        }
                    });
        }

        private void deliver(Desired desired) {
            PacketSnapshot next = desired.snapshot;
            if (next == null) {
                if (this.displayed != null) this.player.connection.send(ClientboundBossEventPacket.createRemovePacket(getId()));
            } else if (this.displayed == null) {
                this.player.connection.send(next.add);
            } else {
                next.update(this.player, this.displayed);
            }
            this.displayed = next;
        }

        private void completed(Desired desired) {
            if (desired.member) return;
            synchronized (stateLock) {
                // Re-add may have reused this same viewer while remove was in
                // flight. Never discard its newer desired state or display.
                if (!this.member && this.delivery.current() == desired && this.delivery.isIdle())
                    viewers.remove(this.player, this);
            }
        }
    }

    private static final class Desired {
        final boolean member;
        final PacketSnapshot snapshot;
        Desired(boolean member, PacketSnapshot snapshot) {
            this.member = member;
            this.snapshot = snapshot;
        }
    }

    private static final class FrozenBossEvent extends BossEvent {
        FrozenBossEvent(java.util.UUID id, Component name, float progress,
                        BossBarColor color, BossBarOverlay overlay,
                        boolean darken, boolean music, boolean fog) {
            super(id, name, color, overlay);
            this.progress = progress;
            this.darkenScreen = darken;
            this.playBossMusic = music;
            this.createWorldFog = fog;
        }
    }

    private static final class PacketSnapshot {
        final Component name;
        final float progress;
        final BossBarColor color;
        final BossBarOverlay overlay;
        final boolean darken, music, fog;
        final ClientboundBossEventPacket add, updateName, updateProgress, updateStyle, updateProperties;

        PacketSnapshot(FrozenBossEvent event) {
            this.name = event.getName();
            this.progress = event.getProgress();
            this.color = event.getColor();
            this.overlay = event.getOverlay();
            this.darken = event.shouldDarkenScreen();
            this.music = event.shouldPlayBossMusic();
            this.fog = event.shouldCreateWorldFog();
            this.add = ClientboundBossEventPacket.createAddPacket(event);
            this.updateName = ClientboundBossEventPacket.createUpdateNamePacket(event);
            this.updateProgress = ClientboundBossEventPacket.createUpdateProgressPacket(event);
            this.updateStyle = ClientboundBossEventPacket.createUpdateStylePacket(event);
            this.updateProperties = ClientboundBossEventPacket.createUpdatePropertiesPacket(event);
        }

        void update(ServerPlayer player, PacketSnapshot previous) {
            if (!Objects.equals(this.name, previous.name)) player.connection.send(this.updateName);
            if (this.progress != previous.progress) player.connection.send(this.updateProgress);
            if (this.color != previous.color || this.overlay != previous.overlay) player.connection.send(this.updateStyle);
            if (this.darken != previous.darken || this.music != previous.music || this.fog != previous.fog)
                player.connection.send(this.updateProperties);
        }
    }
}
