package com.shinoyuki.slipstream.mixin.largepacket;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import net.minecraft.nbt.NbtAccounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lift the NBT read-size quota. {@code accountBytes} is the single enforcer that throws once the running NBT
 * byte count exceeds the quota; cancelling it makes every accounter effectively unlimited (the same behavior as
 * the built-in {@code NbtAccounter.UNLIMITED}), covering all NBT reads regardless of the quota they were created
 * with -- so no separate per-call quota constant needs raising.
 */
@Mixin(NbtAccounter.class)
public class NbtAccounterLimitMixin {

    @Inject(method = "accountBytes", at = @At("HEAD"), cancellable = true)
    private void slipstream$unlimit(long bytes, CallbackInfo ci) {
        if (SlipstreamConfig.largePacketEnabled()) {
            ci.cancel();
        }
    }
}
