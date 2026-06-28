package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-instance state for telemetry (encode counter) and the serialize-once broadcast share (compressed
 * frame future). The future is created atomically because it is reached from two threads: the originating
 * connection's event loop (which completes it from CompressionEncoder) and the main thread (which registers
 * the deferred recipients' flush in ChunkMap.playerLoadedChunk).
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ChunkPacketMixin implements ChunkEncodeProbe {

    private final AtomicInteger slipstream$encodes = new AtomicInteger();
    private final AtomicReference<CompletableFuture<byte[]>> slipstream$frameFutureRef = new AtomicReference<>();
    private volatile int slipstream$uncompressedSize;

    @Override
    public int slipstream$markEncode() {
        return this.slipstream$encodes.incrementAndGet();
    }

    @Override
    public CompletableFuture<byte[]> slipstream$getOrCreateFrameFuture() {
        CompletableFuture<byte[]> existing = this.slipstream$frameFutureRef.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<byte[]> created = new CompletableFuture<>();
        if (this.slipstream$frameFutureRef.compareAndSet(null, created)) {
            return created;
        }
        return this.slipstream$frameFutureRef.get();
    }

    @Override
    public void slipstream$setUncompressedSize(int size) {
        this.slipstream$uncompressedSize = size;
    }

    @Override
    public int slipstream$uncompressedSize() {
        return this.slipstream$uncompressedSize;
    }
}
