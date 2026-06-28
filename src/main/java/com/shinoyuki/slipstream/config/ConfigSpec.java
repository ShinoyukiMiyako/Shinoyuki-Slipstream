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
                         "registered once at startup, and that registration is what makes the codec eligible.")
                .define("zstdEnabled", false);

        ZSTD_LEVEL = b
                .comment("zstd compression level on the network hot path. 1-3 trade ratio for CPU; 3 is the default.",
                         "Higher levels cost more netty-thread CPU per packet for diminishing size gains.")
                .defineInRange("zstdLevel", 3, 1, 9);

        b.pop();
        SPEC = b.build();
    }

    private ConfigSpec() {
    }
}
