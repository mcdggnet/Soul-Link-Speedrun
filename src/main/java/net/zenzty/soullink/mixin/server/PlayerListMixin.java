package net.zenzty.soullink.mixin.server;

import net.minecraft.server.players.PlayerList;
import net.zenzty.soullink.server.settings.Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Tells joining clients the world is hardcore when the Hardcore Hearts setting is on. The client
 * uses that one flag from the login packet to pick the hardcore heart texture; the server keeps its
 * real (non-hardcore) level data, so death is still handled by the run.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @ModifyArg(
            method = "placeNewPlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;<init>(IZLjava/util/Set;IIIZZZLnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;ZZ)V"),
            index = 1)
    private boolean hardcoreHearts(boolean hardcore) {
        return hardcore || Settings.getInstance().isHardcoreHearts();
    }
}
