package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.optimize.BroadcastShare;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Capture half of the serialize-once broadcast share. While ChunkMap.playerLoadedChunk runs for a deferred
 * recipient (capture active for this exact connection), its sends are queued instead of dispatched, so they
 * can be flushed in order behind the shared compressed chunk frame. When no capture is active (the common
 * case, and the originating recipient) sends proceed normally.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void slipstream$captureBroadcast(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        BroadcastShare.Capture cap = BroadcastShare.active();
        if (cap == null || cap.connection != (Connection) (Object) this) {
            return;
        }
        cap.add(packet, listener);
        ci.cancel();
    }
}
