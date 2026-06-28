package com.shinoyuki.slipstream.telemetry;

import java.util.concurrent.CompletableFuture;

/**
 * Duck interface implemented (via mixin) by ClientboundLevelChunkWithLightPacket. Two jobs:
 *
 * <p>Telemetry: count how many connections encode the SAME instance ({@link #slipstream$markEncode()}).
 * One instance encoded by N connections = a synchronous broadcast pass.
 *
 * <p>Serialize-once broadcast share: coordinate a single compression per broadcast pass. The first
 * recipient claims the originating send and compresses normally (off the tick thread, on its event
 * loop); the other recipients await {@link #slipstream$frameFuture()} and reuse the compressed frame
 * instead of running the Deflater again. The future is the chunk snapshot's, so there is nothing to
 * invalidate.
 *
 * <p>MUST live outside the mixin package: a plain class referenced from a registered mixin package
 * throws IllegalClassLoadError at apply time.
 */
public interface ChunkEncodeProbe {

    int slipstream$markEncode();

    /** First caller gets true (the originating send) and creates the frame future. */
    boolean slipstream$claimOriginatingSend();

    /** Compressed frame future, created by the originating claim; null if never claimed. */
    CompletableFuture<byte[]> slipstream$frameFuture();

    void slipstream$setUncompressedSize(int size);

    int slipstream$uncompressedSize();
}
