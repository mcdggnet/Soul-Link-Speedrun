package net.zenzty.soullink.server.run;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The clock fix. A clock only shows the time in a dimension named exactly minecraft:overworld (the
 * vanilla clock model selects on the dimension's name; anything else spins), and Server Mode's
 * worlds are named soullink:server&lt;N&gt;_overworld / _nether / _end. While this is on, every
 * dimension name sent to clients is rewritten to the vanilla one (minecraft:overworld /
 * the_nether / the_end), so clients treat the worlds as vanilla's: clocks work, and compasses,
 * lodestones and death positions stay consistent because every encoded name goes through here
 * (FriendlyByteBufMixin, ResourceKeyMixin). Nothing changes server-side.
 *
 * <p>Side effect: a world reset (and a move from the lobby into the world) now keeps the same name
 * on the client, so the client keeps its loaded level instead of building a new one. The server
 * clears it anyway (forget-chunk and remove-entity packets on leaving a level), as for a death
 * respawn in the same dimension. If that ever misbehaves, /clockfix off turns this off; it applies
 * to each player from their next join or dimension change. Persisted with the settings.
 */
public final class ClientDimensionNames {

    private static volatile boolean enabled = true;

    private ClientDimensionNames() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * The name a client is told for this key: the vanilla dimension for a Server Mode world while
     * the fix is on, otherwise the key itself. Runs on network threads, so it reads nothing but the
     * key and the flag.
     */
    @SuppressWarnings("unchecked")
    public static <T> ResourceKey<T> forClient(ResourceKey<T> key) {
        if (!enabled || key == null || !key.isFor(Registries.DIMENSION)) return key;
        Identifier id = key.identifier();
        String path = id.getPath();
        if (!"soullink".equals(id.getNamespace()) || !path.startsWith("server")) return key;
        if (path.endsWith("_overworld")) return (ResourceKey<T>) Level.OVERWORLD;
        if (path.endsWith("_nether")) return (ResourceKey<T>) Level.NETHER;
        if (path.endsWith("_end")) return (ResourceKey<T>) Level.END;
        return key;
    }
}
