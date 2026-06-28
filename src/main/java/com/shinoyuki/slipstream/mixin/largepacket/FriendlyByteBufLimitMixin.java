package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.largepacket.LargePacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raise the 32767-char string ceiling on the convenience read/write methods, for packets carrying large strings
 * (big JSON, serialized data). The NBT byte ceiling is handled separately by {@link NbtAccounterLimitMixin}.
 */
@Mixin(FriendlyByteBuf.class)
public class FriendlyByteBufLimitMixin {

    @ModifyConstant(method = {
            "readUtf()Ljava/lang/String;",
            "writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;",
            "readResourceLocation()Lnet/minecraft/resources/ResourceLocation;"
    }, constant = @Constant(intValue = 32767))
    private int slipstream$maxString(int original) {
        return SlipstreamConfig.largePacketEnabled() ? LargePacket.MAX_BYTES : original;
    }
}
