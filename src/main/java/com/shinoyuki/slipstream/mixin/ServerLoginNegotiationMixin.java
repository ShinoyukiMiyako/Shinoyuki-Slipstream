package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.Slipstream;
import com.shinoyuki.slipstream.compress.ConnectionCodec;
import com.shinoyuki.slipstream.compress.SlipstreamNetwork;
import com.shinoyuki.slipstream.compress.WireCodec;
import net.minecraft.network.Connection;
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

    @Inject(method = "handleAcceptedLogin", at = @At("HEAD"))
    private void slipstream$negotiateCodec(CallbackInfo ci) {
        boolean zstd = SlipstreamNetwork.remoteSupportsZstd(this.connection);
        ConnectionCodec.set(this.connection.channel(), zstd ? WireCodec.ZSTD : WireCodec.ZLIB);
        Slipstream.LOGGER.info("[Slipstream] login negotiation (server): remote zstd-capable={}", zstd);
    }
}
