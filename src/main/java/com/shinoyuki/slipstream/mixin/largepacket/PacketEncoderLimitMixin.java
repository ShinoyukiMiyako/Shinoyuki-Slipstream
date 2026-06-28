package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.PacketEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 8 MiB "Packet too big" reject in {@code PacketEncoder.encode} (the pre-compression serialized-size
 * guard), so a large packet is allowed to serialize. Coexists with the telemetry {@code PacketEncoderMixin}.
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderLimitMixin {

    @ModifyConstant(method = "encode", constant = @Constant(intValue = 8388608))
    private int slipstream$maxPacket(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
