package net.zenzty.soullink.mixin.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to set the EnderDragonFight on ServerWorld. Needed because Fantasy temporary End
 * worlds don't automatically get one.
 */
@Mixin(ServerLevel.class)
public interface ServerWorldAccessor {

    @Accessor("dragonFight")
    EnderDragonFight getEnderDragonFight();

    @Mutable
    @Accessor("dragonFight")
    void setEnderDragonFight(EnderDragonFight fight);
}
