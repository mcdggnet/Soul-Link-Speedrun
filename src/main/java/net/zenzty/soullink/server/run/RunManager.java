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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
import net.zenzty.soullink.server.manhunt.CompassTrackingHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsPersistence;

/**
 * Facade that coordinates run lifecycle using dedicated services. Manages game state transitions
 * and player coordination.
 */
public class RunManager {

    private static volatile RunManager instance;

    private final MinecraftServer server;
    private final WorldService worldService;
    private final TimerService timerService;
    private final SpawnFinder spawnFinder;
    private final PlayerTeleportService teleportService;

    // Game state
    private volatile RunState gameState = RunState.IDLE;

    // End dimension initialization flag (resets per run)
    private volatile boolean endInitialized = false;

    // ==================== MESSAGE FORMATTING ====================

    /**
     * Creates the [SoulLink] prefix with dark grey brackets and light red text.
     */
    public static Component getPrefix() {
        return Component.empty().append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("SoulLink").withStyle(ChatFormatting.RED))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * Creates a formatted message with the [SoulLink] prefix.
     */
    public static Component formatMessage(String message) {
        return Component.empty().append(getPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Creates a formatted message with a player name highlighted.
     */
    public static Component formatMessageWithPlayer(String beforePlayer, String playerName,
            String afterPlayer) {
        return Component.empty().append(getPrefix())
                .append(Component.literal(beforePlayer).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(playerName).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(afterPlayer).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Creates a clickable text with underline and hover text.
     */
    public static Component formatClickable(String text, String command, String hoverText) {
        return Component.literal(text)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal(hoverText).withStyle(ChatFormatting.GRAY))));
    }

    // ==================== LIFECYCLE ====================

    private RunManager(MinecraftServer server) {
        this.server = server;
        this.worldService = new WorldService(server);
        this.timerService = new TimerService();
        this.spawnFinder = new SpawnFinder();
        this.teleportService = new PlayerTeleportService(server);
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
            currentInstance.worldService.deleteOldWorlds();
            currentInstance.deleteWorlds(true);
            instance = null;
        }
    }

    /**
     * Gets the ServerWorld for a player.
     */
    public static ServerLevel getPlayerWorld(ServerPlayer player) {
        return player.level();
    }

    // ==================== RUN LIFECYCLE ====================

    /**
     * Starts a new run - creates temporary worlds and begins spawn search.
     */
    public void startRun() {
        if (gameState == RunState.RUNNING || gameState == RunState.GENERATING_WORLD) {
            SoulLink.LOGGER.warn("Attempted to start run while already running or generating!");
            return;
        }

        SoulLink.LOGGER.info("Starting new run...");

        EventRegistry.clearDelayedTasks();

        // Apply pending settings first so Manhunt and all Chaos options are correct for this run
        Settings.getInstance().applyPendingSettings();
        SettingsPersistence.save(server);

        if (!Settings.getInstance().isManhuntMode()) {
            ManhuntManager.getInstance().resetRoles();
        }

        // Clear any lingering bossbars from previous run
        clearEnderDragonBossbar();
        clearRaidBossbars();

        // Broadcast starting message
        server.getPlayerList().broadcastSystemMessage(formatMessage("Generating new world..."), false);

        // Save old worlds for later deletion
        worldService.saveCurrentWorldsAsOld();

        // Create new temporary worlds
        long seed = worldService.createTemporaryWorlds();

        // Reset shared stats
        SharedStatsHandler.reset();

        // Reset shared inventory when synced inventory mode is enabled
        if (Settings.getInstance().isSyncedInventory()) {
            net.zenzty.soullink.server.inventory.SharedInventoryHandler.reset();
        }

        // Reset End initialization flag
        endInitialized = false;

        // Reset timer
        timerService.reset();

        // Reset spawn search and start generating
        spawnFinder.reset();
        gameState = RunState.GENERATING_WORLD;

        // Put all players in spectator mode
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);
        }

        SoulLink.LOGGER.info("World created with seed: {}, now searching for spawn...", seed);
    }

    /**
     * Called every server tick to update state.
     */
    public void tick() {
        // Handle incremental world generation
        if (gameState == RunState.GENERATING_WORLD) {
            ServerLevel overworld = worldService.getOverworld();
            if (overworld == null) {
                SoulLink.LOGGER.error("No overworld handle during generation!");
                gameState = RunState.IDLE;
                return;
            }

            if (spawnFinder.processStep(overworld, server)) {
                transitionToRunning();
            }
            return;
        }

        if (gameState != RunState.RUNNING) {
            return;
        }

        // Manually advance time in temporary overworld
        ServerLevel tempOverworld = worldService.getOverworld();
        if (tempOverworld != null) {
            // 1. Get clock manager for the server
            ServerClockManager clockManager = tempOverworld.getServer().clockManager();

            // 2. Get clock for world
            Holder<WorldClock> clock = tempOverworld.dimensionTypeRegistration().value().defaultClock().orElseThrow();

            // 3. Add 1 tick to the clock
            clockManager.addTicks(clock, 1);
        }

        // Handle timer (includes waiting for input)
        timerService.tick(server, this::isInRun, this::shouldSkipTimerActionBarFor);
    }

    /**
     * True when the timer should not overwrite the action bar for this player. In Manhunt, hunters
     * who just switched compass target keep the "Now tracking: X" message visible for a few
     * seconds.
     */
    private boolean shouldSkipTimerActionBarFor(ServerPlayer p) {
        if (!Settings.getInstance().isManhuntMode())
            return false;
        if (!ManhuntManager.getInstance().isHunter(p))
            return false;
        return CompassTrackingHandler.shouldSuppressTimerActionBar(p.getUUID(), server.getTickCount());
    }

    /**
     * Transitions from GENERATING_WORLD to RUNNING.
     */
    private void transitionToRunning() {
        ServerLevel overworld = worldService.getOverworld();
        BlockPos spawnPos = spawnFinder.getSpawnPos();

        if (overworld == null)
            return;

        // Use fallback if no spawn found
        if (spawnPos == null) {
            spawnPos = new BlockPos(0, 64, 0);
            SoulLink.LOGGER.warn("Using fallback spawn at {}", spawnPos);
        }

        // Forceload chunks around spawn
        teleportService.forceloadSpawnChunks(overworld, spawnPos);

        gameState = RunState.RUNNING;

        boolean manhunt = Settings.getInstance().isManhuntMode();
        ManhuntManager manhuntManager = ManhuntManager.getInstance();

        if (manhunt) {
            for (ServerLevel world : server.getAllLevels()) {
                try {
                    server.getCommands().getDispatcher().execute(
                            "execute in " + world.dimension().identifier()
                                    + " run gamerule locator_bar false",
                            server.createCommandSourceStack().withSuppressedOutput());
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Could not disable locator_bar in {}: {}",
                            world.dimension().identifier(), e.getMessage());
                }
            }
            manhuntManager.createTeams(server);
            manhuntManager.assignPlayersToTeams(server);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean syncToShared = !manhunt || manhuntManager.isSpeedrunner(player);
            teleportService.teleportToSpawn(player, overworld, spawnPos, timerService,
                    syncToShared);
        }

        worldService.deleteOldWorlds();

        if (manhunt) {
            CompassTrackingHandler.reset();
            for (UUID hunterId : manhuntManager.getHunters()) {
                ServerPlayer hunter = server.getPlayerList().getPlayer(hunterId);
                if (hunter != null) {
                    CompassTrackingHandler.giveTrackingCompass(hunter);
                }
            }
            applyHeadStartEffects(manhuntManager);
        }

        server.getPlayerList().broadcastSystemMessage(formatMessage("World ready! Good luck!"), false);

        SoulLink.LOGGER.info("World generation complete, run started");
    }

    private static final int HEAD_START_SECONDS = 30;

    /**
     * Applies the Manhunt head start: Speed for Runners, Blindness and Slowness for Hunters, and a
     * countdown for Hunters. After 30 seconds, "HUNTERS RELEASED!" / "GO!" is shown.
     */
    private void applyHeadStartEffects(ManhuntManager manhuntManager) {
        int durationTicks = HEAD_START_SECONDS * 20;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (manhuntManager.isHunter(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                        durationTicks, 0, false, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                        durationTicks, 255, false, false, true));
            } else if (manhuntManager.isSpeedrunner(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, durationTicks,
                        0, false, false, true));
            }
        }

        for (int i = HEAD_START_SECONDS; i >= 1; i--) {
            final int secondsRemaining = i;
            int delayTicks = (HEAD_START_SECONDS - i) * 20;

            EventRegistry.scheduleDelayed(delayTicks, () -> {
                if (gameState != RunState.RUNNING)
                    return;
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
            if (gameState != RunState.RUNNING)
                return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (manhuntManager.isSpeedrunner(player)) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
                    player.connection
                            .send(new ClientboundSetTitleTextPacket(Component.literal("HUNTERS RELEASED!")
                                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                } else if (manhuntManager.isHunter(player)) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("GO!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                }
            }
        });

        SoulLink.LOGGER.info("Applied head start effects - {} seconds", HEAD_START_SECONDS);
    }

    /**
     * Deletes all temporary worlds.
     */
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

    /**
     * Teleports a late-joining player to the current run.
     */
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
                        player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY() + 10,
                                spawnPos.getZ() + 0.5, Set.of(), 0, 0, true);
                    }
                    player.sendSystemMessage(
                            formatMessage(
                                    "A run is in progress. You are spectating until it ends."));
                } else {
                    teleportService.teleportToSpawn(player, overworld, spawnFinder.getSpawnPos(),
                            timerService, true);
                    if (Settings.getInstance().isSyncedInventory()) {
                        net.zenzty.soullink.server.inventory.SharedInventoryHandler
                                .syncPlayerToShared(player);
                    }
                    player.sendSystemMessage(formatMessageWithPlayer("", player.getName().getString(),
                            " joined. Stats synced."));
                }
            }
        }
    }

    // ==================== GAME END STATES ====================

    /**
     * Handles game over - all players died.
     */
    public synchronized void triggerGameOver() {
        if (gameState != RunState.RUNNING) {
            return;
        }

        SoulLink.LOGGER.info("Game Over triggered!");

        timerService.stop();
        gameState = RunState.GAMEOVER;

        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();

        String finalTime = timerService.getFormattedTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInRun(player)) {
                player.setGameMode(GameType.SPECTATOR);
                player.getInventory().clearContent();

                ServerLevel world = getPlayerWorld(player);
                if (world != null) {
                    world.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 0.5f, 0.8f);
                }

                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal("GAME OVER").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));

                player.connection.send(
                        new ClientboundSetSubtitleTextPacket(Component.literal(finalTime).withStyle(ChatFormatting.WHITE)));
            }
        }

        Component restartMessage = Component.empty().append(getPrefix())
                .append(Component.literal("All players are dead. Click ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("here").setStyle(Style.EMPTY.withColor(ChatFormatting.BLUE)
                        .withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/start"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Start a new attempt").withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(" or use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" to start a new attempt.").withStyle(ChatFormatting.GRAY));

        server.getPlayerList().broadcastSystemMessage(restartMessage, false);
    }

    /**
     * Handles victory - Ender Dragon killed.
     */
    public synchronized void triggerVictory() {
        if (gameState != RunState.RUNNING) {
            return;
        }

        SoulLink.LOGGER.info("Victory! Dragon defeated!");

        timerService.stop();
        gameState = RunState.GAMEOVER;

        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();

        String finalTime = timerService.getFormattedTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel world = getPlayerWorld(player);
            if (world != null) {
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
            }

            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("VICTORY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

            player.connection.send(
                    new ClientboundSetSubtitleTextPacket(Component.literal(finalTime).withStyle(ChatFormatting.WHITE)));
        }

        Component victoryMessage = Component.empty().append(getPrefix())
                .append(Component.literal("Dragon defeated in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(finalTime).withStyle(ChatFormatting.WHITE));
        server.getPlayerList().broadcastSystemMessage(victoryMessage, false);

        Component clickableHere = Component.literal("here").setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                .withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/start"))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Click to start a new run!").withStyle(ChatFormatting.GRAY))));

        Component restartMessage = Component.empty().append(getPrefix())
                .append(Component.literal("Victory! Click ").withStyle(ChatFormatting.GRAY))
                .append(clickableHere).append(Component.literal(" or use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" to challenge again.").withStyle(ChatFormatting.GRAY));
        server.getPlayerList().broadcastSystemMessage(restartMessage, false);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Checks if a player is in the active run (in a temporary world). Public for use by
     * SharedInventoryHandler and other handlers.
     */
    public boolean isPlayerInRun(ServerPlayer player) {
        return isInRun(player);
    }

    /**
     * Checks if a player is in the active run (in a temporary world).
     */
    private boolean isInRun(ServerPlayer player) {
        ServerLevel world = getPlayerWorld(player);
        return world != null && isTemporaryWorld(world.dimension());
    }

    /**
     * Clears the Ender Dragon bossbar from all players. This is needed when starting a new run
     * before Minecraft has naturally cleaned up the dragon bossbar from a completed run.
     */
    private void clearEnderDragonBossbar() {
        // Check all worlds for an active dragon fight and clear its bossbar
        for (ServerLevel world : server.getAllLevels()) {
            EnderDragonFight fight = world.getDragonFight();
            if (fight != null) {
                try {
                    ServerBossEvent bossBar = ((EnderDragonFightAccessor) fight).getBossBar();
                    if (bossBar != null) {
                        forceClearBossBar(bossBar);
                        SoulLink.LOGGER.debug("Cleared Ender Dragon bossbar from world: {}",
                                world.dimension().identifier());
                    }
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear Ender Dragon bossbar: {}",
                            e.getMessage());
                }
            }
        }
    }

    /**
     * Force-clears a bossbar by adding all online players first, then removing them. This ensures
     * the removal packet is sent to all clients, even if the players were already removed from the
     * bossbar's internal player list (e.g., when they died).
     */
    private void forceClearBossBar(ServerBossEvent bossBar) {
        // Set invisible first to prevent the bossbar from being re-shown by ongoing game logic
        bossBar.setVisible(false);
        // Add all online players to the bossbar first to ensure they're in the player list
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
        }
        // Now clear - this will send removal packets to all added players
        bossBar.removeAllPlayers();
    }

    /**
     * Clears all raid bossbars from all players. This is needed when starting a new run before
     * Minecraft has naturally cleaned up the raid bossbars from a completed or abandoned run. Note:
     * This method must be called from the server main thread. It uses a two-pass approach to avoid
     * ConcurrentModificationException when invalidating raids.
     */
    private void clearRaidBossbars() {
        // Check all worlds for active raids and clear their bossbars
        for (ServerLevel world : server.getAllLevels()) {
            Raids raidManager = world.getRaids();
            if (raidManager == null) {
                continue;
            }

            // Pass 1: Collect raids into a temporary list to avoid concurrent modification
            List<Raid> raidsToClean =
                    new ArrayList<>(((RaidManagerAccessor) raidManager).getRaids().values());

            // Pass 2: Invalidate each raid and clear its bossbar
            for (Raid raid : raidsToClean) {
                try {
                    // Invalidate the raid to stop it from ticking and re-showing the bossbar
                    raid.stop();

                    ServerBossEvent bossBar = ((RaidAccessor) raid).getBar();
                    if (bossBar != null) {
                        forceClearBossBar(bossBar);
                        SoulLink.LOGGER.debug("Cleared raid bossbar from world: {}",
                                world.dimension().identifier());
                    }
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear raid bossbar: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    public void teleportToVanillaSpawn(ServerPlayer player) {
        teleportService.teleportToVanillaSpawn(player);
    }

    // ==================== GETTERS ====================

    public RunState getGameState() {
        return gameState;
    }

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

    /**
     * Gets the current spawn position for the run. Used for hunter respawn and teleports.
     */
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
