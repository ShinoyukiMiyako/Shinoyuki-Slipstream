package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adds a per-instance encode counter to the chunk packet so telemetry can separate the synchronous
 * broadcast pass (one packet instance encoded for N connections -> serialize-once-capturable, zero
 * invalidation) from movement-path sends (a fresh instance per player -> needs a versioned cache).
 * Encodes run on per-connection event-loop threads, possibly concurrently for one shared instance,
 * so the counter is atomic.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ChunkPacketMixin implements ChunkEncodeProbe {

    private final AtomicInteger slipstream$encodes = new AtomicInteger();
    private volatile byte[] slipstream$compressedFrame;

    @Override
    public int slipstream$markEncode() {
        return this.slipstream$encodes.incrementAndGet();
    }

    @Override
    public byte[] slipstream$compressedFrame() {
        return this.slipstream$compressedFrame;
    }

    @Override
    public void slipstream$cacheCompressedFrame(byte[] frame) {
        // Set-once. Concurrent broadcast encodes may race here, but they produce byte-identical frames,
        // so a lost update is harmless — every reader still gets a correct frame for this chunk snapshot.
        if (this.slipstream$compressedFrame == null) {
            this.slipstream$compressedFrame = frame;
        }
    }
}
