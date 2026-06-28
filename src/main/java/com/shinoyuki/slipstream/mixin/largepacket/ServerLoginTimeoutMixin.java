package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Widen the 600-tick (30 s) login timeout in {@code tick}, so a login carrying a large FML handshake is not cut
 * off as "took too long to log in". Static handler matches PacketFixer's proven injection on this constant.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class ServerLoginTimeoutMixin {

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600))
    private static int slipstream$loginTimeout(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.LOGIN_TIMEOUT_TICKS : original;
    }
}
