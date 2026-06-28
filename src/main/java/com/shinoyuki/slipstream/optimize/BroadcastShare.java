package com.shinoyuki.slipstream.optimize;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

/**
 * Helpers for the serialize-once broadcast share: a thread-local bypass (so the fallback normal send
 * does not re-enter the intercept) and the pre-compressed frame writer.
 */
public final class BroadcastShare {

    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

    public static boolean bypassing() {
        return BYPASS.get() != null;
    }

    public static void markBypass(boolean on) {
        if (on) {
            BYPASS.set(Boolean.TRUE);
        } else {
            BYPASS.remove();
        }
    }

    /**
     * Write an already-compressed frame to a recipient, entering the pipeline just after the
     * compression stage so it skips PacketEncoder + CompressionEncoder but is still length-prefixed
     * (prepender) and encrypted (the per-connection CipherEncoder) before the socket. Runs on the
     * channel event loop.
     */
    public static void sendPreCompressed(Channel channel, ChannelHandlerContext compressCtx,
                                         ClientboundLevelChunkWithLightPacket packet, byte[] frame, int uncompressed) {
        ByteBuf buf = channel.alloc().buffer(frame.length);
        buf.writeBytes(frame);
        compressCtx.writeAndFlush(buf);

        // Deferred sends bypass the encoder telemetry taps, so account for them here to keep the report whole.
        PacketTelemetry telemetry = PacketTelemetry.get();
        telemetry.recordChunkCompressSkipped();
        if (!SlipstreamConfig.telemetryEnabled()) {
            return;
        }
        String label = PacketTelemetry.label(packet.getClass());
        telemetry.global(label).addUncompressed(uncompressed);
        telemetry.global(label).addCompressed(frame.length);
        ConnectionStats stats = channel.attr(PacketTelemetry.STATS_KEY).get();
        if (stats != null) {
            if (SlipstreamConfig.trackChunkDedup()) {
                telemetry.recordChunkSend(packet.getX(), packet.getZ(), stats.id(), false);
            }
            if (SlipstreamConfig.trackPerPlayer()) {
                stats.type(label).addUncompressed(uncompressed);
                stats.type(label).addCompressed(frame.length);
            }
        }
    }

    private BroadcastShare() {
    }
}
