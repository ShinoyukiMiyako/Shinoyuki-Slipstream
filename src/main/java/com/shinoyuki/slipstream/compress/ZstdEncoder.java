package com.shinoyuki.slipstream.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketCapture;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.concurrent.CompletableFuture;

/**
 * Outbound zstd compressor, installed in place of vanilla {@code CompressionEncoder} ("compress") for
 * connections that negotiated zstd. Produces the same envelope as vanilla (via {@link ZstdFrameCodec}) and
 * carries the identical serialize-once + telemetry side effects {@code CompressionEncoderMixin} performs for
 * zlib, so both features behave the same on a zstd connection (where the vanilla encoder, and thus its mixin,
 * never runs).
 */
public final class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    private int threshold;

    public ZstdEncoder(int threshold) {
        this.threshold = threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) {
        byte[] payload = new byte[in.readableBytes()];
        in.readBytes(payload);
        ZstdCompressCtx cctx = ZstdContextPool.compressor(SlipstreamConfig.zstdLevel());
        byte[] frame = ZstdFrameCodec.compress(payload, this.threshold, cctx);
        out.writeBytes(frame);

        ConnectionStats stats = ctx.channel().attr(PacketTelemetry.STATS_KEY).get();
        if (PacketCapture.active()) {
            // 出站抓包: pendingType 由 PacketEncoderMixin 同线程刚设上; payload 即压缩前原始字节 (仿真燃料)。
            PacketCapture.record('O', stats != null ? stats.pendingType() : null, payload.length, frame.length, payload);
        }
        if (stats == null) {
            return;
        }
        if (SlipstreamConfig.chunkSerializeOnce()) {
            ChunkEncodeProbe probe = stats.pendingChunk();
            if (probe != null) {
                CompletableFuture<byte[]> future = probe.slipstream$getOrCreateFrameFuture();
                // complete() returns true only for the thread that actually populates it; that thread is the
                // originating recipient, so it alone stamps the frame's codec for the cohort-aware flush.
                if (future.complete(frame)) {
                    probe.slipstream$setFrameCodec(WireCodec.ZSTD);
                }
            }
        }
        if (SlipstreamConfig.telemetryEnabled()) {
            String label = stats.pendingType();
            if (label != null) {
                PacketTelemetry telemetry = PacketTelemetry.get();
                telemetry.global(label).addCompressed(frame.length);
                if (SlipstreamConfig.trackPerPlayer()) {
                    stats.type(label).addCompressed(frame.length);
                }
            }
        }
        stats.clearPending();
    }
}
