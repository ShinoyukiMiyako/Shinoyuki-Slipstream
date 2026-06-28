package com.shinoyuki.slipstream.compress;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Inbound zstd decompressor, mirror image of vanilla {@code CompressionDecoder}, installed in place of
 * "decompress" for zstd-negotiated connections. The length splitter upstream delivers exactly one
 * length-delimited frame per call, so reading the whole accumulated buffer is one frame (as vanilla does).
 */
public final class ZstdDecoder extends ByteToMessageDecoder {

    private int threshold;
    private boolean validate;

    public ZstdDecoder(int threshold, boolean validate) {
        this.threshold = threshold;
        this.validate = validate;
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
        byte[] payload = ZstdFrameCodec.decompress(frame, this.threshold, this.validate,
                SlipstreamConfig.zstdMaxUncompressedBytes(), ZstdContextPool.decompressor());
        out.add(Unpooled.wrappedBuffer(payload));
    }
}
