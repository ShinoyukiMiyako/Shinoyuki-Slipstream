package com.shinoyuki.slipstream.optimize;

import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.WireCodec;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialize-once broadcast share, ordering-preserving form. While a deferred recipient's
 * ChunkMap.playerLoadedChunk runs (main thread), every send it makes to that recipient is captured here
 * instead of sent. Once the chunk's compressed frame is ready, the captured packets are flushed in their
 * original order on the recipient's event loop: the chunk packet reuses the shared compressed frame
 * (skipping the Deflater), everything else goes via a normal send. This keeps the chunk ahead of the
 * follow-up packets (entity tracking / leash / passengers) exactly as vanilla does.
 */
public final class BroadcastShare {

    public static final class Capture {
        public final Connection connection;
        private final List<Queued> queued = new ArrayList<>(4);

        Capture(Connection connection) {
            this.connection = connection;
        }

        public void add(Packet<?> packet, @Nullable PacketSendListener listener) {
            this.queued.add(new Queued(packet, listener));
        }
    }

    private static final class Queued {
        final Packet<?> packet;
        final PacketSendListener listener;

        Queued(Packet<?> packet, @Nullable PacketSendListener listener) {
            this.packet = packet;
            this.listener = listener;
        }
    }

    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();

    public static void beginCapture(Connection connection) {
        CAPTURE.set(new Capture(connection));
    }

    @Nullable
    public static Capture active() {
        return CAPTURE.get();
    }

    @Nullable
    public static Capture endCapture() {
        Capture c = CAPTURE.get();
        CAPTURE.remove();
        return c;
    }

    /** Flush captured packets in order on the recipient's event loop. The shared frame is reused only when this
     *  recipient's negotiated codec matches the codec that produced the frame; a mismatched recipient (zstd frame
     *  to a zlib peer, or vice versa) falls back to a normal send so it re-compresses with its own codec.
     *  Per-item try/catch + a normal-send last resort guarantee no packet is silently dropped. */
    public static void flush(Capture cap, ClientboundLevelChunkWithLightPacket chunkPacket,
                             @Nullable byte[] frame, int uncompressed, @Nullable WireCodec frameCodec,
                             @Nullable Throwable error) {
        Connection conn = cap.connection;
        Channel channel = conn.channel();
        boolean codecMatches = frameCodec != null && ConnectionCodec.of(channel) == frameCodec;
        ChannelHandlerContext compressCtx = (channel != null) ? channel.pipeline().context("compress") : null;
        for (Queued q : cap.queued) {
            try {
                if (q.packet == chunkPacket && frame != null && compressCtx != null && codecMatches) {
                    sendPreCompressed(channel, compressCtx, chunkPacket, frame, uncompressed);
                } else {
                    conn.send(q.packet, q.listener);
                }
            } catch (Throwable t) {
                try {
                    conn.send(q.packet, q.listener);
                } catch (Throwable ignored) {
                    // connection is going away; nothing more we can do for this packet.
                }
            }
        }
    }

    private static void sendPreCompressed(Channel channel, ChannelHandlerContext compressCtx,
                                          ClientboundLevelChunkWithLightPacket packet, byte[] frame, int uncompressed) {
        ByteBuf buf = channel.alloc().buffer(frame.length);
        buf.writeBytes(frame);
        // Mirror vanilla doSendPacket: surface write failures to Connection.exceptionCaught.
        compressCtx.writeAndFlush(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

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
