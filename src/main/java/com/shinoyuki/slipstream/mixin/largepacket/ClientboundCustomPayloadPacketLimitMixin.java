package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 1 MiB clientbound custom-payload ceiling (both the read constructor and the write-side check), so
 * server-to-client mod channels (MTR data, structure sync) can carry large payloads.
 */
@Mixin(ClientboundCustomPayloadPacket.class)
public class ClientboundCustomPayloadPacketLimitMixin {

    @ModifyConstant(method = {
            "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)V"
    }, constant = @Constant(intValue = 1048576))
    private int slipstream$maxPayload(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
