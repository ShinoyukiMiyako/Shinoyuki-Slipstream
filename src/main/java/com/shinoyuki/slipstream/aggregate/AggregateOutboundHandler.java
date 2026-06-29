package com.shinoyuki.slipstream.aggregate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 服务端出站聚合 (P1)。装在 "encoder" 之前 (出站序 encoder -&gt; 本handler -&gt; compress): 把每个 windowMs
 * 窗内已序列化的出站包攒成一个批 ({@link AggregateFrameCodec} 格式), 再整批交给 compress 压缩 —— 消除每包
 * 帧框架开销, 并把单独不可压的碎实体包变成可压批包 (实测战斗 -57% 的承重墙)。
 *
 * <p><b>PLAY 状态门控</b>: handler 在登录协商期就装上, 但仅当连接进入 PLAY 才攒批/打标记; 登录 (HANDSHAKING/
 * LOGIN) 期一律透传 (write + flush 原样下传, 不缓冲不打标记)。PLAY 切换由登录成功包触发, 两端字节序对齐 ——
 * 服务端发首个 PLAY 帧时打标, 客户端 DEAGG 此刻必已同样进入 PLAY 读标, 与安装时机无关, 根除"客户端先于服务端
 * 就位 -&gt; 拿登录期原始帧当批拆"的窗口竞态。{@code playGate} 由安装方 (mixin) 绑定读连接协议状态, 本类不依赖
 * Minecraft 类型, 可脱机单测。
 *
 * <p>只在本连接的 netty event loop 上运行 (handlerAdded/write/定时器同线程), 故缓冲无锁。每个被缓冲 write 的
 * {@link ChannelPromise} 在批真正发出时统一完成, 否则上游 {@code Connection.send} 的 write future 永不完成会
 * stall。缓冲受 maxBatchBytes 上限约束 (满即提前 flush), 不无界增长。handlerRemoved 排空 + 取消定时器。
 */
public final class AggregateOutboundHandler extends ChannelOutboundHandlerAdapter {

    private final int windowMs;
    private final int maxBatchBytes;
    private final BooleanSupplier playGate;
    private final List<ByteBuf> pending = new ArrayList<>();
    private final List<ChannelPromise> promises = new ArrayList<>();
    private int pendingBytes;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> timer;

    public AggregateOutboundHandler(int windowMs, int maxBatchBytes, BooleanSupplier playGate) {
        this.windowMs = windowMs;
        this.maxBatchBytes = maxBatchBytes;
        this.playGate = playGate;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.timer = ctx.executor().scheduleAtFixedRate(this::flushBatch, windowMs, windowMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        flushBatch();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // 非 PLAY (登录期) 或非 ByteBuf: 原样下传, 不进聚合。登录期透传保证两端只在 PLAY 才开始打/读标记。
        if (!playGate.getAsBoolean() || !(msg instanceof ByteBuf buf)) {
            ctx.write(msg, promise);
            return;
        }
        pending.add(buf);
        promises.add(promise);
        pendingBytes += buf.readableBytes();
        if (pendingBytes >= maxBatchBytes) {
            flushBatch();
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        // 有缓冲 (PLAY 攒批中) 时吞掉上游 flush, 由 windowMs 窗 / maxBatchBytes / flushBatch 的 writeAndFlush 决定
        // 实际下传时机; 无缓冲 (登录期透传, 或刚 flush 完) 时 flush 必须原样下传, 否则登录握手包发不出去会卡死。
        if (pending.isEmpty()) {
            ctx.flush();
        }
    }

    private void flushBatch() {
        if (pending.isEmpty() || ctx == null) {
            return;
        }
        List<ByteBuf> batch = new ArrayList<>(pending);
        List<ChannelPromise> ps = new ArrayList<>(promises);
        pending.clear();
        promises.clear();
        pendingBytes = 0;

        ByteBuf out = ctx.alloc().buffer();
        long payloadBytes = 0;
        try {
            if (batch.size() == 1) {
                // 窗内只一个包: RAW 标记 + 单包原文, 省掉 count/len 开销。
                payloadBytes = batch.get(0).readableBytes();
                out.writeByte(AggregateFrameCodec.TAG_RAW);
                out.writeBytes(batch.get(0));
            } else {
                out.writeByte(AggregateFrameCodec.TAG_BATCH);
                AggregateFrameCodec.writeVarInt(out, batch.size());
                for (ByteBuf b : batch) {
                    payloadBytes += b.readableBytes();
                    AggregateFrameCodec.writeVarInt(out, b.readableBytes());
                    out.writeBytes(b);
                }
            }
        } catch (Throwable t) {
            out.release();
            for (ChannelPromise p : ps) {
                p.tryFailure(t);
            }
            throw t;
        } finally {
            for (ByteBuf b : batch) {
                b.release();
            }
        }
        // 自度量: 攒了几个包 / 入站负载 / 成帧字节 (压缩前)。压缩后 wire 由下游 ZstdEncoder 计入 telemetry。
        AggregateStats.recordBatch(batch.size(), payloadBytes, out.readableBytes());

        ChannelPromise combined = ctx.newPromise();
        combined.addListener(f -> {
            if (f.isSuccess()) {
                for (ChannelPromise p : ps) {
                    p.trySuccess();
                }
            } else {
                for (ChannelPromise p : ps) {
                    p.tryFailure(f.cause());
                }
            }
        });
        ctx.writeAndFlush(out, combined);
    }
}
