package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the client-side 30-second {@code ReadTimeoutHandler} installed by the connect channel initializer
 * ({@code Connection$1}), so a slow large transfer to the client is not torn down as a read timeout. The
 * server-side read timeout is raised via the {@code forge.readTimeout} property set at common setup.
 */
@Mixin(targets = "net.minecraft.network.Connection$1")
public class ConnectionReadTimeoutMixin {

    @ModifyConstant(method = "initChannel", constant = @Constant(intValue = 30))
    private int slipstream$clientReadTimeout(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.CLIENT_READ_TIMEOUT_SECONDS : original;
    }
}
