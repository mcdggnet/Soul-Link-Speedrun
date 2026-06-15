package net.zenzty.soullink.server.run;

import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles player teleportation and reset logic for speedruns.
 */
public class PlayerTeleportService {

    private final MinecraftServer server;

    public PlayerTeleportService(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Teleports a player to the spawn position and sets up for gameplay.
     *
     * @param player The player to teleport
     * @param world The target world
     * @param spawnPos The spawn position
     * @param timerService The timer service for input tracking
     * @param syncToShared When true, syncs to shared stats and starts timer on input. When false
     *        (hunters in Manhunt), uses vanilla mechanics.
     */
    public void teleportToSpawn(ServerPlayer player, ServerLevel world, BlockPos spawnPos,
            TimerService timerService, boolean syncToShared) {
        if (player == null || world == null || spawnPos == null || timerService == null) {
            SoulLink.LOGGER.error("Failed to teleport to spawn: null parameter(s)");
            return;
        }

        resetPlayer(player);

        player.teleportTo(world, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                Set.of(), 0, 0, true);

        if (player.connection != null) {
            player.connection.send(new ClientboundClearTitlesPacket(false));
        }

        if (syncToShared) {
            SharedStatsHandler.syncPlayerToSharedStats(player);
            timerService.beginWaitingForInput(player);
        }

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.5f);
    }

    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    public void teleportToVanillaSpawn(ServerPlayer player) {
        if (player == null || server == null)
            return;

        ServerLevel overworld = server.overworld();
        if (overworld == null)
            return;

        net.minecraft.world.level.storage.LevelData.RespawnData spawn =
                overworld.getLevelData().getRespawnData();

        if (spawn == null || spawn.globalPos() == null) {
            SoulLink.LOGGER.error("Could not find vanilla spawn point!");
            return;
        }

        BlockPos spawnPos = spawn.globalPos().pos();

        player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), true);
    }

    /**
     * Forceloads chunks around spawn for smooth teleport.
     */
    public void forceloadSpawnChunks(ServerLevel world, BlockPos spawnPos) {
        int spawnChunkX = spawnPos.getX() >> 4;
        int spawnChunkZ = spawnPos.getZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunk(spawnChunkX + dx, spawnChunkZ + dz);
            }
        }
    }

    /**
     * Fully resets a player for a new run.
     */
    private void resetPlayer(ServerPlayer player) {
        // Clear inventory
        player.getInventory().clearContent();

        // Clear all status effects
        player.removeAllEffects();

        // Reset experience
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);

        // Apply half heart mode if enabled
        Settings settings = Settings.getInstance();
        var maxHealthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealthAttr != null) {
            if (settings.isHalfHeartMode()) {
                maxHealthAttr.setBaseValue(1.0);
                player.setHealth(1.0f);
                SoulLink.LOGGER.info("Half Heart Mode enabled for {}",
                        player.getName().getString());
            } else {
                maxHealthAttr.setBaseValue(20.0);
                player.setHealth(player.getMaxHealth());
            }
        }

        // Reset hunger
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);

        // Clear ender chest
        player.getEnderChestInventory().clearContent();

        // Reset fire and freeze ticks
        player.setRemainingFireTicks(0);
        player.setTicksFrozen(0);

        // Reset all advancements
        resetPlayerAdvancements(player);

        // Set to survival mode
        player.setGameMode(GameType.SURVIVAL);

        SoulLink.LOGGER.info("Reset player {} for new run", player.getName().getString());
    }

    /**
     * Resets all advancements for a player.
     */
    private void resetPlayerAdvancements(ServerPlayer player) {
        PlayerAdvancements tracker = player.getAdvancements();

        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            AdvancementProgress progress = tracker.getOrStartProgress(advancement);

            for (String criterion : progress.getCompletedCriteria()) {
                tracker.revoke(advancement, criterion);
            }
        }

        SoulLink.LOGGER.info("Reset advancements for player {}", player.getName().getString());
    }
}
