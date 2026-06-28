package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-instance state for telemetry (encode counter) and the serialize-once broadcast share
 * (originating-send claim + compressed-frame future). The broadcast forEach dispatches sequentially on
 * the main thread, so the claim is race-free; the future is completed later on the originating
 * connection's event loop.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ChunkPacketMixin implements ChunkEncodeProbe {

    private final AtomicInteger slipstream$encodes = new AtomicInteger();
    private final AtomicBoolean slipstream$originatingClaimed = new AtomicBoolean();
    private volatile CompletableFuture<byte[]> slipstream$frameFuture;
    private volatile int slipstream$uncompressedSize;

    @Override
    public int slipstream$markEncode() {
        return this.slipstream$encodes.incrementAndGet();
    }

    @Override
    public boolean slipstream$claimOriginatingSend() {
        if (this.slipstream$originatingClaimed.compareAndSet(false, true)) {
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            // Bound the wait: if the originating send never compresses (connection dropped mid-pass),
            // deferred recipients fall back to a normal send instead of hanging forever.
            future.orTimeout(5, TimeUnit.SECONDS);
            this.slipstream$frameFuture = future;
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<byte[]> slipstream$frameFuture() {
        return this.slipstream$frameFuture;
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
