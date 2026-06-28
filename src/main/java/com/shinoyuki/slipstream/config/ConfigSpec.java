package com.shinoyuki.slipstream.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfigSpec {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue TELEMETRY_ENABLED;
    public static final ForgeConfigSpec.BooleanValue TRACK_PER_PLAYER;
    public static final ForgeConfigSpec.BooleanValue TRACK_CHUNK_DEDUP;
    public static final ForgeConfigSpec.IntValue AUTO_REPORT_SECONDS;
    public static final ForgeConfigSpec.BooleanValue CHUNK_SERIALIZE_ONCE;
    public static final ForgeConfigSpec.BooleanValue ZSTD_ENABLED;
    public static final ForgeConfigSpec.IntValue ZSTD_LEVEL;
    public static final ForgeConfigSpec.IntValue ZSTD_MAX_UNCOMPRESSED_MIB;
    public static final ForgeConfigSpec.BooleanValue LARGE_PACKET_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AGGREGATE_ENABLED;
    public static final ForgeConfigSpec.IntValue AGGREGATE_WINDOW_MS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Slipstream network packet telemetry.",
                  "Phase 0: server-side outbound (clientbound) traffic accounting. Pure measurement, no wire changes.")
         .push("telemetry");

        TELEMETRY_ENABLED = b
                .comment("Master switch. When false every netty-side hook returns immediately (zero overhead).")
                .define("enabled", true);

        TRACK_PER_PLAYER = b
                .comment("Accumulate the per-connection (per-player) breakdown used by the heatmap (who receives the most).")
                .define("perPlayer", true);

        TRACK_CHUNK_DEDUP = b
                .comment("Track how often the same chunk is serialized + compressed for multiple players,",
                         "to size the serialize-once broadcast optimization.")
                .define("chunkDedup", true);

        AUTO_REPORT_SECONDS = b
                .comment("Auto-write a telemetry snapshot to logs/slipstream every N seconds. 0 disables (manual /slipstream report only).")
                .defineInRange("autoReportSeconds", 0, 0, 3600);

        b.pop();

        b.comment("Optimization (experimental). Disabled by default: the mod ships as pure telemetry until enabled.")
         .push("optimize");

        CHUNK_SERIALIZE_ONCE = b
                .comment("Serialize-once: compress each chunk packet once per synchronous broadcast pass and reuse",
                         "the compressed frame for the other recipients, skipping the redundant Deflater work.",
                         "Zero invalidation -- the cache is the packet instance itself (one chunk snapshot).",
                         "Any packet not cached falls back to vanilla per-connection compression.")
                .define("chunkSerializeOnce", false);

        ZSTD_ENABLED = b
                .comment("zstd wire compression (P2). Negotiated per-connection: it engages only when BOTH ends run",
                         "Slipstream with this enabled (capability is exchanged during the Forge login handshake).",
                         "Every other connection -- vanilla client, vanilla server, or this disabled on either side --",
                         "stays on byte-for-byte vanilla zlib, so there is no compatibility cost. Cuts chunk-packet",
                         "bytes on the wire roughly 15-35%. Toggling requires a restart: the capability channel is",
                         "registered once at startup, and that registration is what makes the codec eligible.",
                         "Default on: it only engages between two Slipstream ends, so a vanilla peer is never affected.")
                .define("zstdEnabled", true);

        ZSTD_LEVEL = b
                .comment("zstd compression level on the network hot path. 1-3 trade ratio for CPU; 3 is the default.",
                         "Higher levels cost more netty-thread CPU per packet for diminishing size gains.")
                .defineInRange("zstdLevel", 3, 1, 9);

        ZSTD_MAX_UNCOMPRESSED_MIB = b
                .comment("Decompression-bomb guard: reject a server-INBOUND (client->server) zstd frame whose",
                         "declared uncompressed size exceeds this many MiB. Server-to-client frames are not bounded",
                         "(the server is trusted), and the compressed input is already bounded by the outer length",
                         "framing, so this only caps the allocation a malicious modded client could force. Raise it",
                         "if a large-packet mod (XLPackets / PacketFixer) legitimately sends bigger inbound packets.")
                .defineInRange("zstdMaxUncompressedMiB", 256, 8, 1024);

        LARGE_PACKET_ENABLED = b
                .comment("Large-packet support: raise the vanilla packet-size ceilings so big packets (MTR map data,",
                         "vehicle/structure NBT, schematic pastes) do not crash or get rejected -- a clean built-in",
                         "replacement for XLPackets / PacketFixer (remove those when this is on). Always-on, independent",
                         "of zstd: normal small packets are byte-identical to vanilla, so non-Slipstream clients are",
                         "unaffected; large packets need Slipstream on both ends (same requirement those mods imposed),",
                         "and here they are also zstd-compressed. Off = exact vanilla limits.")
                .define("largePacketEnabled", true);

        AGGREGATE_ENABLED = b
                .comment("Small-packet aggregation (P1, combat regime). Coalesce the burst of tiny entity-state packets",
                         "(motion / move / head-rotate) into one framed batch per window before compression: this kills",
                         "the per-packet framing overhead and hands zstd a compressible batch instead of sub-threshold",
                         "packets it cannot touch individually. Dual-end: engages only between two Slipstream ends that",
                         "both enabled it; every other peer keeps vanilla per-packet framing. Off until the aggregation",
                         "pipeline ships -- this flag and the negotiated capability gate it.")
                .define("aggregateEnabled", false);

        AGGREGATE_WINDOW_MS = b
                .comment("Aggregation flush window in milliseconds (one server tick = 50ms). Larger = better compression",
                         "but more added latency; 20ms (sub-tick) is near-imperceptible. Critical packets (keep-alive)",
                         "bypass the window and flush immediately.")
                .defineInRange("aggregateWindowMs", 20, 5, 100);

        b.pop();
        SPEC = b.build();
    }

    private ConfigSpec() {
    }
}
