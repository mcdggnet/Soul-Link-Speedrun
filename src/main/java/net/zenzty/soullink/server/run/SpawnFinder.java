package net.zenzty.soullink.server.run;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.zenzty.soullink.SoulLink;

/**
 * Handles incremental spawn location search to prevent server freezes during world generation. Uses
 * a spiral pattern search optimized with biome pre-checking.
 */
public class SpawnFinder {

    private static final int MAX_SEARCH_RADIUS = 500;
    private static final int SEARCH_STEP = 32;
    private static final int CHECKS_PER_TICK = 5;

    // Incremental spawn search state
    private int searchRadius = 0;
    private int searchSide = 0;
    private int searchOffset = 0;
    private BlockPos validSpawnPos = null;

    /**
     * Resets the spawn search state for a new search.
     */
    public void reset() {
        searchRadius = 0;
        searchSide = 0;
        searchOffset = -SEARCH_STEP;
        validSpawnPos = null;
    }

    /**
     * Gets the found spawn position, or null if not found yet.
     */
    public BlockPos getSpawnPos() {
        return validSpawnPos;
    }

    /**
     * Checks if a valid spawn has been found.
     */
    public boolean hasFoundSpawn() {
        return validSpawnPos != null;
    }

    /**
     * Processes one step of the generation (checks a few spawn locations per tick).
     *
     * @param world The world to search in
     * @param server The server for broadcasting progress
     * @return true if spawn found or search exhausted, false if still searching
     */
    public boolean processStep(ServerLevel world, MinecraftServer server) {
        int checksThisTick = 0;

        // Check multiple spots per tick to speed up without freezing
        while (checksThisTick < CHECKS_PER_TICK) {
            int[] pos = getNextSearchPos();

            if (pos == null) {
                // Exhausted all search positions - no valid spawn found
                SoulLink.LOGGER.warn("No valid spawn found after exhaustive search");
                return true;
            }

            BlockPos candidate = checkSpawnLocation(world, pos[0], pos[1]);
            if (candidate != null) {
                // Found valid spawn!
                validSpawnPos = candidate;
                SoulLink.LOGGER.info("Found land spawn at {} after searching radius {}", candidate,
                        searchRadius);
                return true;
            }

            checksThisTick++;
        }

        // Update action bar with progress for all players
        if (server.getTickCount() % 10 == 0) {
            int progress = Math.min(100, (searchRadius * 100) / MAX_SEARCH_RADIUS);
            Component statusText = Component.empty().append(Component.literal("⟳ ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("Finding spawn... " + progress + "%")
                            .withStyle(ChatFormatting.GRAY));

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendOverlayMessage(statusText);
            }
        }

        return false;
    }

    /**
     * Checks if coordinates are in an ocean biome WITHOUT loading the chunk.
     */
    private boolean isOceanBiome(ServerLevel world, int x, int z) {
        try {
            BiomeManager biomeAccess = world.getBiomeManager();
            BlockPos samplePos = new BlockPos(x, 64, z);
            Holder<Biome> biome = biomeAccess.getBiome(samplePos);
            return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
        } catch (Exception e) {
            SoulLink.LOGGER.trace("Error checking ocean biome at {}, {}: {}", x, z, e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a location is suitable for spawning (solid ground, not water/lava).
     */
    private BlockPos checkSpawnLocation(ServerLevel world, int x, int z) {
        // OPTIMIZATION: Check biome first before loading the chunk
        if (isOceanBiome(world, x, z)) {
            return null;
        }

        // Force chunk to load (only for non-ocean biomes now)
        world.getChunk(x >> 4, z >> 4);

        int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        if (y < 50 || y > 200) {
            return null;
        }

        BlockPos groundPos = new BlockPos(x, y - 1, z);
        BlockPos standPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);
        BlockState groundState = world.getBlockState(groundPos);
        BlockState standState = world.getBlockState(standPos);
        BlockState headState = world.getBlockState(headPos);

        if (groundState.isRedstoneConductor(world, groundPos) && !groundState.is(Blocks.WATER)
                && !groundState.is(Blocks.LAVA) && !groundState.is(Blocks.ICE)
                && !groundState.is(Blocks.PACKED_ICE) && !groundState.is(Blocks.BLUE_ICE)
                && standState.isAir() && headState.isAir()) {
            return new BlockPos(x, y, z);
        }

        return null;
    }

    /**
     * Gets the next search position in the spiral pattern.
     */
    private int[] getNextSearchPos() {
        while (searchRadius <= MAX_SEARCH_RADIUS) {
            // Handle the center point (radius 0)
            if (searchRadius == 0) {
                searchRadius = SEARCH_STEP;
                return new int[] {0, 0};
            }

            // Calculate position based on current side and offset
            int x, z;
            switch (searchSide) {
                case 0:
                    x = searchOffset;
                    z = -searchRadius;
                    break; // North edge
                case 1:
                    x = searchRadius;
                    z = searchOffset;
                    break; // East edge
                case 2:
                    x = searchOffset;
                    z = searchRadius;
                    break; // South edge
                default:
                    x = -searchRadius;
                    z = searchOffset;
                    break; // West edge
            }

            // Move to next position
            searchOffset += SEARCH_STEP;

            // Check if we've completed this side
            if (searchOffset > searchRadius) {
                searchOffset = -searchRadius;
                searchSide++;

                // Check if we've completed all sides at this radius
                if (searchSide >= 4) {
                    searchSide = 0;
                    searchOffset = -searchRadius - SEARCH_STEP;
                    searchRadius += SEARCH_STEP;
                }
            }

            // Skip corner duplicates
            if (searchRadius >= SEARCH_STEP && Math.abs(x) != searchRadius
                    && Math.abs(z) != searchRadius) {
                continue;
            }

            return new int[] {x, z};
        }

        return null; // Exhausted search
    }

    public int getCurrentSearchRadius() {
        return searchRadius;
    }
}
