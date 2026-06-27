package com.shinoyuki.slipstream.telemetry;

import net.minecraft.network.Connection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-connection accumulator. Writes happen on a single netty event-loop thread (netty guarantees all
 * handler callbacks for one channel run serially on its assigned event loop), so the {@link #pendingType}
 * handoff needs no synchronization. The {@code byType} map is a {@link ConcurrentHashMap} only because the
 * report thread reads it while the event loop writes.
 */
public final class ConnectionStats {

    private final Connection connection;
    private final Map<String, TypeStat> byType = new ConcurrentHashMap<>();

    // Handoff slot: PacketEncoder records the type + uncompressed size of the packet it just serialized,
    // and the immediately-following CompressionEncoder (same thread, synchronous) reads it to attribute the
    // compressed size. Single-slot is safe because each write flows fully through the pipeline before the next.
    private String pendingType;
    private int pendingUncompressed;

    public ConnectionStats(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    public Map<String, TypeStat> byType() {
        return byType;
    }

    public TypeStat type(String label) {
        return byType.computeIfAbsent(label, k -> new TypeStat());
    }

    public void setPending(String type, int uncompressed) {
        this.pendingType = type;
        this.pendingUncompressed = uncompressed;
    }

    public String pendingType() {
        return pendingType;
    }

    public int pendingUncompressed() {
        return pendingUncompressed;
    }

    public void clearPending() {
        this.pendingType = null;
    }
}
