package com.shinoyuki.slipstream.telemetry;

import io.netty.util.AttributeKey;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide telemetry registry. Holds global per-type totals, the live set of per-connection
 * accumulators (for the per-player heatmap), and the chunk-broadcast redundancy counters.
 */
public final class PacketTelemetry {

    private static final PacketTelemetry INSTANCE = new PacketTelemetry();

    /** Per-channel accumulator handle, attached lazily; garbage-collected with the channel. */
    public static final AttributeKey<ConnectionStats> STATS_KEY = AttributeKey.valueOf("slipstream:connection_stats");

    public static PacketTelemetry get() {
        return INSTANCE;
    }

    private final Map<String, TypeStat> global = new ConcurrentHashMap<>();
    private final Set<ConnectionStats> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // chunk 去重: 每个 chunkPos 被序列化的次数 (跨所有连接)。distinct = map.size(), total = chunkTotalSends。
    // redundant = total - distinct = serialize-once 广播能省下的序列化+压缩次数。
    private final Map<Long, LongAdder> chunkSends = new ConcurrentHashMap<>();
    private final LongAdder chunkTotalSends = new LongAdder();

    private volatile long startMillis = System.currentTimeMillis();

    public TypeStat global(String label) {
        return global.computeIfAbsent(label, k -> new TypeStat());
    }

    public Map<String, TypeStat> globalView() {
        return global;
    }

    public Set<ConnectionStats> connections() {
        return connections;
    }

    public void registerConnection(ConnectionStats stats) {
        connections.add(stats);
    }

    public void unregisterConnection(ConnectionStats stats) {
        connections.remove(stats);
    }

    public void recordChunkSend(int x, int z) {
        chunkTotalSends.increment();
        chunkSends.computeIfAbsent(packKey(x, z), k -> new LongAdder()).increment();
    }

    public long chunkTotalSends() {
        return chunkTotalSends.sum();
    }

    public long chunkDistinct() {
        return chunkSends.size();
    }

    public long startMillis() {
        return startMillis;
    }

    public void reset() {
        global.clear();
        chunkSends.clear();
        chunkTotalSends.reset();
        for (ConnectionStats stats : connections) {
            stats.byType().clear();
        }
        startMillis = System.currentTimeMillis();
    }

    private static long packKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private PacketTelemetry() {
    }
}
