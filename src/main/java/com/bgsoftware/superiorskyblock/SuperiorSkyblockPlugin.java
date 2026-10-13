package com.bgsoftware.superiorskyblock;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.common.dependencies.DependenciesManager;
import com.bgsoftware.common.nmsloader.INMSLoader;
import com.bgsoftware.common.nmsloader.NMSHandlersFactory;
import com.bgsoftware.common.nmsloader.NMSLoadException;
import com.bgsoftware.common.nmsloader.config.NMSConfiguration;
import com.bgsoftware.common.updater.Updater;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.modules.ModuleLoadTime;
import com.bgsoftware.superiorskyblock.api.platform.IEventsDispatcher;
import com.bgsoftware.superiorskyblock.api.scripts.IScriptEngine;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandsManagerImpl;
import com.bgsoftware.superiorskyblock.commands.admin.AdminCommandsMap;
import com.bgsoftware.superiorskyblock.commands.player.PlayerCommandsMap;
import com.bgsoftware.superiorskyblock.config.SettingsManagerImpl;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.PluginLoadingStage;
import com.bgsoftware.superiorskyblock.core.PluginReloadReason;
import com.bgsoftware.superiorskyblock.core.database.DataManager;
import com.bgsoftware.superiorskyblock.core.engine.EnginesFactory;
import com.bgsoftware.superiorskyblock.core.engine.NashornEngineDownloader;
import com.bgsoftware.superiorskyblock.core.errors.ManagerLoadException;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsDispatcher;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.factory.FactoriesManagerImpl;
import com.bgsoftware.superiorskyblock.core.itemstack.ItemSkulls;
import com.bgsoftware.superiorskyblock.core.key.KeysManagerImpl;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.menu.MenusManagerImpl;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.stackedblocks.StackedBlocksManagerImpl;
import com.bgsoftware.superiorskyblock.core.stackedblocks.container.DefaultStackedBlocksContainer;
import com.bgsoftware.superiorskyblock.core.stats.StatsClient;
import com.bgsoftware.superiorskyblock.core.task.CalcTask;
import com.bgsoftware.superiorskyblock.core.task.ShutdownTask;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.values.BlockValuesManagerImpl;
import com.bgsoftware.superiorskyblock.core.values.container.BlockValuesContainer;
import com.bgsoftware.superiorskyblock.external.ProvidersManagerImpl;
import com.bgsoftware.superiorskyblock.island.GridManagerImpl;
import com.bgsoftware.superiorskyblock.island.cache.IslandCacheKeys;
import com.bgsoftware.superiorskyblock.island.container.DefaultIslandsContainer;
import com.bgsoftware.superiorskyblock.island.flag.IslandFlags;
import com.bgsoftware.superiorskyblock.island.preview.DefaultIslandPreviews;
import com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges;
import com.bgsoftware.superiorskyblock.island.purge.DefaultIslandsPurger;
import com.bgsoftware.superiorskyblock.island.role.RolesManagerImpl;
import com.bgsoftware.superiorskyblock.island.role.container.DefaultRolesContainer;
import com.bgsoftware.superiorskyblock.island.top.SortingComparators;
import com.bgsoftware.superiorskyblock.island.top.SortingTypes;
import com.bgsoftware.superiorskyblock.island.upgrade.UpgradesManagerImpl;
import com.bgsoftware.superiorskyblock.island.upgrade.container.DefaultUpgradesContainer;
import com.bgsoftware.superiorskyblock.island.upgrade.loaders.ItemsUpgradeCostLoader;
import com.bgsoftware.superiorskyblock.island.upgrade.loaders.PlaceholdersUpgradeCostLoader;
import com.bgsoftware.superiorskyblock.island.upgrade.loaders.VaultUpgradeCostLoader;
import com.bgsoftware.superiorskyblock.listener.BukkitListeners;
import com.bgsoftware.superiorskyblock.mission.MissionsManagerImpl;
import com.bgsoftware.superiorskyblock.mission.container.DefaultMissionsContainer;
import com.bgsoftware.superiorskyblock.module.ModulesManagerImpl;
import com.bgsoftware.superiorskyblock.module.container.DefaultModulesContainer;
import com.bgsoftware.superiorskyblock.nms.NMSAlgorithms;
import com.bgsoftware.superiorskyblock.nms.NMSChunks;
import com.bgsoftware.superiorskyblock.nms.NMSDialogs;
import com.bgsoftware.superiorskyblock.nms.NMSDragonFight;
import com.bgsoftware.superiorskyblock.nms.NMSDragonFightChooser;
import com.bgsoftware.superiorskyblock.nms.NMSEntities;
import com.bgsoftware.superiorskyblock.nms.NMSHolograms;
import com.bgsoftware.superiorskyblock.nms.NMSPlayers;
import com.bgsoftware.superiorskyblock.nms.NMSTags;
import com.bgsoftware.superiorskyblock.nms.NMSWorld;
import com.bgsoftware.superiorskyblock.platform.event.GameEventsDispatcher;
import com.bgsoftware.superiorskyblock.player.PlayersManagerImpl;
import com.bgsoftware.superiorskyblock.player.chat.ChatStates;
import com.bgsoftware.superiorskyblock.player.container.DefaultPlayersContainer;
import com.bgsoftware.superiorskyblock.player.inventory.ClearActions;
import com.bgsoftware.superiorskyblock.player.respawn.RespawnActions;
import com.bgsoftware.superiorskyblock.pvc.PvcConfig;
import com.bgsoftware.superiorskyblock.pvc.entitlement.PvcEntitlementService;
import com.bgsoftware.superiorskyblock.pvc.island.PvcIslandService;
import com.bgsoftware.superiorskyblock.pvc.world.PvcBootstrapGenerator;
import com.bgsoftware.superiorskyblock.service.ServicesHandler;
import com.bgsoftware.superiorskyblock.world.WorldGenerator;
import com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider;
import com.bgsoftware.superiorskyblock.world.schematic.SchematicsManagerImpl;
import com.bgsoftware.superiorskyblock.world.schematic.container.DefaultSchematicsContainer;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Optional;
import java.io.File;
import java.io.IOException;

public class SuperiorSkyblockPlugin extends JavaPlugin implements SuperiorSkyblock {

    private static SuperiorSkyblockPlugin plugin;

    private final PvcIslandService pvcIslandService = new PvcIslandService(this);
    private PvcConfig pvcConfig;
    private PvcEntitlementService pvcEntitlements;
    private volatile boolean pvcSettingsReady;
    private boolean pvcEnableStarted;
    private final java.util.concurrent.atomic.AtomicBoolean pvcDisableStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private org.bukkit.scheduler.BukkitTask pvcSnapshotsTask;

    /* Managers */
    private final DataManager dataHandler = new DataManager(this);
    private final FactoriesManagerImpl factoriesHandler = new FactoriesManagerImpl();
    private final GridManagerImpl gridHandler = new GridManagerImpl(this, new DefaultIslandsPurger(), new DefaultIslandPreviews());
    private final StackedBlocksManagerImpl stackedBlocksHandler = new StackedBlocksManagerImpl(this, new DefaultStackedBlocksContainer());
    private final BlockValuesManagerImpl blockValuesHandler = new BlockValuesManagerImpl(this, new BlockValuesContainer(), new BlockValuesContainer());
    private final SchematicsManagerImpl schematicsHandler = new SchematicsManagerImpl(this, new DefaultSchematicsContainer());
    private final PlayersManagerImpl playersHandler = new PlayersManagerImpl(this);
    private final RolesManagerImpl rolesHandler = new RolesManagerImpl(this, new DefaultRolesContainer());
    private final MissionsManagerImpl missionsHandler = new MissionsManagerImpl(this, new DefaultMissionsContainer());
    private final MenusManagerImpl menusHandler = new MenusManagerImpl(this);
    private final KeysManagerImpl keysHandler = new KeysManagerImpl(this);
    private final ProvidersManagerImpl providersHandler = new ProvidersManagerImpl(this);
    private final UpgradesManagerImpl upgradesHandler = new UpgradesManagerImpl(this, new DefaultUpgradesContainer());
    private final CommandsManagerImpl commandsHandler = new CommandsManagerImpl(this, new PlayerCommandsMap(this), new AdminCommandsMap(this));
    private final ModulesManagerImpl modulesHandler = new ModulesManagerImpl(this, new DefaultModulesContainer(this));
    private final ServicesHandler servicesHandler = new ServicesHandler(this);
    private final SettingsManagerImpl settingsHandler = new SettingsManagerImpl(this);

    /* Global handlers */
    private final Updater updater = new Updater(this, "superiorskyblock2");
    private final BukkitListeners bukkitListeners = new BukkitListeners(this);
    private final com.bgsoftware.superiorskyblock.listener.PlayerSnapshotsListener playerSnapshotsListener =
            new com.bgsoftware.superiorskyblock.listener.PlayerSnapshotsListener(this);
    private final PluginEventsDispatcher pluginEventsDispatcher = new PluginEventsDispatcher(this);
    private final GameEventsDispatcher gameEventsDispatcher = new GameEventsDispatcher(this);
    private IScriptEngine scriptEngine = EnginesFactory.createDefaultEngine();
    @Nullable
    private IEventsDispatcher eventsDispatcher = null;

    /* NMS */
    @Nullable
    private NMSAlgorithms nmsAlgorithms;
    private NMSChunks nmsChunks;
    private Optional<NMSDialogs> nmsDialogs;
    private NMSDragonFight nmsDragonFight;
    private NMSEntities nmsEntities;
    private NMSHolograms nmsHolograms;
    private NMSPlayers nmsPlayers;
    private NMSTags nmsTags;
    private NMSWorld nmsWorld;

    private PluginLoadingStage loadingStage = PluginLoadingStage.START;

    public static SuperiorSkyblockPlugin getPlugin() {
        return plugin;
    }

    @Override
    public void onLoad() {
        plugin = this;
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.isFile()) saveResource("config.yml", false);
            pvcConfig = PvcConfig.prepareAndLoad(getDataFolder());
            reloadConfig();
            BukkitExecutor.init(this);
        } catch (IOException error) {
            Log.error(error, "PVC configuration could not be loaded; existing settings were preserved.");
            return;
        }
        pluginEventsDispatcher.registerDefaultListeners();

        DependenciesManager.inject(this);

        bukkitListeners.registerListenerFailureFilter();

        try {
            SuperiorSkyblockAPI.setPluginInstance(this);
        } catch (UnsupportedOperationException error) {
            Log.error("The API instance was already initialized. This can be caused by a reload or another plugin initializing it.");
            return;
        }

        loadingStage = PluginLoadingStage.API_INITIALIZED;

        String serverVersion = Bukkit.getVersion();
        if (serverVersion.toLowerCase(Locale.ENGLISH).contains("arclight")) {
            Log.error("The plugin does not support this server software: " + serverVersion);
            return;
        }

        loadingStage = PluginLoadingStage.SUPPORTED_SERVER_SOFTWARE;

        if (!loadNMSAdapter()) {
            return;
        }

        loadingStage = PluginLoadingStage.NMS_INITIALIZED;

        Runtime.getRuntime().addShutdownHook(new ShutdownTask(this));

        IslandPrivileges.registerPrivileges();
        SortingTypes.registerSortingTypes(this);
        IslandFlags.registerFlags();
        ChatStates.registerStates();
        ClearActions.registerActions();
        RespawnActions.registerActions();
        IslandCacheKeys.registerCacheKeys();

        try {
            SortingComparators.initializeTopIslandMembersSorting();
        } catch (IllegalArgumentException error) {
            Log.error("The TopIslandMembersSorting was already initialized. This can be caused by a reload or another plugin initializing it.");
            return;
        }

        this.servicesHandler.loadDefaultServices(this);

        new Metrics(this, 4119);
        StatsClient client = StatsClient.getInstance();
        client.start();

        loadingStage = PluginLoadingStage.LOADED;
    }

    @Override
    public void onEnable() {
        // The plugin is enabled early so Bukkit can obtain its void generator.
        // World-dependent initialization waits until the server has loaded worlds
        // and enabled permission/providers, including on region-threaded servers.
        if (pvcConfig != null && Bukkit.getWorlds().isEmpty()) {
            org.bukkit.event.Listener bootstrapListener = new org.bukkit.event.Listener() { };
            try {
                @SuppressWarnings("unchecked")
                Class<? extends org.bukkit.event.Event> eventType = (Class<? extends org.bukkit.event.Event>)
                        Class.forName("org.bukkit.event.server.ServerLoadEvent");
                Bukkit.getPluginManager().registerEvent(eventType, bootstrapListener, org.bukkit.event.EventPriority.MONITOR,
                        (listener, event) -> {
                            org.bukkit.event.HandlerList.unregisterAll(listener);
                            enableAfterWorldBootstrap();
                        }, this);
            } catch (ClassNotFoundException legacyServer) {
                BukkitExecutor.global(this::enableAfterWorldBootstrap, 1L);
            }
            return;
        }
        enableAfterWorldBootstrap();
    }

    private void enableAfterWorldBootstrap() {
        if (pvcEnableStarted) return;
        pvcEnableStarted = true;
        try {
            if (pvcConfig == null) {
                ManagerLoadException.handle(new ManagerLoadException(
                        "PVC configuration preflight failed. Correct the logged config error before restarting.",
                        ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN));
                return;
            }
            if (loadingStage != PluginLoadingStage.LOADED) {
                ManagerLoadException.handle(new ManagerLoadException("Failed to load " + getDescription().getName() + ".\n" +
                        "Failed on " + loadingStage.next() + "\n\n" +
                        "Shutting down the server...", ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN));
                return;
            }

            loadingStage = PluginLoadingStage.START_ENABLE;

            BukkitExecutor.init(this);

            loadUpgradeCostLoaders();

            try {
                settingsHandler.loadData();
                pvcSettingsReady = true;
                pvcEntitlements = new PvcEntitlementService(this, pvcConfig);
            } catch (ManagerLoadException ex) {
                if (!ManagerLoadException.handle(ex)) {
                    return;
                }
            }

            loadingStage = PluginLoadingStage.SETTINGS_INITIALIZED;

            modulesHandler.loadData();

            loadingStage = PluginLoadingStage.MODULES_INITIALIZED;

            commandsHandler.loadData();

            loadingStage = PluginLoadingStage.COMMANDS_INITIALIZED;

            modulesHandler.runModuleLifecycle(ModuleLoadTime.PLUGIN_INITIALIZE, false);

            PluginEvent<PluginEventArgs.PluginInitialize> event = PluginEventsFactory.callPluginInitializeEvent();

            this.playersHandler.setPlayersContainer(Optional.ofNullable(event.getArgs().playersContainer).orElse(new DefaultPlayersContainer()));
            this.gridHandler.setIslandsContainer(Optional.ofNullable(event.getArgs().islandsContainer).orElse(new DefaultIslandsContainer(this)));

            modulesHandler.runModuleLifecycle(ModuleLoadTime.BEFORE_WORLD_CREATION, false);

            try {
                com.bgsoftware.superiorskyblock.api.hooks.WorldsProviderCapabilities.require(
                        providersHandler.getWorldsProvider(), BukkitExecutor.isFolia(), false,
                        pvcConfig.getSizeMode() == PvcConfig.SizeMode.PERMISSION_AUTOMATIC);
                providersHandler.getWorldsProvider().prepareWorlds();
            } catch (RuntimeException ex) {
                ManagerLoadException handlerError = new ManagerLoadException(ex, ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN);
                Log.error(handlerError, "An error occurred while preparing worlds:");
                Bukkit.shutdown();
                return;
            }

            loadingStage = PluginLoadingStage.WORLDS_PREPARED;

            modulesHandler.runModuleLifecycle(ModuleLoadTime.NORMAL, false);

            try {
                reloadPlugin(PluginReloadReason.STARTUP);
            } catch (ManagerLoadException error) {
                Log.error(error, "An unexpected error occurred while starting up the plugin:");
                ManagerLoadException.handle(error);
                return;
            }

            loadingStage = PluginLoadingStage.MANAGERS_INITIALIZED;
            // IslandUtils initializes default dimension biomes: load it only after worlds/managers exist.
            com.bgsoftware.superiorskyblock.island.IslandUtils.openCropRefreshes();

            pvcEntitlements.start();

            ChunksProvider.start();
            pvcSnapshotsTask = BukkitExecutor.globalRepeating(playerSnapshotsListener::poll, 1L, 20L);

            loadingStage = PluginLoadingStage.CHUNKS_PROVIDER_INITIALIZED;

            // Check for updates asynchronously
            BukkitExecutor.async(() -> {
                if (updater.isOutdated()) {
                    Log.info("");
                    Log.info("A new version is available (v", updater.getLatestVersion(), ")!");
                    Log.info("Version's description: \"", updater.getVersionDescription(), "\"");
                    Log.info("");
                }
            });

            // Calculate the maximum amount of islands that fit into the world.
            long maxIslands = calculateMaxPossibleIslands();
            if (maxIslands < 1000) {
                Log.warn("It seems like you configured your max-world-size in server.properties to be a small number (", nmsAlgorithms.getMaxWorldSize(), ").");
                Log.warn("This can lead to weird behaviors when new islands are generated beyond this limit.");
                Log.warn("Increase the value to for better experience (Default: 29999984)");
            }

            BukkitExecutor.global(() -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    BukkitExecutor.entity(player, () -> {
                        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                        SuperiorPlayer superiorPlayer = playersHandler.getSuperiorPlayer(player);
                        superiorPlayer.updateLastTimeStatus();

                        Island island = gridHandler.getIslandAt(player.getLocation(wrapper.getHandle()));
                        Island playerIsland = superiorPlayer.getIsland();

                        if (superiorPlayer.hasIslandFlyEnabled()) {
                            if (island != null && island.hasPermission(superiorPlayer, IslandPrivileges.FLY)) {
                                player.setAllowFlight(true);
                                player.setFlying(true);
                            } else {
                                superiorPlayer.toggleIslandFly();
                            }
                        }

                        if (playerIsland != null)
                            playerIsland.setCurrentlyActive(true);

                        if (island != null)
                            island.setPlayerInside(superiorPlayer, true);
                        }
                    });
                }
            });

            PluginEventsFactory.callPluginInitializedEvent();

            loadingStage = PluginLoadingStage.ENABLED;

            PluginEventsFactory.callSettingsUpdateEvent();
        } catch (Throwable error) {
            Log.error(error, "An unexpected error occurred while enabling the plugin:");
            Bukkit.shutdown();
        }
    }

    @Override
    public void onDisable() {
        if (!pvcDisableStarted.compareAndSet(false, true)) return;
        try {
            playerSnapshotsListener.close();
            com.bgsoftware.superiorskyblock.island.IslandUtils.closeCropRefreshes();
            com.bgsoftware.superiorskyblock.island.bank.SIslandBank.shutdownPending();
            if (pvcSnapshotsTask != null) pvcSnapshotsTask.cancel();
            if (loadingStage.isAtLeast(PluginLoadingStage.MANAGERS_INITIALIZED))
                for (Island island : gridHandler.getIslandsToPurge())
                    pvcIslandService.deferDeletion(island);
            if (pvcEntitlements != null) pvcEntitlements.close();
            pvcIslandService.close();
            if (loadingStage.isAtLeast(PluginLoadingStage.START_ENABLE))
                BukkitExecutor.prepareShutdown();

            if (loadingStage.isAtLeast(PluginLoadingStage.CHUNKS_PROVIDER_INITIALIZED)) {
                ChunksProvider.stop();
                com.bgsoftware.superiorskyblock.island.chunk.DirtyChunksContainer.flushPending();
            }

            if (loadingStage.isAtLeast(PluginLoadingStage.MANAGERS_INITIALIZED)) {
                dataHandler.saveDatabase(false);
                gridHandler.disablePlugin();

                playersHandler.savePlayers();
                gridHandler.saveIslands();
            }

            if (loadingStage.isAtLeast(PluginLoadingStage.MODULES_INITIALIZED)) {
                modulesHandler.getModules().forEach(modulesHandler::unregisterModule);
            }

            // Shutdown task is running from another thread, causing closing of inventories to cause errors.
            // This check should prevent it.
            if (!BukkitExecutor.isFolia() && Bukkit.isPrimaryThread()) {
                Bukkit.getOnlinePlayers().forEach(player -> {
                    SuperiorPlayer superiorPlayer = playersHandler.getSuperiorPlayer(player);
                    player.closeInventory();
                    superiorPlayer.updateWorldBorder(null);
                    if (superiorPlayer.hasIslandFlyEnabled()) {
                        player.setAllowFlight(false);
                        player.setFlying(false);
                    }
                });
            }
        } catch (Exception error) {
            Log.error(error, "An unexpected error occurred while disabling the plugin:");
        } finally {
            Log.info("Shutting down stats client...");
            StatsClient.getIfExists().ifPresent(StatsClient::shutdown);

            if (loadingStage.isAtLeast(PluginLoadingStage.MANAGERS_INITIALIZED)) {
                Log.info("Shutting down calculation task...");
                CalcTask.cancelTask();
            }

            if (loadingStage.isAtLeast(PluginLoadingStage.NMS_INITIALIZED))
                nmsChunks.shutdown();

            if (loadingStage.isAtLeast(PluginLoadingStage.START_ENABLE)) {
                Log.info("Shutting down executor");
                BukkitExecutor.close(plugin);
            }

            if (loadingStage.isAtLeast(PluginLoadingStage.MANAGERS_INITIALIZED)) {
                Log.info("Closing database. This may hang the server. Do not shut it down, or data may get lost.");
                dataHandler.closeConnection();
            }
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (!pvcSettingsReady) return new PvcBootstrapGenerator();
        return WorldGenerator.getWorldGenerator(settingsHandler.getWorlds().getDefaultWorldDimension());
    }

    public PvcConfig getPvcConfig() { return pvcConfig; }

    public PvcEntitlementService getPvcEntitlements() { return pvcEntitlements; }

    public PvcIslandService getPvcIslandService() { return pvcIslandService; }

    public boolean isPvcDisableStarted() { return pvcDisableStarted.get(); }

    public Updater getUpdater() {
        return updater;
    }

    public ClassLoader getPluginClassLoader() {
        return super.getClassLoader();
    }

    private boolean loadNMSAdapter() {
        try {
            INMSLoader nmsLoader = NMSHandlersFactory.createNMSLoader(this, NMSConfiguration.forPlugin(this));

            this.nmsAlgorithms = nmsLoader.loadNMSHandler(NMSAlgorithms.class);
            this.nmsChunks = nmsLoader.loadNMSHandler(NMSChunks.class);
            this.nmsEntities = nmsLoader.loadNMSHandler(NMSEntities.class);
            this.nmsHolograms = nmsLoader.loadNMSHandler(NMSHolograms.class);
            this.nmsPlayers = nmsLoader.loadNMSHandler(NMSPlayers.class);
            this.nmsTags = nmsLoader.loadNMSHandler(NMSTags.class);
            this.nmsWorld = nmsLoader.loadNMSHandler(NMSWorld.class);
            this.nmsDragonFight = new NMSDragonFightChooser(plugin, () -> nmsLoader.loadNMSHandler(NMSDragonFight.class));

            try {
                this.nmsDialogs = Optional.of(nmsLoader.loadNMSHandler(NMSDialogs.class));
            } catch (NMSLoadException e) {
                // Failed to load NMSDialogs
                this.nmsDialogs = Optional.empty();
            }

            return true;
        } catch (NMSLoadException error) {
            new ManagerLoadException(error, "The plugin doesn't support your minecraft version.\n" + "Please try a different version.",
                    ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN).printStackTrace();

            return false;
        }
    }

    private boolean checkScriptEngine() {
        return testScriptEngine() || (NashornEngineDownloader.downloadEngine(this) && testScriptEngine());
    }

    private boolean testScriptEngine() {
        try {
            scriptEngine.eval("1+1");
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public void reloadPlugin(PluginReloadReason reloadReason) throws ManagerLoadException {
        if (reloadReason == PluginReloadReason.COMMAND) {
            try {
                PvcConfig refreshed = PvcConfig.prepareAndLoad(getDataFolder());
                if (pvcEntitlements != null) pvcEntitlements.validateReload(refreshed);
                if (pvcConfig.getSizeMode() == PvcConfig.SizeMode.PERMISSION_AUTOMATIC ||
                        refreshed.getSizeMode() == PvcConfig.SizeMode.PERMISSION_AUTOMATIC)
                    throw new com.bgsoftware.superiorskyblock.pvc.PvcReloadRejectedException(
                            "Automatic island sizing requires a normal restart for configuration reload. Running settings remain unchanged.");
                reloadConfig();
                pvcConfig = refreshed;
            } catch (IOException | IllegalArgumentException error) {
                throw new com.bgsoftware.superiorskyblock.pvc.PvcReloadRejectedException(
                        "PVC reload rejected before changing running settings: " + error.getMessage());
            }
        }
        if (reloadReason == PluginReloadReason.COMMAND) {
            bukkitListeners.unregisterListeners();
        }

        providersHandler.loadData();

        ItemSkulls.readTextures(this);

        if (reloadReason == PluginReloadReason.COMMAND) {
            // The reload was requested by a command. We want to reload the commands, settings and call the
            // module lifecycles that are not called regularly. If the reload was due to a startup, then
            // all of that is called already in the onEnable callback of the plugin.
            settingsHandler.loadData();
            pvcSettingsReady = true;

            commandsHandler.loadData();

            modulesHandler.runModuleLifecycle(ModuleLoadTime.PLUGIN_INITIALIZE, true);
            modulesHandler.runModuleLifecycle(ModuleLoadTime.BEFORE_WORLD_CREATION, true);
            modulesHandler.runModuleLifecycle(ModuleLoadTime.NORMAL, true);
        }

        if (!checkScriptEngine()) {
            throw new ManagerLoadException("It seems like the script engine of the plugin is corrupted.\n" + "This may occur by one of the following reasons:\n" + "1. You have a module/plugin that sets a custom script that doesn't work well.\n" + "2. You're using Java 16 without installing an external module engine.\n" + "If that's the case, check out the following link:\n" + "https://github.com/BG-Software-LLC/SuperiorSkyblock2-NashornEngine", ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN);
        }

        blockValuesHandler.loadData();
        upgradesHandler.loadData();
        rolesHandler.loadData();
        if (reloadReason == PluginReloadReason.COMMAND && pvcEntitlements != null)
            pvcEntitlements.reload(pvcConfig);

        Message.reload();

        if (reloadReason == PluginReloadReason.STARTUP) {
            playersHandler.loadData();
            gridHandler.loadData();
            schematicsHandler.loadData();
        } else {
            BukkitExecutor.sync(gridHandler::updateSpawn, 1L);
            gridHandler.syncUpgrades();
            schematicsHandler.loadSchematics();
        }

        menusHandler.loadData();
        missionsHandler.loadData();

        if (reloadReason == PluginReloadReason.STARTUP) {
            dataHandler.loadData();
            stackedBlocksHandler.loadData();
        }

        BukkitExecutor.sync(schematicsHandler::cacheSchematics);

        modulesHandler.runModuleLifecycle(ModuleLoadTime.AFTER_MODULE_DATA_LOAD, reloadReason == PluginReloadReason.COMMAND);

        BukkitExecutor.global(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                BukkitExecutor.entity(player, () -> {
                    try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                    SuperiorPlayer superiorPlayer = playersHandler.getSuperiorPlayer(player);
                    if (superiorPlayer instanceof com.bgsoftware.superiorskyblock.player.SSuperiorPlayer) {
                        com.bgsoftware.superiorskyblock.player.SSuperiorPlayer concrete =
                                (com.bgsoftware.superiorskyblock.player.SSuperiorPlayer) superiorPlayer;
                        concrete.refreshRuntimeState(player);
                        concrete.refreshLocationSnapshot(player);
                        concrete.refreshOpenedView(player);
                    }
                    Island island = gridHandler.getIslandAt(player.getLocation(wrapper.getHandle()));
                    superiorPlayer.updateWorldBorder(island);
                    if (island != null) island.applyEffects(superiorPlayer);
                    }
                });
            }
        });

        CalcTask.startTask();

        modulesHandler.runModuleLifecycle(ModuleLoadTime.AFTER_HANDLERS_LOADING, reloadReason == PluginReloadReason.COMMAND);

        if (reloadReason == PluginReloadReason.STARTUP) {
            modulesHandler.loadModulesData(this);
        }

        try {
            bukkitListeners.registerListeners();
            org.bukkit.event.HandlerList.unregisterAll(playerSnapshotsListener);
            Bukkit.getPluginManager().registerEvents(playerSnapshotsListener, this);
        } catch (RuntimeException ex) {
            throw new ManagerLoadException("Cannot load plugin due to a missing event: " + ex.getMessage() + " - contact @Ome_R!", ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN);
        }
    }

    @Override
    public GridManagerImpl getGrid() {
        return gridHandler;
    }

    private Runnable captureIslandScope(Runnable action) {
        if (!com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isActive()) return action;
        Island target = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
        boolean explicit = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget();
        return () -> {
            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                    com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(target, explicit)) {
                action.run();
            }
        };
    }

    @Override
    public void executePlayer(SuperiorPlayer player, Runnable action, long delay) {
        Player online = player.asPlayer();
        if (online != null) BukkitExecutor.entity(online, captureIslandScope(action), Math.max(0L, delay));
    }

    @Override
    public void executeRegion(Location location, Runnable action, long delay) {
        if (location.getWorld() == null) throw new IllegalArgumentException("Region location requires a world");
        BukkitExecutor.region(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4,
                captureIslandScope(action), Math.max(0L, delay));
    }

    @Override
    public void executeGlobal(Runnable action, long delay) {
        BukkitExecutor.global(captureIslandScope(action), Math.max(0L, delay));
    }

    @Override
    public void executeAsync(Runnable action, long delay) {
        BukkitExecutor.async(captureIslandScope(action), Math.max(0L, delay));
    }

    @Override
    public StackedBlocksManagerImpl getStackedBlocks() {
        return stackedBlocksHandler;
    }

    @Override
    public BlockValuesManagerImpl getBlockValues() {
        return blockValuesHandler;
    }

    @Override
    public SchematicsManagerImpl getSchematics() {
        return schematicsHandler;
    }

    @Override
    public PlayersManagerImpl getPlayers() {
        return playersHandler;
    }

    @Override
    public RolesManagerImpl getRoles() {
        return rolesHandler;
    }

    @Override
    public MissionsManagerImpl getMissions() {
        return missionsHandler;
    }

    @Override
    public MenusManagerImpl getMenus() {
        return menusHandler;
    }

    @Override
    public KeysManagerImpl getKeys() {
        return keysHandler;
    }

    @Override
    public ProvidersManagerImpl getProviders() {
        return providersHandler;
    }

    @Override
    public UpgradesManagerImpl getUpgrades() {
        return upgradesHandler;
    }

    @Override
    public CommandsManagerImpl getCommands() {
        return commandsHandler;
    }

    @Override
    public SettingsManagerImpl getSettings() {
        return settingsHandler;
    }

    @Override
    public FactoriesManagerImpl getFactory() {
        return factoriesHandler;
    }

    @Override
    public ModulesManagerImpl getModules() {
        return modulesHandler;
    }

    @Override
    public IScriptEngine getScriptEngine() {
        return scriptEngine;
    }

    @Override
    public void setScriptEngine(@Nullable IScriptEngine scriptEngine) {
        this.scriptEngine = scriptEngine == null ? EnginesFactory.createDefaultEngine() : scriptEngine;
    }

    @Nullable
    @Override
    public IEventsDispatcher getEventsDispatcher() {
        return this.eventsDispatcher;
    }

    @Override
    public void setEventsDispatcher(@Nullable IEventsDispatcher eventsDispatcher) {
        this.eventsDispatcher = eventsDispatcher;
    }

    public PluginEventsDispatcher getPluginEventsDispatcher() {
        return pluginEventsDispatcher;
    }

    public GameEventsDispatcher getGameEventsDispatcher() {
        return gameEventsDispatcher;
    }

    public ServicesHandler getServices() {
        return servicesHandler;
    }

    public NMSAlgorithms getNMSAlgorithms() {
        return nmsAlgorithms;
    }

    public NMSChunks getNMSChunks() {
        return nmsChunks;
    }

    public Optional<NMSDialogs> getNMSDialogs() {
        return nmsDialogs;
    }

    public NMSDragonFight getNMSDragonFight() {
        return nmsDragonFight;
    }

    public NMSEntities getNMSEntities() {
        return nmsEntities;
    }

    public NMSHolograms getNMSHolograms() {
        return nmsHolograms;
    }

    public NMSPlayers getNMSPlayers() {
        return nmsPlayers;
    }

    public NMSTags getNMSTags() {
        return nmsTags;
    }

    public NMSWorld getNMSWorld() {
        return nmsWorld;
    }

    public String getFileName() {
        return getFile().getName();
    }

    private void loadUpgradeCostLoaders() {
        upgradesHandler.registerUpgradeCostLoader("money", new VaultUpgradeCostLoader());
        upgradesHandler.registerUpgradeCostLoader("placeholders", new PlaceholdersUpgradeCostLoader());
        upgradesHandler.registerUpgradeCostLoader("items", new ItemsUpgradeCostLoader());
    }

    private long calculateMaxPossibleIslands() {
        int islandDistance = settingsHandler.getMaxIslandSize() * 3;
        long worldDistance = nmsAlgorithms.getMaxWorldSize() * 2L;
        long islandsPerSide = worldDistance / islandDistance;
        return islandsPerSide * islandsPerSide;
    }

}
