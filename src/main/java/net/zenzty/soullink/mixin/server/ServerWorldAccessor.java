package net.zenzty.soullink.mixin.server;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to set the EnderDragonFight on ServerWorld. Needed because Fantasy temporary End
 * worlds don't automatically get one. Also sets the custom spawners (phantoms, patrols, ...), which
 * Fantasy builds every runtime world without.
 */
@Mixin(ServerLevel.class)
public interface ServerWorldAccessor {

    @Accessor("dragonFight")
    EnderDragonFight getEnderDragonFight();

    @Mutable
    @Accessor("dragonFight")
    void setEnderDragonFight(EnderDragonFight fight);

    @Mutable
    @Accessor("customSpawners")
    void setCustomSpawners(List<CustomSpawner> spawners);
}
