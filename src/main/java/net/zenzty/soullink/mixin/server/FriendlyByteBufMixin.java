package net.zenzty.soullink.mixin.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.zenzty.soullink.server.run.ClientDimensionNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The clock fix, part one: dimension names written straight to a packet (login and respawn
 * packets, the login packet's level list, death positions) go out under their vanilla names. See
 * ClientDimensionNames.
 */
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufMixin {

    @ModifyVariable(method = "writeResourceKey", at = @At("HEAD"), argsOnly = true)
    private ResourceKey<?> clientDimensionName(ResourceKey<?> key) {
        return ClientDimensionNames.forClient(key);
    }
}
