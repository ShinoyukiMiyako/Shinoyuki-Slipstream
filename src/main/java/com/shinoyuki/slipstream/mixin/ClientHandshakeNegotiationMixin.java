package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.SlipstreamNetwork;
import com.shinoyuki.slipstream.compress.WireCodec;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
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
}
