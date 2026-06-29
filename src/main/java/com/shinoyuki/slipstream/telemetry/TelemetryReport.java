package com.shinoyuki.slipstream.telemetry;

import com.shinoyuki.slipstream.aggregate.AggregateStats;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders {@link PacketTelemetry} into a human-readable table and writes snapshots to logs/slipstream. */
public final class TelemetryReport {

    private static final int MAX_PLAYER_ROWS = 30;

    /**
     * 在 classloader 健康期 (onServerStarting) 触发本类加载, 让关服时的快照写入走已加载缓存。
     * 关服钩子里首次加载本类会撞 Forge 模块 classloader 的 teardown, 抛 NoClassDefFoundError 打断
     * 整条 ServerStoppingEvent 分发链 (后续 mod 的关服钩子全被跳过)。空方法体, 仅为强制类加载。
     */
    public static void preload() {
    }

    public static String render(PacketTelemetry t) {
        StringBuilder sb = new StringBuilder();
        double elapsedSec = Math.max(0.001, (System.currentTimeMillis() - t.startMillis()) / 1000.0);

        sb.append("=== Slipstream packet telemetry ===\n");
        line(sb, String.format(Locale.ROOT, "window %.1fs   connections %d", elapsedSec, t.connections().size()));

        List<Map.Entry<String, TypeStat>> types = new ArrayList<>(t.globalView().entrySet());
        long totalCompressed = 0;
        long totalUncompressed = 0;
        long totalCount = 0;
        for (Map.Entry<String, TypeStat> e : types) {
            totalCompressed += e.getValue().compressedBytes();
            totalUncompressed += e.getValue().uncompressedBytes();
            totalCount += e.getValue().count();
        }
        long compressedDenom = totalCompressed == 0 ? 1 : totalCompressed;
        types.sort(Comparator.comparingLong((Map.Entry<String, TypeStat> e) -> e.getValue().compressedBytes()).reversed());

        line(sb, String.format(Locale.ROOT, "%-42s %9s %11s %11s %6s %6s %10s",
                "packet", "count", "wire(KB)", "raw(KB)", "ratio", "%out", "B/s"));
        for (Map.Entry<String, TypeStat> e : types) {
            TypeStat s = e.getValue();
            double ratio = s.uncompressedBytes() == 0 ? 1.0 : (double) s.compressedBytes() / s.uncompressedBytes();
            line(sb, String.format(Locale.ROOT, "%-42s %9d %11.1f %11.1f %6.2f %5.1f%% %10.0f",
                    e.getKey(), s.count(),
                    s.compressedBytes() / 1024.0, s.uncompressedBytes() / 1024.0,
                    ratio, 100.0 * s.compressedBytes() / compressedDenom, s.compressedBytes() / elapsedSec));
        }
        line(sb, String.format(Locale.ROOT, "%-42s %9d %11.1f %11.1f",
                "TOTAL", totalCount, totalCompressed / 1024.0, totalUncompressed / 1024.0));

        long aggBatches = AggregateStats.batchesOut();
        if (aggBatches > 0) {
            long aggPkts = AggregateStats.packetsIn();
            long payload = AggregateStats.payloadBytesIn();
            long framed = AggregateStats.framedBytesOut();
            line(sb, "");
            line(sb, String.format(Locale.ROOT,
                    "aggregation: %d packets -> %d batches (%.1f pkt/batch)  payload %.1fKB  framed %.1fKB (AGG framing overhead +%.2f%%)",
                    aggPkts, aggBatches, (double) aggPkts / aggBatches,
                    payload / 1024.0, framed / 1024.0,
                    payload == 0 ? 0.0 : 100.0 * (framed - payload) / payload));
        }

        long chunkSends = t.chunkTotalSends();
        long distinct = t.chunkDistinct();
        long pairs = t.chunkDistinctPlayerPairs();
        long instances = t.chunkDistinctInstances();
        if (chunkSends > 0) {
            long safeCapturable = chunkSends - instances;   // 同一实例的同步广播 pass: serialize-once, 零失效
            long cacheExtra = instances - distinct;          // 同 chunk 跨实例 (移动路径): 需 chunkPos+版本 缓存
            long broadcastRedundant = pairs - distinct;
            long temporalRedundant = chunkSends - pairs;
            long totalRedundant = chunkSends - distinct;
            line(sb, "");
            line(sb, String.format(Locale.ROOT,
                    "chunk: %d sends, %d distinct chunks, %d packet instances, %d (chunk,player) pairs",
                    chunkSends, distinct, instances, pairs));
            line(sb, String.format(Locale.ROOT,
                    "  [P0-safe ] broadcast-pass redundant (serialize-once, zero invalidation): %d = %.1f%% of sends",
                    safeCapturable, 100.0 * safeCapturable / chunkSends));
            line(sb, String.format(Locale.ROOT,
                    "  [P0-cache] cross-instance same-chunk (needs versioned cache, transient-desync risk): %d = %.1f%% of sends",
                    cacheExtra, 100.0 * cacheExtra / chunkSends));
            line(sb, String.format(Locale.ROOT,
                    "  (by player: broadcast %.1f%% / temporal %.1f%% ; combined dedupable %.1f%%)",
                    100.0 * broadcastRedundant / chunkSends, 100.0 * temporalRedundant / chunkSends,
                    100.0 * totalRedundant / chunkSends));
            long skipped = t.chunkCompressSkipped();
            if (skipped > 0) {
                line(sb, String.format(Locale.ROOT,
                        "  serialize-once ACTIVE: %d chunk compressions skipped (%.1f%% of sends)",
                        skipped, 100.0 * skipped / chunkSends));
            }
        }

        List<ConnectionStats> conns = new ArrayList<>(t.connections());
        conns.sort(Comparator.comparingLong(TelemetryReport::connectionCompressed).reversed());
        line(sb, "");
        line(sb, "per-player (by on-wire bytes):");
        int shown = 0;
        for (ConnectionStats c : conns) {
            long sent = connectionCompressed(c);
            if (sent == 0) {
                continue;
            }
            line(sb, String.format(Locale.ROOT, "  %-24s %10.1f KB   top: %s",
                    label(c), sent / 1024.0, topTypes(c, 3)));
            if (++shown >= MAX_PLAYER_ROWS) {
                break;
            }
        }
        if (shown == 0) {
            line(sb, "  (no per-player data; telemetry.perPlayer may be disabled)");
        }
        return sb.toString();
    }

    public static Path writeToFile(PacketTelemetry t) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("slipstream");
            Files.createDirectories(dir);
            Path file = dir.resolve("telemetry-" + System.currentTimeMillis() + ".txt");
            Files.write(file, render(t).getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /** 机器可读的每类包聚合 (count/wire/raw + chunk 去重计数), 供离线分析/趋势追踪。 */
    public static String renderJson(PacketTelemetry t) {
        double elapsedSec = Math.max(0.001, (System.currentTimeMillis() - t.startMillis()) / 1000.0);
        List<Map.Entry<String, TypeStat>> types = new ArrayList<>(t.globalView().entrySet());
        types.sort(Comparator.comparingLong((Map.Entry<String, TypeStat> e) -> e.getValue().compressedBytes()).reversed());
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"window_s\":").append(String.format(Locale.ROOT, "%.1f", elapsedSec))
                .append(",\"connections\":").append(t.connections().size())
                .append(",\"chunk_sends\":").append(t.chunkTotalSends())
                .append(",\"chunk_distinct\":").append(t.chunkDistinct())
                .append(",\"aggregation\":{\"packets_in\":").append(AggregateStats.packetsIn())
                .append(",\"batches_out\":").append(AggregateStats.batchesOut())
                .append(",\"payload_bytes\":").append(AggregateStats.payloadBytesIn())
                .append(",\"framed_bytes\":").append(AggregateStats.framedBytesOut())
                .append("}")
                .append(",\"packets\":[");
        boolean first = true;
        for (Map.Entry<String, TypeStat> e : types) {
            TypeStat s = e.getValue();
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"type\":\"").append(e.getKey()).append("\",\"count\":").append(s.count())
                    .append(",\"wire\":").append(s.compressedBytes())
                    .append(",\"raw\":").append(s.uncompressedBytes()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static Path writeJsonToFile(PacketTelemetry t) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("slipstream");
            Files.createDirectories(dir);
            Path file = dir.resolve("telemetry-" + System.currentTimeMillis() + ".json");
            Files.write(file, renderJson(t).getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    private static long connectionCompressed(ConnectionStats c) {
        long sum = 0;
        for (TypeStat s : c.byType().values()) {
            sum += s.compressedBytes();
        }
        return sum;
    }

    private static String topTypes(ConnectionStats c, int n) {
        List<Map.Entry<String, TypeStat>> es = new ArrayList<>(c.byType().entrySet());
        es.sort(Comparator.comparingLong((Map.Entry<String, TypeStat> e) -> e.getValue().compressedBytes()).reversed());
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(n, es.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(es.get(i).getKey()).append('(').append(es.get(i).getValue().compressedBytes() / 1024).append("KB)");
        }
        return sb.toString();
    }

    private static String label(ConnectionStats c) {
        Connection conn = c.connection();
        if (conn != null) {
            PacketListener listener = conn.getPacketListener();
            if (listener instanceof ServerPlayerConnection spc && spc.getPlayer() != null) {
                return spc.getPlayer().getGameProfile().getName();
            }
            if (conn.getRemoteAddress() != null) {
                return conn.getRemoteAddress().toString();
            }
        }
        return "unattributed";
    }

    private TelemetryReport() {
    }
}
