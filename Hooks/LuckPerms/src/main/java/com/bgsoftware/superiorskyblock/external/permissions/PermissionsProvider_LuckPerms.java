package com.bgsoftware.superiorskyblock.external.permissions;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.hooks.PermissionsProvider;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.player.permissions.PlayerPermissionsStore;
import com.bgsoftware.superiorskyblock.pvc.entitlement.EntitlementPolicy;
import com.bgsoftware.superiorskyblock.pvc.entitlement.OwnerPermissionData;
import com.bgsoftware.superiorskyblock.pvc.entitlement.OwnerPermissionProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.RegexPermissionNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("unused")
public class PermissionsProvider_LuckPerms implements PermissionsProvider, OwnerPermissionProvider {

    private final LuckPerms luckPerms;
    private final SuperiorSkyblockPlugin plugin;
    private final Set<UUID> resolvingOwners = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isCompatible() {
        try {
            CachedDataManager.class.getMethod("getPermissionData");
            return true;
        } catch (Throwable ex) {
            Log.warn("You are using an outdated version of LuckPerms. It's recommended to update for a more optimized experience (v5.1+).");
            return false;
        }
    }

    public PermissionsProvider_LuckPerms(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.luckPerms = Bukkit.getServicesManager().getRegistration(LuckPerms.class).getProvider();
        this.luckPerms.getEventBus().subscribe(plugin, NodeAddEvent.class, this::onPermissionsAdd);
        this.luckPerms.getEventBus().subscribe(plugin, NodeRemoveEvent.class, this::onPermissionsRemove);
        this.luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
            UUID owner = event.getUser().getUniqueId();
            // Reading a previously unused static context itself calculates a cache. It is not a policy mutation.
            if (!resolvingOwners.contains(owner)) notifyPermissionsUpdate(owner);
        });
        Log.info("Using LuckPerms as a permissions provider.");
    }

    @Override
    public boolean hasPermission(Player player, String permission) {
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        return user != null && user.getCachedData().getPermissionData().getPermissionMap()
                .getOrDefault(permission.toLowerCase(Locale.ENGLISH), false);
    }

    private void onPermissionsAdd(NodeAddEvent e) {
        if (e.getTarget() instanceof User && isTrackableNode(e.getNode()))
            notifyPermissionsUpdate(((User) e.getTarget()).getUniqueId());
        else if (!(e.getTarget() instanceof User) && isTrackableNode(e.getNode()) && plugin.getPvcEntitlements() != null)
            plugin.getPvcEntitlements().invalidateAll();
    }

    private void onPermissionsRemove(NodeRemoveEvent e) {
        if (e.getTarget() instanceof User && isTrackableNode(e.getNode()))
            notifyPermissionsUpdate(((User) e.getTarget()).getUniqueId());
        else if (!(e.getTarget() instanceof User) && isTrackableNode(e.getNode()) && plugin.getPvcEntitlements() != null)
            plugin.getPvcEntitlements().invalidateAll();
    }

    private void notifyPermissionsUpdate(UUID playerUUID) {
        PlayerPermissionsStore permissionsStore = PlayerPermissionsStore.getPermissionsStore(playerUUID);
        if (permissionsStore != null)
            permissionsStore.markDirty();
        if (plugin.getPvcEntitlements() != null)
            plugin.getPvcEntitlements().invalidate(playerUUID);
    }

    @Override
    public CompletableFuture<OwnerPermissionData> resolveOwnerPermissions(UUID owner, String islandWorld,
            int gridCap, Set<Integer> configuredRadiusCandidates, int maxMatchingNodes) {
        resolvingOwners.add(owner);
        User loaded = this.luckPerms.getUserManager().getUser(owner);
        boolean temporary = loaded == null;
        CompletableFuture<User> userFuture = temporary ? this.luckPerms.getUserManager().loadUser(owner) :
                CompletableFuture.completedFuture(loaded);
        CompletableFuture<OwnerPermissionData> result = userFuture.thenApply(user -> {
            try {
                QueryOptions defaults = this.luckPerms.getContextManager().getStaticQueryOptions();
                ImmutableContextSet context = ImmutableContextSet.builder().addAll(defaults.context())
                        .add("world", islandWorld.toLowerCase(Locale.ENGLISH)).build();
                CachedPermissionData data = user.getCachedData().getPermissionData(defaults.toBuilder().context(context).build());
                Map<String, Boolean> values = new HashMap<>();
                Set<String> candidates = new LinkedHashSet<>();
                for (String raw : data.getPermissionMap().keySet()) {
                    String node = raw.toLowerCase(Locale.ENGLISH);
                    if (EntitlementPolicy.positiveSuffix(node, EntitlementPolicy.OWNERSHIP_PREFIX) != null ||
                            EntitlementPolicy.positiveSuffix(node, EntitlementPolicy.RADIUS_PREFIX) != null) candidates.add(node);
                    if (candidates.size() > maxMatchingNodes) return OwnerPermissionData.unknown();
                }
                // A wildcard applies only to finite known candidates and the reserved-cell ceiling.
                candidates.add(EntitlementPolicy.RADIUS_PREFIX + gridCap);
                for (int radius : configuredRadiusCandidates) candidates.add(EntitlementPolicy.RADIUS_PREFIX + radius);
                candidates.add(EntitlementPolicy.UNLIMITED);
                if (candidates.size() > maxMatchingNodes) return OwnerPermissionData.unknown();
                for (String candidate : candidates) values.put(candidate, data.checkPermission(candidate).asBoolean());
                return OwnerPermissionData.known(values);
            } finally {
                if (temporary) this.luckPerms.getUserManager().cleanupUser(user);
            }
        });
        result.whenComplete((data, error) -> resolvingOwners.remove(owner));
        return result;
    }

    private static boolean isTrackableNode(Node node) {
        if (node instanceof PermissionNode) {
            return ((PermissionNode) node).getPermission().contains("superior");
        }

        // In the case of RegexPermissionNode - we are not going to try and parse the regex.
        // In the case of InheritanceNode - we are not going to go through the inherited group and check for permissions.
        // We will always mark cache as dirty for both of these events.
        return node instanceof RegexPermissionNode || node instanceof InheritanceNode;
    }

}
