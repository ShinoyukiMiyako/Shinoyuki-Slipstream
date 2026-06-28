package com.shinoyuki.slipstream.aggregate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * 客户端入站反聚合 (P1)。装在 "decoder" 之前 (入站序 decompress -&gt; 本handler -&gt; decoder): 把服务端
 * {@link AggregateOutboundHandler} 发来的聚合批拆回各子包, 逐个交给 decoder 解码, 如同它们各自独立到达。
 *
 * <p>协商了聚合的连接上, 服务端把每个出站帧都聚合成批 (含只含 1 个子包的批), 故本 handler 见到的每个入站帧
 * 都是批, 无需 tag 区分。{@code MessageToMessageDecoder} 把 out 列表里每个子包 fire 给下一个入站 handler
 * (decoder), 并负责其引用计数。越界/截断抛 {@link DecoderException}, 由连接最外层统一处理 (不静默读越界)。
 */
public final class DeaggregateInboundHandler extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf batch, List<Object> out) {
        int count;
        try {
            count = AggregateFrameCodec.readVarInt(batch);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("aggregate batch header", e);
        }
        if (count < 0 || count > batch.readableBytes()) {
            throw new DecoderException("aggregate batch declares " + count + " sub-packets in " + batch.readableBytes() + " bytes");
        }
        for (int i = 0; i < count; i++) {
            int len;
            try {
                len = AggregateFrameCodec.readVarInt(batch);
            } catch (IllegalArgumentException e) {
                throw new DecoderException("aggregate sub-packet " + i + " length", e);
            }
            if (len < 0 || len > batch.readableBytes()) {
                throw new DecoderException("aggregate sub-packet " + i + " length " + len + " overruns " + batch.readableBytes());
            }
            // readRetainedSlice: 子包 ByteBuf 引用由 M2M decoder 在 fire 后释放; 零拷贝切片。
            out.add(batch.readRetainedSlice(len));
        }
    }
}
