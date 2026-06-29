package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.aggregate.AggregateNetwork;
import com.shinoyuki.slipstream.aggregate.DeaggregateInboundHandler;
import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.SlipstreamNetwork;
import com.shinoyuki.slipstream.compress.WireCodec;
import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.l2.L2DecodeHandler;
import com.shinoyuki.slipstream.l2.L2Network;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

import java.util.function.BooleanSupplier;
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
     * 入站 DEAGG + L2decode 装在 "decoder" 之前 (入站序 decompress -&gt; DEAGG -&gt; L2decode -&gt; decoder)。注在
     * handleCompression TAIL: 此时 setupCompression 已把 "decompress" 装好。两者放进**同一** eventLoop.execute、
     * 按固定顺序 (DEAGG 先 addBefore decoder, L2decode 后 addBefore decoder -&gt; L2decode 紧贴 decoder、落在 DEAGG
     * 之后), 消除两个独立 @Inject 的执行先后歧义。各自按 capability presence 独立开关; 由连接 PLAY 协议状态门控生效
     * 边界 (登录期透传)。setupCompression 仅压缩开启时调, 故天然只在压缩开启时装 (与服务端同前提)。
     */
    @Inject(method = "handleCompression", at = @At("TAIL"))
    private void slipstream$installInboundHandlers(ClientboundLoginCompressionPacket packet, CallbackInfo ci) {
        boolean agg = SlipstreamConfig.aggregateEnabled() && AggregateNetwork.remoteSupportsAggregate(this.connection);
        boolean l2 = SlipstreamConfig.l2Enabled() && L2Network.remoteSupportsL2(this.connection);
        if (!agg && !l2) {
            return;
        }
        Channel channel = this.connection.channel();
        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("decoder") == null) {
                return;
            }
            BooleanSupplier playGate = () -> channel.attr(Connection.ATTRIBUTE_PROTOCOL).get() == ConnectionProtocol.PLAY;
            if (agg && pipeline.get(AggregateNetwork.DEAGG_HANDLER) == null) {
                pipeline.addBefore("decoder", AggregateNetwork.DEAGG_HANDLER, new DeaggregateInboundHandler(playGate));
                Slipstream.LOGGER.info("[Slipstream] deaggregator installed (client inbound)");
            }
            // L2decode 在 DEAGG 之后 addBefore("decoder") -> 紧贴 decoder, 入站序 DEAGG -> L2decode -> decoder。
            if (l2 && pipeline.get(L2Network.DECODE_HANDLER) == null) {
                pipeline.addBefore("decoder", L2Network.DECODE_HANDLER, new L2DecodeHandler(playGate));
                Slipstream.LOGGER.info("[Slipstream] L2 entity-delta decoder installed (client inbound)");
            }
        });
    }
}
