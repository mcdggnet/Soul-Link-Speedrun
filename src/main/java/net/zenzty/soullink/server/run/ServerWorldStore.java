package net.zenzty.soullink.server.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.zenzty.soullink.SoulLink;

/**
 * What Server Mode needs to remember about its world between restarts: which generation of
 * persistent worlds is current (their registry keys derive from it), the seed they were built
 * with, and the spawn that was found for them. Lives beside the settings file in the world save.
 */
public final class ServerWorldStore {

    private static final String FILENAME = "soullink_server_world.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The persisted record. {@code spawn} is null until the spawn search has finished. */
    public record ServerWorldState(int generation, long seed, BlockPos spawn) {
        public ServerWorldState withSpawn(BlockPos found) {
            return new ServerWorldState(generation, seed, found);
        }
    }

    private final Path path;

    public ServerWorldStore(MinecraftServer server) {
        this.path = server.getWorldPath(LevelResource.ROOT).resolve(FILENAME);
    }

    /** Returns the stored state, or null when Server Mode has never built a world here. */
    public ServerWorldState load() {
        if (!Files.isRegularFile(path)) return null;
        try {
            Object parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (!(parsed instanceof Data data)) return null;
            BlockPos spawn = data.spawnX != null && data.spawnY != null && data.spawnZ != null
                    ? new BlockPos(data.spawnX, data.spawnY, data.spawnZ)
                    : null;
            return new ServerWorldState(data.generation, data.seed, spawn);
        } catch (Exception e) {
            SoulLink.LOGGER.warn("Could not read server world state {}: {}", path, e.getMessage());
            return null;
        }
    }

    public void save(ServerWorldState state) {
        Data data = new Data();
        data.generation = state.generation();
        data.seed = state.seed();
        if (state.spawn() != null) {
            data.spawnX = state.spawn().getX();
            data.spawnY = state.spawn().getY();
            data.spawnZ = state.spawn().getZ();
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not write server world state {}: {}", path, e.getMessage());
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not delete server world state {}: {}", path, e.getMessage());
        }
    }

    /** JSON shape. Read and written by Gson via reflection. */
    private static class Data {
        int generation;
        long seed;
        Integer spawnX;
        Integer spawnY;
        Integer spawnZ;
    }
}
