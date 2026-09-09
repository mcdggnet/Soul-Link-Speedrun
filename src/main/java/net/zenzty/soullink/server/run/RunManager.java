package net.zenzty.soullink.server.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.mixin.server.EnderDragonFightAccessor;
import net.zenzty.soullink.mixin.server.RaidAccessor;
import net.zenzty.soullink.mixin.server.RaidManagerAccessor;
import net.zenzty.soullink.server.event.EventRegistry;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import net.zenzty.soullink.server.manhunt.CompassTrackingHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsPersistence;

public class RunManager {

    private static volatile RunManager instance;

    private final MinecraftServer server;
    private final WorldService worldService;
    private final TimerService timerService;
    private final SpawnFinder spawnFinder;
    private final PlayerTeleportService teleportService;

    // Pool Manager
    private final WorldPoolManager poolManager;

    // Server Mode: the persistent world's bookkeeping
    private final ServerWorldStore serverWorldStore;
    private ServerWorldStore.ServerWorldState serverWorld;

    private volatile RunState gameState = RunState.IDLE;
    private volatile boolean endInitialized = false;
    // World Reset off: a group death is being processed (drop, countdown, respawn).
    private volatile boolean groupDeathInProgress = false;
    // Server Mode world reset: the new world opens no earlier than this tick, so a death can sink in.
    private static final int WORLD_RESET_DELAY_TICKS = 20 * 20;
    private long worldResetNotBefore = 0;

    /**
     * The chat prefix: the DGG emote name "ALARMA", unstyled so the DGG Chat client mod turns it
     * into the emote glyph. A vanilla client just sees the word.
     */
    public static Component getPrefix() {
        return Component.literal("ALARMA ");
    }

    public static Component formatMessage(String message) {
        return Component.empty()
                .append(getPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

    public static Component formatMessageWithPlayer(String beforePlayer, String playerName, String afterPlayer) {
        return Component.empty()
                .append(getPrefix())
                .append(Component.literal(beforePlayer).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(playerName).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(afterPlayer).withStyle(ChatFormatting.GRAY));
    }

    public static Component formatClickable(String text, String command, String hoverText) {
        return Component.literal(text)
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal(hoverText).withStyle(ChatFormatting.GRAY))));
    }

    private RunManager(MinecraftServer server) {
        this.server = server;
        this.worldService = new WorldService(server);
        this.timerService = new TimerService();
        this.spawnFinder = new SpawnFinder();
        this.teleportService = new PlayerTeleportService(server);
        this.poolManager = new WorldPoolManager(this.worldService);
        this.serverWorldStore = new ServerWorldStore(server);
    }

    public static synchronized void init(MinecraftServer server) {
        if (instance != null) {
            SoulLink.LOGGER.warn("RunManager already initialized!");
            return;
        }
        instance = new RunManager(server);
    }

    public static RunManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("RunManager not initialized");
        }
        return instance;
    }

    public static synchronized void cleanup() {
        RunManager currentInstance = instance;
        if (currentInstance != null) {
            ManhuntManager.getInstance().cleanupTeams(currentInstance.server);
            CompassTrackingHandler.reset();
            currentInstance.poolManager.cleanup();
            currentInstance.worldService.deleteOldWorlds();
            if (Settings.getInstance().isServerMode()) {
                // The server world is persistent: leave it (and the players in it) exactly as is.
                currentInstance.worldService.detachCurrentWorlds();
            } else {
                currentInstance.deleteWorlds(true);
            }
            instance = null;
        }
    }

    public static ServerLevel getPlayerWorld(ServerPlayer player) {
        return player.level();
    }

    // ==================== RUN LIFECYCLE ====================

    public void startRun() {
        if (Settings.getInstance().isServerMode()) {
            SoulLink.LOGGER.warn("Attempted to start a run while Server Mode is on");
            return;
        }
        if (gameState == RunState.RUNNING || gameState == RunState.GENERATING_WORLD) {
            SoulLink.LOGGER.warn("Attempted to start run while already running or generating!");
            return;
        }

        SoulLink.LOGGER.info("Starting new run...");
        EventRegistry.clearDelayedTasks();

        Settings.getInstance().applyPendingSettings();
        SettingsPersistence.save(server);

        if (!Settings.getInstance().isManhuntMode()) {
            ManhuntManager.getInstance().resetRoles();
        }

        // World Reset off and a world already exists (after a victory, /stoprun or /reset):
        // start the new attempt in that world instead of swapping in a fresh one.
        boolean keepWorld = !Settings.getInstance().isWorldReset()
                && worldService.getOverworld() != null
                && spawnFinder.hasFoundSpawn();

        clearEnderDragonBossbar();
        clearRaidBossbars();
        groupDeathInProgress = false;

        PooledRun nextRun = null;
        if (!keepWorld) {
            worldService.saveCurrentWorldsAsOld();
            // Get world from storage
            nextRun = poolManager.claimNextRun();
        }

        SharedStatsHandler.reset();
        if (Settings.getInstance().isSyncedInventory()) {
            net.zenzty.soullink.server.inventory.SharedInventoryHandler.reset();
        }
        if (!keepWorld) {
            endInitialized = false;
        }
        timerService.reset();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);
        }

        if (keepWorld) {
            SoulLink.LOGGER.info("World Reset is off: restarting the run in the current world");
            transitionToRunning();
        } else if (nextRun != null) {
            // STORAGE FULL -> INSTANT START
            worldService.adoptPooledRun(nextRun);
            spawnFinder.injectSpawnPos(nextRun.spawnPos());

            SoulLink.LOGGER.info("Storage full! Start");
            transitionToRunning();
        } else {
            // STORAGE EMPTY -> (WAIT FOR POOL MANAGER)
            server.getPlayerList().broadcastSystemMessage(formatMessage("Generating world..."), true);
            gameState = RunState.GENERATING_WORLD;
            SoulLink.LOGGER.info("Pool empty! Waiting for world generation");
        }
    }

    public void tick() {
        boolean serverMode = Settings.getInstance().isServerMode();
        if (!serverMode) {
            poolManager.tick(server);
        }

        if (gameState == RunState.GENERATING_WORLD) {
            if (serverMode) {
                // The server world is generated in place (not pooled): wait for its spawn search,
                // and after a death also for the pause, so everyone can see what happened.
                boolean ready = spawnFinder.isSearchComplete() && serverWorld != null;
                long remainingTicks = worldResetNotBefore - server.getTickCount();
                if (ready && remainingTicks <= 0) {
                    serverWorld = serverWorld.withSpawn(spawnFinder.getSpawnPos());
                    serverWorldStore.save(serverWorld);
                    transitionToRunning();
                    return;
                }
                if (ready && server.getTickCount() % 10 == 0) {
                    long seconds = (remainingTicks + 19) / 20;
                    Component countdown = Component.empty()
                            .append(Component.literal("New world in ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(seconds + "s").withStyle(ChatFormatting.WHITE));
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        player.sendOverlayMessage(countdown);
                    }
                    return;
                }
            } else {
                PooledRun nextRun = poolManager.claimNextRun();
                if (nextRun != null) {
                    worldService.adoptPooledRun(nextRun);
                    spawnFinder.injectSpawnPos(nextRun.spawnPos());
                    transitionToRunning();
                    return;
                }
            }
            if (server.getTickCount() % 10 == 0) {
                Component statusText = Component.empty()
                        .append(Component.literal("⟳ ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("Generating new world...").withStyle(ChatFormatting.GRAY));
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendOverlayMessage(statusText);
                }
            }
            return;
        }

        if (gameState != RunState.RUNNING) {
            // No run: keep a hint where the timer would be, so a fresh join knows what to do.
            if (server.getTickCount() % 10 == 0 && !serverMode) {
                Component idleText = Component.empty()
                        .append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("/start").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" to begin Soul Link").withStyle(ChatFormatting.GRAY));
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendOverlayMessage(idleText);
                }
            }
            return;
        }

        ServerLevel tempOverworld = worldService.getOverworld();
        if (tempOverworld != null) {
            ServerClockManager clockManager = tempOverworld.getServer().clockManager();
            Holder<WorldClock> clock = tempOverworld
                    .dimensionTypeRegistration()
                    .value()
                    .defaultClock()
                    .orElseThrow();
            clockManager.addTicks(clock, 1);
        }

        // No timer in Server Mode.
        if (!serverMode) {
            timerService.tick(server, this::isInRun, this::shouldSkipTimerActionBarFor);
        }
    }

    private boolean shouldSkipTimerActionBarFor(ServerPlayer p) {
        if (!Settings.getInstance().isManhuntMode()) return false;
        if (!ManhuntManager.getInstance().isHunter(p)) return false;
        return CompassTrackingHandler.shouldSuppressTimerActionBar(p.getUUID(), server.getTickCount());
    }

    private void transitionToRunning() {
        ServerLevel overworld = worldService.getOverworld();
        BlockPos spawnPos = spawnFinder.getSpawnPos();

        if (overworld == null) return;
        if (spawnPos == null) spawnPos = new BlockPos(0, 64, 0);

        worldService.resetWeatherForNewRun(overworld);
        teleportService.forceloadSpawnChunks(overworld, spawnPos);

        gameState = RunState.RUNNING;

        boolean manhunt = Settings.getInstance().isManhuntMode();
        ManhuntManager manhuntManager = ManhuntManager.getInstance();

        if (manhunt) {
            for (ServerLevel world : server.getAllLevels()) {
                try {
                    server.getCommands()
                            .getDispatcher()
                            .execute(
                                    "execute in " + world.dimension().identifier() + " run gamerule locator_bar false",
                                    server.createCommandSourceStack().withSuppressedOutput());
                } catch (Exception e) {
                    SoulLink.LOGGER.warn(
                            "Could not disable locator_bar in {}: {}",
                            world.dimension().identifier(),
                            e.getMessage());
                }
            }
            manhuntManager.createTeams(server);
            manhuntManager.assignPlayersToTeams(server);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean syncToShared = !manhunt || manhuntManager.isSpeedrunner(player);
            teleportService.teleportToSpawn(player, overworld, spawnPos, timerService, syncToShared);
        }

        worldService.deleteOldWorlds();

        if (manhunt) {
            CompassTrackingHandler.reset();
            for (UUID hunterId : manhuntManager.getHunters()) {
                ServerPlayer hunter = server.getPlayerList().getPlayer(hunterId);
                if (hunter != null) CompassTrackingHandler.giveTrackingCompass(hunter);
            }
            applyHeadStartEffects(manhuntManager);
        }

        server.getPlayerList().broadcastSystemMessage(formatMessage("World ready! Good luck!"), false);
        SoulLink.LOGGER.info("World generation complete, run started");
    }

    private static final int HEAD_START_SECONDS = 30;

    private void applyHeadStartEffects(ManhuntManager manhuntManager) {
        int durationTicks = HEAD_START_SECONDS * 20;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (manhuntManager.isHunter(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, durationTicks, 0, false, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, durationTicks, 255, false, false, true));
            } else if (manhuntManager.isSpeedrunner(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, durationTicks, 0, false, false, true));
            }
        }

        for (int i = HEAD_START_SECONDS; i >= 1; i--) {
            final int secondsRemaining = i;
            int delayTicks = (HEAD_START_SECONDS - i) * 20;

            EventRegistry.scheduleDelayed(delayTicks, () -> {
                if (gameState != RunState.RUNNING) return;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (manhuntManager.isHunter(player)) {
                        player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 0));
                        ChatFormatting color = secondsRemaining <= 5 ? ChatFormatting.RED : ChatFormatting.GOLD;
                        player.connection.send(
                                new ClientboundSetTitleTextPacket(Component.literal(String.valueOf(secondsRemaining))
                                        .withStyle(color, ChatFormatting.BOLD)));
                        player.connection.send(new ClientboundSetSubtitleTextPacket(
                                Component.literal("Catch the Runners!").withStyle(ChatFormatting.GRAY)));
                    }
                }
            });
        }

        EventRegistry.scheduleDelayed(HEAD_START_SECONDS * 20, () -> {
            if (gameState != RunState.RUNNING) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (manhuntManager.isSpeedrunner(player)) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("HUNTERS RELEASED!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                } else if (manhuntManager.isHunter(player)) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("GO!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                }
            }
        });
    }

    public void deleteWorlds(boolean teleportPlayers) {
        if (teleportPlayers) {
            List<ServerPlayer> allPlayers =
                    new ArrayList<>(server.getPlayerList().getPlayers());
            for (ServerPlayer player : allPlayers) {
                ServerLevel playerWorld = getPlayerWorld(player);
                if (playerWorld != null && isTemporaryWorld(playerWorld.dimension())) {
                    teleportService.teleportToVanillaSpawn(player);
                }
            }
        }
        worldService.deleteCurrentWorlds();
    }

    public void teleportPlayerToRun(ServerPlayer player) {
        if (gameState == RunState.GENERATING_WORLD) {
            player.setGameMode(GameType.SPECTATOR);
            player.getInventory().clearContent();
            player.removeAllEffects();
            player.sendSystemMessage(formatMessage("Finding spawn point, please wait..."));
            return;
        }

        if (gameState == RunState.RUNNING && spawnFinder.hasFoundSpawn()) {
            ServerLevel overworld = worldService.getOverworld();
            if (overworld != null) {
                if (Settings.getInstance().isManhuntMode()) {
                    player.setGameMode(GameType.SPECTATOR);
                    player.getInventory().clearContent();
                    player.removeAllEffects();
                    BlockPos spawnPos = spawnFinder.getSpawnPos();
                    if (spawnPos != null) {
                        player.teleportTo(
                                overworld,
                                spawnPos.getX() + 0.5,
                                spawnPos.getY() + 10,
                                spawnPos.getZ() + 0.5,
                                Set.of(),
                                0,
                                0,
                                true);
                    }
                    player.sendSystemMessage(formatMessage("A run is in progress. You are spectating until it ends."));
                } else {
                    teleportService.teleportToSpawn(player, overworld, spawnFinder.getSpawnPos(), timerService, true);
                    if (Settings.getInstance().isSyncedInventory()) {
                        net.zenzty.soullink.server.inventory.SharedInventoryHandler.syncPlayerToShared(player);
                    }
                    player.sendSystemMessage(
                            formatMessageWithPlayer("", player.getName().getString(), " joined. Stats synced."));
                }
            }
        }
    }

    /**
     * A Soul Link participant reached zero health. With World Reset on this is the end of the run;
     * with it off the whole group dies together and respawns (see EventRegistry.handleGroupDeath).
     * Callers that want the death message shown in the reset case broadcast it themselves, as they
     * did before; the group-death path broadcasts its own.
     */
    public void handleRunnerDeath(ServerPlayer victim, DamageSource source) {
        if (!isRunActive()) return;
        if (!Settings.getInstance().isWorldReset()) {
            requestGroupDeath(victim, source);
            return;
        }
        if (Settings.getInstance().isServerMode()) {
            resetServerWorld(victim, source);
        } else {
            triggerGameOver();
        }
    }

    /**
     * Queues a group death for the next tick. Deferred because deaths surface from inside the damage
     * pipeline (and sometimes inside the shared-health sync loop), where dropping items and changing
     * game modes is not safe. Everyone at zero health is propped up until then so nobody is removed as
     * a corpse in the meantime. Only one group death runs at a time; further deaths in the same tick
     * fold into it.
     */
    private synchronized void requestGroupDeath(ServerPlayer victim, DamageSource source) {
        if (!isRunActive() || groupDeathInProgress) return;
        groupDeathInProgress = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getHealth() <= 0.0f) {
                player.setHealth(1.0f);
            }
        }
        SoulLink.LOGGER.info("Group death queued (victim {})", victim.getName().getString());
        EventRegistry.scheduleDelayed(1, () -> EventRegistry.handleGroupDeath(victim, source, this));
    }

    public boolean isGroupDeathInProgress() {
        return groupDeathInProgress;
    }

    public void finishGroupDeath() {
        groupDeathInProgress = false;
    }

    // ==================== SERVER MODE ====================

    /**
     * Opens the persistent server world (reopening the stored one, or generating the first) and
     * puts everyone in it. Called at server start when Server Mode is on, and when it is turned on.
     */
    public synchronized void openServerWorld() {
        if (!Settings.getInstance().isServerMode()) return;
        if (gameState == RunState.RUNNING || gameState == RunState.GENERATING_WORLD) return;

        EventRegistry.clearDelayedTasks();
        groupDeathInProgress = false;
        SharedStatsHandler.reset();
        SharedInventoryHandler.reset();
        timerService.reset();

        ServerWorldStore.ServerWorldState stored = serverWorldStore.load();
        if (stored == null) {
            SoulLink.LOGGER.info("Server Mode: no server world yet, generating one");
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.setGameMode(GameType.SPECTATOR);
            }
            generateServerWorld();
            return;
        }

        SoulLink.LOGGER.info(
                "Server Mode: reopening server world generation {} (seed {})", stored.generation(), stored.seed());
        serverWorld = stored;
        worldService.adoptPooledRun(worldService.buildServerWorlds(stored.generation(), stored.seed()));
        // The runtime End keeps no fight data across restarts; it is rebuilt on the next visit.
        endInitialized = false;

        if (stored.spawn() == null) {
            ServerLevel overworld = worldService.getOverworld();
            if (overworld == null) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.setGameMode(GameType.SPECTATOR);
            }
            spawnFinder.startSearch(overworld);
            gameState = RunState.GENERATING_WORLD;
            return;
        }

        spawnFinder.injectSpawnPos(stored.spawn());
        ServerLevel overworld = worldService.getOverworld();
        if (overworld != null) {
            teleportService.forceloadSpawnChunks(overworld, stored.spawn());
        }
        gameState = RunState.RUNNING;
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!isInRun(player)) {
                teleportPlayerToRun(player);
            }
        }
    }

    /** Builds the next generation of the server world and starts its spawn search. */
    private void generateServerWorld() {
        int generation = serverWorld != null ? serverWorld.generation() + 1 : 1;
        long seed = new java.util.Random().nextLong();
        serverWorld = new ServerWorldStore.ServerWorldState(generation, seed, null);
        serverWorldStore.save(serverWorld);

        worldService.adoptPooledRun(worldService.buildServerWorlds(generation, seed));
        endInitialized = false;
        ServerLevel overworld = worldService.getOverworld();
        if (overworld != null) {
            spawnFinder.startSearch(overworld);
        }
        gameState = RunState.GENERATING_WORLD;
        server.getPlayerList().broadcastSystemMessage(formatMessage("Generating world..."), true);
        SoulLink.LOGGER.info("Server Mode: generating server world generation {} (seed {})", generation, seed);
    }

    /**
     * Server Mode with World Reset on: a death ends the world. Everyone is told, parked as a
     * spectator, and a fresh world is generated; the old one is deleted once the new one is ready.
     * Also what /stoprun does in Server Mode (victim and source null).
     */
    public synchronized void resetServerWorld(ServerPlayer victim, DamageSource source) {
        if (!Settings.getInstance().isServerMode() || gameState != RunState.RUNNING) return;

        SoulLink.LOGGER.info("Server Mode: world reset ({})", victim != null ? "death" : "command");
        EventRegistry.clearDelayedTasks();
        groupDeathInProgress = false;

        if (victim != null && source != null) {
            server.getPlayerList()
                    .broadcastSystemMessage(
                            Component.empty()
                                    .append(getPrefix())
                                    .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                                    .append(source.getLocalizedDeathMessage(victim)
                                            .copy()
                                            .withStyle(ChatFormatting.RED)),
                            false);
        }
        server.getPlayerList()
                .broadcastSystemMessage(
                        formatMessage(
                                victim != null
                                        ? "Everyone died. The world is being reset."
                                        : "The world is being reset."),
                        false);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isInRun(player)) continue;
            player.setGameMode(GameType.SPECTATOR);
            player.getInventory().clearContent();
            player.setHealth(player.getMaxHealth());
            player.clearFire();
            ServerLevel world = getPlayerWorld(player);
            if (world != null) {
                world.playSound(
                        null,
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        SoundEvents.WITHER_DEATH,
                        SoundSource.PLAYERS,
                        0.5f,
                        0.8f);
            }
            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal(victim != null ? "EVERYONE DIED" : "WORLD RESET")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("A new world is on its way").withStyle(ChatFormatting.GRAY)));
        }

        clearEnderDragonBossbar();
        clearRaidBossbars();
        SharedStatsHandler.reset();
        SharedInventoryHandler.reset();
        worldService.saveCurrentWorldsAsOld();
        worldResetNotBefore = server.getTickCount() + WORLD_RESET_DELAY_TICKS;
        generateServerWorld();
    }

    /**
     * Turns Server Mode on. Refused while a speedrun is running or generating. Any leftover run
     * world is torn down, then the server world is opened (resumed if one exists on disk).
     * The caller flips Settings.serverMode before calling this when it returns true; the check
     * here is on the raw run state so it works either way.
     */
    public synchronized boolean enterServerMode() {
        if (gameState == RunState.RUNNING || gameState == RunState.GENERATING_WORLD) {
            return false;
        }
        SoulLink.LOGGER.info("Entering Server Mode");
        EventRegistry.clearDelayedTasks();
        groupDeathInProgress = false;
        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();
        clearEnderDragonBossbar();
        clearRaidBossbars();
        deleteWorlds(true);
        worldService.deleteOldWorlds();
        timerService.reset();
        gameState = RunState.IDLE;
        Settings.getInstance().setServerMode(true);
        openServerWorld();
        return true;
    }

    /**
     * Turns Server Mode off. Players are moved to the normal spawn with what they carry; the server
     * world is unloaded but stays on disk, so turning the mode back on resumes it.
     */
    public synchronized void leaveServerMode() {
        SoulLink.LOGGER.info("Leaving Server Mode");
        EventRegistry.clearDelayedTasks();
        groupDeathInProgress = false;
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (isInRun(player)) {
                teleportService.teleportToVanillaSpawn(player);
            }
            if (player.isSpectator()) {
                player.setGameMode(GameType.SURVIVAL);
            }
            var maxHealthAttr = player.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealthAttr != null) {
                maxHealthAttr.setBaseValue(20.0);
            }
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(false));
        }
        worldService.unloadCurrentWorlds();
        spawnFinder.reset();
        endInitialized = false;
        gameState = RunState.IDLE;
        Settings.getInstance().setServerMode(false);
        SharedStatsHandler.reset();
    }

    /**
     * Run-scoped settings normally take effect when a run starts. In Server Mode the world is
     * always running, so the ones with live state are applied here as soon as they change.
     * Difficulty is baked into the worlds when they are generated, so it waits for the next reset.
     */
    public void applyServerModeSettings(
            ServerPlayer changedBy, Settings.SettingsSnapshot before, Settings.SettingsSnapshot after) {
        if (after.halfHeartMode() != before.halfHeartMode()) {
            SharedStatsHandler.reset();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!isInRun(player)) continue;
                var maxHealthAttr = player.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    maxHealthAttr.setBaseValue(after.halfHeartMode() ? 1.0 : 20.0);
                }
                SharedStatsHandler.syncPlayerToSharedStats(player);
            }
        }
        if (after.syncedInventory() && !before.syncedInventory()) {
            // Seed the shared inventory from whoever turned it on, so nobody's items vanish.
            SharedInventoryHandler.reset();
            SharedInventoryHandler.syncFromPlayerToAll(changedBy);
        }
    }

    public synchronized void triggerGameOver() {
        if (gameState != RunState.RUNNING) return;

        if (Settings.getInstance().isServerMode()) {
            // Nothing ends in Server Mode; the nearest thing is a fresh world.
            resetServerWorld(null, null);
            return;
        }

        SoulLink.LOGGER.info("Game Over triggered!");
        timerService.stop();
        gameState = RunState.GAMEOVER;
        groupDeathInProgress = false;

        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();

        String finalTime = timerService.getFormattedTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInRun(player)) {
                player.setGameMode(GameType.SPECTATOR);
                player.getInventory().clearContent();

                ServerLevel world = getPlayerWorld(player);
                if (world != null) {
                    world.playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.WITHER_DEATH,
                            SoundSource.PLAYERS,
                            0.5f,
                            0.8f);
                }

                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal("GAME OVER").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal(finalTime).withStyle(ChatFormatting.WHITE)));
            }
        }

        Component restartMessage = Component.empty()
                .append(getPrefix())
                .append(Component.literal("All players are dead. Click ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("here")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.BLUE)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/start"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Start a new attempt").withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(" or use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" to start a new attempt.").withStyle(ChatFormatting.GRAY));

        server.getPlayerList().broadcastSystemMessage(restartMessage, false);
    }

    public synchronized void triggerVictory() {
        if (gameState != RunState.RUNNING) return;

        if (Settings.getInstance().isServerMode()) {
            // No run to finish: say it happened and play on.
            SoulLink.LOGGER.info("Server Mode: dragon defeated");
            server.getPlayerList().broadcastSystemMessage(formatMessage("The Ender Dragon has been defeated!"), false);
            return;
        }

        SoulLink.LOGGER.info("Victory! Dragon defeated!");
        timerService.stop();
        gameState = RunState.GAMEOVER;
        groupDeathInProgress = false;

        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();

        String finalTime = timerService.getFormattedTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel world = getPlayerWorld(player);
            if (world != null)
                world.playSound(
                        null,
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                        SoundSource.PLAYERS,
                        1.0f,
                        1.0f);

            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("VICTORY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal(finalTime).withStyle(ChatFormatting.WHITE)));
        }

        Component victoryMessage = Component.empty()
                .append(getPrefix())
                .append(Component.literal("Dragon defeated in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(finalTime).withStyle(ChatFormatting.WHITE));
        server.getPlayerList().broadcastSystemMessage(victoryMessage, false);

        Component clickableHere = Component.literal("here")
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/start"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Click to start a new run!").withStyle(ChatFormatting.GRAY))));
        Component restartMessage = Component.empty()
                .append(getPrefix())
                .append(Component.literal("Victory! Click ").withStyle(ChatFormatting.GRAY))
                .append(clickableHere)
                .append(Component.literal(" or use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" to challenge again.").withStyle(ChatFormatting.GRAY));
        server.getPlayerList().broadcastSystemMessage(restartMessage, false);
    }

    public boolean isPlayerInRun(ServerPlayer player) {
        return isInRun(player);
    }

    private boolean isInRun(ServerPlayer player) {
        ServerLevel world = getPlayerWorld(player);
        return world != null && isRunWorld(world.dimension());
    }

    /**
     * Whether the shared mechanics apply in this world: the run's worlds, which in Server Mode are
     * the persistent server worlds. Same set as isTemporaryWorld; named for what callers mean.
     */
    public boolean isRunWorld(ResourceKey<Level> worldKey) {
        return isTemporaryWorld(worldKey);
    }

    private void clearEnderDragonBossbar() {
        for (ServerLevel world : server.getAllLevels()) {
            EnderDragonFight fight = world.getDragonFight();
            if (fight != null) {
                try {
                    ServerBossEvent bossBar = ((EnderDragonFightAccessor) fight).getBossBar();
                    if (bossBar != null) forceClearBossBar(bossBar);
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear Ender Dragon bossbar: {}", e.getMessage());
                }
            }
        }
    }

    private void forceClearBossBar(ServerBossEvent bossBar) {
        bossBar.setVisible(false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) bossBar.addPlayer(player);
        bossBar.removeAllPlayers();
    }

    private void clearRaidBossbars() {
        for (ServerLevel world : server.getAllLevels()) {
            Raids raidManager = world.getRaids();
            if (raidManager == null) continue;
            List<Raid> raidsToClean = new ArrayList<>(
                    ((RaidManagerAccessor) raidManager).getRaids().values());
            for (Raid raid : raidsToClean) {
                try {
                    raid.stop();
                    ServerBossEvent bossBar = ((RaidAccessor) raid).getBar();
                    if (bossBar != null) forceClearBossBar(bossBar);
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear raid bossbar: {}", e.getMessage());
                }
            }
        }
    }

    public void teleportToVanillaSpawn(ServerPlayer player) {
        teleportService.teleportToVanillaSpawn(player);
    }

    public RunState getGameState() {
        return gameState;
    }

    /** Whether the shared mechanics are live: a run (or the Server Mode world) is running. */
    public boolean isRunActive() {
        return gameState == RunState.RUNNING;
    }

    public boolean isGameOver() {
        return gameState == RunState.GAMEOVER;
    }

    public boolean isEndInitialized() {
        return endInitialized;
    }

    public void setEndInitialized(boolean initialized) {
        this.endInitialized = initialized;
    }

    public ServerLevel getTemporaryOverworld() {
        return worldService.getOverworld();
    }

    public ServerLevel getTemporaryNether() {
        return worldService.getNether();
    }

    public ServerLevel getTemporaryEnd() {
        return worldService.getEnd();
    }

    public ResourceKey<Level> getTemporaryOverworldKey() {
        return worldService.getOverworldKey();
    }

    public ResourceKey<Level> getTemporaryNetherKey() {
        return worldService.getNetherKey();
    }

    public ResourceKey<Level> getTemporaryEndKey() {
        return worldService.getEndKey();
    }

    public boolean isTemporaryWorld(ResourceKey<Level> worldKey) {
        return worldService.isTemporaryWorld(worldKey);
    }

    public ServerLevel getLinkedNetherWorld(ServerLevel fromWorld) {
        return worldService.getLinkedNetherWorld(fromWorld);
    }

    public BlockPos getSpawnPos() {
        return spawnFinder != null ? spawnFinder.getSpawnPos() : null;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public String getFormattedTime() {
        return timerService.getFormattedTime();
    }

    public long getElapsedTimeMillis() {
        return timerService.getElapsedTimeMillis();
    }

    public void stopTimer() {
        timerService.stop();
    }
}
