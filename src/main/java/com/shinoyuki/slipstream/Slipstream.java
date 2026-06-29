package com.shinoyuki.slipstream;

import com.mojang.logging.LogUtils;
import com.shinoyuki.slipstream.aggregate.AggregateNetwork;
import com.shinoyuki.slipstream.command.SlipstreamCommand;
import com.shinoyuki.slipstream.compress.SlipstreamNetwork;
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
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
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
        modBus.addListener(this::onCommonSetup);

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

    // Register the P2 capability channel only when zstd is enabled. Done here (not in the constructor) because
    // the config is loaded before common setup; gating the registration on config is what keeps zstd opt-in and,
    // since each end only registers when its own config enables it, makes the per-connection swap decision
    // symmetric (isRemotePresent is true on an end iff both ends enabled it).
    private void onCommonSetup(FMLCommonSetupEvent event) {
        if (SlipstreamConfig.zstdEnabled()) {
            event.enqueueWork(SlipstreamNetwork::register);
            LOGGER.info("[Slipstream] zstd wire codec enabled (level {}); capability channel registered",
                    SlipstreamConfig.zstdLevel());
        } else {
            LOGGER.info("[Slipstream] zstd wire codec disabled (telemetry / serialize-once only)");
        }

        if (SlipstreamConfig.largePacketEnabled()) {
            // Set before CompressionEncoder / the connection pipeline class-load (first login, after setup):
            // silence Forge's per-oversized-packet debug spam, and raise the server read timeout for slow
            // large transfers. The actual size ceilings are raised by the large-packet mixins.
            System.setProperty("forge.disablePacketCompressionDebug", "true");
            System.setProperty("forge.readTimeout", "120");
            LOGGER.info("[Slipstream] large-packet support enabled (replaces XLPackets / PacketFixer)");
        }

        if (SlipstreamConfig.aggregateEnabled()) {
            event.enqueueWork(AggregateNetwork::register);
            LOGGER.info("[Slipstream] small-packet aggregation enabled (window {}ms); capability channel registered",
                    SlipstreamConfig.aggregateWindowMs());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 趁 classloader 健康先加载关服快照写入类, 否则关服钩子里首次加载会撞模块 classloader teardown
        // 抛 NoClassDefFoundError, 打断整条 ServerStoppingEvent 分发, 把后续 mod 的关服收尾全跳过。
        TelemetryReport.preload();
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
        // 收尾落一份快照, 便于关服后回看本次会话的出站画像。这是关服事件链上的一环且快照纯属可选,
        // 任何失败 (含类加载异常) 都必须就地吞掉: 否则异常冒泡会打断 ServerStoppingEvent 分发, 让后续
        // mod 的关服收尾被跳过 (BAS 等的 worker 不被 drain -> 服务器关不掉)。最外层钩子, 兜底捕获。
        try {
            Path file = TelemetryReport.writeToFile(PacketTelemetry.get());
            if (file != null) {
                LOGGER.info("[Slipstream] final telemetry snapshot written to {}", file);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Slipstream] 关服遥测快照写入失败, 跳过 (不影响关服)", t);
        }
    }
}
