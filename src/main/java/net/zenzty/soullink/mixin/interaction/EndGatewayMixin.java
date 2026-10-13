package net.zenzty.soullink.mixin.interaction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes End gateways work in run Ends. A gateway spawned by the dragon fight has no exit until
 * first used, and vanilla only creates one (the outer-island exit gateway) when the level is
 * exactly minecraft:the_end, so in a run End (soullink:...) a gateway did nothing - players and
 * pearls went in and stayed put. Treat any level of the End dimension type as the End here.
 */
@Mixin(TheEndGatewayBlockEntity.class)
public abstract class EndGatewayMixin {

    @Redirect(
            method = "getPortalPosition",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> runEndIsTheEnd(ServerLevel level) {
        return level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END) ? Level.END : level.dimension();
    }
}
