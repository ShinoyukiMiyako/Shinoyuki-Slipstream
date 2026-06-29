package com.shinoyuki.slipstream.l2;

import com.shinoyuki.slipstream.l2.L2FieldCodec.L2Type;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 服务端出站 L2 字段 delta 编码 (P1 战斗 regime, E1 无损)。装在 encoder 与 AGG 之间 (出站序 encoder -&gt;
 * L2Encode -&gt; AGG -&gt; compress): 把 encoder 刚序列化出的单个实体包按 per-(entity,type) baseline 做字段 delta
 * 重写, 其它包原样透传, 再交给 AGG 攒批。
 *
 * <p><b>类型识别</b>: 由 {@link com.shinoyuki.slipstream.mixin} 里的 encoder 钩子在 {@link #L2_TYPE} channel attr
 * 上标好本包的 {@link L2Type} (实体包才标, 否则 null); 本 handler 读后即清。encoder 与本 handler 在同一出站流上
 * 逐包同序执行 (单 event-loop), 故 attr 是当前包的、不串。
 *
 * <p><b>PLAY 门控</b> (与 AGG 同款): 登录期 (非 PLAY) 一律透传不打标记; PLAY 期每帧首字节打 TAG —— 0=RAW 透传包,
 * {@code ordinal+1}=对应 L2Type 的 delta 包。两端 PLAY 切换字节序对齐, 故 TAG 只在 PLAY 帧出现, 无安装窗口竞态。
 * baseline 表 per-connection (单 event-loop 无锁, 仿 AGG pending), 首包 (无 baseline) 发全量。
 */
public final class L2EncodeHandler extends ChannelOutboundHandlerAdapter {

    /** encoder 钩子标本包 L2 类型 (实体包) 或 null (其它包) 的 channel attr; 本 handler 读后清。 */
    public static final AttributeKey<L2Type> L2_TYPE = AttributeKey.valueOf("slipstream:l2_type");

    private final BooleanSupplier playGate;
    private final Map<Long, int[]> baselines = new HashMap<>();

    public L2EncodeHandler(BooleanSupplier playGate) {
        this.playGate = playGate;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!playGate.getAsBoolean() || !(msg instanceof ByteBuf buf)) {
            ctx.write(msg, promise);    // 登录期 / 非 ByteBuf: 原样透传 (不打 TAG, 两端对称)
            return;
        }
        Attribute<L2Type> attr = ctx.channel().attr(L2_TYPE);
        L2Type type = attr.getAndSet(null);   // 读即清, 防陈旧值串到下一个非实体包

        ByteBuf out = ctx.alloc().buffer();
        try {
            if (type == null) {
                out.writeByte(0);             // TAG_RAW: 非实体包整体透传
                out.writeBytes(buf);
            } else {
                int rawLen = buf.readableBytes();      // vanilla 实体包字节 (自度量基准)
                buf.markReaderIndex();
                L2FieldCodec.readVarInt(buf);          // packetId (仅为定位 entityId)
                int entityId = L2FieldCodec.readVarInt(buf);
                buf.resetReaderIndex();
                long key = ((long) entityId << 8) | type.ordinal();
                out.writeByte(type.ordinal() + 1);     // TAG = 该 L2Type
                baselines.put(key, L2FieldCodec.encode(type, buf, out, baselines.get(key)));
                L2Stats.record(rawLen, out.readableBytes());
            }
        } catch (Throwable t) {
            out.release();
            buf.release();
            promise.tryFailure(t);
            throw t;
        }
        buf.release();
        ctx.write(out, promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        baselines.clear();
    }
}
