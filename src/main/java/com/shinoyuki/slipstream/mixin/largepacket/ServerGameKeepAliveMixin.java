package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Widen the 15-second keep-alive window in {@code tick}, so a player blocked receiving a large packet is not
 * disconnected for a missed keep-alive before the transfer finishes.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGameKeepAliveMixin {

    @ModifyConstant(method = "tick", constant = @Constant(longValue = 15000L))
    private long slipstream$keepAliveWindow(long original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.KEEPALIVE_WINDOW_MS : original;
    }
}
