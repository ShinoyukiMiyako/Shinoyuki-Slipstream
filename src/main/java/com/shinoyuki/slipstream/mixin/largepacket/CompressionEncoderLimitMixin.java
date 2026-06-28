package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 8 MiB oversize-debug threshold in the zlib encoder's {@code encode} (the
 * {@code i > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH} check), so large zlib-compressed packets are sent
 * without tripping it. Coexists with the telemetry {@code CompressionEncoderMixin} (different injector).
 */
@Mixin(CompressionEncoder.class)
public class CompressionEncoderLimitMixin {

    @ModifyConstant(method = "encode", constant = @Constant(intValue = 8388608))
    private int slipstream$maxUncompressed(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
