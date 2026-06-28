package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Outbound counterpart to {@link Varint21FrameDecoderLimitMixin}: vanilla {@code encode} asserts the length
 * varint fits in 3 bytes; raise it so a length needing up to 5 bytes can be written for large outbound packets.
 */
@Mixin(Varint21LengthFieldPrepender.class)
public class Varint21LengthFieldPrependerLimitMixin {

    @ModifyConstant(method = "encode", constant = @Constant(intValue = 3))
    private int slipstream$frameVarIntBytes(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.FRAME_VARINT_BYTES : original;
    }
}
