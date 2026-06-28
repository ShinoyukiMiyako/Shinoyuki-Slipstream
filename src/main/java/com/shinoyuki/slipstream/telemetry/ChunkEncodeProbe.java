package com.shinoyuki.slipstream.telemetry;

/**
 * Duck interface implemented (via mixin) by ClientboundLevelChunkWithLightPacket. Counts how many
 * connections encode the SAME packet instance: the first encode marks a distinct instance, and every
 * further encode of that same instance is the synchronous broadcast pass (vanilla shares one packet
 * object across all current trackers) — exactly the redundancy serialize-once can remove with zero
 * invalidation. A fresh instance per player (the movement path) instead returns 1 each time.
 *
 * <p>MUST live outside the mixin package: a plain class referenced from a registered mixin package
 * throws IllegalClassLoadError at apply time.
 */
public interface ChunkEncodeProbe {
    int slipstream$markEncode();

    /** The compressed frame from the first encode of this instance, or null until populated. */
    byte[] slipstream$compressedFrame();

    /** Cache the compressed frame so other recipients of this same instance can reuse it. Set-once. */
    void slipstream$cacheCompressedFrame(byte[] frame);
}
