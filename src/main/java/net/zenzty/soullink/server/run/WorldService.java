package net.zenzty.soullink.server.run;

import java.util.Random;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.settings.Settings;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * Manages the lifecycle of temporary Fantasy worlds for speedruns. Handles creation, deletion, and
 * world key lookups.
 */
public class WorldService {

    private final MinecraftServer server;
    private final Fantasy fantasy;

    // Temporary world handles
    private RuntimeLevelHandle overworldHandle;
    private RuntimeLevelHandle netherHandle;
    private RuntimeLevelHandle endHandle;

    // Old world handles (to delete after teleporting to new world)
    private RuntimeLevelHandle oldOverworldHandle;
    private RuntimeLevelHandle oldNetherHandle;
    private RuntimeLevelHandle oldEndHandle;

    // Current run seed
    private long currentSeed;

    public WorldService(MinecraftServer server) {
        this.server = server;
        this.fantasy = Fantasy.get(server);
    }

    /**
     * Creates the three temporary dimensions: Overworld, Nether, and End.
     *
     * @return The generated seed used for all worlds
     */
    public long createTemporaryWorlds() {
        // Generate new seed for this run
        currentSeed = new Random().nextLong();

        // Get the difficulty from settings
        Difficulty serverDifficulty = Settings.getInstance().getDifficulty();

        // Create temporary Overworld
        ServerLevel vanillaOverworld = server.overworld();
        RuntimeLevelConfig overworldConfig = new RuntimeLevelConfig()
                .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(serverDifficulty)
                .setGameRule(GameRules.ADVANCE_TIME, true).setSeed(currentSeed)
                .setGenerator(vanillaOverworld.getChunkSource().getGenerator());

        overworldHandle = fantasy.openTemporaryLevel(overworldConfig);
        ServerLevel tempWorld = overworldHandle.asLevel();
        ServerClockManager clockManager = tempWorld.getServer().clockManager();
        Holder<WorldClock> clock = tempWorld.dimensionTypeRegistration().value().defaultClock().orElseThrow();

        // Set time to 0 (day)
        clockManager.setTotalTicks(clock, 0L);
        SoulLink.LOGGER.info("Created temporary overworld: {}",
                overworldHandle.getRegistryKey().identifier());

        // Create temporary Nether
        ServerLevel vanillaNether = server.getLevel(Level.NETHER);
        if (vanillaNether != null) {
            RuntimeLevelConfig netherConfig =
                    new RuntimeLevelConfig().setDimensionType(BuiltinDimensionTypes.NETHER)
                            .setDifficulty(serverDifficulty).setSeed(currentSeed)
                            .setGenerator(vanillaNether.getChunkSource().getGenerator());

            netherHandle = fantasy.openTemporaryLevel(netherConfig);
            SoulLink.LOGGER.info("Created temporary nether: {}",
                    netherHandle.getRegistryKey().identifier());
        }

        // Create temporary End
        ServerLevel vanillaEnd = server.getLevel(Level.END);
        if (vanillaEnd != null) {
            RuntimeLevelConfig endConfig =
                    new RuntimeLevelConfig().setDimensionType(BuiltinDimensionTypes.END)
                            .setDifficulty(serverDifficulty).setSeed(currentSeed)
                            .setGenerator(vanillaEnd.getChunkSource().getGenerator());

            endHandle = fantasy.openTemporaryLevel(endConfig);
            SoulLink.LOGGER.info("Created temporary end: {}",
                    endHandle.getRegistryKey().identifier());
        }

        return currentSeed;
    }

    /**
     * Saves current world handles as "old" for later deletion. Call this before creating new
     * worlds.
     */
    public void saveCurrentWorldsAsOld() {
        oldOverworldHandle = overworldHandle;
        oldNetherHandle = netherHandle;
        oldEndHandle = endHandle;
        overworldHandle = null;
        netherHandle = null;
        endHandle = null;
    }

    private void safeDeleteWorld(RuntimeLevelHandle handle, String worldName) {
        if (handle != null) {
            try {
                handle.delete();
                SoulLink.LOGGER.info("Deleted {}", worldName);
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete {}", worldName, e);
            }
        }
    }

    /**
     * Deletes the old world handles saved from previous run.
     */
    public void deleteOldWorlds() {
        safeDeleteWorld(oldOverworldHandle, "old temporary overworld");
        oldOverworldHandle = null;

        safeDeleteWorld(oldNetherHandle, "old temporary nether");
        oldNetherHandle = null;

        safeDeleteWorld(oldEndHandle, "old temporary end");
        oldEndHandle = null;
    }

    /**
     * Deletes all current temporary worlds.
     */
    public void deleteCurrentWorlds() {
        safeDeleteWorld(overworldHandle, "temporary overworld");
        overworldHandle = null;

        safeDeleteWorld(netherHandle, "temporary nether");
        netherHandle = null;

        safeDeleteWorld(endHandle, "temporary end");
        endHandle = null;
    }

    /**
     * Checks if a world key belongs to one of our temporary dimensions.
     */
    public boolean isTemporaryWorld(ResourceKey<Level> worldKey) {
        if (worldKey == null)
            return false;

        ResourceKey<Level> tempOverworld = getOverworldKey();
        ResourceKey<Level> tempNether = getNetherKey();
        ResourceKey<Level> tempEnd = getEndKey();

        return worldKey.equals(tempOverworld) || worldKey.equals(tempNether)
                || worldKey.equals(tempEnd);
    }

    // ==================== GETTERS ====================

    public ServerLevel getOverworld() {
        return overworldHandle != null ? overworldHandle.asLevel() : null;
    }

    public ServerLevel getNether() {
        return netherHandle != null ? netherHandle.asLevel() : null;
    }

    public ServerLevel getEnd() {
        return endHandle != null ? endHandle.asLevel() : null;
    }

    public ResourceKey<Level> getOverworldKey() {
        return overworldHandle != null ? overworldHandle.getRegistryKey() : null;
    }

    public ResourceKey<Level> getNetherKey() {
        return netherHandle != null ? netherHandle.getRegistryKey() : null;
    }

    public ResourceKey<Level> getEndKey() {
        return endHandle != null ? endHandle.getRegistryKey() : null;
    }

    public long getCurrentSeed() {
        return currentSeed;
    }

    /**
     * Gets the linked nether world for portal travel from a given world.
     */
    public ServerLevel getLinkedNetherWorld(ServerLevel fromWorld) {
        if (fromWorld == null)
            return null;

        ResourceKey<Level> fromKey = fromWorld.dimension();

        if (fromKey.equals(getOverworldKey())) {
            return getNether();
        } else if (fromKey.equals(getNetherKey())) {
            return getOverworld();
        }

        return null;
    }
}
