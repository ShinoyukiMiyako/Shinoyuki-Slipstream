package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.aggregate.AggregateNetwork;
import com.shinoyuki.slipstream.aggregate.AggregateOutboundHandler;
import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.SlipstreamNetwork;
import com.shinoyuki.slipstream.compress.WireCodec;
import com.shinoyuki.slipstream.compress.ZstdEncoder;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side codec negotiation. The FML login handshake (mod list + channel exchange) has completed by the
 * time handleAcceptedLogin runs -- the login state machine only reaches READY_TO_ACCEPT after negotiation --
 * and this fires before the same method installs compression (setupCompression). Record whether the remote
 * also carries the Slipstream capability channel onto the connection, for the setupCompression swap to read.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNegotiationMixin {

    @Shadow @Final Connection connection;

    @Shadow @Final MinecraftServer server;

    @Inject(method = "handleAcceptedLogin", at = @At("HEAD"))
    private void slipstream$negotiateCodec(CallbackInfo ci) {
        boolean zstd = SlipstreamNetwork.remoteSupportsZstd(this.connection);
        ConnectionCodec.set(this.connection.channel(), zstd ? WireCodec.ZSTD : WireCodec.ZLIB);
        Slipstream.LOGGER.info("[Slipstream] login negotiation (server): remote zstd-capable={}", zstd);
        if (zstd && SlipstreamConfig.zstdEnabled()) {
            slipstream$installServerZstdEncoder();
        }
        if (SlipstreamConfig.aggregateEnabled() && AggregateNetwork.remoteSupportsAggregate(this.connection)) {
            slipstream$installServerAggregator();
        }
    }

    /**
     * 出站聚合器装在 "encoder" 之前 (出站序 encoder -&gt; AGG -&gt; compress): 攒批后整批交给压缩。和 zstd 编码器
     * 同在 handleAcceptedLogin (FML 握手后 capability 已知), 且必在玩家放置 (placeNewPlayer 发首批 PLAY 包) 之前
     * 排到 event loop, 故 PLAY 包发出时 AGG 已就位。聚合器本身按连接 PLAY 协议状态门控, 登录期透传 —— 见
     * {@link AggregateOutboundHandler}。与 zstd 同前提: 仅在压缩开启 (threshold &gt;= 0) 时启用。
     */
    private void slipstream$installServerAggregator() {
        if (this.server.getCompressionThreshold() < 0) {
            return;
        }
        Channel channel = this.connection.channel();
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(AggregateNetwork.AGG_HANDLER) == null && pipeline.get("encoder") != null) {
                pipeline.addBefore("encoder", AggregateNetwork.AGG_HANDLER, new AggregateOutboundHandler(
                        SlipstreamConfig.aggregateWindowMs(), AggregateNetwork.MAX_BATCH_BYTES,
                        () -> channel.attr(Connection.ATTRIBUTE_PROTOCOL).get() == ConnectionProtocol.PLAY));
                Slipstream.LOGGER.info("[Slipstream] aggregator installed (server->client, window={}ms)",
                        SlipstreamConfig.aggregateWindowMs());
            }
        });
    }

    /**
     * 服务端的 zstd 编码器只能在这里装, 不能靠 setupCompression 那个 swap: 服务端的 setupCompression 跑在 netty
     * 发送回调里, 早于 handleAcceptedLogin, 那时本协商还没把 codec 记上, swap 读到 peerZstd=false 就把 zlib 编码器
     * 留下了 (server->client 永远升不到 zstd)。handleAcceptedLogin 既在 FML 握手之后 (channel presence 已知)、
     * 在此流程里又晚于 setupCompression, 故在这里换编码器。pipeline 改动必须回到 netty event loop 上做; instanceof
     * 守卫保证与 setupCompression 的 swap 幂等 (协商早于 setupCompression 的反向顺序下 ConnectionCodec 已记上,
     * 由 setupCompression 先装, 这里就成 no-op)。
     */
    private void slipstream$installServerZstdEncoder() {
        int threshold = this.server.getCompressionThreshold();
        if (threshold < 0) {
            return;   // 服务端全局关压缩 -> 无可换。
        }
        Channel channel = this.connection.channel();
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("compress") != null && !(pipeline.get("compress") instanceof ZstdEncoder)) {
                pipeline.replace("compress", "compress", new ZstdEncoder(threshold));
                Slipstream.LOGGER.info("[Slipstream] zstd encoder installed (server->client, threshold={})", threshold);
            }
        });
    }
}
