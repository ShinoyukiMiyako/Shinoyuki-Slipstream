package com.shinoyuki.slipstream.compress;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Inbound adaptive decompressor, installed in place of "decompress" whenever zstd is enabled locally. It accepts
 * EITHER a zstd frame or a vanilla zlib frame (see {@link ZstdFrameCodec}), so a peer that did not swap to zstd is
 * still decoded correctly instead of crashing the connection. The length splitter upstream delivers exactly one
 * length-delimited frame per call. A zlib fallback is logged once per connection -- it means the swap was
 * asymmetric (the peer's encoder stayed on zlib).
 */
public final class ZstdDecoder extends ByteToMessageDecoder {

    private int threshold;
    private boolean validate;
    private final boolean expectZstd;
    private boolean loggedZlibFallback;

    public ZstdDecoder(int threshold, boolean validate, boolean expectZstd) {
        this.threshold = threshold;
        this.validate = validate;
        this.expectZstd = expectZstd;
    }

    public void setThreshold(int threshold, boolean validate) {
        this.threshold = threshold;
        this.validate = validate;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() == 0) {
            return;
        }
        byte[] frame = new byte[in.readableBytes()];
        in.readBytes(frame);
        if (this.expectZstd && !this.loggedZlibFallback && ZstdFrameCodec.isZlibFallback(frame)) {
            this.loggedZlibFallback = true;
            Slipstream.LOGGER.warn("[Slipstream] zstd-negotiated peer (inbound validate={}) sent a zlib frame; its encoder "
                    + "did not swap to zstd -- decoding as zlib (asymmetric swap). Connection stays up.", this.validate);
        }
        byte[] payload = ZstdFrameCodec.decompress(frame, this.threshold, this.validate,
                SlipstreamConfig.zstdMaxUncompressedBytes(), ZstdContextPool.decompressor(), ZstdContextPool.inflater());
        out.add(Unpooled.wrappedBuffer(payload));
    }
}
