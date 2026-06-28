package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 2 MiB "Chunk Packet trying to allocate too much memory" guard in the chunk-data read constructor, so
 * a heavily-modded chunk (many block entities / large palettes) can be received.
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public class ChunkPacketDataLimitMixin {

    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;II)V", constant = @Constant(intValue = 2097152))
    private int slipstream$maxChunkData(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
