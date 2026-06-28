package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.compress.WireCodec;
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

import java.util.concurrent.CompletableFuture;

/**
 * Outbound tap #2 plus the originating side of the serialize-once broadcast share. Telemetry records the
 * compressed (on-wire) size paired with the packet type stashed by {@link PacketEncoderMixin}. For the
 * originating chunk send, the compressed frame is handed to the packet instance's future so the deferred
 * recipients (see ChunkMapMixin / ConnectionMixin) can reuse it instead of running the Deflater again.
 */
@Mixin(CompressionEncoder.class)
public abstract class CompressionEncoderMixin {

    @Inject(method = "encode", at = @At("TAIL"))
    private void slipstream$measureCompressed(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        ConnectionStats stats = ctx.channel().attr(PacketTelemetry.STATS_KEY).get();
        if (stats == null) {
            return;
        }
        if (SlipstreamConfig.chunkSerializeOnce()) {
            ChunkEncodeProbe probe = stats.pendingChunk();
            if (probe != null) {
                CompletableFuture<byte[]> future = probe.slipstream$getOrCreateFrameFuture();
                // complete() returns true only for the thread that actually populates the frame -- the
                // originating recipient -- so it alone stamps the codec the cohort-aware flush compares against.
                if (future.complete(ByteBufUtil.getBytes(out))) {
                    probe.slipstream$setFrameCodec(WireCodec.ZLIB);
                }
            }
        }
        if (SlipstreamConfig.telemetryEnabled()) {
            String label = stats.pendingType();
            if (label != null) {
                PacketTelemetry telemetry = PacketTelemetry.get();
                telemetry.global(label).addCompressed(out.readableBytes());
                if (SlipstreamConfig.trackPerPlayer()) {
                    stats.type(label).addCompressed(out.readableBytes());
                }
            }
        }
        stats.clearPending();
    }
}
