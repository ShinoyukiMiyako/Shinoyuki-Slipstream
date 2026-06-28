package com.shinoyuki.slipstream.aggregate;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 聚合能力协商 + 双端安装握手 (P1)。专用 SimpleChannel "slipstream:agg":
 * <ol>
 *   <li>服务端在玩家进入 PLAY 后, 若对端也注册了本 channel (即客户端也开了聚合) 发 {@link AggregateOffer};</li>
 *   <li>客户端收 Offer, 在 netty event loop 上于 "decoder" 前装 {@link DeaggregateInboundHandler}, <b>装好后</b>回
 *       {@link AggregateAck};</li>
 *   <li>服务端收 Ack, 在 "encoder" 前装 {@link AggregateOutboundHandler}, 开始攒批。</li>
 * </ol>
 *
 * <p>"客户端先装 deagg 再 ack、服务端等 ack 才批" 是硬同步: 无论 Forge 把消息处理器派在 netty 还是主线程,
 * 安装与 ack 都落在 event loop 上同序执行, 保证客户端 deagg <b>证明性地先于第一个聚合批就位</b>。聚合是帧结构
 * 改动、没有 zstd 那种自适应解码安全网 (客户端没 deagg 直接 DecoderException 崩连接), 故必须握手, 不能靠
 * setupCompression 的模糊时序。vanilla / 未开聚合的客户端不注册本 channel, 服务端 isRemotePresent 为假即不发,
 * 保持逐包 vanilla, 零兼容代价。
 */
public final class AggregateNetwork {

    public static final String PROTOCOL_VERSION = "1";
    private static final String DEAGG = "slipstream_deaggregate";
    private static final String AGG = "slipstream_aggregate";
    private static final int MAX_BATCH_BYTES = 256 * 1024;

    private static volatile SimpleChannel channel;

    public record AggregateOffer() {
    }

    public record AggregateAck() {
    }

    /** Idempotent. 仅在 aggregateEnabled 时于 common setup 注册 (每端只在自己开了聚合时注册 -> 双端 presence 即双端同意)。 */
    public static void register() {
        if (channel != null) {
            return;
        }
        SimpleChannel ch = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Slipstream.MOD_ID, "agg"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
        ch.registerMessage(0, AggregateOffer.class, (m, b) -> {
        }, b -> new AggregateOffer(), AggregateNetwork::onOffer, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        ch.registerMessage(1, AggregateAck.class, (m, b) -> {
        }, b -> new AggregateAck(), AggregateNetwork::onAck, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel = ch;
    }

    /** 服务端: 玩家进 PLAY 后调。对端注册了本 channel (客户端也开聚合) 才发 offer。 */
    public static void offerTo(ServerPlayer player) {
        SimpleChannel ch = channel;
        if (ch == null || !SlipstreamConfig.aggregateEnabled()) {
            return;
        }
        Connection conn = player.connection.connection;
        if (!ch.isRemotePresent(conn)) {
            return;
        }
        ch.send(PacketDistributor.PLAYER.with(() -> player), new AggregateOffer());
        Slipstream.LOGGER.info("[Slipstream] aggregation offered to {}", player.getGameProfile().getName());
    }

    // 客户端收 offer: event loop 上先装 deagg, 装好后才 ack (保证 deagg 先于服务端开批就位)。
    private static void onOffer(AggregateOffer msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        Channel nch = ctx.getNetworkManager().channel();
        nch.eventLoop().execute(() -> {
            ChannelPipeline p = nch.pipeline();
            if (p.get(DEAGG) == null && p.get("decoder") != null) {
                p.addBefore("decoder", DEAGG, new DeaggregateInboundHandler());
                Slipstream.LOGGER.info("[Slipstream] aggregation: deaggregator installed");
            }
            channel.reply(new AggregateAck(), ctx);
        });
        ctx.setPacketHandled(true);
    }

    // 服务端收 ack: event loop 上装 agg, 开始攒批 (客户端 deagg 此时必已就位)。
    private static void onAck(AggregateAck msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        Channel nch = ctx.getNetworkManager().channel();
        nch.eventLoop().execute(() -> {
            ChannelPipeline p = nch.pipeline();
            if (p.get(AGG) == null && p.get("encoder") != null) {
                p.addBefore("encoder", AGG, new AggregateOutboundHandler(SlipstreamConfig.aggregateWindowMs(), MAX_BATCH_BYTES));
                Slipstream.LOGGER.info("[Slipstream] aggregation: aggregator installed, batching active");
            }
        });
        ctx.setPacketHandled(true);
    }

    private AggregateNetwork() {
    }
}
