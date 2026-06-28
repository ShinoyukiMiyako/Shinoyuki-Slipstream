package com.shinoyuki.slipstream.telemetry;

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

        long chunkSends = t.chunkTotalSends();
        long distinct = t.chunkDistinct();
        long pairs = t.chunkDistinctPlayerPairs();
        if (chunkSends > 0) {
            long broadcastRedundant = pairs - distinct;
            long temporalRedundant = chunkSends - pairs;
            long totalRedundant = chunkSends - distinct;
            line(sb, "");
            line(sb, String.format(Locale.ROOT,
                    "chunk: %d sends, %d distinct chunks, %d (chunk,player) pairs",
                    chunkSends, distinct, pairs));
            line(sb, String.format(Locale.ROOT,
                    "  broadcast redundant (serialize-once-broadcast saves this CPU): %d = %.1f%% of sends",
                    broadcastRedundant, 100.0 * broadcastRedundant / chunkSends));
            line(sb, String.format(Locale.ROOT,
                    "  temporal  redundant (same player revisits; needs short-lived cache): %d = %.1f%% of sends",
                    temporalRedundant, 100.0 * temporalRedundant / chunkSends));
            line(sb, String.format(Locale.ROOT,
                    "  combined dedupable: %.1f%%", 100.0 * totalRedundant / chunkSends));
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
