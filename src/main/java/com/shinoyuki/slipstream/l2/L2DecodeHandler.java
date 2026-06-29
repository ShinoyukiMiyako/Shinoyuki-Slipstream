package com.shinoyuki.slipstream.l2;

import com.shinoyuki.slipstream.l2.L2FieldCodec.L2Type;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 客户端入站 L2 字段 delta 解码 (E1 无损), {@link L2EncodeHandler} 的逆。装在 DEAGG 之后、decoder 之前 (入站序
 * decompress -&gt; DEAGG -&gt; L2Decode -&gt; decoder): DEAGG 把聚合批拆回单个 (可能 L2 编码的) 帧, 本 handler 据帧首
 * TAG 把 delta 帧按 per-(entity,type) baseline 重建回逐字节一致的 vanilla 包字节, 再交 decoder 解析。
 *
 * <p>PLAY 门控与发送端对称: 登录期 (非 PLAY) 整帧透传 (那时发送端也没打 TAG); PLAY 期读首字节 TAG —— 0=RAW
 * 透传, {@code ordinal+1}=对应 L2Type 的 delta 帧。两端处理同一 TCP 有序流且 baseline 同源演进 -&gt; 还原必正确。
 * 越界/截断/未知 TAG 一律抛 {@link DecoderException} 由连接最外层处理 (不静默读越界)。
 */
public final class L2DecodeHandler extends MessageToMessageDecoder<ByteBuf> {

    private static final L2Type[] TYPES = L2Type.values();

    private final BooleanSupplier playGate;
    private final Map<Long, int[]> baselines = new HashMap<>();

    public L2DecodeHandler(BooleanSupplier playGate) {
        this.playGate = playGate;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        if (!playGate.getAsBoolean()) {
            out.add(frame.retain());            // 登录期: 整帧原样透传 (发送端也未打 TAG)
            return;
        }
        int tag = frame.readByte() & 0xFF;
        if (tag == 0) {
            out.add(frame.readRetainedSlice(frame.readableBytes()));   // RAW 透传包
            return;
        }
        int ord = tag - 1;
        if (ord >= TYPES.length) {
            throw new DecoderException("unknown L2 frame tag " + tag);
        }
        L2Type type = TYPES[ord];
        frame.markReaderIndex();
        L2FieldCodec.readVarInt(frame);                 // packetId (仅为定位 entityId)
        int entityId = L2FieldCodec.readVarInt(frame);
        frame.resetReaderIndex();
        long key = ((long) entityId << 8) | type.ordinal();

        ByteBuf rebuilt = ctx.alloc().buffer();
        try {
            baselines.put(key, L2FieldCodec.decode(type, frame, rebuilt, baselines.get(key)));
        } catch (Throwable t) {
            rebuilt.release();
            throw (t instanceof DecoderException) ? (DecoderException) t : new DecoderException("L2 decode", t);
        }
        out.add(rebuilt);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        baselines.clear();
    }
}
