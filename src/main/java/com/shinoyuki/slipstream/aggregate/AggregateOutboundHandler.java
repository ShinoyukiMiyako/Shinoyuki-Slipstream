package com.shinoyuki.slipstream.aggregate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 服务端出站聚合 (P1)。装在 "encoder" 之前 (出站序 encoder -&gt; 本handler -&gt; compress): 把每个 windowMs
 * 窗内已序列化的出站包攒成一个批 ({@link AggregateFrameCodec} 格式), 再整批交给 compress 压缩 —— 消除每包
 * 帧框架开销, 并把单独不可压的碎实体包变成可压批包 (实测战斗 -57% 的承重墙)。
 *
 * <p>只在本连接的 netty event loop 上运行 (handlerAdded/write/定时器同线程), 故缓冲无锁。每个被缓冲 write 的
 * {@link ChannelPromise} 在批真正发出时统一完成, 否则上游 {@code Connection.send} 的 write future 永不完成会
 * stall。缓冲受 maxBatchBytes 上限约束 (满即提前 flush), 不无界增长。handlerRemoved 排空 + 取消定时器。
 *
 * <p>v1 严格 FIFO 不做 critical 旁路 (统一 windowMs 延迟; bundle 跨 flush 由客户端 stateful bundler 安全重组,
 * 见 PacketBundlePacker)。critical 旁路 / 背压折叠是后续增量。
 */
public final class AggregateOutboundHandler extends ChannelOutboundHandlerAdapter {

    private final int windowMs;
    private final int maxBatchBytes;
    private final List<ByteBuf> pending = new ArrayList<>();
    private final List<ChannelPromise> promises = new ArrayList<>();
    private int pendingBytes;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> timer;

    public AggregateOutboundHandler(int windowMs, int maxBatchBytes) {
        this.windowMs = windowMs;
        this.maxBatchBytes = maxBatchBytes;
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
        if (!(msg instanceof ByteBuf buf)) {
            // 非 ByteBuf (理论上 encoder 之后只剩 ByteBuf) 原样下传, 不进聚合。
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
        // 上游 (Connection.send) 的 flush 不立即下传: flush 时机由 windowMs 窗 / maxBatchBytes 上限决定,
        // 实际下传 flush 在 flushBatch 的 writeAndFlush 里完成。
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
        try {
            AggregateFrameCodec.writeVarInt(out, batch.size());
            for (ByteBuf b : batch) {
                AggregateFrameCodec.writeVarInt(out, b.readableBytes());
                out.writeBytes(b);
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
