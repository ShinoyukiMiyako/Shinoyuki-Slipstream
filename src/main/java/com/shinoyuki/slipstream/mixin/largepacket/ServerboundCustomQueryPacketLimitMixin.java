package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 1 MiB serverbound login-query answer ceiling. The size check lives in a constructor lambda
 * ({@code lambda$new$0}, a static synthetic), so the handler is static to match.
 */
@Mixin(ServerboundCustomQueryPacket.class)
public class ServerboundCustomQueryPacketLimitMixin {

    @ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 1048576))
    private static int slipstream$maxQuery(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
