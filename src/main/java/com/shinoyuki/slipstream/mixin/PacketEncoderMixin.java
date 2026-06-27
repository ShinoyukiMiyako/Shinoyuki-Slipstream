package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Outbound tap #1. At this point the packet has been serialized into {@code out} (VarInt id + data), so we
 * have both the packet type (from the live {@link Packet} object) and the pre-compression size. The compressed
 * on-wire size is filled in by {@link CompressionEncoderMixin} via the per-connection pending slot.
 *
 * <p>Runs on the netty event-loop, never the server tick thread.
 */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {

    @Inject(method = "encode", at = @At("TAIL"))
    private void slipstream$measureOutbound(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        if (!SlipstreamConfig.telemetryEnabled()) {
            return;
        }
        int uncompressed = out.readableBytes();
        String label = packet.getClass().getSimpleName();
        PacketTelemetry telemetry = PacketTelemetry.get();
        telemetry.global(label).addUncompressed(uncompressed);

        if (SlipstreamConfig.trackChunkDedup() && packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            telemetry.recordChunkSend(chunkPacket.getX(), chunkPacket.getZ());
        }

        ConnectionStats stats = stats(ctx, telemetry);
        // pending 始终设置 (全局压缩后字节的归属也依赖它); 每连接明细仅在开启 perPlayer 时累加。
        stats.setPending(label, uncompressed);
        if (SlipstreamConfig.trackPerPlayer()) {
            stats.type(label).addUncompressed(uncompressed);
        }
    }

    private static ConnectionStats stats(ChannelHandlerContext ctx, PacketTelemetry telemetry) {
        ConnectionStats existing = ctx.channel().attr(PacketTelemetry.STATS_KEY).get();
        if (existing != null) {
            return existing;
        }
        // Connection 是管线里名为 "packet_handler" 的尾部 handler; 编码时它一定已就位。
        Connection connection = ctx.pipeline().get(Connection.class);
        ConnectionStats created = new ConnectionStats(connection);
        ctx.channel().attr(PacketTelemetry.STATS_KEY).set(created);
        telemetry.registerConnection(created);
        // 通道关闭即从注册表移除, 避免持有已断开连接的强引用。
        ctx.channel().closeFuture().addListener(future -> telemetry.unregisterConnection(created));
        return created;
    }
}
