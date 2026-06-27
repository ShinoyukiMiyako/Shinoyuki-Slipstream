package com.shinoyuki.slipstream.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfigSpec {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue TELEMETRY_ENABLED;
    public static final ForgeConfigSpec.BooleanValue TRACK_PER_PLAYER;
    public static final ForgeConfigSpec.BooleanValue TRACK_CHUNK_DEDUP;
    public static final ForgeConfigSpec.IntValue AUTO_REPORT_SECONDS;

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
        SPEC = b.build();
    }

    private ConfigSpec() {
    }
}
