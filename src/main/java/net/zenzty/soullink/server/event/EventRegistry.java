package net.zenzty.soullink.server.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.storage.LevelData;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.health.SharedJumpHandler;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import net.zenzty.soullink.server.manhunt.CompassTrackingHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsPersistence;

/**
 * Registers all Fabric events: server lifecycle, player connections, tick updates, entity events.
 */
public class EventRegistry {

    private static class DelayedTask {
        int remainingTicks;
        final Runnable task;

        DelayedTask(int remainingTicks, Runnable task) {
            this.remainingTicks = remainingTicks;
            this.task = task;
        }
    }

    // Track delayed tasks (list of tasks with remaining ticks)
    private static final List<DelayedTask> DELAYED_TASKS = new ArrayList<>();

    /**
     * Registers all events for the SoulLink mod.
     */
    public static void registerAll() {
        registerServerEvents();
        registerConnectionEvents();
        registerTickEvents();
        registerEntityEvents();
        registerUseEvents();
        CompassTrackingHandler.register();
    }

    /**
     * Block/item use events. Delayed sync (UseBlockCallback/UseItemCallback + scheduleDelayed) was
     * causing "invalid player data" when the task ran during disconnect/save. Disabled; block
     * placement sync is best fixed by hooking the exact place vanilla consumes the item.
     */
    private static void registerUseEvents() {
        // No delayed sync - causes invalid player data when player disconnects or saves.
    }

    /**
     * Registers server lifecycle events for initialization and cleanup.
     */
    private static void registerServerEvents() {
        // Server started - initialize RunManager and load persisted settings
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SoulLink.LOGGER.info("Server started - initializing RunManager");
            RunManager.init(server);
            SettingsPersistence.load(server);
            ManhuntManager.getInstance().resetRoles();
            ManhuntManager.getInstance().cleanupTeams(server);
            if (Settings.getInstance().isServerMode()) {
                RunManager.getInstance().openServerWorld();
            }
        });

        // Server stopping - save settings, then cleanup worlds
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SoulLink.LOGGER.info("Server stopping - saving settings and cleaning up temporary worlds");
            SettingsPersistence.save(server);
            DELAYED_TASKS.clear(); // Clear pending tasks
            RunManager.cleanup();
        });
    }

    /**
     * Registers player connection events for player connections and disconnects.
     */
    private static void registerConnectionEvents() {
        // Player joins - show welcome or handle late join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            // Check if RunManager is initialized (might not be if server just started)
            // But usually SERVER_STARTED runs before player join.
            // However, use try-catch or check to be safe if getInstance throws.
            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager == null) {
                return;
            }

            // IMMEDIATELY teleport if IDLE to prevent suffocation damage
            if (runManager.getGameState() == RunState.IDLE
                    && !Settings.getInstance().isServerMode()) {
                runManager.teleportToVanillaSpawn(player);
            }

            // Delay other handling to ensure player is fully loaded
            scheduleDelayed(10, () -> {
                // Return early if player has disconnected in the meantime
                if (player.isRemoved()) {
                    return;
                }

                RunState state = runManager.getGameState();

                if (Settings.getInstance().isServerMode()
                        && Settings.getInstance().isJoinMessagesEnabled()) {
                    sendServerModeWelcome(player);
                }

                switch (state) {
                    case IDLE:
                        if (Settings.getInstance().isJoinMessagesEnabled()
                                && !Settings.getInstance().isServerMode()) {
                            sendWelcomeMessage(player);
                        }
                        break;

                    case GENERATING_WORLD:
                    case RUNNING:
                        // Run in progress - teleport player to it
                        ServerLevel playerWorld = player.level();
                        if (playerWorld == null) {
                            return;
                        }

                        if (!runManager.isTemporaryWorld(playerWorld.dimension())) {
                            SoulLink.LOGGER.info(
                                    "Late joiner detected: {} - teleporting to run",
                                    player.getName().getString());
                            runManager.teleportPlayerToRun(player);
                        } else if (Settings.getInstance().isServerMode()
                                && state == RunState.RUNNING
                                && player.isSpectator()) {
                            // Back in the server world but parked as a spectator: play on.
                            player.setGameMode(GameType.SURVIVAL);
                            SharedStatsHandler.syncPlayerToSharedStats(player);
                        }
                        break;

                    case GAMEOVER:
                        if (Settings.getInstance().isJoinMessagesEnabled()) {
                            player.sendSystemMessage(
                                    RunManager.formatMessage("Run has ended. Use /start to begin a new run."));
                        }
                        break;
                }
            });
        });

        // Player disconnects - log for debugging
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null && runManager.isRunActive()) {
                SoulLink.LOGGER.info(
                        "Player {} disconnected during active run",
                        player.getName().getString());
            }
        });
    }

    /**
     * Sends the welcome message to a player explaining the mod.
     */
    private static void sendWelcomeMessage(ServerPlayer player) {
        // Title - Show beta version info only if version contains "beta"
        var container = FabricLoader.getInstance().getModContainer(SoulLinkConstants.MOD_ID);
        if (container.isPresent()) {
            String version = container.get().getMetadata().getVersion().getFriendlyString();
            if (version.contains("beta")) {
                player.sendSystemMessage(Component.empty()
                        .append(Component.literal("SOUL LINK SPEEDRUN - BETA RELEASE " + version)
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            } else {
                player.sendSystemMessage(Component.empty()
                        .append(Component.literal("SOUL LINK SPEEDRUN")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            }
        } else {
            player.sendSystemMessage(Component.empty()
                    .append(Component.literal("SOUL LINK SPEEDRUN")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }

        // Empty line
        player.sendSystemMessage(Component.empty());

        // Soul Link info
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("❤ ").withStyle(ChatFormatting.RED))
                .append(Component.literal("Soul Link").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(
                                Settings.getInstance().isSharedHealth()
                                        ? " - All players share health and hunger."
                                        : " - Everyone has their own health; deaths are shared.")
                        .withStyle(ChatFormatting.GRAY)));

        // Goal info
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("⚔ ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Goal").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - Defeat the Ender Dragon together.")
                        .withStyle(ChatFormatting.GRAY)));

        // Death info
        String deathText = Settings.getInstance().isWorldReset()
                ? " - If anyone dies, the run ends for all."
                : " - If anyone dies, everyone dies and drops their items; the run goes on.";
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal("Death").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(deathText).withStyle(ChatFormatting.GRAY)));

        // Empty line
        player.sendSystemMessage(Component.empty());

        // Start command
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" or ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("click here")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.BLUE)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/start"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Start a new run").withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(" to begin.").withStyle(ChatFormatting.GRAY)));

        // Empty line
        player.sendSystemMessage(Component.empty());

        // Settings tip
        player.sendSystemMessage(settingsTip());
    }

    private static Component settingsTip() {
        return Component.empty()
                .append(Component.literal("TIP: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("Change the rules with ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/settings")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.GOLD)
                                .withClickEvent(new ClickEvent.RunCommand("/settings"))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the settings menu")
                                        .withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
    }

    /**
     * The short welcome for Server Mode: what is shared and what death means. No /start here.
     */
    private static void sendServerModeWelcome(ServerPlayer player) {
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("SOUL LINK").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("❤ ").withStyle(ChatFormatting.RED))
                .append(Component.literal("Soul Link").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(
                                Settings.getInstance().isSharedHealth()
                                        ? " - All players share health and hunger."
                                        : " - Everyone has their own health; deaths are shared.")
                        .withStyle(ChatFormatting.GRAY)));
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal("Death").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(
                                Settings.getInstance().isWorldReset()
                                        ? " - If anyone dies, everyone dies and the world resets."
                                        : " - If anyone dies, everyone dies and drops their items in one pile.")
                        .withStyle(ChatFormatting.GRAY)));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(settingsTip());
    }

    /**
     * Registers tick events for timer updates and periodic sync.
     */
    private static void registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processDelayedTasks(server);

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null) {
                runManager.tick();
                if (runManager.isRunActive() && Settings.getInstance().isManhuntMode()) {
                    CompassTrackingHandler.tick(server);
                }
            }

            SharedJumpHandler.processJumpsAtTickEnd(server);
            SharedStatsHandler.tickSync(server);
        });
    }

    /**
     * Registers entity events for death handling and dragon victory.
     */
    private static void registerEntityEvents() {
        // Handle entity death - check for dragon
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof EnderDragon dragon) {
                RunManager runManager;
                try {
                    runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                    return;
                }

                if (runManager == null || !runManager.isRunActive()) {
                    return;
                }

                if (dragon.level() instanceof ServerLevel dragonWorld
                        && runManager.isTemporaryWorld(dragonWorld.dimension())) {
                    SoulLink.LOGGER.info("Ender Dragon killed in temporary End - triggering victory!");
                    runManager.triggerVictory();
                }
            }
        });

        // Handle damage - intercept lethal damage to prevent death screen
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return true;
            }

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return true;
            }

            if (runManager == null) {
                return true;
            }

            // Block ALL damage during game over state
            if (runManager.isGameOver()) {
                return false;
            }

            if (!runManager.isRunActive()) {
                return true;
            }

            if (SharedStatsHandler.isSyncing()) {
                return true;
            }

            if (player.isBlocking()) {
                return true;
            }

            // Allow all damage through - the actual death check happens in ServerPlayerEntityMixin
            // when health truly hits 0 (after armor/enchantment reductions are applied).
            // Previously we checked raw damage here, but that caused false positives since
            // 'amount' is before armor reduction (e.g., iron golem 15 raw → 7 actual with armor).
            return true;
        });

        // After damage is applied
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return;
            }

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager == null || !runManager.isRunActive()) {
                return;
            }

            if (damageTaken <= 0) {
                return;
            }

            if (player.getHealth() <= 0) {
                SoulLink.LOGGER.warn(
                        "Player {} reached 0 health despite mixin check - triggering death handler",
                        player.getName().getString());
                if (Settings.getInstance().isManhuntMode()
                        && ManhuntManager.getInstance().isHunter(player)) {
                    handleHunterDeath(player, source, runManager);
                } else {
                    handlePlayerDeath(player, source, runManager);
                }
                return;
            }

            ServerLevel playerWorld = player.level();
            if (playerWorld == null) {
                return;
            }

            if (!runManager.isRunWorld(playerWorld.dimension())) {
                return;
            }

            if (Settings.getInstance().isManhuntMode()
                    && ManhuntManager.getInstance().isHunter(player)) {
                return;
            }

            SharedStatsHandler.onPlayerHealthChanged(player, player.getHealth(), source);
        });
    }

    /**
     * Handles player death logic (broadcast message, reset health, trigger game over).
     */
    private static void handlePlayerDeath(ServerPlayer player, DamageSource source, RunManager runManager) {
        if (!Settings.getInstance().isWorldReset() || Settings.getInstance().isServerMode()) {
            player.setHealth(player.getMaxHealth());
            runManager.handleRunnerDeath(player, source);
            return;
        }

        Component deathMessage = source.getLocalizedDeathMessage(player);
        Component formattedDeathMessage = Component.empty()
                .append(RunManager.getPrefix())
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(deathMessage.copy().withStyle(ChatFormatting.RED));
        runManager.getServer().getPlayerList().broadcastSystemMessage(formattedDeathMessage, false);

        player.setHealth(player.getMaxHealth());
        runManager.triggerGameOver();
    }

    /**
     * Handles Hunter death in Manhunt: broadcast, clear bad effects, switch to spectator, drop
     * non-compass items, 5s countdown, then respawn at run spawn with full stats and a new tracking
     * compass.
     *
     * @param player the hunter who died
     * @param source the damage source
     * @param runManager the run manager (used for spawn, run state, and overworld)
     */
    public static void handleHunterDeath(ServerPlayer player, DamageSource source, RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        Component deathMessage = source.getLocalizedDeathMessage(player);
        Component formatted = Component.empty()
                .append(RunManager.getPrefix())
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(deathMessage.copy().withStyle(ChatFormatting.RED));
        server.getPlayerList().broadcastSystemMessage(formatted, false);

        List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> toRemove =
                player.getActiveEffects().stream()
                        .filter(e -> !e.getEffect().value().isBeneficial())
                        .map(e -> e.getEffect())
                        .toList();
        toRemove.forEach(player::removeEffect);

        player.setGameMode(GameType.SPECTATOR);

        ServerLevel world = player.level();
        double x = player.getX(), y = player.getY(), z = player.getZ();

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && !stack.is(Items.COMPASS)) {
                ItemEntity ent = new ItemEntity(world, x, y, z, stack.copy());
                ent.setDeltaMovement(
                        world.getRandom().nextGaussian() * 0.05,
                        world.getRandom().nextGaussian() * 0.05 + 0.2,
                        world.getRandom().nextGaussian() * 0.05);
                world.addFreshEntity(ent);
            }
        }

        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();

        for (int i = 5; i >= 1; i--) {
            final int c = i;
            scheduleDelayed((5 - i) * 20, () -> {
                if (player.isRemoved() || !runManager.isRunActive()) return;
                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal(String.valueOf(c)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal("Respawning...").withStyle(ChatFormatting.GRAY)));
            });
        }

        scheduleDelayed(5 * 20, () -> {
            if (player.isRemoved() || !runManager.isRunActive()) return;

            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("RESPAWN").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

            player.setGameMode(GameType.SURVIVAL);

            ServerLevel targetWorld = runManager.getTemporaryOverworld();
            BlockPos targetPos = runManager.getSpawnPos();

            ServerPlayer.RespawnConfig resp = player.getRespawnConfig();
            if (resp != null) {
                var data = resp.respawnData();
                if (data != null && runManager.isTemporaryWorld(data.dimension())) {
                    ServerLevel sw = server.getLevel(data.dimension());
                    if (sw != null) {
                        targetWorld = sw;
                        targetPos = data.pos();
                    }
                }
            }

            if (targetWorld != null && targetPos != null) {
                LevelData.RespawnData sp = LevelData.RespawnData.of(targetWorld.dimension(), targetPos, 0.0f, 0.0f);
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(sp, true), false);
                player.teleportTo(
                        targetWorld,
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        Set.of(),
                        0.0f,
                        0.0f,
                        true);
            }

            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0f);

            CompassTrackingHandler.giveTrackingCompass(player);

            SoulLink.LOGGER.info(
                    "Hunter {} respawned after death", player.getName().getString());
        });
    }

    private static final int GROUP_RESPAWN_SECONDS = 5;

    /**
     * Death with World Reset off. The whole group dies at once: every item they carry lands in one
     * pile where the victim fell (one copy when Synced Inventory is on, since everyone holds the same
     * items), XP is lost, everyone watches a short countdown as a spectator and then respawns at the
     * run spawn (or their bed if it is in the run world) with full shared stats. The run, its world
     * and its timer carry on. Queued from RunManager.requestGroupDeath so it runs outside the damage
     * pipeline.
     */
    public static void handleGroupDeath(ServerPlayer victim, DamageSource source, RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null || !runManager.isRunActive()) {
            runManager.finishGroupDeath();
            return;
        }

        Component deathMessage = source != null
                ? source.getLocalizedDeathMessage(victim)
                : Component.literal(victim.getName().getString() + " died");
        server.getPlayerList()
                .broadcastSystemMessage(
                        Component.empty()
                                .append(RunManager.getPrefix())
                                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                                .append(deathMessage.copy().withStyle(ChatFormatting.RED)),
                        false);

        ServerLevel pileWorld = victim.level();
        double x = victim.getX();
        double y = victim.getY();
        double z = victim.getZ();
        BlockPos pilePos = victim.blockPosition();

        List<ServerPlayer> group = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isRemoved() || p.hasDisconnected()) continue;
            if (!runManager.isPlayerInRun(p)) continue;
            if (Settings.getInstance().isManhuntMode()
                    && ManhuntManager.getInstance().isHunter(p)) continue;
            group.add(p);
        }
        if (!group.contains(victim) && !victim.isRemoved()) {
            group.add(victim);
        }

        boolean synced = Settings.getInstance().isSyncedInventory();
        boolean dropped = false;
        int stacks = 0;
        for (ServerPlayer p : group) {
            if (!synced || !dropped) {
                stacks += dropEverything(p, pileWorld, x, y, z);
                dropped = true;
            }
            p.getInventory().clearContent();
            p.setExperienceLevels(0);
            p.setExperiencePoints(0);
            List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> harmful =
                    p.getActiveEffects().stream()
                            .filter(e -> !e.getEffect().value().isBeneficial())
                            .map(e -> e.getEffect())
                            .toList();
            harmful.forEach(p::removeEffect);
            p.clearFire();
            p.setGameMode(GameType.SPECTATOR);
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_DEATH),
                    SoundSource.PLAYERS,
                    p.getX(),
                    p.getY(),
                    p.getZ(),
                    1.0f,
                    1.0f,
                    p.getRandom().nextLong()));
            p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 5));
            p.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("EVERYONE DIED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("Respawning...").withStyle(ChatFormatting.GRAY)));
        }
        if (synced) {
            SharedInventoryHandler.reset();
        }
        SharedStatsHandler.reset();

        server.getPlayerList()
                .broadcastSystemMessage(
                        Component.empty()
                                .append(RunManager.getPrefix())
                                .append(Component.literal("Everyone died. ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(stacks + (stacks == 1 ? " stack" : " stacks"))
                                        .withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" dropped at ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(
                                                pilePos.getX() + ", " + pilePos.getY() + ", " + pilePos.getZ())
                                        .withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" in " + describeDimension(pileWorld) + ".")
                                        .withStyle(ChatFormatting.GRAY)),
                        false);
        SoulLink.LOGGER.info(
                "Group death: {} players, {} stacks dropped at {} ({})",
                group.size(),
                stacks,
                pilePos,
                describeDimension(pileWorld));

        for (int i = GROUP_RESPAWN_SECONDS; i >= 1; i--) {
            final int c = i;
            scheduleDelayed((GROUP_RESPAWN_SECONDS - i) * 20, () -> {
                if (!runManager.isRunActive()) return;
                for (ServerPlayer p : group) {
                    if (p.isRemoved() || p.hasDisconnected()) continue;
                    p.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal(String.valueOf(c)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                    p.connection.send(new ClientboundSetSubtitleTextPacket(
                            Component.literal("Respawning...").withStyle(ChatFormatting.GRAY)));
                }
            });
        }

        scheduleDelayed(GROUP_RESPAWN_SECONDS * 20, () -> {
            try {
                if (!runManager.isRunActive()) return;
                for (ServerPlayer p : group) {
                    if (p.isRemoved() || p.hasDisconnected()) continue;
                    respawnInRun(p, runManager, synced);
                }
                SoulLink.LOGGER.info("Group respawned after death");
            } finally {
                runManager.finishGroupDeath();
            }
        });
    }

    /** Drops every stack the player carries (main, hotbar, armor, offhand) at one point. */
    private static int dropEverything(ServerPlayer p, ServerLevel world, double x, double y, double z) {
        int count = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            ItemEntity ent = new ItemEntity(world, x, y, z, stack.copy());
            ent.setDeltaMovement(
                    world.getRandom().nextGaussian() * 0.05,
                    world.getRandom().nextGaussian() * 0.05 + 0.2,
                    world.getRandom().nextGaussian() * 0.05);
            ent.setDefaultPickUpDelay();
            world.addFreshEntity(ent);
            count++;
        }
        return count;
    }

    /** Puts a group member back into the run after a group death. */
    private static void respawnInRun(ServerPlayer p, RunManager runManager, boolean synced) {
        MinecraftServer server = runManager.getServer();
        ServerLevel targetWorld = runManager.getTemporaryOverworld();
        BlockPos targetPos = runManager.getSpawnPos();

        ServerPlayer.RespawnConfig resp = p.getRespawnConfig();
        if (resp != null) {
            var data = resp.respawnData();
            if (data != null && runManager.isRunWorld(data.dimension())) {
                ServerLevel sw = server.getLevel(data.dimension());
                if (sw != null) {
                    targetWorld = sw;
                    targetPos = data.pos();
                }
            }
        }

        p.setGameMode(GameType.SURVIVAL);
        if (targetWorld != null && targetPos != null) {
            p.teleportTo(
                    targetWorld,
                    targetPos.getX() + 0.5,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5,
                    Set.of(),
                    p.getYRot(),
                    p.getXRot(),
                    true);
        }
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
        SharedStatsHandler.syncPlayerToSharedStats(p);
        if (synced) {
            SharedInventoryHandler.syncPlayerToShared(p);
        }
        p.connection.send(new ClientboundClearTitlesPacket(false));
    }

    private static String describeDimension(ServerLevel world) {
        var type = world.dimensionTypeRegistration();
        if (type.is(BuiltinDimensionTypes.NETHER)) return "the Nether";
        if (type.is(BuiltinDimensionTypes.END)) return "the End";
        return "the Overworld";
    }

    /**
     * Schedule a task to run after a delay in ticks.
     */
    public static void scheduleDelayed(int delayTicks, Runnable task) {
        DELAYED_TASKS.add(new DelayedTask(delayTicks, task));
    }

    /**
     * Clears all pending delayed tasks. Called when starting a new run so that tasks from a
     * previous run (e.g. hunter respawn countdown) do not carry over.
     */
    public static void clearDelayedTasks() {
        DELAYED_TASKS.clear();
    }

    /**
     * Process any delayed tasks that are ready to run.
     */
    private static void processDelayedTasks(MinecraftServer server) {
        // Collect first, run after: a task may schedule further tasks (the group death does),
        // which must not touch the list while it is being iterated.
        List<DelayedTask> ready = new ArrayList<>();
        Iterator<DelayedTask> iterator = DELAYED_TASKS.iterator();
        while (iterator.hasNext()) {
            DelayedTask task = iterator.next();
            task.remainingTicks--;
            if (task.remainingTicks <= 0) {
                ready.add(task);
                iterator.remove();
            }
        }
        for (DelayedTask task : ready) {
            try {
                task.task.run();
            } catch (Exception e) {
                SoulLink.LOGGER.error("Error running delayed task", e);
            }
        }
    }
}
