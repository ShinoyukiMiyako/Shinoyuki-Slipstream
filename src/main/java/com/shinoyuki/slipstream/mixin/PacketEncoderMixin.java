package com.shinoyuki.slipstream.mixin;

import com.shinoyuki.slipstream.config.SlipstreamConfig;
import com.shinoyuki.slipstream.l2.L2EncodeHandler;
import com.shinoyuki.slipstream.l2.L2FieldCodec;
import com.shinoyuki.slipstream.telemetry.ChunkEncodeProbe;
import com.shinoyuki.slipstream.telemetry.ConnectionStats;
import com.shinoyuki.slipstream.telemetry.PacketTelemetry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
        String label = PacketTelemetry.label(packet.getClass());
        PacketTelemetry telemetry = PacketTelemetry.get();
        telemetry.global(label).addUncompressed(uncompressed);

        ConnectionStats stats = stats(ctx, telemetry);
        // pending 始终设置 (全局压缩后字节的归属也依赖它); 每连接明细仅在开启 perPlayer 时累加。
        stats.setPending(label, uncompressed);
        if (SlipstreamConfig.trackPerPlayer()) {
            stats.type(label).addUncompressed(uncompressed);
        }

        // chunk 包: 把实例引用交给压缩阶段 (serialize-once 复用判定), 并按需记录拆分遥测。
        // pendingChunk 始终随包设置/清除, 不依赖 trackChunkDedup, 否则优化会随遥测开关失灵。
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            ChunkEncodeProbe probe = (ChunkEncodeProbe) chunkPacket;
            stats.setPendingChunk(probe);
            // carried so deferred recipients (which never serialize) can still account uncompressed bytes.
            probe.slipstream$setUncompressedSize(uncompressed);
            if (SlipstreamConfig.trackChunkDedup()) {
                telemetry.recordChunkSend(chunkPacket.getX(), chunkPacket.getZ(), stats.id(),
                        probe.slipstream$markEncode() == 1);
            }
        } else {
            stats.setPendingChunk(null);
        }
    }

    /**
     * Outbound tap #2 (L2): 趁这里有 live {@link Packet} 对象, 把本包是否属于 L2 可处理的实体类型 (及其 {@link
     * L2FieldCodec.L2Type}) 标到 channel attr, 供紧随其后的 {@link L2EncodeHandler} 读取路由 —— 避免在已序列化
     * 字节上解析 packet id (省去 packet-id 解析, RotateHead 等无法构造实例的坑)。非实体包标 null。encoder 与
     * L2Encode 在同一出站流逐包同序执行 (单 event-loop), 故此 attr 是当前包的。仅 l2Enabled 时做, 与遥测开关无关。
     */
    @Inject(method = "encode", at = @At("TAIL"))
    private void slipstream$stashL2Type(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        if (!SlipstreamConfig.l2Enabled()) {
            return;
        }
        L2FieldCodec.L2Type type = null;
        if (packet instanceof ClientboundMoveEntityPacket.Pos) {
            type = L2FieldCodec.L2Type.MOVE_POS;
        } else if (packet instanceof ClientboundMoveEntityPacket.PosRot) {
            type = L2FieldCodec.L2Type.MOVE_POSROT;
        } else if (packet instanceof ClientboundMoveEntityPacket.Rot) {
            type = L2FieldCodec.L2Type.MOVE_ROT;
        } else if (packet instanceof ClientboundSetEntityMotionPacket) {
            type = L2FieldCodec.L2Type.MOTION;
        } else if (packet instanceof ClientboundRotateHeadPacket) {
            type = L2FieldCodec.L2Type.ROT_HEAD;
        }
        ctx.channel().attr(L2EncodeHandler.L2_TYPE).set(type);
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
