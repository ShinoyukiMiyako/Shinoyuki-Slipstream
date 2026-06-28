package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the inbound length-prefix limit. Vanilla {@code decode} reads the frame length into a {@code new byte[3]}
 * (3-byte varint = 21 bits = 2 MiB) and rejects anything wider; raising the 3 to a 5-byte varint admits packets
 * up to a 32-bit length. This is the load-bearing change for receiving large packets.
 */
@Mixin(Varint21FrameDecoder.class)
public class Varint21FrameDecoderLimitMixin {

    @ModifyConstant(method = "decode", constant = @Constant(intValue = 3))
    private int slipstream$frameVarIntBytes(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.FRAME_VARINT_BYTES : original;
    }
}
