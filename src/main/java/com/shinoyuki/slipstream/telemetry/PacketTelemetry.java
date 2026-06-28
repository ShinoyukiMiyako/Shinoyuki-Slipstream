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
    private final Map<Long, Set<Long>> chunkFanout = new ConcurrentHashMap<>();
    private final LongAdder chunkTotalSends = new LongAdder();
    private final LongAdder chunkInstances = new LongAdder();
    private final LongAdder chunkCompressSkipped = new LongAdder();

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

    // Bound the dedup tracking so a long-lived large world cannot grow chunkFanout without limit.
    private static final int MAX_TRACKED_CHUNK_POSITIONS = 200_000;

    public void recordChunkSend(int x, int z, long connId, boolean firstEncodeOfInstance) {
        chunkTotalSends.increment();
        if (firstEncodeOfInstance) {
            chunkInstances.increment();
        }
        long key = packKey(x, z);
        Set<Long> receivers = chunkFanout.get(key);
        if (receivers == null) {
            if (chunkFanout.size() >= MAX_TRACKED_CHUNK_POSITIONS) {
                return;   // tracking capped; metric stays approximate, memory stays bounded.
            }
            receivers = chunkFanout.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        }
        receivers.add(connId);
    }

    public long chunkTotalSends() {
        return chunkTotalSends.sum();
    }

    public long chunkDistinct() {
        return chunkFanout.size();
    }

    // distinct (chunkPos, player) 对数 = 每个 chunk 的不同接收者之和。
    // broadcastRedundant = pairs - distinctChunks (同 chunk 发给多个玩家, serialize-once 直接省的)。
    // temporalRedundant  = totalSends - pairs     (同玩家重访同 chunk, 需短时缓存)。
    public long chunkDistinctPlayerPairs() {
        long sum = 0;
        for (Set<Long> players : chunkFanout.values()) {
            sum += players.size();
        }
        return sum;
    }

    // distinct ClientboundLevelChunkWithLightPacket 实例数 (markEncode 首次返回 1 的次数)。
    // safeCapturable = totalSends - instances (同一实例的广播 pass 内 serialize-once, 零失效)。
    // cacheExtra     = instances  - distinctChunks (同 chunk 跨实例/移动路径, 需 chunkPos+版本 缓存)。
    public long chunkDistinctInstances() {
        return chunkInstances.sum();
    }

    // serialize-once 实际生效计数: 命中实例缓存、跳过 Deflater 的 chunk 发送次数。
    public void recordChunkCompressSkipped() {
        chunkCompressSkipped.increment();
    }

    public long chunkCompressSkipped() {
        return chunkCompressSkipped.sum();
    }

    public long startMillis() {
        return startMillis;
    }

    public void reset() {
        global.clear();
        chunkFanout.clear();
        chunkTotalSends.reset();
        chunkInstances.reset();
        chunkCompressSkipped.reset();
        for (ConnectionStats stats : connections) {
            stats.byType().clear();
        }
        startMillis = System.currentTimeMillis();
    }

    /** Stable display label; disambiguates inner-class packets (e.g. ClientboundMoveEntityPacket$Pos). */
    public static String label(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        if (enclosing != null) {
            return enclosing.getSimpleName() + "$" + type.getSimpleName();
        }
        return type.getSimpleName();
    }

    private static long packKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private PacketTelemetry() {
    }
}
