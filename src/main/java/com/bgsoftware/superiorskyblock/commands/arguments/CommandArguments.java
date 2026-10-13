package com.bgsoftware.superiorskyblock.commands.arguments;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.enums.BorderColor;
import com.bgsoftware.superiorskyblock.api.enums.Rating;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandFlag;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.island.warps.IslandWarp;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionCategory;
import com.bgsoftware.superiorskyblock.api.schematic.Schematic;
import com.bgsoftware.superiorskyblock.api.upgrades.Upgrade;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.island.flag.IslandFlags;
import com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges;
import com.bgsoftware.superiorskyblock.island.role.SPlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CommandArguments {

    private CommandArguments() {

    }

    public static IslandArgument getIsland(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        SuperiorPlayer targetPlayer = plugin.getPlayers().getSuperiorPlayer(argument);
        Island island = findIsland(plugin, sender, argument);
        if (island == null && targetPlayer != null &&
                (targetPlayer.getOwnedIslands().size() > 1 || targetPlayer.getIslands().size() > 1)) {
            Message.CUSTOM.send(sender, "&cSpecify the destination island by name or ID.", true);
            return new IslandArgument(null, targetPlayer);
        }

        if (island != null && !plugin.getPvcIslandService().isAvailable(island.getUniqueId())) {
            Message.CUSTOM.send(sender, "&cThis island has an operation in progress. Please wait.", true);
            return new IslandArgument(null, targetPlayer);
        }
        if (island == null) {
            if (argument.equalsIgnoreCase(sender.getName()))
                Message.INVALID_ISLAND.send(sender);
            else if (targetPlayer == null)
                Message.INVALID_ISLAND_OTHER_NAME.send(sender, Formatters.STRIP_COLOR_FORMATTER.format(argument));
            else
                Message.INVALID_ISLAND_OTHER.send(sender, targetPlayer.getName());
        }

        return new IslandArgument(island, targetPlayer);
    }

    /** Silent resolver shared by execution and completion. */
    public static Island findIsland(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        Island context = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
        if (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget() && context != null) return context;
        SuperiorPlayer subject = plugin.getPlayers().getSuperiorPlayer(argument);
        if (subject == null) return plugin.getPvcIslandService().resolve(argument);
        if (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isInheritedTarget() &&
                context != null && context.isMember(subject)) return context;
        boolean precise = !(sender instanceof Player) || sender.hasPermission("superior.admin") ||
                com.bgsoftware.superiorskyblock.commands.MultiIslandCommands.unlocked(plugin, sender);
        return resolvePlayerIsland(subject, precise);
    }

    public static Island resolvePlayerIsland(SuperiorPlayer subject, boolean precise) {
        List<Island> owned = subject.getOwnedIslands();
        if (owned.size() == 1) return owned.get(0);
        List<Island> candidates = owned.isEmpty() ? subject.getIslands() : owned;
        if (candidates.size() == 1) return candidates.get(0);
        if (precise || candidates.isEmpty()) return null;
        Island selected = subject.getSelectedIsland();
        return candidates.contains(selected) ? selected : candidates.get(0);
    }

    public static IslandsListArgument getMultipleIslands(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        List<Island> islands = new LinkedList<>();
        SuperiorPlayer targetPlayer;

        Island explicitTarget = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget() ?
                com.bgsoftware.superiorskyblock.pvc.island.IslandExecutionContext.current() : null;
        if (explicitTarget != null) {
            targetPlayer = explicitTarget.getOwner(); islands.add(explicitTarget);
        } else if (argument.equals("*")) {
            targetPlayer = null;
            islands = plugin.getGrid().getIslands();
        } else {
            IslandArgument arguments = getIsland(plugin, sender, argument);
            targetPlayer = arguments.getSuperiorPlayer();
            if (arguments.getIsland() != null)
                islands.add(arguments.getIsland());
        }

        return new IslandsListArgument(Collections.unmodifiableList(islands), targetPlayer);
    }

    public static IslandArgument getSenderIsland(SuperiorSkyblockPlugin plugin, CommandSender sender) {
        SuperiorPlayer superiorPlayer = sender instanceof Player ? plugin.getPlayers().getSuperiorPlayer(sender) : null;
        Island island = superiorPlayer == null ?
                com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current() : superiorPlayer.getIsland();

        if (island != null && !plugin.getPvcIslandService().isAvailable(island.getUniqueId())) {
            Message.CUSTOM.send(sender, "&cThis island is being recovered or removed. Please wait.", true);
            island = null;
        }

        if (island == null)
            Message.INVALID_ISLAND.send(sender);

        return new IslandArgument(island, superiorPlayer);
    }

    /** Public reporters can inspect a precise foreign subject without changing membership-based wrappers. */
    public static IslandArgument getReportingIsland(SuperiorSkyblockPlugin plugin, CommandSender sender) {
        return reportingIsland(com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget() ||
                        com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isInheritedTarget(),
                com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current(),
                () -> getSenderIsland(plugin, sender), island -> {
                    if (plugin.getGrid().getIslandByUUID(island.getUniqueId()) != island ||
                            !plugin.getPvcIslandService().isAvailable(island.getUniqueId())) {
                        Message.INVALID_ISLAND.send(sender); return IslandArgument.EMPTY;
                    }
                    return getIsland(plugin, sender, "uuid:" + island.getUniqueId());
                });
    }

    static IslandArgument reportingIsland(boolean precise, Island captured,
                                           java.util.function.Supplier<IslandArgument> ordinary,
                                           java.util.function.Function<Island, IslandArgument> report) {
        return precise && captured != null ? report.apply(captured) : ordinary.get();
    }

    public static SuperiorPlayer getPlayer(SuperiorSkyblockPlugin plugin, SuperiorPlayer superiorPlayer, String argument) {
        return getPlayer(plugin, superiorPlayer.asPlayer(), argument);
    }

    public static SuperiorPlayer getPlayer(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        SuperiorPlayer targetPlayer = plugin.getPlayers().getSuperiorPlayer(argument);

        if (targetPlayer == null)
            Message.INVALID_PLAYER.send(sender, argument);

        return targetPlayer;
    }

    public static List<SuperiorPlayer> getMultiplePlayers(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        List<SuperiorPlayer> players = new LinkedList<>();

        if (argument.equals("*")) {
            players = plugin.getPlayers().getAllPlayers();
        } else {
            SuperiorPlayer targetPlayer = getPlayer(plugin, sender, argument);
            if (targetPlayer != null)
                players.add(targetPlayer);
        }

        return Collections.unmodifiableList(players);
    }

    public static IslandArgument getIslandWhereStandingOrSenderIsland(SuperiorSkyblockPlugin plugin, CommandSender sender) {
        Island target = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget() ?
                com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current() : null;
        if (target != null) return new IslandArgument(target, target.getOwner());
        if (!(sender instanceof Player)) {
            Message.CUSTOM.send(sender, "&cYou must specify a player's name.", true);
            return IslandArgument.EMPTY;
        }


        SuperiorPlayer superiorPlayer;
        Island island;
        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            Island locationIsland = plugin.getGrid().getIslandAt(((Player) sender).getLocation(wrapper.getHandle()));
            if (locationIsland == null || locationIsland.isSpawn()) {
                superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);
                island = superiorPlayer.getIsland();
            } else {
                island = locationIsland;
                superiorPlayer = island.getOwner();
            }
        }

        if (island == null)
            Message.INVALID_ISLAND.send(sender);

        return new IslandArgument(island, superiorPlayer);
    }

    public static IslandArgument getIslandWhereStanding(SuperiorSkyblockPlugin plugin, CommandSender sender) {
        Island target = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget() ?
                com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current() : null;
        if (target != null) return new IslandArgument(target, plugin.getPlayers().getSuperiorPlayer(sender));
        if (!(sender instanceof Player)) {
            Message.CUSTOM.send(sender, "&cYou must specify a player's name.", true);
            return IslandArgument.EMPTY;
        }

        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);

        Island island;
        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            Island locationIsland = plugin.getGrid().getIslandAt(((Player) sender).getLocation(wrapper.getHandle()));
            island = locationIsland == null || locationIsland.isSpawn() ? null : locationIsland;
        }

        if (island == null)
            Message.INVALID_ISLAND_LOCATION.send(sender);

        return new IslandArgument(island, superiorPlayer);
    }

    public static Mission<?> getMission(SuperiorSkyblockPlugin plugin, SuperiorPlayer superiorPlayer, String argument) {
        return getMission(plugin, superiorPlayer.asPlayer(), argument);
    }

    public static Mission<?> getMission(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        Mission<?> mission = plugin.getMissions().getMission(argument);

        if (mission == null)
            Message.INVALID_MISSION.send(sender, argument);

        return mission;
    }

    public static List<Mission<?>> getMultipleMissions(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        List<Mission<?>> missions = new LinkedList<>();

        if (argument.equals("*")) {
            missions = plugin.getMissions().getAllMissions();
        } else {
            Mission<?> mission = getMission(plugin, sender, argument);
            if (mission != null)
                missions.add(mission);
        }

        return Collections.unmodifiableList(missions);
    }

    public static MissionCategory getMissionCategory(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        MissionCategory missionCategory = plugin.getMissions().getMissionCategory(argument);

        if (missionCategory == null)
            Message.INVALID_MISSION_CATEGORY.send(sender, argument);

        return missionCategory;
    }

    public static Upgrade getUpgrade(SuperiorSkyblockPlugin plugin, SuperiorPlayer superiorPlayer, String argument) {
        return getUpgrade(plugin, superiorPlayer.asPlayer(), argument);
    }

    public static Upgrade getUpgrade(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        Upgrade upgrade = plugin.getUpgrades().getUpgrade(argument);

        if (upgrade == null) {
            Message.INVALID_UPGRADE.send(sender, argument, plugin.getUpgrades().getUpgradesNames());
        }

        return upgrade;
    }

    public static String buildLongString(String[] args, int start, boolean colorize) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = start; i < args.length; i++)
            stringBuilder.append(" ").append(args[i]);

        return colorize ? Formatters.COLOR_FORMATTER.format(stringBuilder.substring(1)) : stringBuilder.substring(1);
    }

    public static PlayerRole getPlayerRole(CommandSender sender, String argument) {
        PlayerRole playerRole = null;

        try {
            playerRole = SPlayerRole.of(argument);
        } catch (IllegalArgumentException ignored) {
        }

        if (playerRole == null) {
            Message.INVALID_ROLE.send(sender, argument, SPlayerRole.getAllRoleNames());
        }

        return playerRole;
    }

    public static PlayerRole getPlayerRoleFromLadder(CommandSender sender, String argument) {
        PlayerRole playerRole = null;

        try {
            playerRole = SPlayerRole.of(argument);
        } catch (IllegalArgumentException ignored) {
        }

        if (playerRole == null || !playerRole.isRoleLadder()) {
            Message.INVALID_ROLE.send(sender, argument, SPlayerRole.getRolesLadderNames());
            playerRole = null;
        }

        return playerRole;
    }

    public static PlayerRole getPlayerRoleForLimit(CommandSender sender, String argument) {
        PlayerRole playerRole = null;

        try {
            playerRole = SPlayerRole.of(argument);
        } catch (IllegalArgumentException ignored) {
        }

        if (playerRole == null || !IslandUtils.isValidRoleForLimit(playerRole)) {
            Message.INVALID_ROLE.send(sender, argument, SPlayerRole.getRoleLimitsNames());
            playerRole = null;
        }

        return playerRole;
    }

    public static NumberArgument<Integer> getLimit(CommandSender sender, String argument) {
        return getInt(sender, argument, Message.INVALID_LIMIT);
    }

    public static BigDecimal getBigDecimalAmount(CommandSender sender, String argument) {
        BigDecimal amount = null;

        try {
            amount = new BigDecimal(argument);
        } catch (NumberFormatException ex) {
            Message.INVALID_AMOUNT.send(sender);
        }

        return amount;
    }

    public static NumberArgument<Integer> getAmount(CommandSender sender, String argument) {
        return getInt(sender, argument, Message.INVALID_AMOUNT);
    }

    public static NumberArgument<Double> getMultiplier(CommandSender sender, String argument) {
        double multiplier = 0;
        boolean status = true;

        try {
            multiplier = Double.parseDouble(argument);
            // Makes sure the multiplier is rounded.
            multiplier = Math.round(multiplier * 100) / 100D;
        } catch (IllegalArgumentException ex) {
            Message.INVALID_MULTIPLIER.send(sender, argument);
            status = false;
        }

        return new NumberArgument<>(multiplier, status);
    }

    public static PotionEffectType getPotionEffect(CommandSender sender, String argument) {
        PotionEffectType potionEffectType = PotionEffectType.getByName(argument.toUpperCase(Locale.ENGLISH));

        if (potionEffectType == null)
            Message.INVALID_EFFECT.send(sender, argument);

        return potionEffectType;
    }

    public static NumberArgument<Integer> getLevel(CommandSender sender, String argument) {
        return getInt(sender, argument, Message.INVALID_LEVEL);
    }

    public static Material getMaterial(CommandSender sender, String argument) {
        Material material = null;

        try {
            material = Material.valueOf(argument.split(":")[0].toUpperCase(Locale.ENGLISH));
        } catch (Exception ex) {
            Message.INVALID_MATERIAL.send(sender, argument);
        }

        return material;
    }

    public static NumberArgument<Integer> getSize(CommandSender sender, String argument) {
        return getInt(sender, argument, Message.INVALID_SIZE);
    }

    public static IslandWarp getWarp(CommandSender sender, Island island, String[] args, int start) {
        String warpName = buildLongString(args, start, false);
        IslandWarp islandWarp = island.getWarp(warpName);

        if (islandWarp == null)
            Message.INVALID_WARP.send(sender, warpName);

        return islandWarp;
    }

    public static Biome getBiome(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        Biome biome = plugin.getNMSAlgorithms().getBiome(argument);

        if (biome == null) {
            Message.INVALID_BIOME.send(sender, argument);
        }

        return biome;
    }

    public static World getWorld(CommandSender sender, String argument) {
        World world = Bukkit.getWorld(argument);

        if (world == null)
            Message.INVALID_WORLD.send(sender, argument);

        return world;
    }

    public static Location getLocation(CommandSender sender, World world, String x, String y, String z) {
        Location location = null;

        try {
            location = new Location(world, Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z));
        } catch (Throwable ex) {
            Message.INVALID_BLOCK.send(sender, world.getName() + ", " + x + ", " + y + ", " + z);
        }

        return location;
    }

    public static NumberArgument<Integer> getPage(CommandSender sender, String argument) {
        return getInt(sender, argument, Message.INVALID_PAGE);
    }

    public static NumberArgument<Integer> getRows(CommandSender sender, String argument) {
        return getInt(sender, argument, Message.INVALID_ROWS);
    }

    public static IslandPrivilege getIslandPrivilege(CommandSender sender, String argument) {
        IslandPrivilege islandPrivilege = null;

        try {
            islandPrivilege = IslandPrivilege.getByName(argument);
        } catch (NullPointerException ignored) {
        }

        if (islandPrivilege == null) {
            Message.INVALID_ISLAND_PERMISSION.send(sender, argument, IslandPrivileges.getPrivilegesNames());
        }

        return islandPrivilege;
    }

    public static Rating getRating(CommandSender sender, String argument) {
        Rating rating = null;

        try {
            rating = Rating.valueOf(argument.toUpperCase(Locale.ENGLISH));
        } catch (Exception ex) {
            Message.INVALID_RATE.send(sender, argument, Rating.getValuesString());
        }

        return rating;
    }

    public static IslandFlag getIslandFlag(CommandSender sender, String argument) {
        IslandFlag islandFlag = null;

        try {
            islandFlag = IslandFlag.getByName(argument);
        } catch (NullPointerException ignored) {
        }

        if (islandFlag == null) {
            Message.INVALID_SETTINGS.send(sender, argument, IslandFlags.getFlagsNames());
        }

        return islandFlag;
    }

    public static Dimension getDimension(CommandSender sender, String argument) {
        Dimension dimension = null;

        try {
            dimension = Dimension.getByName(argument.toUpperCase(Locale.ENGLISH));
        } catch (Exception ignored) {
        }

        if (dimension == null)
            Message.INVALID_ENVIRONMENT.send(sender, argument);

        return dimension;
    }

    public static Schematic getSchematic(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        Schematic schematic = plugin.getSchematics().getSchematic(argument);

        if (schematic == null || argument.endsWith("_nether") || argument.endsWith("_normal") || argument.endsWith("_the_end")) {
            Message.INVALID_SCHEMATIC.send(sender, argument);
            return null;
        }

        return schematic;
    }

    public static NumberArgument<Integer> getInterval(CommandSender sender, String argument) {
        NumberArgument<Integer> interval = getInt(sender, argument, Message.INVALID_INTERVAL);

        if (interval.isSucceed() && interval.getNumber() < 0) {
            Message.INVALID_INTERVAL.send(sender, argument);
            return new NumberArgument<>(interval.getNumber(), false);
        }

        return interval;
    }

    public static Map<String, String> parseArguments(String[] args) {
        Map<String, String> parsedArgs = new HashMap<>();
        String currentKey = null;
        StringBuilder stringBuilder = new StringBuilder();

        for (String arg : args) {
            if (arg.startsWith("-")) {
                if (currentKey != null && stringBuilder.length() > 0) {
                    parsedArgs.put(currentKey, stringBuilder.substring(1));
                }

                currentKey = arg.substring(1).toLowerCase(Locale.ENGLISH);
                stringBuilder = new StringBuilder();
            } else if (currentKey != null) {
                stringBuilder.append(" ").append(arg);
            }
        }

        if (currentKey != null && stringBuilder.length() > 0) {
            parsedArgs.put(currentKey, stringBuilder.substring(1));
        }

        return parsedArgs;
    }

    public static BorderColor getBorderColor(CommandSender sender, String argument) {
        BorderColor borderColor = null;

        try {
            borderColor = BorderColor.valueOf(argument.toUpperCase(Locale.ENGLISH));
        } catch (Exception ignored) {
        }

        if (borderColor == null)
            Message.INVALID_BORDER_COLOR.send(sender, argument);

        return borderColor;
    }

    private static NumberArgument<Integer> getInt(CommandSender sender, String argument, Message locale) {
        int i = 0;
        boolean status = true;

        try {
            i = Integer.parseInt(argument);
        } catch (IllegalArgumentException ex) {
            locale.send(sender, argument);
            status = false;
        }

        return new NumberArgument<>(i, status);
    }

}
