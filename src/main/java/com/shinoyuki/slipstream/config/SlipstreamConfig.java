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

    public static void onLoad(ModConfigEvent.Loading event) {
        refresh();
        LOGGER.info("[Slipstream] config loaded enabled={} perPlayer={} chunkDedup={}",
                telemetryEnabled, trackPerPlayer, trackChunkDedup);
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        refresh();
        LOGGER.info("[Slipstream] config reloaded enabled={} perPlayer={} chunkDedup={}",
                telemetryEnabled, trackPerPlayer, trackChunkDedup);
    }

    private static void refresh() {
        telemetryEnabled = ConfigSpec.TELEMETRY_ENABLED.get();
        trackPerPlayer = ConfigSpec.TRACK_PER_PLAYER.get();
        trackChunkDedup = ConfigSpec.TRACK_CHUNK_DEDUP.get();
        autoReportSeconds = ConfigSpec.AUTO_REPORT_SECONDS.get();
    }

    private SlipstreamConfig() {
    }
}
