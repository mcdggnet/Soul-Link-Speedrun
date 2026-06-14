package net.zenzty.soullink.mixin.server;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to get the bar from Raid. Needed to clear the raid boss bar when starting a new
 * run, as the vanilla raid bossbar persists if players start a new run before the game cleans it up
 * naturally.
 */
@Mixin(Raid.class)
public interface RaidAccessor {

    @Accessor("raidEvent")
    ServerBossEvent getBar();
}
