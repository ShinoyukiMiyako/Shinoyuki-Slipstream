package com.shinoyuki.slipstream.config;

import com.shinoyuki.slipstream.Slipstream;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

/**
 * Hot-path snapshot of {@link ConfigSpec}. The netty encoders read these volatile fields instead of
 * calling ForgeConfigSpec getters on every packet; values are refreshed on config load/reload.
 */
public final class SlipstreamConfig {

    private static final Logger LOGGER = Slipstream.LOGGER;

    private static volatile boolean telemetryEnabled;
    private static volatile boolean trackPerPlayer;
    private static volatile boolean trackChunkDedup;
    private static volatile int autoReportSeconds;
    private static volatile boolean chunkSerializeOnce;
    private static volatile boolean zstdEnabled;
    private static volatile int zstdLevel;
    private static volatile int zstdMaxUncompressedBytes;

    public static boolean telemetryEnabled() {
        return telemetryEnabled;
    }

    public static boolean trackPerPlayer() {
        return trackPerPlayer;
    }

    public static boolean trackChunkDedup() {
        return trackChunkDedup;
    }

    public static int autoReportSeconds() {
        return autoReportSeconds;
    }

    public static boolean chunkSerializeOnce() {
        return chunkSerializeOnce;
    }

    public static boolean zstdEnabled() {
        return zstdEnabled;
    }

    public static int zstdLevel() {
        return zstdLevel;
    }

    public static int zstdMaxUncompressedBytes() {
        return zstdMaxUncompressedBytes;
    }

    public static void onLoad(ModConfigEvent.Loading event) {
        refresh();
        LOGGER.info("[Slipstream] config loaded enabled={} perPlayer={} chunkDedup={} serializeOnce={} zstd={}/{}",
                telemetryEnabled, trackPerPlayer, trackChunkDedup, chunkSerializeOnce, zstdEnabled, zstdLevel);
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        refresh();
        LOGGER.info("[Slipstream] config reloaded enabled={} perPlayer={} chunkDedup={} serializeOnce={} zstd={}/{}",
                telemetryEnabled, trackPerPlayer, trackChunkDedup, chunkSerializeOnce, zstdEnabled, zstdLevel);
    }

    private static void refresh() {
        telemetryEnabled = ConfigSpec.TELEMETRY_ENABLED.get();
        trackPerPlayer = ConfigSpec.TRACK_PER_PLAYER.get();
        trackChunkDedup = ConfigSpec.TRACK_CHUNK_DEDUP.get();
        autoReportSeconds = ConfigSpec.AUTO_REPORT_SECONDS.get();
        chunkSerializeOnce = ConfigSpec.CHUNK_SERIALIZE_ONCE.get();
        zstdEnabled = ConfigSpec.ZSTD_ENABLED.get();
        zstdLevel = ConfigSpec.ZSTD_LEVEL.get();
        zstdMaxUncompressedBytes = ConfigSpec.ZSTD_MAX_UNCOMPRESSED_MIB.get() * 1024 * 1024;
    }

    private SlipstreamConfig() {
    }
}
