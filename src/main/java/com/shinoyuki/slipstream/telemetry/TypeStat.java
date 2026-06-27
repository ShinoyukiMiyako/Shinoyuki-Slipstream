package com.shinoyuki.slipstream.telemetry;

import java.util.concurrent.atomic.LongAdder;

/**
 * Count + byte accumulators for one packet type. Thread-safe: the global instance for a type is hit
 * concurrently from multiple netty event-loop threads (one per connection).
 *
 * <p>Recording is two-step because the two sizes are observed by two different pipeline handlers:
 * the uncompressed size (and the count) at {@code PacketEncoder}, the on-wire compressed size at the
 * downstream {@code CompressionEncoder}. When compression is disabled the compressed half stays zero.
 */
public final class TypeStat {

    private final LongAdder count = new LongAdder();
    private final LongAdder uncompressedBytes = new LongAdder();
    private final LongAdder compressedBytes = new LongAdder();

    public void addUncompressed(int bytes) {
        count.increment();
        uncompressedBytes.add(bytes);
    }

    public void addCompressed(int bytes) {
        compressedBytes.add(bytes);
    }

    public long count() {
        return count.sum();
    }

    public long uncompressedBytes() {
        return uncompressedBytes.sum();
    }

    public long compressedBytes() {
        return compressedBytes.sum();
    }
}
