package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.optimize.BroadcastShare;
import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Dispatch half of the serialize-once broadcast share. ChunkMap.playerLoadedChunk is called once per
 * recipient of a chunk broadcast (sequentially, on the main thread; the shared MutableObject means every
 * recipient after the first sees an already-constructed packet). The first recipient runs unchanged: it
 * compresses the chunk on its event loop and completes the frame future. For each subsequent recipient we
 * capture the entire send sequence (chunk + entity-tracking / leash / passenger follow-ups) and replay it
 * in order once the frame is ready, so the chunk never arrives after a packet that depends on it.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Inject(method = "playerLoadedChunk", at = @At("HEAD"))
    private void slipstream$beginCapture(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
                                         LevelChunk chunk, CallbackInfo ci) {
        if (!SlipstreamConfig.chunkSerializeOnce() || packetCache.getValue() == null) {
            return;   // optimization off, or the first recipient (which builds + compresses the packet).
        }
        BroadcastShare.beginCapture(player.connection.connection);
    }

    @Inject(method = "playerLoadedChunk", at = @At("RETURN"))
    private void slipstream$flushCapture(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
                                         LevelChunk chunk, CallbackInfo ci) {
        BroadcastShare.Capture cap = BroadcastShare.endCapture();
        if (cap == null) {
            return;
        }
        ClientboundLevelChunkWithLightPacket packet = packetCache.getValue();
        ChunkEncodeProbe probe = (ChunkEncodeProbe) packet;
        CompletableFuture<byte[]> future = probe.slipstream$getOrCreateFrameFuture();
        Connection conn = cap.connection;
        Channel channel = conn.channel();
        if (channel == null) {
            BroadcastShare.flush(cap, packet, null, probe.slipstream$uncompressedSize(), null);
            return;
        }
        EventLoop loop = channel.eventLoop();
        // Bound the wait on the originating compression; cancel the timer when the frame arrives (no leak).
        ScheduledFuture<?> timeout = loop.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException("chunk frame not ready"));
            }
        }, 5, TimeUnit.SECONDS);
        future.whenCompleteAsync((frame, error) -> {
            timeout.cancel(false);
            BroadcastShare.flush(cap, packet, frame, probe.slipstream$uncompressedSize(), error);
        }, loop);
    }
}
