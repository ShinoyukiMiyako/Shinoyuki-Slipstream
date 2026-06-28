package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 1 MiB serverbound login-query answer ceiling. The size check lives in a constructor lambda whose
 * synthetic name is reobf-fragile (dev {@code lambda$new$0} does not match the production jar's name), so match
 * the constant across all methods instead of by lambda name, and make it best-effort (require = 0): a missing
 * raise here only leaves the rarely-hit serverbound login answer at the vanilla limit, never a crash. Static
 * handler matches both the synthetic lambda and the static field initializer.
 */
@Mixin(ServerboundCustomQueryPacket.class)
public class ServerboundCustomQueryPacketLimitMixin {

    @ModifyConstant(method = "*", constant = @Constant(intValue = 1048576), require = 0)
    private static int slipstream$maxQuery(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
