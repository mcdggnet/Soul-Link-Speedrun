package net.zenzty.soullink.mixin.server;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.zenzty.soullink.server.run.ClientDimensionNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The clock fix, part two: the network codec for dimension keys (GlobalPos.STREAM_CODEC is built on
 * it, so the spawn point, lodestone compasses and other positions) encodes vanilla names. Decoding
 * is untouched. See ClientDimensionNames.
 */
@Mixin(ResourceKey.class)
public abstract class ResourceKeyMixin {

    @Inject(method = "streamCodec", at = @At("RETURN"), cancellable = true)
    private static <T> void clientDimensionNames(
            ResourceKey<? extends Registry<T>> registry,
            CallbackInfoReturnable<StreamCodec<ByteBuf, ResourceKey<T>>> cir) {
        if (!registry.equals(Registries.DIMENSION)) return;
        StreamCodec<ByteBuf, ResourceKey<T>> original = cir.getReturnValue();
        cir.setReturnValue(new StreamCodec<>() {
            @Override
            public ResourceKey<T> decode(ByteBuf buf) {
                return original.decode(buf);
            }

            @Override
            public void encode(ByteBuf buf, ResourceKey<T> key) {
                original.encode(buf, ClientDimensionNames.forClient(key));
            }
        });
    }
}
