package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.WireCodec;
import com.shinoyuki.slipstream.compress.ZstdDecoder;
import com.shinoyuki.slipstream.compress.ZstdEncoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In-place wire-codec swap. After vanilla {@code Connection.setupCompression} installs the zlib
 * "compress"/"decompress" handlers, replace them with zstd equivalents for connections that negotiated zstd
 * during login (see {@link ServerLoginNegotiationMixin} / {@link ClientHandshakeNegotiationMixin}). The handler
 * names are preserved, so the rest of the pipeline (length splitter, cipher) and the serialize-once broadcast
 * share -- which resolves {@code pipeline.context("compress")} -- are unaffected. Every other connection keeps
 * vanilla zlib untouched. setupCompression is called exactly once per connection with threshold >= 0, so this
 * runs once; the already-swapped guard is purely defensive.
 */
@Mixin(Connection.class)
public abstract class ConnectionCompressionMixin {

    @Shadow private Channel channel;

    @Shadow public abstract boolean isMemoryConnection();

    @Inject(method = "setupCompression(IZ)V", at = @At("TAIL"))
    private void slipstream$swapToZstd(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        if (threshold < 0 || isMemoryConnection()) {
            return;   // compression disabled, or loopback has no compress handlers to replace.
        }
        if (ConnectionCodec.of(this.channel) != WireCodec.ZSTD) {
            return;
        }
        ChannelPipeline pipeline = this.channel.pipeline();
        if (pipeline.get("compress") instanceof ZstdEncoder) {
            return;   // already swapped.
        }
        if (pipeline.get("compress") != null) {
            pipeline.replace("compress", "compress", new ZstdEncoder(threshold));
        }
        if (pipeline.get("decompress") != null) {
            pipeline.replace("decompress", "decompress", new ZstdDecoder(threshold, validateDecompressed));
        }
        Slipstream.LOGGER.debug("[Slipstream] wire codec swapped to zstd (threshold={})", threshold);
    }
}
