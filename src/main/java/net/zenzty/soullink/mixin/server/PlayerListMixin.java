package net.zenzty.soullink.mixin.server;

import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelData;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells joining clients the world is hardcore when the Hardcore Hearts setting is on. The client
 * uses that one flag from the login packet to pick the hardcore heart texture; the server keeps its
 * real (non-hardcore) level data, so death is still handled by the run.
 *
 * <p>Also gives clients in a run overworld that world's spawn, so compasses work there.
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

    /**
     * The spawn vanilla sends is the server's, tagged minecraft:overworld, and a compass only points
     * at a spawn in the holder's own dimension, so in a run overworld every compass spun. Follow it
     * with the run world's own spawn. Login, respawn and dimension changes all come through here.
     */
    @Inject(method = "sendLevelInfo", at = @At("TAIL"))
    private void sendRunWorldSpawn(ServerPlayer player, ServerLevel level, CallbackInfo ci) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }
        LevelData.RespawnData spawn = runManager.getCompassSpawn(level);
        if (spawn != null) {
            player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(spawn));
        }
    }
}
