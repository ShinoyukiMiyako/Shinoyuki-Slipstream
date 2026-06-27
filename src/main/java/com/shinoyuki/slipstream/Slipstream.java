package com.shinoyuki.slipstream;

import com.mojang.logging.LogUtils;
import com.shinoyuki.slipstream.command.SlipstreamCommand;
import com.shinoyuki.slipstream.config.ConfigSpec;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import com.shinoyuki.slipstream.telemetry.TelemetryReport;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(Slipstream.MOD_ID)
public final class Slipstream {

    public static final String MOD_ID = "slipstream";
    // 与 BetterAutoSave 同系列, config 归到同一父目录便于运营统一管理。
    public static final String SERIES_CONFIG_DIR = "Shinoyuki-Optimize";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Slipstream() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(SlipstreamConfig::onLoad);
        modBus.addListener(SlipstreamConfig::onReload);

        Path configRoot = FMLPaths.CONFIGDIR.get().resolve(SERIES_CONFIG_DIR).resolve(MOD_ID);
        try {
            Files.createDirectories(configRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create config directory " + configRoot, e);
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ConfigSpec.SPEC,
                SERIES_CONFIG_DIR + "/" + MOD_ID + "/common.toml");

        // 声明本 mod 为"服务端可缺省": 装了它的服务器不强制客户端也装, 纯 vanilla / 未装本 mod 的
        // 客户端照常连入。Phase 0 只在服务端做出站遥测, 不改线格式, 这个声明保证零兼容代价。
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, isFromServer) -> true));

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Slipstream] telemetry {} (perPlayer={}, chunkDedup={})",
                SlipstreamConfig.telemetryEnabled() ? "armed" : "disabled",
                SlipstreamConfig.trackPerPlayer(), SlipstreamConfig.trackChunkDedup());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SlipstreamCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 收尾落一份快照, 便于关服后回看本次会话的出站画像。
        Path file = TelemetryReport.writeToFile(PacketTelemetry.get());
        if (file != null) {
            LOGGER.info("[Slipstream] final telemetry snapshot written to {}", file);
        }
    }
}
