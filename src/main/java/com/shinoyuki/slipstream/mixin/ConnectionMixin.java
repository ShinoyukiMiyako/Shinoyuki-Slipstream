package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.optimize.BroadcastShare;
import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Serialize-once broadcast share ({@code optimize.chunkSerializeOnce}, default off).
 *
 * <p>On a synchronous broadcast pass vanilla hands the SAME chunk packet instance to each recipient's
 * {@code Connection.send} (sequentially, on the main thread). The first recipient compresses normally
 * off the tick thread (its event loop); the others await its compressed frame and reuse it, skipping
 * the Deflater. The frame is shared pre-encryption, so each connection still encrypts its own copy.
 * Any failure/timeout falls back to a normal vanilla send.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void slipstream$shareBroadcast(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (BroadcastShare.bypassing() || !SlipstreamConfig.chunkSerializeOnce()) {
            return;
        }
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)) {
            return;
        }
        ChunkEncodeProbe probe = (ChunkEncodeProbe) chunkPacket;
        if (probe.slipstream$claimOriginatingSend()) {
            return;   // originating send: compress normally; its CompressionEncoder completes the future.
        }
        // Subsequent recipient of the same instance: defer and reuse the compressed frame.
        Connection self = (Connection) (Object) this;
        Channel channel = self.channel();
        if (channel == null) {
            return;
        }
        ChannelHandlerContext compressCtx = channel.pipeline().context("compress");
        CompletableFuture<byte[]> future = probe.slipstream$frameFuture();
        if (compressCtx == null || future == null) {
            return;   // compression disabled or no future: fall through to a vanilla send.
        }
        future.whenCompleteAsync((frame, error) -> {
            if (frame != null) {
                BroadcastShare.sendPreCompressed(channel, compressCtx, chunkPacket, frame, probe.slipstream$uncompressedSize());
            } else {
                // Originating send failed or timed out: fall back to a normal send (bypassing this hook).
                BroadcastShare.markBypass(true);
                try {
                    self.send(packet, listener);
                } finally {
                    BroadcastShare.markBypass(false);
                }
            }
        }, channel.eventLoop());
        ci.cancel();
    }
}
