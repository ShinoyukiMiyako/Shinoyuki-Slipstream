package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Outbound tap #2. Runs immediately after {@link PacketEncoderMixin} for the same packet on the same
 * event-loop thread (synchronous pipeline handoff), so the per-connection pending slot still holds this
 * packet's type. {@code out} is the on-wire frame body (VarInt dataLength + deflated bytes, or VarInt 0 +
 * raw bytes for sub-threshold packets), i.e. the true post-compression size.
 */
@Mixin(CompressionEncoder.class)
public abstract class CompressionEncoderMixin {

    @Inject(method = "encode", at = @At("TAIL"))
    private void slipstream$measureCompressed(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        if (!SlipstreamConfig.telemetryEnabled()) {
            return;
        }
        ConnectionStats stats = ctx.channel().attr(PacketTelemetry.STATS_KEY).get();
        if (stats == null) {
            return;
        }
        String label = stats.pendingType();
        if (label == null) {
            return;
        }
        stats.clearPending();

        int compressed = out.readableBytes();
        PacketTelemetry telemetry = PacketTelemetry.get();
        telemetry.global(label).addCompressed(compressed);
        if (SlipstreamConfig.trackPerPlayer()) {
            stats.type(label).addCompressed(compressed);
        }
    }
}
