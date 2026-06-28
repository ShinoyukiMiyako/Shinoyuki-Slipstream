package com.shinoyuki.slipstream.telemetry;

import java.util.concurrent.CompletableFuture;

/**
 * Duck interface on ClientboundLevelChunkWithLightPacket. Telemetry: count how many connections encode
 * the same instance ({@link #slipstream$markEncode()}). Serialize-once: the first recipient compresses
 * normally and completes the frame future; subsequent recipients (whose whole chunk-delivery sequence is
 * captured and replayed in order) await it and reuse the compressed frame.
 *
 * <p>MUST live outside the mixin package (a plain class referenced from a registered mixin package
 * throws IllegalClassLoadError at apply time).
 */
public interface ChunkEncodeProbe {

    int slipstream$markEncode();

    /** Frame future for the compressed chunk; created atomically by whoever needs it first. */
    CompletableFuture<byte[]> slipstream$getOrCreateFrameFuture();

    void slipstream$setUncompressedSize(int size);

    int slipstream$uncompressedSize();
}
