package com.shinoyuki.slipstream.aggregate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 客户端入站反聚合 (P1)。装在 "decoder" 之前 (入站序 decompress -&gt; 本handler -&gt; decoder): 把服务端
 * {@link AggregateOutboundHandler} 在 PLAY 期发来的帧拆回各子包, 逐个交给 decoder 解码, 如同它们各自独立到达。
 *
 * <p><b>PLAY 状态门控</b> (与发送端对称): 仅当连接进入 PLAY 才按标记拆帧; 登录 (HANDSHAKING/LOGIN) 期一律透传
 * (原样把帧交给 decoder, 不读标记)。服务端也只在 PLAY 才打标记, 两端 PLAY 切换由登录成功包触发、字节序对齐, 故
 * 本 handler 在 PLAY 期收到的每个帧都带 {@link AggregateFrameCodec} 标记; 绝不会把登录期原始帧误当批拆 ——
 * 这正是早期 offer/ack 握手窗口竞态崩溃 (IndexOutOfBounds) 的根因, 现由协议状态门控根除。
 *
 * <p>PLAY 帧首字节是类型标记: {@code TAG_RAW} 后接单个完整序列化包; {@code TAG_BATCH} 后接 VarInt(count) 与
 * count 个 (VarInt(len)+len 字节)。{@code MessageToMessageDecoder} 把 out 里每个子包 fire 给 decoder 并负责其
 * 引用计数。越界/截断/未知标记一律抛 {@link DecoderException}, 由连接最外层统一处理 (绝不静默读越界)。
 * {@code playGate} 由安装方 (mixin) 绑定读连接协议状态, 本类不依赖 Minecraft 类型, 可脱机单测。
 */
public final class DeaggregateInboundHandler extends MessageToMessageDecoder<ByteBuf> {

    private final BooleanSupplier playGate;

    public DeaggregateInboundHandler(BooleanSupplier playGate) {
        this.playGate = playGate;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        if (!playGate.getAsBoolean()) {
            // 登录期透传: 原样把帧交给下游 decoder (retain 抵消 M2M 对入参的释放)。
            out.add(frame.retain());
            return;
        }
        int tag = frame.readByte() & 0xFF;
        if (tag == AggregateFrameCodec.TAG_RAW) {
            out.add(frame.readRetainedSlice(frame.readableBytes()));
            return;
        }
        if (tag != AggregateFrameCodec.TAG_BATCH) {
            throw new DecoderException("unknown aggregate frame tag " + tag);
        }
        int count;
        try {
            count = AggregateFrameCodec.readVarInt(frame);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("aggregate batch header", e);
        }
        if (count < 0 || count > frame.readableBytes()) {
            throw new DecoderException("aggregate batch declares " + count + " sub-packets in " + frame.readableBytes() + " bytes");
        }
        for (int i = 0; i < count; i++) {
            int len;
            try {
                len = AggregateFrameCodec.readVarInt(frame);
            } catch (IllegalArgumentException e) {
                throw new DecoderException("aggregate sub-packet " + i + " length", e);
            }
            if (len < 0 || len > frame.readableBytes()) {
                throw new DecoderException("aggregate sub-packet " + i + " length " + len + " overruns " + frame.readableBytes());
            }
            // readRetainedSlice: 子包 ByteBuf 引用由 M2M decoder 在 fire 后释放; 零拷贝切片。
            out.add(frame.readRetainedSlice(len));
        }
    }
}
