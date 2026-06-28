package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Outbound tap #2 plus the P0-safe serialize-once optimization.
 *
 * <p>Telemetry: records the compressed (on-wire) size, paired with the packet type stashed by
 * {@link PacketEncoderMixin} on the per-connection {@link ConnectionStats}.
 *
 * <p>Optimization ({@code optimize.chunkSerializeOnce}, default off): vanilla shares ONE chunk packet
 * instance across all recipients of a synchronous broadcast pass, yet compresses it once per connection.
 * We compress it on the first encode, cache the compressed frame on the packet instance, and write that
 * cached frame directly for the other recipients, skipping the Deflater. The cache is the chunk snapshot
 * itself (the packet instance), so there is nothing to invalidate; it dies with the instance. The shared
 * bytes are pre-encryption, so each connection's CipherEncoder still encrypts its own copy downstream.
 * Anything not cached falls through to vanilla compression unchanged.
 */
@Mixin(CompressionEncoder.class)
public abstract class CompressionEncoderMixin {

    @Inject(method = "encode", at = @At("HEAD"), cancellable = true)
    private void slipstream$reuseCompressedFrame(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        if (!SlipstreamConfig.chunkSerializeOnce()) {
            return;
        }
        ConnectionStats stats = ctx.channel().attr(PacketTelemetry.STATS_KEY).get();
        if (stats == null) {
            return;
        }
        ChunkEncodeProbe probe = stats.pendingChunk();
        if (probe == null) {
            return;
        }
        byte[] cached = probe.slipstream$compressedFrame();
        if (cached == null) {
            return;
        }
        // Cache hit: this chunk instance was already compressed for an earlier recipient of the same
        // broadcast pass. Reuse the frame and skip the Deflater. (in is released by MessageToByteEncoder.)
        out.writeBytes(cached);
        slipstream$record(stats, cached.length);
        PacketTelemetry.get().recordChunkCompressSkipped();
        stats.clearPending();
        ci.cancel();
    }

    @Inject(method = "encode", at = @At("TAIL"))
    private void slipstream$measureCompressed(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        ConnectionStats stats = ctx.channel().attr(PacketTelemetry.STATS_KEY).get();
        if (stats == null) {
            return;
        }
        // First encode of a chunk instance: cache its compressed frame for the rest of the broadcast pass.
        if (SlipstreamConfig.chunkSerializeOnce()) {
            ChunkEncodeProbe probe = stats.pendingChunk();
            if (probe != null && probe.slipstream$compressedFrame() == null) {
                probe.slipstream$cacheCompressedFrame(ByteBufUtil.getBytes(out));
            }
        }
        if (SlipstreamConfig.telemetryEnabled()) {
            slipstream$record(stats, out.readableBytes());
        }
        stats.clearPending();
    }

    private static void slipstream$record(ConnectionStats stats, int compressedBytes) {
        String label = stats.pendingType();
        if (label == null) {
            return;
        }
        PacketTelemetry telemetry = PacketTelemetry.get();
        telemetry.global(label).addCompressed(compressedBytes);
        if (SlipstreamConfig.trackPerPlayer()) {
            stats.type(label).addCompressed(compressedBytes);
        }
    }
}
