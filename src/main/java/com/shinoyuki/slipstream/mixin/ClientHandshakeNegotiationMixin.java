package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.aggregate.AggregateNetwork;
import com.shinoyuki.slipstream.aggregate.DeaggregateInboundHandler;
import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.SlipstreamNetwork;
import com.shinoyuki.slipstream.compress.WireCodec;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side codec negotiation, mirror of the server side. By the time the client handles the compression
 * packet it has already processed every server login custom query (TCP-ordered, single netty thread), so the
 * channel-presence check is valid. Fires before this same method calls setupCompression.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeNegotiationMixin {

    @Shadow @Final private Connection connection;

    @Inject(method = "handleCompression", at = @At("HEAD"))
    private void slipstream$negotiateCodec(ClientboundLoginCompressionPacket packet, CallbackInfo ci) {
        boolean zstd = SlipstreamNetwork.remoteSupportsZstd(this.connection);
        ConnectionCodec.set(this.connection.channel(), zstd ? WireCodec.ZSTD : WireCodec.ZLIB);
        Slipstream.LOGGER.info("[Slipstream] login negotiation (client): remote zstd-capable={}", zstd);
    }

    /**
     * 入站反聚合器装在 "decoder" 之前 (入站序 decompress -&gt; DEAGG -&gt; decoder)。注在 handleCompression TAIL:
     * 此时 setupCompression 已把 "decompress" 装好, addBefore("decoder") 落在它之后, 顺序正确。和服务端聚合器
     * 对称由 capability presence 决定, 由连接 PLAY 协议状态门控生效边界 (登录期透传), 见 {@link DeaggregateInboundHandler}。
     * 注: setupCompression 仅在压缩开启时被 handleCompression 调用, 故此处天然只在压缩开启时装 (与服务端同前提)。
     */
    @Inject(method = "handleCompression", at = @At("TAIL"))
    private void slipstream$installDeaggregator(ClientboundLoginCompressionPacket packet, CallbackInfo ci) {
        if (!SlipstreamConfig.aggregateEnabled() || !AggregateNetwork.remoteSupportsAggregate(this.connection)) {
            return;
        }
        Channel channel = this.connection.channel();
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(AggregateNetwork.DEAGG_HANDLER) == null && pipeline.get("decoder") != null) {
                pipeline.addBefore("decoder", AggregateNetwork.DEAGG_HANDLER, new DeaggregateInboundHandler(
                        () -> channel.attr(Connection.ATTRIBUTE_PROTOCOL).get() == ConnectionProtocol.PLAY));
                Slipstream.LOGGER.info("[Slipstream] deaggregator installed (client inbound)");
            }
        });
    }
}
