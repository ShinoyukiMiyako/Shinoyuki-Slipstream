package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise vanilla's 8 MiB uncompressed-size ceiling in the zlib decoder (the {@code i > 8388608} reject in
 * {@code decode}). Only affects zlib connections; zstd connections run {@code ZstdDecoder} with its own
 * configurable bound.
 */
@Mixin(CompressionDecoder.class)
public class CompressionDecoderLimitMixin {

    @ModifyConstant(method = "decode", constant = @Constant(intValue = 8388608))
    private int slipstream$maxUncompressed(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
